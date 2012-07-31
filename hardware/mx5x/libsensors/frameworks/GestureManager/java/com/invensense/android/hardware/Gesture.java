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
 *   Derived from Android Sensor implementation.
 *   modified by kpowell@invensense.com
 */


package com.invensense.android.hardware;

/** 
 * Class representing a gesture.
 */
public class Gesture {

	public static final int TYPE_ALL            = -1;
    public static final int TYPE_TAP            = 1;
    public static final int TYPE_SHAKE          = 2;
    public static final int TYPE_YAW_IMAGE_ROT  = 3;
    public static final int TYPE_ORIENTATION    = 4;
    public static final int TYPE_GRID_NUM       = 5;
    public static final int TYPE_GRID_CHANGE    = 6;
    public static final int TYPE_CTRL_SIGNAL    = 7;
    public static final int TYPE_MOTION         = 8;
    public static final int TYPE_STEP           = 9;

    /* Some of these fields are set only by the native bindings in 
     * gestureManager.
     */
    private String  mName;
    private String  mVendor;
    private int     mVersion;
    private int     mHandle;
    private int     mType;
    
    
    Gesture() {
    }

    /**
     * @return name string of the gesture.
     */
    public String getName() {
        return mName;
    }

    /**
     * @return vendor string of this gesture.
     */
    public String getVendor() {
        return mVendor;
    }
    
    /**
     * @return generic type of this gesture.
     */
    public int getType() {
        return mType;
    }
    
    /**
     * @return version of the gesture's module.
     */
    public int getVersion() {
        return mVersion;
    }
    
    
    int getHandle() {
        return mHandle;
    }
}
