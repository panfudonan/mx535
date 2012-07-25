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

package com.android.server;

import static android.net.EthernetManager.ETHERNET_STATE_DISABLING;
import static android.net.EthernetManager.ETHERNET_STATE_DISABLED;
import static android.net.EthernetManager.ETHERNET_STATE_ENABLING;
import static android.net.EthernetManager.ETHERNET_STATE_ENABLED;
import static android.net.EthernetManager.ETHERNET_STATE_UNKNOWN;


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.IEthernetManager;
import android.net.EthernetManager;
import android.net.EthernetStateTracker;
import android.net.ConnectivityManager;
import android.net.InterfaceConfiguration;
import android.net.NetworkStateTracker;
import android.net.DhcpInfo;
import android.net.NetworkUtils;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.Slog;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.UnknownHostException;

import com.android.internal.app.IBatteryStats;
import android.app.backup.IBackupManager;
import com.android.server.am.BatteryStatsService;
import com.android.internal.R;

/**
 * EthernetService handles remote Ethernet operation requests by implementing
 * the IEthernetManager interface. 
 *
 * @hide
 */
public class EthernetService extends IEthernetManager.Stub {
    private static final String TAG = "EthernetService";
    private static final boolean DBG = false;
    private final EthernetStateTracker mEthernetStateTracker;
    /* TODO: fetch a configurable interface */
    private static final String SOFTAP_IFACE = "wl0.1";

    private Context mContext;

    private AlarmManager mAlarmManager;
    private PendingIntent mIdleIntent;
    private static final int IDLE_REQUEST = 0;
    private boolean mScreenOff;
    private boolean mDeviceIdle;
    private int mPluggedType;

    private enum DriverAction {DRIVER_UNLOAD, NO_DRIVER_UNLOAD};

    private final LockList mLocks = new LockList();
    // some Ethernet lock statistics
    private int mFullHighPerfLocksAcquired;
    private int mFullHighPerfLocksReleased;
    private int mFullLocksAcquired;
    private int mFullLocksReleased;
    private int mScanLocksAcquired;
    private int mScanLocksReleased;


    private INetworkManagementService nwService;
    ConnectivityManager mCm;
    /**
     * See {@link Settings.Secure#ETHERNET_IDLE_MS}. This is the default value if a
     * Settings.Secure value is not present. This timeout value is chosen as
     * the approximate point at which the battery drain caused by Ethernet
     * being enabled but not active exceeds the battery drain caused by
     * re-establishing a connection to the mobile data network.
     */
    private static final long DEFAULT_IDLE_MILLIS = 15 * 60 * 1000; /* 15 minutes */

    private static final String WAKELOCK_TAG = "*ethernet*";

    /**
     * The maximum amount of time to hold the wake lock after a disconnect
     * caused by stopping the driver. Establishing an EDGE connection has been
     * observed to take about 5 seconds under normal circumstances. This
     * provides a bit of extra margin.
     */
    private static final int DEFAULT_WAKELOCK_TIMEOUT = 8000;

    // Wake lock used by driver-stop operation
    private static PowerManager.WakeLock sDriverStopWakeLock;
    // Wake lock used by other operations
    private static PowerManager.WakeLock sWakeLock;

    private static final int MESSAGE_ENABLE_ETHERNET        = 0;
    private static final int MESSAGE_DISABLE_ETHERNET       = 1;
    private static final int MESSAGE_STOP_ETHERNET          = 2;
    private static final int MESSAGE_START_ETHERNET         = 3;
    private static final int MESSAGE_RELEASE_WAKELOCK   = 4;
    private static final int MESSAGE_UPDATE_STATE       = 5;
    private static final int MESSAGE_START_ACCESS_POINT = 6;
    private static final int MESSAGE_STOP_ACCESS_POINT  = 7;
    private static final int MESSAGE_SET_CHANNELS       = 8;
    private static final int MESSAGE_ENABLE_NETWORKS    = 9;
    private static final int MESSAGE_START_SCAN         = 10;
    private static final int MESSAGE_REPORT_WORKSOURCE  = 11;
    private static final int MESSAGE_ENABLE_RSSI_POLLING = 12;


