/*
 * Copyright 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/* modified from the original (sensormanager) for use as a new Gesture Manager
 * by kpowell@invensense.com */

#define LOG_TAG "GestureManager"

#include "utils/Log.h"

#include <gui/Sensor.h>
#include <gui/SensorManager.h>
#include <gui/SensorEventQueue.h>
#include <binder/IServiceManager.h>
#include <gui/ISensorServer.h>
#include <gui/IMplConnection.h>

#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <stddef.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/poll.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <linux/input.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <fcntl.h>

#include "jni.h"
#include "JNIHelp.h"
#define LOG_NDEBUG 0
#define FUNC_LOG LOGV("%s", __FUNCTION__)

/* these defines must match the constants in Gesture.java */
/* note that these are merely identifiers, they happen to track the
 * handles/array offsets but do not have to.  Be careful.
 */
#define GESTURE_TYPE_TAP           (1)
#define GESTURE_TYPE_SHAKE         (2)
#define GESTURE_TYPE_YAW_IMAGE_ROT (3)
#define GESTURE_TYPE_ORIENTATION   (4)
#define GESTURE_TYPE_GRID_NUM      (5)
#define GESTURE_TYPE_GRID_CHANGE   (6)
#define GESTURE_TYPE_CTRL_SIGNAL   (7)
#define GESTURE_TYPE_MOTION        (8)
#define GESTURE_TYPE_STEP          (9)
#define GESTURE_TYPE_SNAP          (10)

struct gesture_t {
    /* name of this gesture */
    const char*     name;
    /* vendor of the hardware part */
    const char*     vendor;
    /* version of the hardware part + driver. The value of this field is
     * left to the implementation and doesn't have to be monotonicaly
     * increasing.
     */
    int             version;
    /* handle that identifies this sensors. This handle is used to activate
     * and deactivate this sensor. The value of the handle must be 8 bits
     * in this version of the API.
     */
    int             handle;
    /* this sensor's type. */
    int             type;
    /* reserved fields, must be zero */
    void*           reserved[9];
};

typedef struct {
    /* gesture identifier */
    int             gesture;

    int values[6];

    /* time is in nanosecond */
    int64_t         time;
    int8_t          status;

    uint32_t        reserved;
} gestures_data_t;


/* note to developers : we need a consistent
 * data passing structure -- we use t_mpl_data  for all the 'gesture' outputs.
 * (here, gesture means anything not covered by the official google apis)
 * we extend the type definitions thusly:
PITCH_SHAKE                  0x01
ROLL_SHAKE                   0x02
YAW_SHAKE                    0x04
TAP                          0x08
YAW_IMAGE_ROTATE             0x10
ORIENTATION                  0x20
MOTION                       0x40
GRID_NUM                     0x80
GRID_CHANGE                 0x100
CONTROL_SIG                 0x200
STEP                        0x400
SNAP                        0x800

Note that there are several markers to keep track of
  -handle (also position in the gesture array)
  -Java type: GESTURE_TYPE_xxxx
  -mpl type : as in the table above

there is some overlap in the information conveyed, but each one is used in
a different way.  Be careful.
 */

