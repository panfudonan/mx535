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
 * adapted from Android SensorManager.  APIs are deliberately similar for programming convienience.
 * modified by kpowell@invensense.com
 */

package com.invensense.android.hardware;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Class that lets you access Gesture events. Functions similarly to SensorManager.
 * However, this class is not managed by the Android framework, so the developer is
 * responsible for ensuring that only one instance per process is instantiated.
 */
public class GestureManager
{
    static {
        /*
         * Load the library.  If it's already loaded, this does nothing.
         */
    	
        System.loadLibrary("gesture_manager_jni");
        Log.d("gm-dbg", "GM java static init");
    }

    private static final String TAG = "GestureManager";
   
    /*-----------------------------------------------------------------------*/

    Looper mMainLooper;

    /*-----------------------------------------------------------------------*/

    private static final int GESTURE_DISABLE = -1;
    private static boolean sGestureModuleInitialized = false;
    private static ArrayList<Gesture> sFullGestureList = new ArrayList<Gesture>();
    private static SparseArray<List<Gesture>> sGestureListByType = new SparseArray<List<Gesture>>();
    
    /* The thread and the gesture list are global to the process
     * but the actual thread is spawned on demand */
    private static GestureThread sGestureThread;
    private static int sQueue;

    // Used within this module from outside GestureManager, don't make private
    static SparseArray<Gesture> sHandleToGesture = new SparseArray<Gesture>();
    static final ArrayList<ListenerDelegate> sListeners = new ArrayList<ListenerDelegate>();

    /*-----------------------------------------------------------------------*/

    static private class GestureThread {

        Thread mThread;
        boolean mGesturesReady;

        GestureThread() {
            
        }

        @Override
        protected void finalize() {
        }

        // must be called with sListeners lock
        boolean startLocked() {
            try {
                if (mThread == null) {
                    
					mGesturesReady = false;
					GestureThreadRunnable runnable = new GestureThreadRunnable();
					Thread thread = new Thread(runnable, GestureThread.class.getName());
					thread.start();
					synchronized (runnable) {
						while (mGesturesReady == false) {
							runnable.wait();
						}
					}
					mThread = thread;
                    
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in startLocked: ", e);
            //} catch (InterruptedException e) {
            }
            return mThread == null ? false : true;
        }

        private class GestureThreadRunnable implements Runnable {
            
            GestureThreadRunnable() {
            }

            private boolean open() {
                sQueue = gestures_create_queue();
                return true;
            }

            public void run() {
                //Log.d(TAG, "entering main gesture thread");
                final int[] values = new int[6];
                final int[] status = new int[1];
                final long timestamp[] = new long[1];
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

                if (!open()) {
                    return;
                }

                synchronized (this) {
                    // we've opened the driver, we're ready to open the gestures
                    mGesturesReady = true;
                    this.notify();
                }

                while (true) {
                    // wait for an event
                    final int gesture = gestures_data_poll(sQueue, values, status, timestamp);

                    int accuracy = status[0];
                    synchronized (sListeners) {
                        if (gesture == -1 || sListeners.isEmpty()) {
                            if (gesture == -1) {
                                // we lost the connection to the event stream. this happens
                                // when the last listener is removed.
                                Log.d(TAG, "_gestures_data_poll() failed, we bail out.");
                            }

                            // we have no more listeners or polling failed, terminate the thread
                            gestures_destroy_queue(sQueue);
                            sQueue = 0;
                            mThread = null;
                            break;
                        }
                        final Gesture gestureObject = sHandleToGesture.get(gesture);
                        if (gestureObject != null) {
                            // report the gesture event to all listeners that
                            // care about it.
                            final int size = sListeners.size();
                            for (int i=0 ; i<size ; i++) {
                                ListenerDelegate listener = sListeners.get(i);
                                if (listener.hasGesture(gestureObject)) {
                                    // this is asynchronous (okay to call
                                    // with sListeners lock held).
                                    listener.onGestureLocked(gestureObject,
                                            values, timestamp, accuracy);
                                }
                            }
                        }
                    }
                }
                Log.d(TAG, "exiting main gesture thread");
            }
        }
    }

    /*-----------------------------------------------------------------------*/

    private class ListenerDelegate {
        final GestureEventListener mGestureEventListener;
        private final ArrayList<Gesture> mGestureList = new ArrayList<Gesture>();
        private final Handler mHandler;
        private GestureEvent mValuesPool;
        public SparseBooleanArray mGestures= new SparseBooleanArray();

