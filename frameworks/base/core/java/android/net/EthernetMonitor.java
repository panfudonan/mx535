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

import android.util.Log;
import android.util.Config;
import android.net.NetworkInfo;
import android.net.NetworkStateTracker;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Listens for events from the ethernet driver, and passes them on
 * to the {@link EthernetStateTracker} for handling. Runs in its own thread.
 *
 * @hide
 */
public class EthernetMonitor {

    private static final String TAG = "EthernetMonitor";

    /** Events we receive from the supplicant daemon */

    private static final int CONNECTED    = 1;
    private static final int DISCONNECTED = 2;
    private static final int STATE_CHANGE = 3;
    private static final int SCAN_RESULTS = 4;
    private static final int LINK_SPEED   = 5;
    private static final int TERMINATING  = 6;
    private static final int DRIVER_STATE = 7;
    private static final int UNKNOWN      = 8;
    private static final int PLUGGED_AND_UP         =9;
    private static final int PLUGGED_AND_DOWN       =10;
    private static final int UNPLUGGED_AND_DOWN     =11;
    private static final int UNPLUGGED_AND_UP       =12;    
    private static final int ERROR                  =13;    
    
    /** All events coming from the HAL start with this prefix */
    private static final String eventPrefix = "CTRL-EVENT-";
    private static final int eventPrefixLen = eventPrefix.length();

    /**
     * Names of events from HAL (minus the prefix). In the
     * format descriptions, * &quot;<code>x</code>&quot;
     * designates a dynamic value that needs to be parsed out from the event
     * string
     */
    /**
     * <pre>
     * CTRL-EVENT-CONNECTED - Connection to xx:xx:xx:xx:xx:xx completed
     * </pre>
     * <code>xx:xx:xx:xx:xx:xx</code> is the BSSID of the associated access point
     */
    private static final String connectedEvent =    "CONNECTED";
    /**
     * <pre>
     * CTRL-EVENT-DISCONNECTED - Disconnect event - remove keys
     * </pre>
     */
    private static final String disconnectedEvent = "DISCONNECTED";
    /**
     * <pre>
     * CTRL-EVENT-STATE-CHANGE x
     * </pre>
     * <code>x</code> is the numerical value of the new state.
     */
    private static final String stateChangeEvent =  "STATE-CHANGE";
    /**
     * <pre>
     * CTRL-EVENT-SCAN-RESULTS ready
     * </pre>
     */
    private static final String scanResultsEvent =  "SCAN-RESULTS";

    /**
     * <pre>
     * CTRL-EVENT-LINK-SPEED x Mb/s
     * </pre>
     * {@code x} is the link speed in Mb/sec.
     */
    private static final String linkSpeedEvent = "LINK-SPEED";
    /**
     * <pre>
     * CTRL-EVENT-TERMINATING - signal x
     * </pre>
     * <code>x</code> is the signal that caused termination.
     */
    private static final String terminatingEvent =  "TERMINATING";
    /**
     * <pre>
     * CTRL-EVENT-DRIVER-STATE state
     * </pre>
     * <code>state</code> is either STARTED or STOPPED
     */
    private static final String driverStateEvent = "DRIVER-STATE";

    /**
     * Regex pattern for extracting an Ethernet-style MAC address from a string.
     * Matches a strings like the following:<pre>
     * CTRL-EVENT-CONNECTED - Connection to 00:1e:58:ec:d5:6d completed (reauth) [id=1 id_str=]</pre>
     */
    private static Pattern mConnectedEventPattern =
        Pattern.compile("((?:[0-9a-f]{2}:){5}[0-9a-f]{2}) .* \\[id=([0-9]+) ");

    private final EthernetStateTracker mEthernetStateTracker;

    /**
     * This indicates the supplicant connection for the monitor is closed
     */
    private static final String monitorSocketClosed = "connection closed";

    /**
     * This indicates a read error on the monitor socket conenction
     */
    private static final String halRecvError = "recv error";

    /**
     * Tracks consecutive receive errors
     */
    private int mRecvErrors = 0;

    private int mStopMonitor = 0;
    
    private int mLastEvent = -1;
    /**
     * Max errors before we close supplicant connection
     */
    private static final int MAX_RECV_ERRORS    = 10;