namespace android {

struct GestureOffsets
{
    jfieldID    name;
    jfieldID    vendor;
    jfieldID    version;
    jfieldID    handle;
    jfieldID    type;
} gGestureOffsets;

//KLP -- handle values must match the handles assigned in the HAL layer
/*      Tap             = 0, //0 Tap
        Shake           = 1, //1 Shake
        YawIR           = 2, //2 Yaw Image Rotate
        Orient6         = 3,//3 orientation
        GridNum         = 4,//4 grid num
        GridDelta       = 5,//5 grid change
        CtrlSig         = 6,//6 ctrl sig
        Motion          = 7,//7 motion
        Step            = 8,//8 step
        Snap            = 9,//9 snap
*/
static const struct gesture_t sGestureList[] = {
   {"Tap", "Invensense, Inc.", 1,            0, GESTURE_TYPE_TAP, { }},
   {"Shake", "Invensense, Inc.", 1,          1, GESTURE_TYPE_SHAKE, { }},
   {"Yaw Rotate", "Invensense, Inc.", 1,     2, GESTURE_TYPE_YAW_IMAGE_ROT, { }},
   {"Orientation", "Invensense, Inc.", 1,    3, GESTURE_TYPE_ORIENTATION, { }},
   {"Grid Num", "Invensense, Inc.", 1,       4, GESTURE_TYPE_GRID_NUM, { }},
   {"Grid Change", "Invensense, Inc.", 1,    5, GESTURE_TYPE_GRID_CHANGE, { }},
   {"Control Signal", "Invensense, Inc.", 1, 6, GESTURE_TYPE_CTRL_SIGNAL, { }},
   {"Motion", "Invensense, Inc.", 1,         7, GESTURE_TYPE_MOTION, { }},
   {"Step", "Invensense, Inc.", 1,           8, GESTURE_TYPE_STEP, { }},
   {"Snap", "Invensense, Inc.", 1,           9, GESTURE_TYPE_SNAP, { }},
};


static jint
gestures_module_init(JNIEnv *env, jclass clazz)
{
    FUNC_LOG;
    int err = 0;
    SensorManager::getInstance();
    return err;
}

static jint
gestures_module_get_next_gesture(JNIEnv *env, jobject clazz, jobject gesture, jint next)
{
    FUNC_LOG;
    GestureOffsets& gestureOffsets = gGestureOffsets;
    const struct gesture_t* list = sGestureList;
    int count = sizeof(sGestureList)/sizeof(gesture_t);
    if (next >= count)
        return -1;
    
    list += next;

    jstring name = env->NewStringUTF(list->name);
    jstring vendor = env->NewStringUTF(list->vendor);
    env->SetObjectField(gesture, gestureOffsets.name,  name);
    env->SetObjectField(gesture, gestureOffsets.vendor,vendor);
    env->SetIntField(gesture, gestureOffsets.version,  list->version);
    env->SetIntField(gesture, gestureOffsets.handle,   list->handle);
    env->SetIntField(gesture, gestureOffsets.type,     list->type);
    
    next++;
    return next<count ? next : 0;
}

//----------------------------------------------------------------------------

static jint
gestures_create_queue(JNIEnv *env, jclass clazz)
{
    SensorManager& mgr(SensorManager::getInstance());
    sp<SensorEventQueue> queue(mgr.createEventQueue());
    queue->incStrong(clazz);
    return reinterpret_cast<int>(queue.get());
}

static void
gestures_destroy_queue(JNIEnv *env, jclass clazz, jint nativeQueue)
{
    sp<SensorEventQueue> queue(reinterpret_cast<SensorEventQueue *>(nativeQueue));
    if (queue != 0) {
        queue->decStrong(clazz);
    }
}

static jboolean
gestures_enable_gesture(JNIEnv *env, jclass clazz,
        jint nativeQueue, jstring name, jint gesture, jint delay)
{
    sp<SensorEventQueue> queue(reinterpret_cast<SensorEventQueue *>(nativeQueue));
    LOGD("gestures_enable_gesture %d %d %d", nativeQueue, gesture, delay );
    if (queue == 0) return JNI_FALSE;
    status_t res;
    if (delay >= 0) {
        res = queue->enableSensor(gesture, delay*1000);
    } else {
        res = queue->disableSensor(gesture);
    }
    return res == NO_ERROR ? true : false;
}

static jint
gestures_data_poll(JNIEnv *env, jclass clazz, jint nativeQueue,
        jintArray values, jintArray status, jlongArray timestamp)
{
    sp<SensorEventQueue> queue(reinterpret_cast<SensorEventQueue *>(nativeQueue));
    if (queue == 0) return -1;

    status_t res;
    ASensorEvent event;

    res = queue->read(&event, 1);
    if (res == -EAGAIN) {
        res = queue->waitForEvent();
        if (res != NO_ERROR)
            return -1;
        res = queue->read(&event, 1);
    }
    if (res < 0)
        return -1;
/*
    LOGE("Gesture Event");
    LOGE("  ver: %d", event.version);
    LOGE("  sen: %d", event.sensor);
    LOGE("  typ: %d", event.type);
*/
    jint accuracy = event.vector.status;
    env->SetIntArrayRegion(values, 0, 6, (int*)(event.vector.v));
    env->SetIntArrayRegion(status, 0, 1, &accuracy);
    env->SetLongArrayRegion(timestamp, 0, 1, &event.timestamp);

    return event.sensor;
}

static void
nativeClassInit (JNIEnv *_env, jclass _this)
{
    jclass gestureClass = _env->FindClass("com/invensense/android/hardware/Gesture");
    GestureOffsets& gestureOffsets = gGestureOffsets;
    gestureOffsets.name        = _env->GetFieldID(gestureClass, "mName",      "Ljava/lang/String;");
    gestureOffsets.vendor      = _env->GetFieldID(gestureClass, "mVendor",    "Ljava/lang/String;");
    gestureOffsets.version     = _env->GetFieldID(gestureClass, "mVersion",   "I");
    gestureOffsets.handle      = _env->GetFieldID(gestureClass, "mHandle",    "I");
    gestureOffsets.type        = _env->GetFieldID(gestureClass, "mType",      "I");
}

static JNINativeMethod gMethods[] = {
    {"nativeClassInit", "()V",              (void*)nativeClassInit },
    {"gestures_module_init","()I",           (void*)gestures_module_init },
    {"gestures_module_get_next_gesture","(Lcom/invensense/android/hardware/Gesture;I)I",
                                            (void*)gestures_module_get_next_gesture },

    {"gestures_create_queue",  "()I",        (void*)gestures_create_queue },
    {"gestures_destroy_queue", "(I)V",       (void*)gestures_destroy_queue },
    {"gestures_enable_gesture", "(ILjava/lang/String;II)Z",
                                            (void*)gestures_enable_gesture },

    {"gestures_data_poll",  "(I[I[I[J)I",     (void*)gestures_data_poll },
};

sp<IMplConnection> get_mpl_binder() {
    static sp<IMplConnection> s_mpl_b = 0;

    /* Get the mpl service */
    if (s_mpl_b == NULL)
    {
        sp<ISensorServer> mSensorServer;
        const String16 name("sensorservice");
        while (getService(name, &mSensorServer) != NO_ERROR) {
            usleep(250000);
        }

        s_mpl_b = mSensorServer->createMplConnection();
    }
    if (s_mpl_b == NULL)
    {
      LOGE("The mpld is not published");
      return false; /* return an errorcode... */
    }
    return s_mpl_b;
}

}; // namespace android

