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
/* Copyright 2010-2011 Freescale Semiconductor Inc. */

#define LOG_TAG "ethernet"

#include "jni.h"
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/String16.h>

#include "ethernet.h"

#define ETHERNET_PKG_NAME "android/net/EthernetNative"

namespace android {

static jboolean sScanModeActive = false;

/*
 * The following remembers the jfieldID's of the fields
 * of the DhcpInfo Java object, so that we don't have
 * to look them up every time.
 */
static struct fieldIds {
    jclass dhcpInfoClass;
    jmethodID constructorId;
    jfieldID ipaddress;
    jfieldID gateway;
    jfieldID netmask;
    jfieldID dns1;
    jfieldID dns2;
    jfieldID serverAddress;
    jfieldID leaseDuration;
} dhcpInfoFieldIds;

static int doCommand(const char *cmd, char *replybuf, int replybuflen)
{
    size_t reply_len = replybuflen - 1;

    if (::ethernet_command(cmd, replybuf, &reply_len) != 0)
    {
        return -1;
    }
    else {
        // Strip off trailing newline
        if (reply_len > 0 && replybuf[reply_len-1] == '\n')
            replybuf[reply_len-1] = '\0';
        else
            replybuf[reply_len] = '\0';
        return 0;
    }
}

static jint doIntCommand(const char *cmd)
{
    char reply[256];

    if (doCommand(cmd, reply, sizeof(reply)) != 0) {
        return (jint)-1;
    } else {
        return (jint)atoi(reply);
    }
}

static jboolean doBooleanCommand(const char *cmd, const char *expect)
{
    char reply[256];

    if (doCommand(cmd, reply, sizeof(reply)) != 0) {
        return (jboolean)JNI_FALSE;
    } else {
        return (jboolean)(strcmp(reply, expect) == 0);
    }
}

// Send a command to the supplicant, and return the reply as a String
static jstring doStringCommand(JNIEnv *env, const char *cmd)
{
    char reply[4096];

    if (doCommand(cmd, reply, sizeof(reply)) != 0) {
        return env->NewStringUTF(NULL);
    } else {
        String16 str((char *)reply);
        return env->NewString((const jchar *)str.string(), str.size());
    }
}

static jboolean android_net_ethernet_loadDriver(JNIEnv* env, jobject clazz)
{
    return (jboolean)(::ethernet_load_driver() == 0);
}

static jboolean android_net_ethernet_unloadDriver(JNIEnv* env, jobject clazz)
{
    return (jboolean)(::ethernet_unload_driver() == 0);
}


static jstring android_net_ethernet_waitForEvent(JNIEnv* env, jobject clazz)
{
    char buf[256];

    int nread = ::ethernet_wait_for_event(buf, sizeof buf);
    if (nread > 0) {
        return env->NewStringUTF(buf);
    } else {
        return  env->NewStringUTF(NULL);
    }
}

static jstring android_net_ethernet_statusCommand(JNIEnv* env, jobject clazz)
{
    return doStringCommand(env, "STATUS");
}

static jboolean android_net_ethernet_pingCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("PING", "PONG");
}


static jboolean android_net_ethernet_disconnectCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DISCONNECT", "OK");
}

static jboolean android_net_ethernet_reconnectCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("RECONNECT", "OK");
}
static jboolean android_net_ethernet_reassociateCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("REASSOCIATE", "OK");
}



static jboolean android_net_ethernet_startDriverCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DRIVER START", "OK");
}

static jboolean android_net_ethernet_stopDriverCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DRIVER STOP", "OK");
}

static jboolean android_net_ethernet_upEthernetCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DRIVER UP", "OK");
}

static jboolean android_net_ethernet_downEthernetCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DRIVER DOWN", "OK");
}


static jboolean android_net_ethernet_IpAvailableCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DRIVER IP", "OK");
}


static jstring android_net_ethernet_getMacAddressCommand(JNIEnv* env, jobject clazz)
{
    char reply[256];
    char buf[256];

    if (doCommand("DRIVER MACADDR", reply, sizeof(reply)) != 0) {
        return env->NewStringUTF(NULL);
    }
    // reply comes back in the form "Macaddr = XX.XX.XX.XX.XX.XX" where XX
    // is the part of the string we're interested in.
    if (sscanf(reply, "%*s = %255s", buf) == 1)
        return env->NewStringUTF(buf);
    else
        return env->NewStringUTF(NULL);
}


