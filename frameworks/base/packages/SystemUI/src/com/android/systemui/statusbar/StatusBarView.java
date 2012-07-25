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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.systemui.R;
import android.view.KeyEvent;
import android.content.Intent;

public class StatusBarView extends FrameLayout {
    private static final String TAG = "StatusBarView";
    public static final int RESV_KEY_BACK = KeyEvent.KEYCODE_BACK;
    public static final int RESV_KEY_VOL_DOWN = KeyEvent.KEYCODE_VOLUME_DOWN;
    public static final int RESV_KEY_VOL_UP = KeyEvent.KEYCODE_VOLUME_UP;

    static final int DIM_ANIM_TIME = 400;
    
    StatusBarService mService;
    boolean mTracking;
    int mStartX, mStartY;
    ViewGroup mNotificationIcons;
    ViewGroup mStatusIcons;
    View mDate;
    FixedSizeDrawable mBackground;
    ImageView mBackIcon;
    ImageView mVolUp;
    ImageView mVolDown;

    int mResvKeyState = -1;
    int mResvKeyCode  = -1;
    
    public StatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mNotificationIcons = (ViewGroup)findViewById(R.id.notificationIcons);
        mStatusIcons = (ViewGroup)findViewById(R.id.statusIcons);
        mDate = findViewById(R.id.date);

        mBackground = new FixedSizeDrawable(mDate.getBackground());
        mBackground.setFixedBounds(0, 0, 0, 0);
        mDate.setBackgroundDrawable(mBackground);
        mBackIcon = (ImageView)findViewById(R.id.status_back);
        mVolUp = (ImageView)findViewById(R.id.status_vol_up);
        mVolDown = (ImageView)findViewById(R.id.status_vol_down);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mService.onBarViewAttached();
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mService.updateExpandedViewPos(StatusBarService.EXPANDED_LEAVE_ALONE);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        // put the date date view quantized to the icons
        int oldDateRight = mDate.getRight();
        int newDateRight;

        newDateRight = getDateSize(mNotificationIcons, oldDateRight,
                getViewOffset(mNotificationIcons));
        if (newDateRight < 0) {
            int offset = getViewOffset(mStatusIcons);
            if (oldDateRight < offset) {
                newDateRight = oldDateRight;
            } else {
                newDateRight = getDateSize(mStatusIcons, oldDateRight, offset);
                if (newDateRight < 0) {
                    newDateRight = r;
                }
            }
        }
        int max = r - getPaddingRight();
        if (newDateRight > max) {
            newDateRight = max;
        }