        ListenerDelegate(GestureEventListener listener, Gesture gesture, Handler handler) {
            mGestureEventListener = listener;
            Looper looper = (handler != null) ? handler.getLooper() : mMainLooper;
            // currently we create one Handler instance per listener, but we could
            // have one per looper (we'd need to pass the ListenerDelegate
            // instance to handleMessage and keep track of them separately).
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    GestureEvent t = (GestureEvent)msg.obj;
                    mGestureEventListener.onGesture(t);
                    returnToPool(t);
                }
            };
            addGesture(gesture);
        }

        protected GestureEvent createGestureEvent() {
            return new GestureEvent(6);
        }

        protected GestureEvent getFromPool() {
            GestureEvent t = null;
            synchronized (this) {
                // remove the array from the pool
                t = mValuesPool;
                mValuesPool = null;
            }
            if (t == null) {
                // the pool was empty, we need a new one
                t = createGestureEvent();
            }
            return t;
        }

        protected void returnToPool(GestureEvent t) {
            synchronized (this) {
                // put back the array into the pool
                if (mValuesPool == null) {
                    mValuesPool = t;
                }
            }
        }

        Object getListener() {
            return mGestureEventListener;
        }

        void addGesture(Gesture gesture) {
            mGestures.put(gesture.getHandle(), true);
            mGestureList.add(gesture);
        }
        int removeGesture(Gesture gesture) {
            mGestures.delete(gesture.getHandle());
            mGestureList.remove(gesture);
            return mGestures.size();
        }
        boolean hasGesture(Gesture gesture) {
            return mGestures.get(gesture.getHandle());
        }
        List<Gesture> getGestures() {
            return mGestureList;
        }