static jboolean android_net_ethernet_setSuspendOptimizationsCommand(JNIEnv* env, jobject clazz, jboolean enabled)
{
    char cmdstr[25];

    snprintf(cmdstr, sizeof(cmdstr), "DRIVER SETSUSPENDOPT %d", enabled ? 0 : 1);
    return doBooleanCommand(cmdstr, "OK");
}


static jboolean android_net_ethernet_doDhcpRequest(JNIEnv* env, jobject clazz, jobject info)
{
    jint ipaddr, gateway, mask, dns1, dns2, server, lease;
    jboolean succeeded = ((jboolean)::do_dhcp_request(&ipaddr, &gateway, &mask,
                                        &dns1, &dns2, &server, &lease) == 0);
    if (succeeded && dhcpInfoFieldIds.dhcpInfoClass != NULL) {
        env->SetIntField(info, dhcpInfoFieldIds.ipaddress, ipaddr);
        env->SetIntField(info, dhcpInfoFieldIds.gateway, gateway);
        env->SetIntField(info, dhcpInfoFieldIds.netmask, mask);
        env->SetIntField(info, dhcpInfoFieldIds.dns1, dns1);
        env->SetIntField(info, dhcpInfoFieldIds.dns2, dns2);
        env->SetIntField(info, dhcpInfoFieldIds.serverAddress, server);
        env->SetIntField(info, dhcpInfoFieldIds.leaseDuration, lease);
    }
    return succeeded;
}

static jstring android_net_ethernet_getDhcpError(JNIEnv* env, jobject clazz)
{
    return env->NewStringUTF(::get_dhcp_error_string());
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gEthernetMethods[] = {
    /* name, signature, funcPtr */

    { "loadDriver", "()Z",  (void *)android_net_ethernet_loadDriver },
    { "unloadDriver", "()Z",  (void *)android_net_ethernet_unloadDriver },

    { "waitForEvent", "()Ljava/lang/String;", (void*) android_net_ethernet_waitForEvent },
    { "statusCommand", "()Ljava/lang/String;", (void*) android_net_ethernet_statusCommand },
    { "pingCommand", "()Z",  (void *)android_net_ethernet_pingCommand },
    { "disconnectCommand", "()Z",  (void *)android_net_ethernet_disconnectCommand },
    { "reconnectCommand", "()Z",  (void *)android_net_ethernet_reconnectCommand },
    { "reassociateCommand", "()Z",  (void *)android_net_ethernet_reassociateCommand },
    { "startDriverCommand", "()Z", (void*) android_net_ethernet_startDriverCommand },
    { "stopDriverCommand", "()Z", (void*) android_net_ethernet_stopDriverCommand },
    { "upEthernetCommand", "()Z", (void*) android_net_ethernet_upEthernetCommand },
    { "downEthernetCommand", "()Z", (void*) android_net_ethernet_downEthernetCommand },
    { "IpAvailableCommand", "()Z", (void*) android_net_ethernet_IpAvailableCommand },    
    { "getMacAddressCommand", "()Ljava/lang/String;", (void*) android_net_ethernet_getMacAddressCommand },
    { "doDhcpRequest", "(Landroid/net/DhcpInfo;)Z", (void*) android_net_ethernet_doDhcpRequest },
    { "getDhcpError", "()Ljava/lang/String;", (void*) android_net_ethernet_getDhcpError },
};

int register_android_net_EthernetManager(JNIEnv* env)
{
    jclass ethernet = env->FindClass(ETHERNET_PKG_NAME);
    LOG_FATAL_IF(ethernet == NULL, "Unable to find class " ETHERNET_PKG_NAME);

    dhcpInfoFieldIds.dhcpInfoClass = env->FindClass("android/net/DhcpInfo");
    if (dhcpInfoFieldIds.dhcpInfoClass != NULL) {
        dhcpInfoFieldIds.constructorId = env->GetMethodID(dhcpInfoFieldIds.dhcpInfoClass, "<init>", "()V");
        dhcpInfoFieldIds.ipaddress = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "ipAddress", "I");
        dhcpInfoFieldIds.gateway = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "gateway", "I");
        dhcpInfoFieldIds.netmask = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "netmask", "I");
        dhcpInfoFieldIds.dns1 = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "dns1", "I");
        dhcpInfoFieldIds.dns2 = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "dns2", "I");
        dhcpInfoFieldIds.serverAddress = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "serverAddress", "I");
        dhcpInfoFieldIds.leaseDuration = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "leaseDuration", "I");
    }

    return AndroidRuntime::registerNativeMethods(env,
            ETHERNET_PKG_NAME, gEthernetMethods, NELEM(gEthernetMethods));
}

}; // namespace android