using namespace android;

int register_invensense_hardware_GestureManager(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "com/invensense/android/hardware/GestureManager",
            gMethods, NELEM(gMethods));
}

// ----------------------------------------------------------------------------



/* -------------------------------------------------------------------------- */
/* -------------------------------------------------------------------------- */
/* GLYPH API -- mostly generated by SWIG */


#ifdef __cplusplus
template<typename T> class SwigValueWrapper {
    T *tt;
public:
    SwigValueWrapper() : tt(0) { }
    SwigValueWrapper(const SwigValueWrapper<T>& rhs) : tt(new T(*rhs.tt)) { }
    SwigValueWrapper(const T& t) : tt(new T(t)) { }
    ~SwigValueWrapper() { delete tt; }
    SwigValueWrapper& operator=(const T& t) { delete tt; tt = new T(t); return *this; }
    operator T&() const { return *tt; }
    T *operator&() { return tt; }
private:
    SwigValueWrapper& operator=(const SwigValueWrapper<T>& rhs);
};

template <typename T> T SwigValueInit() {
  return T();
}
#endif

/* -----------------------------------------------------------------------------
 *  This section contains generic SWIG labels for method/variable
 *  declarations/attributes, and other compiler dependent labels.
 * ----------------------------------------------------------------------------- */

/* template workaround for compilers that cannot correctly implement the C++ standard */
#ifndef SWIGTEMPLATEDISAMBIGUATOR
# if defined(__SUNPRO_CC) && (__SUNPRO_CC <= 0x560)
#  define SWIGTEMPLATEDISAMBIGUATOR template
# elif defined(__HP_aCC)
/* Needed even with `aCC -AA' when `aCC -V' reports HP ANSI C++ B3910B A.03.55 */
/* If we find a maximum version that requires this, the test would be __HP_aCC <= 35500 for A.03.55 */
#  define SWIGTEMPLATEDISAMBIGUATOR template
# else
#  define SWIGTEMPLATEDISAMBIGUATOR
# endif
#endif

/* inline attribute */
#ifndef SWIGINLINE
# if defined(__cplusplus) || (defined(__GNUC__) && !defined(__STRICT_ANSI__))
#   define SWIGINLINE inline
# else
#   define SWIGINLINE
# endif
#endif

/* attribute recognised by some compilers to avoid 'unused' warnings */
#ifndef SWIGUNUSED
# if defined(__GNUC__)
#   if !(defined(__cplusplus)) || (__GNUC__ > 3 || (__GNUC__ == 3 && __GNUC_MINOR__ >= 4))
#     define SWIGUNUSED __attribute__ ((__unused__))
#   else
#     define SWIGUNUSED
#   endif
# elif defined(__ICC)
#   define SWIGUNUSED __attribute__ ((__unused__))
# else
#   define SWIGUNUSED
# endif
#endif

#ifndef SWIG_MSC_UNSUPPRESS_4505
# if defined(_MSC_VER)
#   pragma warning(disable : 4505) /* unreferenced local function has been removed */
# endif
#endif

#ifndef SWIGUNUSEDPARM
# ifdef __cplusplus
#   define SWIGUNUSEDPARM(p)
# else
#   define SWIGUNUSEDPARM(p) p SWIGUNUSED
# endif
#endif

/* internal SWIG method */
#ifndef SWIGINTERN
# define SWIGINTERN static SWIGUNUSED
#endif

/* internal inline SWIG method */
#ifndef SWIGINTERNINLINE
# define SWIGINTERNINLINE SWIGINTERN SWIGINLINE
#endif

/* exporting methods */
#if (__GNUC__ >= 4) || (__GNUC__ == 3 && __GNUC_MINOR__ >= 4)
#  ifndef GCC_HASCLASSVISIBILITY
#    define GCC_HASCLASSVISIBILITY
#  endif
#endif

#ifndef SWIGEXPORT
# if defined(_WIN32) || defined(__WIN32__) || defined(__CYGWIN__)
#   if defined(STATIC_LINKED)
#     define SWIGEXPORT
#   else
#     define SWIGEXPORT __declspec(dllexport)
#   endif
# else
#   if defined(__GNUC__) && defined(GCC_HASCLASSVISIBILITY)
#     define SWIGEXPORT __attribute__ ((visibility("default")))
#   else
#     define SWIGEXPORT
#   endif
# endif
#endif

