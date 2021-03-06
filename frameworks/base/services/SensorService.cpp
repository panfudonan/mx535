/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <stdint.h>
#include <math.h>
#include <sys/types.h>
#include <dlfcn.h>

#include <utils/SortedVector.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>
#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Singleton.h>
#include <utils/String16.h>

#include <binder/BinderService.h>
#include <binder/IServiceManager.h>

#include <gui/ISensorServer.h>
#include <gui/ISensorEventConnection.h>
#include <gui/IMplSysConnection.h>
#include <gui/IMplConnection.h>

#include <hardware/sensors.h>

#include "SensorService.h"
#include "GravitySensor.h"
#include "LinearAccelerationSensor.h"
#include "RotationVectorSensor.h"

namespace android {
// ---------------------------------------------------------------------------

SensorService::SensorService()
    : Thread(false),
      mDump("android.permission.DUMP"),
      mInitCheck(NO_INIT)
{
}

void SensorService::onFirstRef()
{
    LOGD("nuSensorService starting...");

    SensorDevice& dev(SensorDevice::getInstance());

    if (dev.initCheck() == NO_ERROR) {
        uint32_t virtualSensorsNeeds =
                (1<<SENSOR_TYPE_GRAVITY) |
                (1<<SENSOR_TYPE_LINEAR_ACCELERATION) |
                (1<<SENSOR_TYPE_ROTATION_VECTOR);
        sensor_t const* list;
        int count = dev.getSensorList(&list);
        mLastEventSeen.setCapacity(count);
        for (int i=0 ; i<count ; i++) {
            registerSensor( new HardwareSensor(list[i]) );
            switch (list[i].type) {
                case SENSOR_TYPE_GRAVITY:
                case SENSOR_TYPE_LINEAR_ACCELERATION:
                case SENSOR_TYPE_ROTATION_VECTOR:
                    virtualSensorsNeeds &= ~(1<<list[i].type);
                    break;
            }
        }

        if (virtualSensorsNeeds & (1<<SENSOR_TYPE_GRAVITY)) {
            registerVirtualSensor( new GravitySensor(list, count) );
        }
        if (virtualSensorsNeeds & (1<<SENSOR_TYPE_LINEAR_ACCELERATION)) {
            registerVirtualSensor( new LinearAccelerationSensor(list, count) );
        }
        if (virtualSensorsNeeds & (1<<SENSOR_TYPE_ROTATION_VECTOR)) {
            registerVirtualSensor( new RotationVectorSensor(list, count) );
        }

        run("SensorService", PRIORITY_URGENT_DISPLAY);
        mInitCheck = NO_ERROR;
    }
}

void SensorService::registerSensor(SensorInterface* s)
{
    sensors_event_t event;
    memset(&event, 0, sizeof(event));

    const Sensor sensor(s->getSensor());
    // add to the sensor list (returned to clients)
    mSensorList.add(sensor);
    // add to our handle->SensorInterface mapping
    mSensorMap.add(sensor.getHandle(), s);
    // create an entry in the mLastEventSeen array
    mLastEventSeen.add(sensor.getHandle(), event);
}

void SensorService::registerVirtualSensor(SensorInterface* s)
{
    registerSensor(s);
    mVirtualSensorList.add( s );
}

SensorService::~SensorService()
{
    for (size_t i=0 ; i<mSensorMap.size() ; i++)
        delete mSensorMap.valueAt(i);
}

status_t SensorService::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 1024;
    char buffer[SIZE];
    String8 result;
    if (!mDump.checkCalling()) {
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump SurfaceFlinger from pid=%d, uid=%d\n",
                IPCThreadState::self()->getCallingPid(),
                IPCThreadState::self()->getCallingUid());
        result.append(buffer);
    } else {
        Mutex::Autolock _l(mLock);
        snprintf(buffer, SIZE, "Sensor List:\n");
        result.append(buffer);
        for (size_t i=0 ; i<mSensorList.size() ; i++) {
            const Sensor& s(mSensorList[i]);
            const sensors_event_t& e(mLastEventSeen.valueFor(s.getHandle()));
            snprintf(buffer, SIZE, "%-48s| %-32s | 0x%08x | maxRate=%7.2fHz | last=<%5.1f,%5.1f,%5.1f>\n",
                    s.getName().string(),
                    s.getVendor().string(),
                    s.getHandle(),
                    s.getMinDelay() ? (1000000.0f / s.getMinDelay()) : 0.0f,
                    e.data[0], e.data[1], e.data[2]);
            result.append(buffer);
        }
        SensorDevice::getInstance().dump(result, buffer, SIZE);

        snprintf(buffer, SIZE, "%d active connections\n",
                mActiveConnections.size());
        result.append(buffer);
        snprintf(buffer, SIZE, "Active sensors:\n");
        result.append(buffer);
        for (size_t i=0 ; i<mActiveSensors.size() ; i++) {
            int handle = mActiveSensors.keyAt(i);
            snprintf(buffer, SIZE, "%s (handle=0x%08x, connections=%d)\n",
                    getSensorName(handle).string(),
                    handle,
                    mActiveSensors.valueAt(i)->getNumConnections());
            result.append(buffer);
        }
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

bool SensorService::threadLoop()
{
    LOGD("nuSensorService thread starting...");

    const size_t numEventMax = 16 * (1 + mVirtualSensorList.size());
    sensors_event_t buffer[numEventMax];
    sensors_event_t scratch[numEventMax];
    SensorDevice& device(SensorDevice::getInstance());
    const size_t vcount = mVirtualSensorList.size();

    ssize_t count;
    do {
        count = device.poll(buffer, numEventMax);
        if (count<0) {
            LOGE("sensor poll failed (%s)", strerror(-count));
            break;
        }

        recordLastValue(buffer, count);

        // handle virtual sensors
        if (count && vcount) {
            const DefaultKeyedVector<int, SensorInterface*> virtualSensors(
                    getActiveVirtualSensors());
            const size_t activeVirtualSensorCount = virtualSensors.size();
            if (activeVirtualSensorCount) {
                size_t k = 0;
                for (size_t i=0 ; i<size_t(count) ; i++) {
                    sensors_event_t const * const event = buffer;
                    for (size_t j=0 ; j<activeVirtualSensorCount ; j++) {
                        sensors_event_t out;
                        if (virtualSensors.valueAt(j)->process(&out, event[i])) {
                            buffer[count + k] = out;
                            k++;
                        }
                    }
                }
                if (k) {
                    // record the last synthesized values
                    recordLastValue(&buffer[count], k);
                    count += k;
                    // sort the buffer by time-stamps
                    sortEventBuffer(buffer, count);
                }
            }
        }

        // send our events to clients...
        const SortedVector< wp<SensorEventConnection> > activeConnections(
                getActiveConnections());
        size_t numConnections = activeConnections.size();
        for (size_t i=0 ; i<numConnections ; i++) {
            sp<SensorEventConnection> connection(
                    activeConnections[i].promote());
            if (connection != 0) {
                connection->sendEvents(buffer, count, scratch);
            }
        }
    } while (count >= 0 || Thread::exitPending());

    LOGW("Exiting SensorService::threadLoop!");
    return false;
}

void SensorService::recordLastValue(
        sensors_event_t const * buffer, size_t count)
{
    Mutex::Autolock _l(mLock);

    // record the last event for each sensor
    int32_t prev = buffer[0].sensor;
    for (size_t i=1 ; i<count ; i++) {
        // record the last event of each sensor type in this buffer
        int32_t curr = buffer[i].sensor;
        if (curr != prev) {
            mLastEventSeen.editValueFor(prev) = buffer[i-1];
            prev = curr;
        }
    }
    mLastEventSeen.editValueFor(prev) = buffer[count-1];
}

void SensorService::sortEventBuffer(sensors_event_t* buffer, size_t count)
{
    struct compar {
        static int cmp(void const* lhs, void const* rhs) {
            sensors_event_t const* l = static_cast<sensors_event_t const*>(lhs);
            sensors_event_t const* r = static_cast<sensors_event_t const*>(rhs);
            return r->timestamp - l->timestamp;
        }
    };
    qsort(buffer, count, sizeof(sensors_event_t), compar::cmp);
}

SortedVector< wp<SensorService::SensorEventConnection> >
SensorService::getActiveConnections() const
{
    Mutex::Autolock _l(mLock);
    return mActiveConnections;
}

DefaultKeyedVector<int, SensorInterface*>
SensorService::getActiveVirtualSensors() const
{
    Mutex::Autolock _l(mLock);
    return mActiveVirtualSensors;
}

String8 SensorService::getSensorName(int handle) const {
    size_t count = mSensorList.size();
    for (size_t i=0 ; i<count ; i++) {
        const Sensor& sensor(mSensorList[i]);
        if (sensor.getHandle() == handle) {
            return sensor.getName();
        }
    }
    String8 result("unknown");
    return result;
}

Vector<Sensor> SensorService::getSensorList()
{
    return mSensorList;
}

sp<ISensorEventConnection> SensorService::createSensorEventConnection()
{
    sp<SensorEventConnection> result(new SensorEventConnection(this));
    return result;
}

void SensorService::cleanupConnection(const wp<SensorEventConnection>& connection)
{
    Mutex::Autolock _l(mLock);
    size_t size = mActiveSensors.size();
    for (size_t i=0 ; i<size ; ) {
        SensorRecord* rec = mActiveSensors.valueAt(i);
        if (rec && rec->removeConnection(connection)) {
            int handle = mActiveSensors.keyAt(i);
            SensorInterface* sensor = mSensorMap.valueFor( handle );
            if (sensor) {
                sensor->activate(connection.unsafe_get(), false);
            }
            mActiveSensors.removeItemsAt(i, 1);
            mActiveVirtualSensors.removeItem(handle);
            delete rec;
            size--;
        } else {
            i++;
        }
    }
    mActiveConnections.remove(connection);
}

status_t SensorService::enable(const sp<SensorEventConnection>& connection,
        int handle)
{
    if (mInitCheck != NO_ERROR)
        return mInitCheck;

    Mutex::Autolock _l(mLock);
    SensorInterface* sensor = mSensorMap.valueFor(handle);
    status_t err = sensor ? sensor->activate(connection.get(), true) : status_t(BAD_VALUE);
    if (err == NO_ERROR) {
        SensorRecord* rec = mActiveSensors.valueFor(handle);
        if (rec == 0) {
            rec = new SensorRecord(connection);
            mActiveSensors.add(handle, rec);
            if (sensor->isVirtual()) {
                mActiveVirtualSensors.add(handle, sensor);
            }
        } else {
            if (rec->addConnection(connection)) {
                // this sensor is already activated, but we are adding a
                // connection that uses it. Immediately send down the last
                // known value of the requested sensor.
                sensors_event_t scratch;
                sensors_event_t& event(mLastEventSeen.editValueFor(handle));
                if (event.version == sizeof(sensors_event_t)) {
                    connection->sendEvents(&event, 1);
                }
            }
        }
        if (err == NO_ERROR) {
            // connection now active
            if (connection->addSensor(handle)) {
                // the sensor was added (which means it wasn't already there)
                // so, see if this connection becomes active
                if (mActiveConnections.indexOf(connection) < 0) {
                    mActiveConnections.add(connection);
                }
            }
        }
    }
    return err;
}

status_t SensorService::disable(const sp<SensorEventConnection>& connection,
        int handle)
{
    if (mInitCheck != NO_ERROR)
        return mInitCheck;

    status_t err = NO_ERROR;
    Mutex::Autolock _l(mLock);
    SensorRecord* rec = mActiveSensors.valueFor(handle);
    if (rec) {
        // see if this connection becomes inactive
        connection->removeSensor(handle);
        if (connection->hasAnySensor() == false) {
            mActiveConnections.remove(connection);
        }
        // see if this sensor becomes inactive
        if (rec->removeConnection(connection)) {
            mActiveSensors.removeItem(handle);
            mActiveVirtualSensors.removeItem(handle);
            delete rec;
        }
        SensorInterface* sensor = mSensorMap.valueFor(handle);
        err = sensor ? sensor->activate(connection.get(), false) : status_t(BAD_VALUE);
    }
    return err;
}

status_t SensorService::setEventRate(const sp<SensorEventConnection>& connection,
        int handle, nsecs_t ns)
{
    if (mInitCheck != NO_ERROR)
        return mInitCheck;

    if (ns < 0)
        return BAD_VALUE;

    if (ns < MINIMUM_EVENTS_PERIOD)
        ns = MINIMUM_EVENTS_PERIOD;

    SensorInterface* sensor = mSensorMap.valueFor(handle);
    if (!sensor) return BAD_VALUE;
    return sensor->setDelay(connection.get(), handle, ns);
}

// ---------------------------------------------------------------------------

SensorService::SensorRecord::SensorRecord(
        const sp<SensorEventConnection>& connection)
{
    mConnections.add(connection);
}

bool SensorService::SensorRecord::addConnection(
        const sp<SensorEventConnection>& connection)
{
    if (mConnections.indexOf(connection) < 0) {
        mConnections.add(connection);
        return true;
    }
    return false;
}

bool SensorService::SensorRecord::removeConnection(
        const wp<SensorEventConnection>& connection)
{
    ssize_t index = mConnections.indexOf(connection);
    if (index >= 0) {
        mConnections.removeItemsAt(index, 1);
    }
    return mConnections.size() ? false : true;
}

// ---------------------------------------------------------------------------

SensorService::SensorEventConnection::SensorEventConnection(
        const sp<SensorService>& service)
    : mService(service), mChannel(new SensorChannel())
{
}

SensorService::SensorEventConnection::~SensorEventConnection()
{
    mService->cleanupConnection(this);
}

void SensorService::SensorEventConnection::onFirstRef()
{
}

bool SensorService::SensorEventConnection::addSensor(int32_t handle) {
    Mutex::Autolock _l(mConnectionLock);
    if (mSensorInfo.indexOf(handle) <= 0) {
        mSensorInfo.add(handle);
        return true;
    }
    return false;
}

bool SensorService::SensorEventConnection::removeSensor(int32_t handle) {
    Mutex::Autolock _l(mConnectionLock);
    if (mSensorInfo.remove(handle) >= 0) {
        return true;
    }
    return false;
}

bool SensorService::SensorEventConnection::hasSensor(int32_t handle) const {
    Mutex::Autolock _l(mConnectionLock);
    return mSensorInfo.indexOf(handle) >= 0;
}

bool SensorService::SensorEventConnection::hasAnySensor() const {
    Mutex::Autolock _l(mConnectionLock);
    return mSensorInfo.size() ? true : false;
}

status_t SensorService::SensorEventConnection::sendEvents(
        sensors_event_t const* buffer, size_t numEvents,
        sensors_event_t* scratch)
{
    // filter out events not for this connection
    size_t count = 0;
    if (scratch) {
        Mutex::Autolock _l(mConnectionLock);
        size_t i=0;
        while (i<numEvents) {
            const int32_t curr = buffer[i].sensor;
            if (mSensorInfo.indexOf(curr) >= 0) {
                do {
                    scratch[count++] = buffer[i++];
                } while ((i<numEvents) && (buffer[i].sensor == curr));
            } else {
                i++;
            }
        }
    } else {
        scratch = const_cast<sensors_event_t *>(buffer);
        count = numEvents;
    }

    if (count == 0)
        return 0;

    ssize_t size = mChannel->write(scratch, count*sizeof(sensors_event_t));
    if (size == -EAGAIN) {
        // the destination doesn't accept events anymore, it's probably
        // full. For now, we just drop the events on the floor.
        LOGW("dropping %d events on the floor", count);
        return size;
    }

    LOGE_IF(size<0, "dropping %d events on the floor (%s)",
            count, strerror(-size));

    return size < 0 ? status_t(size) : status_t(NO_ERROR);
}

sp<SensorChannel> SensorService::SensorEventConnection::getSensorChannel() const
{
    return mChannel;
}

status_t SensorService::SensorEventConnection::enableDisable(
        int handle, bool enabled)
{
    status_t err;
    if (enabled) {
        err = mService->enable(this, handle);
    } else {
        err = mService->disable(this, handle);
    }
    return err;
}

status_t SensorService::SensorEventConnection::setEventRate(
        int handle, nsecs_t ns)
{
    return mService->setEventRate(this, handle, ns);
}

// ---------------------------------------------------------------------------
extern "C" {
//This struct *must* match the one defined in MPLSensor.cpp
typedef struct {
	int (*getBiases)(float *f);
	int (*setBiases)(float *f);
	int (*setBiasUpdateFunc)(long f);
	int (*setSensors)(long s);
    int (*getSensors)(long* s);
	int (*resetCal)();
	int (*selfTest)();
} tMplSysApi;

//This struct *must* match the one defined in MPLSensor.cpp
typedef struct {
 int (*fpAddGlyph)(unsigned short GlyphID);
 int (*fpBestGlyph)(unsigned short *finalGesture);
 int (*fpSetGlyphSpeedThresh)(unsigned short speed);
 int (*fpStartGlyph)(void);
 int (*fpStopGlyph)(void);
 int (*fpGetGlyph)(int index, int *x, int *y);
 int (*fpGetGlyphLength)(unsigned short *length);
 int (*fpClearGlyph)(void);
 int (*fpLoadGlyphs)(unsigned char *libraryData);
 int (*fpStoreGlyphs)(unsigned char *libraryData, unsigned short *length);
 int (*fpSetGlyphProbThresh)(unsigned short prob);
 int (*fpGetLibraryLength)(unsigned short *length);
} tMplGlyphApi;

typedef struct {
    int (*fpStartPed)(void);
    int (*fpStopPed)(void);
    int (*fpGetSteps)(void);
    int (*fpGetWalkTime)(void);
    int (*fpClearPedData)(void);
} tMplPedometerApi;

tMplSysApi* mplSysApi_l;
tMplGlyphApi* mplGlyphApi_l;
tMplPedometerApi* mplPedApi_l;
}

/* MPLSysConnection ***************************************************** */

sp<IMplSysConnection> SensorService::createMplSysConnection()
{
	sp<MplSysConnection> result(new MplSysConnection(this));
	return result;
}

SensorService::MplSysConnection::MplSysConnection(
        const sp<SensorService>& service)
    : mService(service)
{
}

void SensorService::MplSysConnection::onFirstRef()
{
	mplSysApi_l = (tMplSysApi*)dlsym(RTLD_DEFAULT, "mplSysApi");
	if(mplSysApi_l == NULL) {
		LOGE("could not find symbol for mplSysApi");
	} else {
		LOGV("mplSysApi found at %p", mplSysApi_l);
		LOGV("  getBiases function at %p", mplSysApi_l->getBiases);
		LOGV("  setBiases function at %p", mplSysApi_l->setBiases);
	}
	
    mplPedApi_l = (tMplPedometerApi*)dlsym(RTLD_DEFAULT, "mplPedApi");
    if(mplPedApi_l == NULL) {
        LOGE("could not find symbol for mplPedApi");
    } else {
        LOGV("mplPedApi found at %p", mplGlyphApi_l);
    }
}

SensorService::MplSysConnection::~MplSysConnection()
{
}

status_t SensorService::MplSysConnection::test()
{
	LOGV("Test MPL Sys Connection");
	return 4242;
}

status_t SensorService::MplSysConnection::getBiases(float *f)
{
	LOGV("server side getBiases %p %p %p", mplSysApi_l, mplSysApi_l->getBiases, f);
	return (status_t)(mplSysApi_l->getBiases(f));
}

status_t SensorService::MplSysConnection::setBiases(float *f)
{
	return (status_t)(mplSysApi_l->setBiases(f));
}

status_t SensorService::MplSysConnection::setBiasUpdateFunc(long f)
{
	return (status_t)(mplSysApi_l->setBiasUpdateFunc(f));
}

status_t SensorService::MplSysConnection::setSensors(long s)
{
	return (status_t)(mplSysApi_l->setSensors(s));
}

status_t SensorService::MplSysConnection::getSensors(long* s)
{
	return (status_t)(mplSysApi_l->getSensors(s));
}

status_t SensorService::MplSysConnection::resetCal()
{
	return (status_t)(mplSysApi_l->resetCal());
}

status_t SensorService::MplSysConnection::selfTest()
{
    return (status_t)(mplSysApi_l->selfTest());
}

status_t SensorService::MplSysConnection::rpcStartPed(void) {
	return mplPedApi_l->fpStartPed();
}

status_t SensorService::MplSysConnection::rpcStopPed(void) {
	return mplPedApi_l->fpStopPed();
}

status_t SensorService::MplSysConnection::rpcGetSteps(void) {
	return mplPedApi_l->fpGetSteps();
}

status_t SensorService::MplSysConnection::rpcGetWalkTime(void) {
	return mplPedApi_l->fpGetWalkTime();
}

status_t SensorService::MplSysConnection::rpcClearPedData(void) {
	return mplPedApi_l->fpClearPedData();
}

/* ** MPLConnection ********************************************************* */
sp<IMplConnection> SensorService::createMplConnection()
{
    sp<MplConnection> result(new MplConnection(this));
    return result;
}

SensorService::MplConnection::MplConnection(
        const sp<SensorService>& service)
    : mService(service)
{
}

void SensorService::MplConnection::onFirstRef()
{
    mplGlyphApi_l = (tMplGlyphApi*)dlsym(RTLD_DEFAULT, "mplGlyphApi");
    if(mplGlyphApi_l == NULL) {
        LOGE("could not find symbol for mplGlyphApi");
    } else {
        LOGV("mplGlyphApi found at %p", mplGlyphApi_l);
    }
}

SensorService::MplConnection::~MplConnection()
{
}

/* Glyph apis */
int SensorService::MplConnection::rpcAddGlyph(unsigned short GlyphID)
{
    return mplGlyphApi_l->fpAddGlyph(GlyphID);
}

int SensorService::MplConnection::rpcBestGlyph(unsigned short *finalGesture)
{
    return mplGlyphApi_l->fpBestGlyph(finalGesture);
}
int SensorService::MplConnection::rpcSetGlyphSpeedThresh(unsigned short speed)
{
    return mplGlyphApi_l->fpSetGlyphSpeedThresh(speed);
}
int SensorService::MplConnection::rpcStartGlyph(void)
{
    return mplGlyphApi_l->fpStartGlyph();
}
int SensorService::MplConnection::rpcStopGlyph(void)
{
    return mplGlyphApi_l->fpStopGlyph();
}
int SensorService::MplConnection::rpcGetGlyph(int index, int *x, int *y)
{
    return mplGlyphApi_l->fpGetGlyph(index, x, y);
}
int SensorService::MplConnection::rpcGetGlyphLength(unsigned short *length)
{
    return mplGlyphApi_l->fpGetGlyphLength(length);
}

int SensorService::MplConnection::rpcClearGlyph(void)
{
    return mplGlyphApi_l->fpClearGlyph();
}

int SensorService::MplConnection::rpcLoadGlyphs(unsigned char *libraryData)
{
    return mplGlyphApi_l->fpLoadGlyphs(libraryData);
}

int SensorService::MplConnection::rpcStoreGlyphs(unsigned char *libraryData, unsigned short *length)
{
    return mplGlyphApi_l->fpStoreGlyphs(libraryData, length);
}

int SensorService::MplConnection::rpcSetGlyphProbThresh(unsigned short prob)
{
    return mplGlyphApi_l->fpSetGlyphProbThresh(prob);
}

int SensorService::MplConnection::rpcGetLibraryLength(unsigned short *length)
{
    return mplGlyphApi_l->fpGetLibraryLength(length);
}

}; // namespace android