    private final  EthernetHandler mEthernetHandler;

    private boolean mNeedReconfig;

    /**
     * Temporary for computing UIDS that are responsible for starting ETHERNET.
     * Protected by mEthernetStateTracker lock.
     */
    private final WorkSource mTmpWorkSource = new WorkSource();

    /*
     * Last UID that asked to enable ETHERNET.
     */
    private int mLastEnableUid = Process.myUid();


    private static final String ACTION_DEVICE_IDLE =
            "com.android.server.EthernetManager.action.DEVICE_IDLE";

    EthernetService(Context context, EthernetStateTracker tracker) {
        Slog.i(TAG,"start EthernetService");
        mContext = context;
        mEthernetStateTracker = tracker;

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        nwService = INetworkManagementService.Stub.asInterface(b);


        HandlerThread ethernetThread = new HandlerThread("EthernetService");
        ethernetThread.start();
        mEthernetHandler = new EthernetHandler(ethernetThread.getLooper());

        mEthernetStateTracker.setEthernetState(ETHERNET_STATE_DISABLED);

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent idleIntent = new Intent(ACTION_DEVICE_IDLE, null);
        mIdleIntent = PendingIntent.getBroadcast(mContext, IDLE_REQUEST, idleIntent, 0);

        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        sWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        sDriverStopWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

    }

    /**
     * Check if Ethernet needs to be enabled and start
     * if needed
     *
     * This function is used only at boot time
     */
    public void startEthernet() {
        /* Start if Ethernet is enabled or the saved state indicates Ethernet was on */
        boolean ethernetEnabled = 
                (getPersistedEthernetEnabled() || testAndClearEthernetSavedState());
        Slog.i(TAG, "EthernetService starting up with Ethernet " +
                (ethernetEnabled ? "enabled" : "disabled"));
        setEthernetEnabled(ethernetEnabled);
    }


    private boolean testAndClearEthernetSavedState() {
        final ContentResolver cr = mContext.getContentResolver();
        int ethernetSavedState = 0;
        try {
            ethernetSavedState = Settings.Secure.getInt(cr, Settings.Secure.ETHERNET_SAVED_STATE);
            if(ethernetSavedState == 1)
                Settings.Secure.putInt(cr, Settings.Secure.ETHERNET_SAVED_STATE, 0);
        } catch (Settings.SettingNotFoundException e) {
            ;
        }
        return (ethernetSavedState == 1);
    }

    private boolean getPersistedEthernetEnabled() {
        final ContentResolver cr = mContext.getContentResolver();
        try {
            return Settings.Secure.getInt(cr, Settings.Secure.ETHERNET_ON) == 1;
        } catch (Settings.SettingNotFoundException e) {
            Settings.Secure.putInt(cr, Settings.Secure.ETHERNET_ON, 0);
            return false;
        }
    }

    private void persistEthernetEnabled(boolean enabled) {
        final ContentResolver cr = mContext.getContentResolver();
        Settings.Secure.putInt(cr, Settings.Secure.ETHERNET_ON, enabled ? 1 : 0);
    }

    NetworkStateTracker getNetworkStateTracker() {
        return mEthernetStateTracker;
    }

