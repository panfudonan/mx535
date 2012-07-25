/*
 * Copyright (C) 2008 The Android Open Source Project
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

/* Copyright 2010-2011 Freescale Semiconductor Inc. */

package android.net;

import android.net.DhcpInfo;

/**
 * Native calls for sending requests to the supplicant daemon, and for
 * receiving asynchronous events. All methods of the form "xxxxCommand()"
 * must be single-threaded, to avoid requests and responses initiated
 * from multiple threads from being intermingled.
 * <p/>
 * Note that methods whose names are not of the form "xxxCommand()" do
 * not talk to the supplicant daemon.
 * Also, note that all WifiNative calls should happen in the
 * WifiStateTracker class except for waitForEvent() call which is
 * on a separate monitor channel for WifiMonitor
 *
 * {@hide}
 */
public class EthernetNative {

    public native static String getErrorString(int errorCode);

    public native static boolean loadDriver();
    
    public native static boolean unloadDriver();

    public native static boolean pingCommand();

    public native static boolean reconnectCommand();

    public native static boolean reassociateCommand();

    public native static boolean disconnectCommand();

    public native static String statusCommand();

    public native static String getMacAddressCommand();

    public native static boolean startDriverCommand();

    public native static boolean stopDriverCommand();
    /**
     * Start filtering out multicast packets, to reduce battery consumption
     * that would result from processing them, only to discard them.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public native static boolean startPacketFiltering();

    /**
     * Stop filtering out multicast packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public native static boolean stopPacketFiltering();
    
    public native static boolean setPowerModeCommand(int mode);

    public native static int getPowerModeCommand();

    public native static boolean doDhcpRequest(DhcpInfo results);

    public native static String getDhcpError();

    public native static boolean setSuspendOptimizationsCommand(boolean enabled);

    /**
     * Wait for the supplicant to send an event, returning the event string.
     * @return the event string sent by the supplicant.
     */
    public native static String waitForEvent();
    
    
    public native static boolean upEthernetCommand();

    public native static boolean downEthernetCommand();    

    public native static boolean IpAvailableCommand();    
        
}