        void onGestureLocked(Gesture gesture, int[] values, long[] timestamp, int accuracy) {
            GestureEvent t = getFromPool();
            final int[] v = t.values;
            v[0] = values[0];
            v[1] = values[1];
            v[2] = values[2];
            v[3] = values[3];
            v[4] = values[4];
            v[5] = values[5];
            t.timestamp = timestamp[0];
            t.gesture = gesture;
            Message msg = Message.obtain();
            msg.what = 0;
            msg.obj = t;
            mHandler.sendMessage(msg);
        }
    }

   /**
     * {@hide}
     */
    public GestureManager(Looper mainLooper) {
        
        mMainLooper = mainLooper;

        synchronized(sListeners) {
            if (!sGestureModuleInitialized) {
                sGestureModuleInitialized = true;

                nativeClassInit();
 
                // initialize the gesture list
                gestures_module_init();
                final ArrayList<Gesture> fullList = sFullGestureList;
                int i = 0;
                do {
                    Gesture gesture = new Gesture();
                    i = gestures_module_get_next_gesture(gesture, i);

                    if (i>=0) {
                        //Log.d(TAG, "found gesture: " + gesture.getName() +
                        //        ", handle=" + gesture.getHandle());
                        fullList.add(gesture);
                        sHandleToGesture.append(gesture.getHandle(), gesture);
                    }
                } while (i>0);

                sGestureThread = new GestureThread();
            }
        }
    }

    /**
     * Use this method to get the list of available gestures of a certain
     * type. 
     * @param type of gestures requested
     * @return a list of gestures matching the asked type.
     */
    public List<Gesture> getGestureList(int type) {
        // cache the returned lists the first time
        List<Gesture> list;
        final ArrayList<Gesture> fullList = sFullGestureList;
        synchronized(fullList) {
            list = sGestureListByType.get(type);
            if (list == null) {
                if (type == Gesture.TYPE_ALL) {
                    list = fullList;
                } else {
                    list = new ArrayList<Gesture>();
                    for (Gesture i : fullList) {
                        if (i.getType() == type)
                            list.add(i);
                    }
                }
                list = Collections.unmodifiableList(list);
                sGestureListByType.append(type, list);
            }
        }
        return list;
    }

    /**
     * Use this method to get the default gesture for a given type. 
     *
     * @param type of gestures requested
     * @return the default gesture matching the asked type.
     */
    public Gesture getDefaultGesture(int type) {
        // TODO: need to be smarter, for now, just return the 1st gesture
        List<Gesture> l = getGestureList(type);
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * Unregisters a listener for the gestures with which it is registered.
     *
     * @param listener a GestureEventListener object
     * @param gesture the gesture to unregister from
     *
     */
    public void unregisterListener(GestureEventListener listener, Gesture gesture) {
        unregisterListener((Object)listener, gesture);
    }

    /**
     * Unregisters a listener for all gestures.
     *
     * @param listener a GestureEventListener object
     *
     */
    public void unregisterListener(GestureEventListener listener) {
        unregisterListener((Object)listener);
    }


    private boolean enableGestureLocked(Gesture gesture) {
    	int delay = 30;
        boolean result = false;
        for (ListenerDelegate i : sListeners) {
            if (i.hasGesture(gesture)) {
                String name = gesture.getName();
                int handle = gesture.getHandle();
                result = gestures_enable_gesture(sQueue, name, handle, delay);
                Log.d("gm-dbg", "called jni gesture_enable_gesture");
                break;
            }
        }
        Log.d("gm-dbg", "enableGestureLocked returning " + result);
        return result;
    }

    private boolean disableGestureLocked(Gesture gesture) {
        for (ListenerDelegate i : sListeners) {
            if (i.hasGesture(gesture)) {
                // not an error, it's just that this gesture is still in use
                return true;
            }
        }
        String name = gesture.getName();
        int handle = gesture.getHandle();
        return gestures_enable_gesture(sQueue, name, handle, GESTURE_DISABLE);
    }

    /**
     * Registers a GestureEventListener for the given gesture.
     *
     * @param listener A GestureEventListener object.
     * @param gesture The Gesture to register to.
     *
     * @return true if the gesture is supported and successfully enabled.
     *
     */
    public boolean registerListener(GestureEventListener listener, Gesture gesture) {
        return registerListener(listener, gesture, null);
    }

    /**
     * Registers a GestureEventListener for the given gesture.
     *
     * @param listener A GestureEventListener object.
     * @param gesture The Gesture to register to.
     * @param handler The {@link android.os.Handler Handler} the
     * {@link android.hardware.GestureEvent gesture events} will be delivered to.
     *
     * @return true if the gesture is supported and successfully enabled.
     *
     */
    public boolean registerListener(GestureEventListener listener, Gesture gesture, Handler handler) {
    	if (listener == null || gesture == null) {
    		return false;
    	}
    	boolean result = true;
        
    	synchronized (sListeners) {
    		ListenerDelegate l = null;
    		for (ListenerDelegate i : sListeners) {
    			if (i.getListener() == listener) {
    				l = i;
    				break;
    			}
    		}

    		if (l == null) {
    			l = new ListenerDelegate(listener, gesture, handler);
    			sListeners.add(l);
    			if (!sListeners.isEmpty()) {
    				if(sGestureThread.startLocked()) {
    					if(!enableGestureLocked(gesture)) {
    						sListeners.remove(l);
    						Log.d("gm-dbg", "1");
    						result = false;
    					}
    				} else {
    					sListeners.remove(l);
    					Log.d("gm-dbg", "2");
    					result = false;
    				}
    			} else {
    				Log.d("gm-dbg", "3");
    				result = false;
    			}
    		} else {
    			l.addGesture(gesture);
    			if(!enableGestureLocked(gesture)) {
    				l.removeGesture(gesture);
    				Log.d("gm-dbg", "4");
    				result = false;
    			}
    		}
    	}

    	return result;
    }

    private void unregisterListener(Object listener, Gesture gesture) {
        if (listener == null || gesture == null) {
            return;
        }

        synchronized (sListeners) {
            final int size = sListeners.size();
            for (int i=0 ; i<size ; i++) {
                ListenerDelegate l = sListeners.get(i);
                if (l.getListener() == listener) {
                    if (l.removeGesture(gesture) == 0) {
                        // if we have no more gestures enabled on this listener,
                        // take it off the list.
                        sListeners.remove(i);
                    }
                    break;
                }
            }
            disableGestureLocked(gesture);
        }
    }

    private void unregisterListener(Object listener) {
        if (listener == null) {
            return;
        }

        synchronized (sListeners) {
            final int size = sListeners.size();
            for (int i=0 ; i<size ; i++) {
                ListenerDelegate l = sListeners.get(i);
                if (l.getListener() == listener) {
                    sListeners.remove(i);
                    // disable all gestures for this listener
                    for (Gesture gesture : l.getGestures()) {
                        disableGestureLocked(gesture);
                    }
                    break;
                }
            }
        }
    }


    private static native void nativeClassInit();

    private static native int gestures_module_init();
    private static native int gestures_module_get_next_gesture(Gesture gesture, int next);

    // Used within this module from outside gestureManager, don't make private
    static native int gestures_create_queue();
    static native void gestures_destroy_queue(int queue);
    static native boolean gestures_enable_gesture(int queue, String name, int gesture, int enable);
    static native int gestures_data_poll(int queue, int[] values, int[] status, long[] timestamp);

}
