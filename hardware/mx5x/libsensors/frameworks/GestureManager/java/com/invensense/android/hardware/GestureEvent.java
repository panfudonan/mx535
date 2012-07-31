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
 * derived from Android SensorEvent implementation
 * modified by kpowell@invensense.com
 */

package com.invensense.android.hardware;

/**
 * This class represents a gesture event and holds informations such as the
 * gesture type (eg: tap, shake, etc...), the time-stamp, 
 * accuracy and of course the gesture's data.
 *
 
 */

public class GestureEvent {

    public final int[] values;

    /**
     * The sensor that generated this event.
     * See {@link android.hardware.SensorManager SensorManager}
     * for details.
     */
    public Gesture gesture;
    
    /**
     * The time in nanosecond at which the event happened
     */
    public long timestamp;
    
    GestureEvent(int size) {
        values = new int[size];
    }
}