    /**
     * see {@link android.net.EthernetManager#setEthernetEnabled(boolean)}
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    public boolean setEthernetEnabled(boolean enable) {
        Slog.i(TAG, "setEthernetEnabled");
        enforceChangePermission();
        if (mEthernetHandler == null) return false;

        synchronized (mEthernetHandler) {
            // caller may not have WAKE_LOCK permission - it's not required here
            long ident = Binder.clearCallingIdentity();
            sWakeLock.acquire();
            Binder.restoreCallingIdentity(ident);

            mLastEnableUid = Binder.getCallingUid();

            sendEnableMessage(enable, true, Binder.getCallingUid());
        }

        return true;
    }

    /**
     * Enables/disables Ethernet synchronously.
     * @param enable {@code true} to turn Ethernet on, {@code false} to turn it off.
     * @param persist {@code true} if the setting should be persisted.
     * @param uid The UID of the process making the request.
     * @return {@code true} if the operation succeeds (or if the existing state
     *         is the same as the requested state)
     */
    private boolean setEthernetEnabledBlocking(boolean enable, boolean persist, int uid) {
        final int eventualEthernetState = enable ? ETHERNET_STATE_ENABLED : ETHERNET_STATE_DISABLED;
        final int ethernetState = mEthernetStateTracker.getEthernetState();

        Slog.i(TAG, "eventualEthernetState="+eventualEthernetState+" ethernetState="+ethernetState+" enable="+enable);
        if (ethernetState == eventualEthernetState) {
            return true;
        }

        if ((ethernetState == ETHERNET_STATE_UNKNOWN) && !enable) {
            return false;
        }


        setEthernetEnabledState(enable ? ETHERNET_STATE_ENABLING : ETHERNET_STATE_DISABLING, uid);

        if (enable) {

            if (!mEthernetStateTracker.loadDriver()) {
                Slog.e(TAG, "Failed to load Ethernet driver.");
                setEthernetEnabledState(ETHERNET_STATE_UNKNOWN, uid);
                return false;
            }
            
            registerForBroadcasts();
            mEthernetStateTracker.startEventLoop();

        } else {

            mContext.unregisterReceiver(mReceiver);

            mEthernetStateTracker.stopEventLoop();
            mEthernetStateTracker.resetConnections(true);
            mEthernetStateTracker.notifyEthernetDisabled();

            if (!mEthernetStateTracker.unloadDriver()) {
                Slog.e(TAG, "Failed to unload Ethernet driver.");
                setEthernetEnabledState(ETHERNET_STATE_UNKNOWN, uid);
            }
            
        }

        // Success!

        if (persist) {
            persistEthernetEnabled(enable);
        }
        setEthernetEnabledState(eventualEthernetState, uid);
        return true;
    }

    private void setEthernetEnabledState(int ethernetState, int uid) {
        final int previousEthernetState = mEthernetStateTracker.getEthernetState();

        long ident = Binder.clearCallingIdentity();

        // Update state
        mEthernetStateTracker.setEthernetState(ethernetState);

        // Broadcast
        final Intent intent = new Intent(EthernetManager.ETHERNET_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(EthernetManager.EXTRA_ETHERNET_STATE, ethernetState);
        intent.putExtra(EthernetManager.EXTRA_PREVIOUS_ETHERNET_STATE, previousEthernetState);
        mContext.sendStickyBroadcast(intent);
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_ETHERNET_STATE,
                                                "EthernetService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_ETHERNET_STATE,
                                                "EthernetService");

    }

    /**
     * see {@link EthernetManager#getEthernetState()}
     * @return One of {@link EthernetManager#ETHERNET_STATE_DISABLED},
     *         {@link EthernetManager#ETHERNET_STATE_DISABLING},
     *         {@link EthernetManager#ETHERNET_STATE_ENABLED},
     *         {@link EthernetManager#ETHERNET_STATE_ENABLING},
     *         {@link EthernetManager#ETHERNET_STATE_UNKNOWN}
     */
    public int getEthernetEnabledState() {
        enforceAccessPermission();
        return mEthernetStateTracker.getEthernetState();
    }

    /**
     * see {@link android.net.EthernetManager#disconnect()}
     * @return {@code true} if the operation succeeds
     */
    public boolean disconnect() {
        enforceChangePermission();

        return false;
    }

    /**
     * see {@link android.net.EthernetManager#reconnect()}
     * @return {@code true} if the operation succeeds
     */
    public boolean reconnect() {
        enforceChangePermission();

        return false;
    }

    /**
     * see {@link android.net.EthernetManager#reassociate()}
     * @return {@code true} if the operation succeeds
     */
    public boolean reassociate() {
        enforceChangePermission();

        return false;
    }



