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
/*
 *   Copyright 2009-2011 Freescale Semiconductor, Inc. All Rights Reserved.
 */

package com.android.server;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.UEventObserver;
import android.util.Slog;
import android.media.AudioManager;

import java.io.FileReader;
import java.io.FileNotFoundException;

/**
 * <p>HDMIObserver monitors for a wired HDMI connection.
 */
class HDMIObserver extends UEventObserver {
    private static final String TAG = HDMIObserver.class.getSimpleName();
    private static final boolean LOG = true;

    private static final String HDMI_UEVENT_MATCH = "DEVPATH=/devices/platform/sii902x.0";
    private static final String HDMI_STATE_PATH = "/sys/devices/platform/sii902x.0/cable_state";
/*
    private static final int BIT_HEADSET = (1 << 0);
    private static final int BIT_HEADSET_NO_MIC = (1 << 1);
    private static final int SUPPORTED_HEADSETS = (BIT_HEADSET|BIT_HEADSET_NO_MIC);
    private static final int HEADSETS_WITH_MIC = BIT_HEADSET;
*/
    private String mHDMIState     = "plugout";
    private String mPrevHDMIState = "plugout";
    private String mHDMIName = "sii9022";

    private final Context mContext;
    private final WakeLock mWakeLock;  // held while there is a pending route change

    public HDMIObserver(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HDMIObserver");
        mWakeLock.setReferenceCounted(false);

        startObserving(HDMI_UEVENT_MATCH);
        init();  // set initial status
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        if (LOG) Slog.e(TAG, "HDMI UEVENT: " + event.toString());

        try {
            update(event.get("DEVNAME"), event.get("EVENT"));
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Could not parse switch state from event " + event);
        }
    }

    private synchronized final void init() {
        char[] buffer = new char[1024];

        String newName    = mHDMIName;
        String newState   = mHDMIState;
        mPrevHDMIState    = mHDMIState;
        try {
            FileReader file = new FileReader(HDMI_STATE_PATH);
            int len = file.read(buffer, 0, 1024);
            // newState = Integer.valueOf((new String(buffer, 0, len)).trim());

            //file = new FileReader(HEADSET_NAME_PATH);
            //len = file.read(buffer, 0, 1024);
            newState = new String(buffer, 0, len).trim();
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "This kernel does not have wired HDMI support");
        } catch (Exception e) {
            Slog.e(TAG, "" , e);
        }

        update(newName, newState);
    }

    private synchronized final void update(String newName, String newState) {
        // Retain only relevant bits
        String HDMIState = newState;
        int    mHDMIState_int     =0;
        int    mPrevHDMIState_int =0;
        int delay = 0;
        // reject all suspect transitions: only accept state changes from:
        // - a: 0 heaset to 1 headset
        // - b: 1 headset to 0 headset
        if (mHDMIState == HDMIState ) {
            return;
        }

        mPrevHDMIState = mHDMIState;
        mHDMIState     = HDMIState;
        
        if(mHDMIState.equals("plugin")) mHDMIState_int = 1;
        if(mPrevHDMIState.equals("plugin")) mPrevHDMIState_int = 1;

        delay = 1000;
        mWakeLock.acquire();
        mHandler.sendMessageDelayed(mHandler.obtainMessage(0,
                                                           mHDMIState_int,
                                                           mPrevHDMIState_int,
                                                           mHDMIName),
                                    delay);
    }

    private synchronized final void sendIntents(int HDMIState, int prevHDMIState, String HDMIName) {


        if ((HDMIState  != prevHDMIState )) {
            //  Pack up the values and broadcast them to everyone
            Intent intent = new Intent(Intent.ACTION_HDMI_PLUG);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            int state = HDMIState;


            intent.putExtra("state", state);
            intent.putExtra("name", HDMIName);

            if (LOG) Slog.e(TAG, "Intent.ACTION_HDMI_PLUG: state: "+state+" name: "+HDMIName);
            // TODO: Should we require a permission?
            ActivityManagerNative.broadcastStickyIntent(intent, null);
        }          
          
    }
    


    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            sendIntents(msg.arg1, msg.arg2, (String)msg.obj);
            mWakeLock.release();
        }
    };
}