/* calling conventions for Windows */
#ifndef SWIGSTDCALL
# if defined(_WIN32) || defined(__WIN32__) || defined(__CYGWIN__)
#   define SWIGSTDCALL __stdcall
# else
#   define SWIGSTDCALL
# endif
#endif

/* Deal with Microsoft's attempt at deprecating C standard runtime functions */
#if !defined(SWIG_NO_CRT_SECURE_NO_DEPRECATE) && defined(_MSC_VER) && !defined(_CRT_SECURE_NO_DEPRECATE)
# define _CRT_SECURE_NO_DEPRECATE
#endif

/* Deal with Microsoft's attempt at deprecating methods in the standard C++ library */
#if !defined(SWIG_NO_SCL_SECURE_NO_DEPRECATE) && defined(_MSC_VER) && !defined(_SCL_SECURE_NO_DEPRECATE)
# define _SCL_SECURE_NO_DEPRECATE
#endif



/* Fix for jlong on some versions of gcc on Windows */
#if defined(__GNUC__) && !defined(__INTELC__)
  typedef long long __int64;
#endif

/* Fix for jlong on 64-bit x86 Solaris */
#if defined(__x86_64)
# ifdef _LP64
#   undef _LP64
# endif
#endif

#include <stdlib.h>
#include <string.h>


/* Support for throwing Java exceptions */
typedef enum {
  SWIG_JavaOutOfMemoryError = 1,
  SWIG_JavaIOException,
  SWIG_JavaRuntimeException,
  SWIG_JavaIndexOutOfBoundsException,
  SWIG_JavaArithmeticException,
  SWIG_JavaIllegalArgumentException,
  SWIG_JavaNullPointerException,
  SWIG_JavaDirectorPureVirtual,
  SWIG_JavaUnknownError
} SWIG_JavaExceptionCodes;

typedef struct {
  SWIG_JavaExceptionCodes code;
  const char *java_exception;
} SWIG_JavaExceptions_t;


static void SWIGUNUSED SWIG_JavaThrowException(JNIEnv *jenv, SWIG_JavaExceptionCodes code, const char *msg) {
  jclass excep;
  static const SWIG_JavaExceptions_t java_exceptions[] = {
    { SWIG_JavaOutOfMemoryError, "java/lang/OutOfMemoryError" },
    { SWIG_JavaIOException, "java/io/IOException" },
    { SWIG_JavaRuntimeException, "java/lang/RuntimeException" },
    { SWIG_JavaIndexOutOfBoundsException, "java/lang/IndexOutOfBoundsException" },
    { SWIG_JavaArithmeticException, "java/lang/ArithmeticException" },
    { SWIG_JavaIllegalArgumentException, "java/lang/IllegalArgumentException" },
    { SWIG_JavaNullPointerException, "java/lang/NullPointerException" },
    { SWIG_JavaDirectorPureVirtual, "java/lang/RuntimeException" },
    { SWIG_JavaUnknownError,  "java/lang/UnknownError" },
    { (SWIG_JavaExceptionCodes)0,  "java/lang/UnknownError" } };
  const SWIG_JavaExceptions_t *except_ptr = java_exceptions;

  while (except_ptr->code != code && except_ptr->code)
    except_ptr++;

  jenv->ExceptionClear();
  excep = jenv->FindClass(except_ptr->java_exception);
  if (excep)
    jenv->ThrowNew(excep, msg);
}


/* Contract support */

#define SWIG_contract_assert(nullreturn, expr, msg) if (!(expr)) {SWIG_JavaThrowException(jenv, SWIG_JavaIllegalArgumentException, msg); return nullreturn; } else


#if defined(SWIG_NOINCLUDE) || defined(SWIG_NOARRAYS)


int SWIG_JavaArrayInBool (JNIEnv *jenv, jboolean **jarr, bool **carr, jbooleanArray input);
void SWIG_JavaArrayArgoutBool (JNIEnv *jenv, jboolean *jarr, bool *carr, jbooleanArray input);
jbooleanArray SWIG_JavaArrayOutBool (JNIEnv *jenv, bool *result, jsize sz);


int SWIG_JavaArrayInSchar (JNIEnv *jenv, jbyte **jarr, signed char **carr, jbyteArray input);
void SWIG_JavaArrayArgoutSchar (JNIEnv *jenv, jbyte *jarr, signed char *carr, jbyteArray input);
jbyteArray SWIG_JavaArrayOutSchar (JNIEnv *jenv, signed char *result, jsize sz);


int SWIG_JavaArrayInUchar (JNIEnv *jenv, jshort **jarr, unsigned char **carr, jshortArray input);
void SWIG_JavaArrayArgoutUchar (JNIEnv *jenv, jshort *jarr, unsigned char *carr, jshortArray input);
jshortArray SWIG_JavaArrayOutUchar (JNIEnv *jenv, unsigned char *result, jsize sz);


int SWIG_JavaArrayInShort (JNIEnv *jenv, jshort **jarr, short **carr, jshortArray input);
void SWIG_JavaArrayArgoutShort (JNIEnv *jenv, jshort *jarr, short *carr, jshortArray input);
jshortArray SWIG_JavaArrayOutShort (JNIEnv *jenv, short *result, jsize sz);