    private static String removeDoubleQuotes(String string) {
        if (string.length() <= 2) return "";
        return string.substring(1, string.length() - 1);
    }

    private static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }


    private static String makeString(BitSet set, String[] strings) {
        StringBuffer buf = new StringBuffer();
        int nextSetBit = -1;

        /* Make sure all set bits are in [0, strings.length) to avoid
         * going out of bounds on strings.  (Shouldn't happen, but...) */
        set = set.get(0, strings.length);

        while ((nextSetBit = set.nextSetBit(nextSetBit + 1)) != -1) {
            buf.append(strings[nextSetBit].replace('_', '-')).append(' ');
        }

        // remove trailing space
        if (set.cardinality() > 0) {
            buf.setLength(buf.length() - 1);
        }

        return buf.toString();
    }

    private static int lookupString(String string, String[] strings) {
        int size = strings.length;

        string = string.replace('-', '_');

        for (int i = 0; i < size; i++)
            if (string.equals(strings[i]))
                return i;

        if (DBG) {
            Slog.w(TAG, "Failed to look-up a string: " + string);
        }

        return -1;
    }




    /**
     * Return the DHCP-assigned addresses from the last successful DHCP request,
     * if any.
     * @return the DHCP information
     */
    public DhcpInfo getDhcpInfo() {
        enforceAccessPermission();
        return mEthernetStateTracker.getDhcpInfo();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            long idleMillis = DEFAULT_IDLE_MILLIS;

            int stayAwakeConditions =
                Settings.System.getInt(mContext.getContentResolver(),
                                       Settings.System.STAY_ON_WHILE_PLUGGED_IN, 0);
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                if (DBG) {
                    Slog.d(TAG, "ACTION_SCREEN_ON");
                }
                mAlarmManager.cancel(mIdleIntent);
                mDeviceIdle = false;
                mScreenOff = false;
                // Once the screen is on, we are not keeping ETHERNET running
                // because of any locks so clear that tracking immediately.
                sendReportWorkSourceMessage();
                /* DHCP or other temporary failures in the past can prevent
                 * a disabled network from being connected to, enable on screen on
                 */
                if (mEthernetStateTracker.isAnyNetworkDisabled()) {
                    sendEnableNetworksMessage();
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                if (DBG) {
                    Slog.d(TAG, "ACTION_SCREEN_OFF");
                }
                mScreenOff = true;
                /*
                 * Set a timer to put Ethernet to sleep, but only if the screen is off
                 * AND the "stay on while plugged in" setting doesn't match the
                 * current power conditions (i.e, not plugged in, plugged in to USB,
                 * or plugged in to AC).
                 */
                if (!shouldEthernetStayAwake(stayAwakeConditions, mPluggedType)) {

                }
                /* we can return now -- there's nothing to do until we get the idle intent back */
                return;
            } else if (action.equals(ACTION_DEVICE_IDLE)) {
                if (DBG) {
                    Slog.d(TAG, "got ACTION_DEVICE_IDLE");
                }
                mDeviceIdle = true;
                sendReportWorkSourceMessage();
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                /*
                 * Set a timer to put Ethernet to sleep, but only if the screen is off
                 * AND we are transitioning from a state in which the device was supposed
                 * to stay awake to a state in which it is not supposed to stay awake.
                 * If "stay awake" state is not changing, we do nothing, to avoid resetting
                 * the already-set timer.
                 */
                int pluggedType = intent.getIntExtra("plugged", 0);
                if (DBG) {
                    Slog.d(TAG, "ACTION_BATTERY_CHANGED pluggedType: " + pluggedType);
                }
                if (mScreenOff && shouldEthernetStayAwake(stayAwakeConditions, mPluggedType) &&
                        !shouldEthernetStayAwake(stayAwakeConditions, pluggedType)) {
                    long triggerTime = System.currentTimeMillis() + idleMillis;
                    if (DBG) {
                        Slog.d(TAG, "setting ACTION_DEVICE_IDLE timer for " + idleMillis + "ms");
                    }
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, mIdleIntent);
                    mPluggedType = pluggedType;
                    return;
                }
                mPluggedType = pluggedType;

            } else {
                return;
            }

            updateEthernetState();
        }

        /**
         * Determines whether the Ethernet chipset should stay awake or be put to
         * sleep. Looks at the setting for the sleep policy and the current
         * conditions.
         *
         * @see #shouldDeviceStayAwake(int, int)
         */
        private boolean shouldEthernetStayAwake(int stayAwakeConditions, int pluggedType) {
            return true;
        }

        /**
         * Determine whether the bit value corresponding to {@code pluggedType} is set in
         * the bit string {@code stayAwakeConditions}. Because a {@code pluggedType} value
         * of {@code 0} isn't really a plugged type, but rather an indication that the
         * device isn't plugged in at all, there is no bit value corresponding to a
         * {@code pluggedType} value of {@code 0}. That is why we shift by
         * {@code pluggedType&nbsp;&#8212;&nbsp;1} instead of by {@code pluggedType}.
         * @param stayAwakeConditions a bit string specifying which "plugged types" should
         * keep the device (and hence Ethernet) awake.
         * @param pluggedType the type of plug (USB, AC, or none) for which the check is
         * being made
         * @return {@code true} if {@code pluggedType} indicates that the device is
         * supposed to stay awake, {@code false} otherwise.
         */
        private boolean shouldDeviceStayAwake(int stayAwakeConditions, int pluggedType) {
            return (stayAwakeConditions & pluggedType) != 0;
        }
    };

    private void sendEnableMessage(boolean enable, boolean persist, int uid) {
        Message msg = Message.obtain(mEthernetHandler,
                                     (enable ? MESSAGE_ENABLE_ETHERNET : MESSAGE_DISABLE_ETHERNET),
                                     (persist ? 1 : 0), uid);
        msg.sendToTarget();
    }

    private void sendStartMessage(int lockMode) {
        Message.obtain(mEthernetHandler, MESSAGE_START_ETHERNET, lockMode, 0).sendToTarget();
    }

    private void sendEnableNetworksMessage() {
        Message.obtain(mEthernetHandler, MESSAGE_ENABLE_NETWORKS).sendToTarget();
    }

    private void sendReportWorkSourceMessage() {
        Message.obtain(mEthernetHandler, MESSAGE_REPORT_WORKSOURCE).sendToTarget();
    }


    private void reportStartWorkSource() {
        synchronized (mEthernetStateTracker) {
            mTmpWorkSource.clear();
            if (mDeviceIdle) {
                for (int i=0; i<mLocks.mList.size(); i++) {
                    mTmpWorkSource.add(mLocks.mList.get(i).mWorkSource);
                }
            }
            sWakeLock.setWorkSource(mTmpWorkSource);
        }
    }

    private void updateEthernetState() {
        // send a message so it's all serialized
        Message.obtain(mEthernetHandler, MESSAGE_UPDATE_STATE, 0, 0).sendToTarget();
    }

    private void doUpdateEthernetState() {
        boolean ethernetEnabled = getPersistedEthernetEnabled();

        boolean lockHeld;
        synchronized (mLocks) {
            lockHeld = mLocks.hasLocks();
        }


        boolean ethernetShouldBeEnabled = ethernetEnabled;
        boolean ethernetShouldBeStarted = !mDeviceIdle || lockHeld;

        synchronized (mEthernetHandler) {
            if ((mEthernetStateTracker.getEthernetState() == ETHERNET_STATE_ENABLING) ) {
                return;
            }

            if (ethernetShouldBeEnabled) {
                if (ethernetShouldBeStarted) {
                    sWakeLock.acquire();
                    sendEnableMessage(true, false, mLastEnableUid);
                    
                } else if (!mEthernetStateTracker.isDriverStopped()) {
                    int wakeLockTimeout = DEFAULT_WAKELOCK_TIMEOUT;
                    /*
                     * We are assuming that ConnectivityService can make
                     * a transition to cellular data within wakeLockTimeout time.
                     * The wakelock is released by the delayed message.
                     */
                    sDriverStopWakeLock.acquire();
                    mEthernetHandler.sendEmptyMessage(MESSAGE_STOP_ETHERNET);
                    mEthernetHandler.sendEmptyMessageDelayed(MESSAGE_RELEASE_WAKELOCK, wakeLockTimeout);
                }
            } else {
                sWakeLock.acquire();
                sendEnableMessage(false, false, mLastEnableUid);
            }
        }
    }

    private void registerForBroadcasts() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(ACTION_DEVICE_IDLE);
        mContext.registerReceiver(mReceiver, intentFilter);
    }
    
    /**
     * Handler that allows posting to the EthernetThread.
     */
    private class EthernetHandler extends Handler {
        public EthernetHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {

                case MESSAGE_ENABLE_ETHERNET:
                    setEthernetEnabledBlocking(true, msg.arg1 == 1, msg.arg2);
                    sWakeLock.release();
                    break;

                case MESSAGE_START_ETHERNET:
                    reportStartWorkSource();
                    mEthernetStateTracker.restart();
                    sWakeLock.release();
                    break;

                case MESSAGE_UPDATE_STATE:
                    doUpdateEthernetState();
                    break;

                case MESSAGE_DISABLE_ETHERNET:
                    // a non-zero msg.arg1 value means the "enabled" setting
                    // should be persisted
                    setEthernetEnabledBlocking(false, msg.arg1 == 1, msg.arg2);
                    sWakeLock.release();
                    break;

                case MESSAGE_STOP_ETHERNET:
                    mEthernetStateTracker.disconnectAndStop();
                    // don't release wakelock
                    break;

                case MESSAGE_RELEASE_WAKELOCK:
                    sDriverStopWakeLock.release();
                    break;

                case MESSAGE_ENABLE_NETWORKS:
                    break;

                case MESSAGE_REPORT_WORKSOURCE:
                    reportStartWorkSource();
                    break;
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump EthernetService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        pw.println("Ethernet is " + stateName(mEthernetStateTracker.getEthernetState()));
        pw.println("Stay-awake conditions: " +
                Settings.System.getInt(mContext.getContentResolver(),
                                       Settings.System.STAY_ON_WHILE_PLUGGED_IN, 0));
        pw.println();

        pw.println("Internal state:");
        pw.println(mEthernetStateTracker);
        pw.println();
        pw.println("Latest scan results:");
        pw.println();
        pw.println("Locks acquired: " + mFullLocksAcquired + " full, " +
                mFullHighPerfLocksAcquired + " full high perf, " +
                mScanLocksAcquired + " scan");
        pw.println("Locks released: " + mFullLocksReleased + " full, " +
                mFullHighPerfLocksReleased + " full high perf, " +
                mScanLocksReleased + " scan");
        pw.println();
        pw.println("Locks held:");
        mLocks.dump(pw);
    }

    private static String stateName(int ethernetState) {
        switch (ethernetState) {
            case ETHERNET_STATE_DISABLING:
                return "disabling";
            case ETHERNET_STATE_DISABLED:
                return "disabled";
            case ETHERNET_STATE_ENABLING:
                return "enabling";
            case ETHERNET_STATE_ENABLED:
                return "enabled";
            case ETHERNET_STATE_UNKNOWN:
                return "unknown state";
            default:
                return "[invalid state]";
        }
    }

    private class EthernetLock extends DeathRecipient {
        EthernetLock(int lockMode, String tag, IBinder binder, WorkSource ws) {
            super(lockMode, tag, binder, ws);
        }

        public void binderDied() {
            synchronized (mLocks) {
                releaseEthernetLockLocked(mBinder);
            }
        }

        public String toString() {
            return "EthernetLock{" + mTag + " type=" + mMode + " binder=" + mBinder + "}";
        }
    }

    private class LockList {
        private List<EthernetLock> mList;

        private LockList() {
            mList = new ArrayList<EthernetLock>();
        }

        private synchronized boolean hasLocks() {
            return !mList.isEmpty();
        }


        private void addLock(EthernetLock lock) {
            if (findLockByBinder(lock.mBinder) < 0) {
                mList.add(lock);
            }
        }

        private EthernetLock removeLock(IBinder binder) {
            int index = findLockByBinder(binder);
            if (index >= 0) {
                EthernetLock ret = mList.remove(index);
                ret.unlinkDeathRecipient();
                return ret;
            } else {
                return null;
            }
        }

        private int findLockByBinder(IBinder binder) {
            int size = mList.size();
            for (int i = size - 1; i >= 0; i--)
                if (mList.get(i).mBinder == binder)
                    return i;
            return -1;
        }

        private void dump(PrintWriter pw) {
            for (EthernetLock l : mList) {
                pw.print("    ");
                pw.println(l);
            }
        }
    }

    void enforceWakeSourcePermission(int uid, int pid) {
        if (uid == Process.myUid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                pid, uid, null);
    }

    public boolean acquireEthernetLock(IBinder binder, int lockMode, String tag, WorkSource ws) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        if (ws != null && ws.size() == 0) {
            ws = null;
        }
        if (ws != null) {
            enforceWakeSourcePermission(Binder.getCallingUid(), Binder.getCallingPid());
        }
        if (ws == null) {
            ws = new WorkSource(Binder.getCallingUid());
        }
        EthernetLock ethernetLock = new EthernetLock(lockMode, tag, binder, ws);
        synchronized (mLocks) {
            return acquireEthernetLockLocked(ethernetLock);
        }
    }

    private void noteAcquireEthernetLock(EthernetLock ethernetLock) throws RemoteException {

    }

    private void noteReleaseEthernetLock(EthernetLock ethernetLock) throws RemoteException {

    }

    private boolean acquireEthernetLockLocked(EthernetLock ethernetLock) {
        if (DBG) Slog.d(TAG, "acquireEthernetLockLocked: " + ethernetLock);

        mLocks.addLock(ethernetLock);

        long ident = Binder.clearCallingIdentity();
        try {
            noteAcquireEthernetLock(ethernetLock);


            // Be aggressive about adding new locks into the accounted state...
            // we want to over-report rather than under-report.
            sendReportWorkSourceMessage();

            updateEthernetState();
            return true;
        } catch (RemoteException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void updateEthernetLockWorkSource(IBinder lock, WorkSource ws) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (ws != null && ws.size() == 0) {
            ws = null;
        }
        if (ws != null) {
            enforceWakeSourcePermission(uid, pid);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLocks) {
                int index = mLocks.findLockByBinder(lock);
                if (index < 0) {
                    throw new IllegalArgumentException("Ethernet lock not active");
                }
                EthernetLock wl = mLocks.mList.get(index);
                noteReleaseEthernetLock(wl);
                wl.mWorkSource = ws != null ? new WorkSource(ws) : new WorkSource(uid);
                noteAcquireEthernetLock(wl);
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean releaseEthernetLock(IBinder lock) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        synchronized (mLocks) {
            return releaseEthernetLockLocked(lock);
        }
    }

    private boolean releaseEthernetLockLocked(IBinder lock) {
        boolean hadLock;

        EthernetLock ethernetLock = mLocks.removeLock(lock);

        if (DBG) Slog.d(TAG, "releaseEthernetLockLocked: " + ethernetLock);

        hadLock = (ethernetLock != null);

        long ident = Binder.clearCallingIdentity();
        try {
            if (hadLock) {
                noteAcquireEthernetLock(ethernetLock);

            }

            // TODO - should this only happen if you hadLock?
            updateEthernetState();

        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return hadLock;
    }

    private abstract class DeathRecipient
            implements IBinder.DeathRecipient {
        String mTag;
        int mMode;
        IBinder mBinder;
        WorkSource mWorkSource;

        DeathRecipient(int mode, String tag, IBinder binder, WorkSource ws) {
            super();
            mTag = tag;
            mMode = mode;
            mBinder = binder;
            mWorkSource = ws;
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }
    }

}
