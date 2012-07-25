/*
 * Copyright (C) 2006 The Android Open Source Project
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
/* Copyright (c) 2011 Freescale Semiconductor, Inc. */

package android.view;

import android.graphics.Rect;

/**
 * Defines the responsibilities for a class that will be a parent of a View.
 * This is the API that a view sees when it wants to interact with its parent.
 * 
 */
public interface ViewParentEink extends ViewParent{

    /**
     * All or part of a child is dirty and needs to be redrawn.
     * 
     * @param child The child which is dirty
     * @param r The area within the child that is invalid
     */
    public void invalidateChild(View child, Rect r, int updateMode);

    /**
     * All or part of a child is dirty and needs to be redrawn.
     *
     * The location array is an array of two int values which respectively
     * define the left and the top position of the dirty child.
     *
     * This method must return the parent of this ViewParent if the specified
     * rectangle must be invalidated in the parent. If the specified rectangle
     * does not require invalidation in the parent or if the parent does not
     * exist, this method must return null.
     *
     * When this method returns a non-null value, the location array must
     * have been updated with the left and top coordinates of this ViewParent.
     *
     * @param location An array of 2 ints containing the left and top
     *        coordinates of the child to invalidate
     * @param r The area within the child that is invalid
     *
     * @return the parent of this ViewParent or null
     */

    public ViewParentEink invalidateChildInParent(int[] location, Rect r, int updateMode);
    
    
    public int  numOfAvailableBuffer(View child,boolean lock);
    
}