int SWIG_JavaArrayInUshort (JNIEnv *jenv, jint **jarr, unsigned short **carr, jintArray input);
void SWIG_JavaArrayArgoutUshort (JNIEnv *jenv, jint *jarr, unsigned short *carr, jintArray input);
jintArray SWIG_JavaArrayOutUshort (JNIEnv *jenv, unsigned short *result, jsize sz);


int SWIG_JavaArrayInInt (JNIEnv *jenv, jint **jarr, int **carr, jintArray input);
void SWIG_JavaArrayArgoutInt (JNIEnv *jenv, jint *jarr, int *carr, jintArray input);
jintArray SWIG_JavaArrayOutInt (JNIEnv *jenv, int *result, jsize sz);


int SWIG_JavaArrayInUint (JNIEnv *jenv, jlong **jarr, unsigned int **carr, jlongArray input);
void SWIG_JavaArrayArgoutUint (JNIEnv *jenv, jlong *jarr, unsigned int *carr, jlongArray input);
jlongArray SWIG_JavaArrayOutUint (JNIEnv *jenv, unsigned int *result, jsize sz);


int SWIG_JavaArrayInLong (JNIEnv *jenv, jint **jarr, long **carr, jintArray input);
void SWIG_JavaArrayArgoutLong (JNIEnv *jenv, jint *jarr, long *carr, jintArray input);
jintArray SWIG_JavaArrayOutLong (JNIEnv *jenv, long *result, jsize sz);


int SWIG_JavaArrayInUlong (JNIEnv *jenv, jlong **jarr, unsigned long **carr, jlongArray input);
void SWIG_JavaArrayArgoutUlong (JNIEnv *jenv, jlong *jarr, unsigned long *carr, jlongArray input);
jlongArray SWIG_JavaArrayOutUlong (JNIEnv *jenv, unsigned long *result, jsize sz);


int SWIG_JavaArrayInLonglong (JNIEnv *jenv, jlong **jarr, jlong **carr, jlongArray input);
void SWIG_JavaArrayArgoutLonglong (JNIEnv *jenv, jlong *jarr, jlong *carr, jlongArray input);
jlongArray SWIG_JavaArrayOutLonglong (JNIEnv *jenv, jlong *result, jsize sz);


int SWIG_JavaArrayInFloat (JNIEnv *jenv, jfloat **jarr, float **carr, jfloatArray input);
void SWIG_JavaArrayArgoutFloat (JNIEnv *jenv, jfloat *jarr, float *carr, jfloatArray input);
jfloatArray SWIG_JavaArrayOutFloat (JNIEnv *jenv, float *result, jsize sz);


int SWIG_JavaArrayInDouble (JNIEnv *jenv, jdouble **jarr, double **carr, jdoubleArray input);
void SWIG_JavaArrayArgoutDouble (JNIEnv *jenv, jdouble *jarr, double *carr, jdoubleArray input);
jdoubleArray SWIG_JavaArrayOutDouble (JNIEnv *jenv, double *result, jsize sz);


#else


/* bool[] support */
int SWIG_JavaArrayInBool (JNIEnv *jenv, jboolean **jarr, bool **carr, jbooleanArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetBooleanArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new bool[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = ((*jarr)[i] != 0);
  return 1;
}

void SWIG_JavaArrayArgoutBool (JNIEnv *jenv, jboolean *jarr, bool *carr, jbooleanArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jboolean)carr[i];
  jenv->ReleaseBooleanArrayElements(input, jarr, 0);
}

jbooleanArray SWIG_JavaArrayOutBool (JNIEnv *jenv, bool *result, jsize sz) {
  jboolean *arr;
  int i;
  jbooleanArray jresult = jenv->NewBooleanArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetBooleanArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jboolean)result[i];
  jenv->ReleaseBooleanArrayElements(jresult, arr, 0);
  return jresult;
}


/* signed char[] support */
int SWIG_JavaArrayInSchar (JNIEnv *jenv, jbyte **jarr, signed char **carr, jbyteArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetByteArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new signed char[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (signed char)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutSchar (JNIEnv *jenv, jbyte *jarr, signed char *carr, jbyteArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jbyte)carr[i];
  jenv->ReleaseByteArrayElements(input, jarr, 0);
}

jbyteArray SWIG_JavaArrayOutSchar (JNIEnv *jenv, signed char *result, jsize sz) {
  jbyte *arr;
  int i;
  jbyteArray jresult = jenv->NewByteArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetByteArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jbyte)result[i];
  jenv->ReleaseByteArrayElements(jresult, arr, 0);
  return jresult;
}