        mDate.layout(mDate.getLeft(), mDate.getTop(), newDateRight, mDate.getBottom());
        mBackground.setFixedBounds(-mDate.getLeft(), -mDate.getTop(), (r-l), (b-t));
    }

    /**
     * Gets the left position of v in this view.  Throws if v is not
     * a child of this.
     */
    private int getViewOffset(View v) {
        int offset = 0;
        while (v != this) {
            offset += v.getLeft();
            ViewParent p = v.getParent();
            if (v instanceof View) {
                v = (View)p;
            } else {
                throw new RuntimeException(v + " is not a child of " + this);
            }
        }
        return offset;
    }

    private int getDateSize(ViewGroup g, int w, int offset) {
        final int N = g.getChildCount();
        for (int i=0; i<N; i++) {
            View v = g.getChildAt(i);
            int l = v.getLeft() + offset;
            int r = v.getRight() + offset;
            if (w >= l && w <= r) {
                return r;
            }
        }
        return -1;
    }

    /**
     * Ensure that, if there is no target under us to receive the touch,
     * that we process it ourself.  This makes sure that onInterceptTouchEvent()
     * is always called for the entire gesture.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(mService.mExpanded==true || mService.mTracking==true) {
            if (event.getAction() != MotionEvent.ACTION_DOWN) {
                mService.interceptTouchEvent(event);
            }
            return true;
        }

        if(mResvKeyState == -1) { // remembered key state, no reserve
            switch(getResvKeyArea(event)) {
	        case RESV_KEY_VOL_UP:
	        case RESV_KEY_VOL_DOWN:
                case RESV_KEY_BACK:
                {
                    mResvKeyState = event.getAction();
                    mResvKeyCode  = getResvKeyArea(event);
                    updateResvKeyIcon(mResvKeyState, mResvKeyCode);
                }
                break;

                default:
                    if (event.getAction() != MotionEvent.ACTION_DOWN) {
                        mService.interceptTouchEvent(event);
                    }
          }
          } else {
              mResvKeyState = event.getAction(); // new state
              if (mResvKeyState == MotionEvent.ACTION_MOVE){
                  if (mResvKeyCode != getResvKeyArea(event)) {
                      /* out of bound, resume the icon */
                      updateResvKeyIcon(MotionEvent.ACTION_UP, mResvKeyCode);
                      mResvKeyCode  = -1;
                      mResvKeyState = -1;
                  }
              } else if (mResvKeyState == MotionEvent.ACTION_UP) {
                  updateResvKeyIcon(mResvKeyState, mResvKeyCode);
                  mResvKeyCode  = -1;
                  mResvKeyState = -1;
              } else {
              //Log.d(TAG, "state machine error! Never be here!");
              }
          }
          return true;
   }

    private int getResvKeyArea(MotionEvent event) {
        int offset = getViewOffset(mStatusIcons);
        int oldDateRight = mDate.getRight();
        int newDateRight = getDateSize(mNotificationIcons, oldDateRight,
                 getViewOffset(mNotificationIcons));
        if ((event.getX() >= (mBackIcon.getLeft()+newDateRight))
                && (event.getY() <= this.getHeight())) {
             return RESV_KEY_BACK;
        } else if ((event.getX() >= (mVolDown.getLeft()))
		&& (event.getX()<(mBackIcon.getRight()))
                && (event.getY() <= this.getHeight())){
	     return RESV_KEY_VOL_DOWN;
	} else if ((event.getX() >= (mVolUp.getLeft()))
		&& (event.getX()<(mVolDown.getRight()))
                && (event.getY() <= this.getHeight())) {
	     return RESV_KEY_VOL_UP;
	}
             return -1;
   }

    private int updateResvKeyIcon(int state, int key) {
    if(key == RESV_KEY_BACK) {
        if(state == MotionEvent.ACTION_UP) {
            mBackIcon.setImageResource(R.drawable.stat_back);
            Intent intent = new Intent(Intent.ACTION_ICONKEY_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            intent.putExtra("keycode",   mResvKeyCode);
            mService.sendIntent(intent);
        }
        else if (state == MotionEvent.ACTION_DOWN) {
            mBackIcon.setImageResource(R.drawable.stat_back_pressed);
        }
    }else if(key == RESV_KEY_VOL_DOWN){
	if(state == MotionEvent.ACTION_UP){
            mVolDown.setImageResource(R.drawable.stat_vol_down);
            Intent intent = new Intent(Intent.ACTION_ICONKEY_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            intent.putExtra("keycode",   mResvKeyCode);
            mService.sendIntent(intent);
        }else if(state == MotionEvent.ACTION_DOWN){
            mVolDown.setImageResource(R.drawable.stat_vol_down_pressed);
        }
    } else if (key == RESV_KEY_VOL_UP){
	if (state == MotionEvent.ACTION_UP) {
            mVolUp.setImageResource(R.drawable.stat_vol_up);
            Intent intent = new Intent(Intent.ACTION_ICONKEY_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            intent.putExtra("keycode",   mResvKeyCode);
            mService.sendIntent(intent);
        } else if (state == MotionEvent.ACTION_DOWN) {
            mVolUp.setImageResource(R.drawable.stat_vol_up_pressed);
        }
    }
    return 0;
   }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if((event.getX() < mVolUp.getLeft())){
              return mService.interceptTouchEvent(event) ? true : super.onInterceptTouchEvent(event);
        }
        return false;
   }
}