    public EthernetMonitor(EthernetStateTracker tracker) {
        mEthernetStateTracker = tracker;
    }

    public void startMonitoring() {
        mStopMonitor = 0;
        mLastEvent = -1;
        new MonitorThread().start();
    }

    public void stopMonitoring() {
        mStopMonitor = 1;
    }
    
    public NetworkStateTracker getNetworkStateTracker() {
        return mEthernetStateTracker;
    }

    class MonitorThread extends Thread {
        public MonitorThread() {
            super("EthernetMonitor");
        }
        
        public void run() {

            //noinspection InfiniteLoopStatement
            for (;;) {
                String eventStr = EthernetNative.statusCommand();
            
                String eventName = eventStr.substring(eventPrefixLen);
                int nameEnd = eventName.indexOf(' ');
                if (nameEnd != -1)
                    eventName = eventName.substring(0, nameEnd);
                if (eventName.length() == 0) {
                    if (Config.LOGD) Log.i(TAG, "Received HAL event with empty event name");
                    continue;
                }
                /*
                 * Map event name into event enum
                 */
                int event;
                if (eventName.equals("PLUGGED_AND_UP"))
                    event = PLUGGED_AND_UP;
                else if (eventName.equals("PLUGGED_AND_DOWN"))
                    event = PLUGGED_AND_DOWN;
                else if (eventName.equals("UNPLUGGED_AND_DOWN"))
                    event = UNPLUGGED_AND_DOWN;
                else if (eventName.equals("UNPLUGGED_AND_UP"))
                    event = UNPLUGGED_AND_UP;
                else if (eventName.equals("UNKNOWN"))
                    event = UNKNOWN;
                else 
                    event = ERROR;
                    
                String eventData = eventStr;
                if(event != mLastEvent)
                {
                    handleEvent(event, eventData);
                    mLastEvent = event;
                }

                nap(2);
                mRecvErrors = 0;
                
                if(mStopMonitor == 1)
                {
                    break;
                }
            }
        }

        private void handleDriverEvent(String state) {
            if (state == null) {
                return;
            }
            if (state.equals("STOPPED")) {
                mEthernetStateTracker.notifyDriverStopped();
            } else if (state.equals("STARTED")) {
                mEthernetStateTracker.notifyDriverStarted();
            } else if (state.equals("HANGED")) {
                mEthernetStateTracker.notifyDriverHung();
            }
        }

        /**
         * Handle all supplicant events except STATE-CHANGE
         * @param event the event type
         * @param remainder the rest of the string following the
         * event name and &quot;&#8195;&#8212;&#8195;&quot;
         */
        void handleEvent(int event, String remainder) {
            switch (event) {
                case PLUGGED_AND_UP:
                    //handleNetworkStateChange(NetworkInfo.DetailedState.DISCONNECTED, remainder);
                    mEthernetStateTracker.notifyEthernetPluggedandUp();
                    break;

                case PLUGGED_AND_DOWN:
                    //handleNetworkStateChange(NetworkInfo.DetailedState.CONNECTED, remainder);
                    mEthernetStateTracker.notifyEthernetpluggedAndDown();
                    break;

                case UNPLUGGED_AND_DOWN:
                    //handleNetworkStateChange(NetworkInfo.DetailedState.CONNECTED, remainder);
                    mEthernetStateTracker.notifyEthernetUnpluggedAndDown();
                    break;
                    
                case UNPLUGGED_AND_UP:
                    //handleNetworkStateChange(NetworkInfo.DetailedState.CONNECTED, remainder);
                    mEthernetStateTracker.notifyEthernetUnpluggedAndUp();
                    break;
                    
                case UNKNOWN:
                    mEthernetStateTracker.notifyEthernetUnKnown();
                    break;
                    
                case ERROR:
                    mEthernetStateTracker.notifyEthernetUnKnown();
                    break;
            }
        }
    }

    private void handleNetworkStateChange(NetworkInfo.DetailedState newState, String data) {
        mEthernetStateTracker.notifyStateChange(newState);
    }

    /**
     * Sleep for a period of time.
     * @param secs the number of seconds to sleep
     */
    private static void nap(int secs) {
        try {
            Thread.sleep(secs * 1000);
        } catch (InterruptedException ignore) {
        }
    }
}