/* unsigned char[] support */
int SWIG_JavaArrayInUchar (JNIEnv *jenv, jshort **jarr, unsigned char **carr, jshortArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetShortArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new unsigned char[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (unsigned char)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutUchar (JNIEnv *jenv, jshort *jarr, unsigned char *carr, jshortArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jshort)carr[i];
  jenv->ReleaseShortArrayElements(input, jarr, 0);
}

jshortArray SWIG_JavaArrayOutUchar (JNIEnv *jenv, unsigned char *result, jsize sz) {
  jshort *arr;
  int i;
  jshortArray jresult = jenv->NewShortArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetShortArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jshort)result[i];
  jenv->ReleaseShortArrayElements(jresult, arr, 0);
  return jresult;
}


/* short[] support */
int SWIG_JavaArrayInShort (JNIEnv *jenv, jshort **jarr, short **carr, jshortArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetShortArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new short[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (short)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutShort (JNIEnv *jenv, jshort *jarr, short *carr, jshortArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jshort)carr[i];
  jenv->ReleaseShortArrayElements(input, jarr, 0);
}

jshortArray SWIG_JavaArrayOutShort (JNIEnv *jenv, short *result, jsize sz) {
  jshort *arr;
  int i;
  jshortArray jresult = jenv->NewShortArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetShortArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jshort)result[i];
  jenv->ReleaseShortArrayElements(jresult, arr, 0);
  return jresult;
}


/* unsigned short[] support */
int SWIG_JavaArrayInUshort (JNIEnv *jenv, jint **jarr, unsigned short **carr, jintArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetIntArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new unsigned short[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (unsigned short)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutUshort (JNIEnv *jenv, jint *jarr, unsigned short *carr, jintArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jint)carr[i];
  jenv->ReleaseIntArrayElements(input, jarr, 0);
}

jintArray SWIG_JavaArrayOutUshort (JNIEnv *jenv, unsigned short *result, jsize sz) {
  jint *arr;
  int i;
  jintArray jresult = jenv->NewIntArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetIntArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jint)result[i];
  jenv->ReleaseIntArrayElements(jresult, arr, 0);
  return jresult;
}


/* int[] support */
int SWIG_JavaArrayInInt (JNIEnv *jenv, jint **jarr, int **carr, jintArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetIntArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new int[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (int)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutInt (JNIEnv *jenv, jint *jarr, int *carr, jintArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jint)carr[i];
  jenv->ReleaseIntArrayElements(input, jarr, 0);
}

jintArray SWIG_JavaArrayOutInt (JNIEnv *jenv, int *result, jsize sz) {
  jint *arr;
  int i;
  jintArray jresult = jenv->NewIntArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetIntArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jint)result[i];
  jenv->ReleaseIntArrayElements(jresult, arr, 0);
  return jresult;
}


/* unsigned int[] support */
int SWIG_JavaArrayInUint (JNIEnv *jenv, jlong **jarr, unsigned int **carr, jlongArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetLongArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new unsigned int[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (unsigned int)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutUint (JNIEnv *jenv, jlong *jarr, unsigned int *carr, jlongArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jlong)carr[i];
  jenv->ReleaseLongArrayElements(input, jarr, 0);
}

jlongArray SWIG_JavaArrayOutUint (JNIEnv *jenv, unsigned int *result, jsize sz) {
  jlong *arr;
  int i;
  jlongArray jresult = jenv->NewLongArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetLongArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jlong)result[i];
  jenv->ReleaseLongArrayElements(jresult, arr, 0);
  return jresult;
}


/* long[] support */
int SWIG_JavaArrayInLong (JNIEnv *jenv, jint **jarr, long **carr, jintArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetIntArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new long[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (long)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutLong (JNIEnv *jenv, jint *jarr, long *carr, jintArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jint)carr[i];
  jenv->ReleaseIntArrayElements(input, jarr, 0);
}

jintArray SWIG_JavaArrayOutLong (JNIEnv *jenv, long *result, jsize sz) {
  jint *arr;
  int i;
  jintArray jresult = jenv->NewIntArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetIntArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jint)result[i];
  jenv->ReleaseIntArrayElements(jresult, arr, 0);
  return jresult;
}


/* unsigned long[] support */
int SWIG_JavaArrayInUlong (JNIEnv *jenv, jlong **jarr, unsigned long **carr, jlongArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetLongArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new unsigned long[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (unsigned long)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutUlong (JNIEnv *jenv, jlong *jarr, unsigned long *carr, jlongArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jlong)carr[i];
  jenv->ReleaseLongArrayElements(input, jarr, 0);
}

jlongArray SWIG_JavaArrayOutUlong (JNIEnv *jenv, unsigned long *result, jsize sz) {
  jlong *arr;
  int i;
  jlongArray jresult = jenv->NewLongArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetLongArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jlong)result[i];
  jenv->ReleaseLongArrayElements(jresult, arr, 0);
  return jresult;
}


/* jlong[] support */
int SWIG_JavaArrayInLonglong (JNIEnv *jenv, jlong **jarr, jlong **carr, jlongArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetLongArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new jlong[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (jlong)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutLonglong (JNIEnv *jenv, jlong *jarr, jlong *carr, jlongArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jlong)carr[i];
  jenv->ReleaseLongArrayElements(input, jarr, 0);
}

jlongArray SWIG_JavaArrayOutLonglong (JNIEnv *jenv, jlong *result, jsize sz) {
  jlong *arr;
  int i;
  jlongArray jresult = jenv->NewLongArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetLongArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jlong)result[i];
  jenv->ReleaseLongArrayElements(jresult, arr, 0);
  return jresult;
}


/* float[] support */
int SWIG_JavaArrayInFloat (JNIEnv *jenv, jfloat **jarr, float **carr, jfloatArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetFloatArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new float[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (float)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutFloat (JNIEnv *jenv, jfloat *jarr, float *carr, jfloatArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jfloat)carr[i];
  jenv->ReleaseFloatArrayElements(input, jarr, 0);
}

jfloatArray SWIG_JavaArrayOutFloat (JNIEnv *jenv, float *result, jsize sz) {
  jfloat *arr;
  int i;
  jfloatArray jresult = jenv->NewFloatArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetFloatArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jfloat)result[i];
  jenv->ReleaseFloatArrayElements(jresult, arr, 0);
  return jresult;
}


/* double[] support */
int SWIG_JavaArrayInDouble (JNIEnv *jenv, jdouble **jarr, double **carr, jdoubleArray input) {
  int i;
  jsize sz;
  if (!input) {
    SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array");
    return 0;
  }
  sz = jenv->GetArrayLength(input);
  *jarr = jenv->GetDoubleArrayElements(input, 0);
  if (!*jarr)
    return 0;
  *carr = new double[sz];
  if (!*carr) {
    SWIG_JavaThrowException(jenv, SWIG_JavaOutOfMemoryError, "array memory allocation failed");
    return 0;
  }
  for (i=0; i<sz; i++)
    (*carr)[i] = (double)(*jarr)[i];
  return 1;
}

void SWIG_JavaArrayArgoutDouble (JNIEnv *jenv, jdouble *jarr, double *carr, jdoubleArray input) {
  int i;
  jsize sz = jenv->GetArrayLength(input);
  for (i=0; i<sz; i++)
    jarr[i] = (jdouble)carr[i];
  jenv->ReleaseDoubleArrayElements(input, jarr, 0);
}

jdoubleArray SWIG_JavaArrayOutDouble (JNIEnv *jenv, double *result, jsize sz) {
  jdouble *arr;
  int i;
  jdoubleArray jresult = jenv->NewDoubleArray(sz);
  if (!jresult)
    return NULL;
  arr = jenv->GetDoubleArrayElements(jresult, 0);
  if (!arr)
    return NULL;
  for (i=0; i<sz; i++)
    arr[i] = (jdouble)result[i];
  jenv->ReleaseDoubleArrayElements(jresult, arr, 0);
  return jresult;
}


#endif


#ifdef __cplusplus
extern "C" {
#endif

SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLAddGlyph(JNIEnv *jenv, jclass jcls, jint jarg1) {
  jint jresult = 0 ;
  unsigned short arg1 ;
  int result;

  (void)jenv;
  (void)jcls;
  arg1 = (unsigned short)jarg1;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcAddGlyph(arg1);
  jresult = (jint)result;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLBestGlyph(JNIEnv *jenv, jclass jcls, jintArray jarg1) {
  jint jresult = 0 ;
  unsigned short *arg1 = (unsigned short *) 0 ;
  jint *jarr1 ;
  int result;

  (void)jenv;
  (void)jcls;
  if (!SWIG_JavaArrayInUshort(jenv, &jarr1, &arg1, jarg1)) return 0;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcBestGlyph(arg1);
  jresult = (jint)result;
  SWIG_JavaArrayArgoutUshort(jenv, jarr1, arg1, jarg1);
  delete [] arg1;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLSetGlyphSpeedThresh(JNIEnv *jenv, jclass jcls, jint jarg1) {
  jint jresult = 0 ;
  unsigned short arg1 ;
  int result;

  (void)jenv;
  (void)jcls;
  arg1 = (unsigned short)jarg1;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcSetGlyphSpeedThresh(arg1);
  jresult = (jint)result;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLStartGlyph(JNIEnv *jenv, jclass jcls) {
  jint jresult = 0 ;
  int result;

  (void)jenv;
  (void)jcls;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcStartGlyph();
  jresult = (jint)result;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLStopGlyph(JNIEnv *jenv, jclass jcls) {
  jint jresult = 0 ;
  int result;

  (void)jenv;
  (void)jcls;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcStopGlyph();
  jresult = (jint)result;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLGetGlyph(JNIEnv *jenv, jclass jcls, jint jarg1, jintArray jarg2, jintArray jarg3) {
  jint jresult = 0 ;
  int arg1 ;
  int *arg2 = (int *) 0 ;
  int *arg3 = (int *) 0 ;
  jint *jarr2 ;
  jint *jarr3 ;
  int result;

  (void)jenv;
  (void)jcls;
  arg1 = (int)jarg1;
  if (!SWIG_JavaArrayInInt(jenv, &jarr2, &arg2, jarg2)) return 0;
  if (!SWIG_JavaArrayInInt(jenv, &jarr3, &arg3, jarg3)) return 0;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcGetGlyph(arg1,arg2,arg3);
  jresult = (jint)result;
  SWIG_JavaArrayArgoutInt(jenv, jarr2, arg2, jarg2);
  SWIG_JavaArrayArgoutInt(jenv, jarr3, arg3, jarg3);
  delete [] arg2;
  delete [] arg3;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLGetGlyphLength(JNIEnv *jenv, jclass jcls, jintArray jarg1) {
  jint jresult = 0 ;
  unsigned short *arg1 = (unsigned short *) 0 ;
  jint *jarr1 ;
  int result;

  (void)jenv;
  (void)jcls;
  if (!SWIG_JavaArrayInUshort (jenv, &jarr1, &arg1, jarg1)) return 0;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcGetGlyphLength(arg1);
  jresult = (jint)result;
  SWIG_JavaArrayArgoutUshort(jenv, jarr1, arg1, jarg1);
  delete [] arg1;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLClearGlyph(JNIEnv *jenv, jclass jcls) {
  jint jresult = 0 ;
  int result;

  (void)jenv;
  (void)jcls;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcClearGlyph();
  jresult = (jint)result;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLLoadGlyphs(JNIEnv *jenv, jclass jcls, jshortArray jarg1) {
  jint jresult = 0 ;
  unsigned char *arg1 = (unsigned char *) 0 ;
  jshort *jarr1 ;
  int result;

  (void)jenv;
  (void)jcls;
  if (!SWIG_JavaArrayInUchar(jenv, &jarr1, &arg1, jarg1)) return 0;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcLoadGlyphs(arg1);
  jresult = (jint)result;
  SWIG_JavaArrayArgoutUchar(jenv, jarr1, arg1, jarg1);
  delete [] arg1;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLStoreGlyphs(JNIEnv *jenv, jclass jcls, jshortArray jarg1, jintArray jarg2) {
  jint jresult = 0 ;
  unsigned char *arg1 = (unsigned char *) 0 ;
  unsigned short *arg2 = (unsigned short *) 0 ;
  jshort *jarr1 ;
  jint *jarr2 ;
  int result;

  (void)jenv;
  (void)jcls;
  if (!SWIG_JavaArrayInUchar(jenv, &jarr1, &arg1, jarg1)) return 0;
  if (!SWIG_JavaArrayInUshort(jenv, &jarr2, &arg2, jarg2)) return 0;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcStoreGlyphs(arg1,arg2);
  jresult = (jint)result;
  SWIG_JavaArrayArgoutUchar(jenv, jarr1, arg1, jarg1);
  SWIG_JavaArrayArgoutUshort(jenv, jarr2, arg2, jarg2);
  delete [] arg1;
  delete [] arg2;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLSetGlyphProbThresh(JNIEnv *jenv, jclass jcls, jint jarg1) {
  jint jresult = 0 ;
  unsigned short arg1 ;
  int result;

  (void)jenv;
  (void)jcls;
  arg1 = (unsigned short)jarg1;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcSetGlyphProbThresh(arg1);
  jresult = (jint)result;
  return jresult;
}


SWIGEXPORT jint JNICALL Java_com_invensense_android_hardware_glyphapiJNI_MLGetLibraryLength(JNIEnv *jenv, jclass jcls, jintArray jarg1) {
  jint jresult = 0 ;
  unsigned short *arg1 = (unsigned short *) 0 ;
  jint *jarr1 ;
  int result;

  (void)jenv;
  (void)jcls;
  if (!SWIG_JavaArrayInUshort(jenv, &jarr1, &arg1, jarg1)) return 0;
  sp<IMplConnection> s_mpl_b = get_mpl_binder(); if(s_mpl_b==0) return false;
  result = (int)s_mpl_b->rpcGetLibraryLength(arg1);
  jresult = (jint)result;
  SWIG_JavaArrayArgoutUshort(jenv, jarr1, arg1, jarg1);
  delete [] arg1;
  return jresult;
}


/* ------  end of glyph ------------------ */


#ifdef __cplusplus
}
#endif

static JNINativeMethod glyphMethods[] = {
    {"MLAddGlyph",   "(I)I",  (void*)Java_com_invensense_android_hardware_glyphapiJNI_MLAddGlyph},
    {"MLLoadGlyphs", "([S)I", (void*)Java_com_invensense_android_hardware_glyphapiJNI_MLLoadGlyphs},
};


/*
 * This is called by the VM when the shared library is first loaded.
 */
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    FUNC_LOG;
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != NULL);

    if (register_invensense_hardware_GestureManager(env) != 0) {
        LOGE("ERROR: PlatformLibrary native registration failed\n");
        goto bail;
    }

    if( jniRegisterNativeMethods(env, "com/invensense/android/hardware/glyphapiJNI", glyphMethods, NELEM(glyphMethods)) != 0) {
        LOGE("ERROR: Could not register native methods for glyphapiJNI\n");
        goto bail;
    }
    
    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    return result;
}

