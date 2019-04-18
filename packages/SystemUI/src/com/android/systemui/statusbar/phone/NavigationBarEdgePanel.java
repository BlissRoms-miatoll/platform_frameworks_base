/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.util.FloatProperty;
import android.util.MathUtils;
import android.view.ContextThemeWrapper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.VibratorHelper;

public class NavigationBarEdgePanel extends View {

    // TODO: read from resources once drawing is finalized.
    private static final int PROTECTION_WIDTH_PX = 4;
    private static final int BASE_EXTENT = 32;
    private static final int ARROW_HEIGHT_DP = 32;
    private static final int POINT_EXTENT_DP = 8;
    private static final int ARROW_THICKNESS_DP = 4;
    private static final float TRACK_LENGTH_MULTIPLIER = 1.5f;
    private static final float START_POINTING_RATIO = 0.3f;
    private static final float POINTEDNESS_BEFORE_SNAP_RATIO = 0.4f;
    private static final int ANIM_DURATION_MS = 150;
    private static final long HAPTIC_TIMEOUT_MS = 200;

    private final VibratorHelper mVibratorHelper;

    private final Paint mPaint = new Paint();
    private final Paint mProtectionPaint = new Paint();

    private final ObjectAnimator mEndAnimator;
    private final ObjectAnimator mLegAnimator;

    private final float mDensity;
    private final float mBaseExtent;
    private final float mPointExtent;
    private final float mHeight;
    private final float mStrokeThickness;

    private final float mSwipeThreshold;

    private boolean mIsDark = false;
    private boolean mShowProtection = false;
    private int mProtectionColorLight;
    private int mArrowColorLight;
    private int mProtectionColorDark;
    private int mArrowColorDark;
    private int mProtectionColor;
    private int mArrowColor;

    private boolean mIsLeftPanel;

    private float mStartX;

    private boolean mDragSlopPassed;
    private long mLastSlopHapticTime;
    private boolean mGestureDetected;
    private boolean mArrowsPointLeft;
    private float mGestureLength;
    private float mLegProgress;
    private float mDragProgress;

    // How much the "legs" of the back arrow have proceeded from being a line to an arrow.
    private static final FloatProperty<NavigationBarEdgePanel> LEG_PROGRESS =
            new FloatProperty<NavigationBarEdgePanel>("legProgress") {
        @Override
        public void setValue(NavigationBarEdgePanel object, float value) {
            object.setLegProgress(value);
        }

        @Override
        public Float get(NavigationBarEdgePanel object) {
            return object.getLegProgress();
        }
    };

    // How far across the view the arrow should be drawn.
    private static final FloatProperty<NavigationBarEdgePanel> DRAG_PROGRESS =
            new FloatProperty<NavigationBarEdgePanel>("dragProgress") {

                @Override
                public void setValue(NavigationBarEdgePanel object, float value) {
                    object.setDragProgress(value);
                }

                @Override
                public Float get(NavigationBarEdgePanel object) {
                    return object.getDragProgress();
                }
            };

    public NavigationBarEdgePanel(Context context) {
        super(context);

        mVibratorHelper = Dependency.get(VibratorHelper.class);

        mEndAnimator = ObjectAnimator.ofFloat(this, DRAG_PROGRESS, 1f);
        mEndAnimator.setAutoCancel(true);
        mEndAnimator.setDuration(ANIM_DURATION_MS);

        mLegAnimator = ObjectAnimator.ofFloat(this, LEG_PROGRESS, 1f);
        mLegAnimator.setAutoCancel(true);
        mLegAnimator.setDuration(ANIM_DURATION_MS);

        mDensity = context.getResources().getDisplayMetrics().density;

        mBaseExtent = dp(BASE_EXTENT);
        mHeight = dp(ARROW_HEIGHT_DP);
        mPointExtent = dp(POINT_EXTENT_DP);
        mStrokeThickness = dp(ARROW_THICKNESS_DP);

        mPaint.setStrokeWidth(mStrokeThickness);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setAntiAlias(true);

        mProtectionPaint.setStrokeWidth(mStrokeThickness + PROTECTION_WIDTH_PX);
        mProtectionPaint.setStrokeCap(Paint.Cap.ROUND);
        mProtectionPaint.setAntiAlias(true);

        loadColors(context);
        // Both panels arrow point the same way
        mArrowsPointLeft = getLayoutDirection() == LAYOUT_DIRECTION_LTR;

        mSwipeThreshold = context.getResources()
                .getDimension(R.dimen.navigation_edge_action_drag_threshold);
        setVisibility(GONE);
    }

    private void loadColors(Context context) {
        final int dualToneDarkTheme = Utils.getThemeAttr(context, R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(context, R.attr.lightIconTheme);
        Context lightContext = new ContextThemeWrapper(context, dualToneLightTheme);
        Context darkContext = new ContextThemeWrapper(context, dualToneDarkTheme);
        mArrowColorLight = Utils.getColorAttrDefaultColor(lightContext, R.attr.singleToneColor);
        mArrowColorDark = Utils.getColorAttrDefaultColor(darkContext, R.attr.singleToneColor);
        mProtectionColorDark = mArrowColorLight;
        mProtectionColorLight = mArrowColorDark;
        updateIsDark(false /* animate */);
    }

    private void updateIsDark(boolean animate) {
        mArrowColor = mIsDark ? mArrowColorDark : mArrowColorLight;
        mProtectionColor = mIsDark ? mProtectionColorDark : mProtectionColorLight;
        mProtectionPaint.setColor(mProtectionColor);
        mPaint.setColor(mArrowColor);
        // TODO: add animation
    }

    public void setIsDark(boolean isDark, boolean animate) {
        mIsDark = isDark;
        updateIsDark(animate);
    }

    public void setShowProtection(boolean showProtection) {
        mShowProtection = showProtection;
        invalidate();
    }

    public void setIsLeftPanel(boolean isLeftPanel) {
        mIsLeftPanel = isLeftPanel;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float edgeOffset = mBaseExtent * mDragProgress - mStrokeThickness;
        float animatedOffset = mPointExtent * mLegProgress;
        canvas.save();
        canvas.translate(
                mIsLeftPanel ? edgeOffset : getWidth() - edgeOffset,
                (getHeight() - mHeight) * 0.5f);

        float outsideX = mArrowsPointLeft ? animatedOffset : 0;
        float middleX = mArrowsPointLeft ? 0 : animatedOffset;

        if (mShowProtection) {
            canvas.drawLine(outsideX, 0, middleX, mHeight * 0.5f, mProtectionPaint);
            canvas.drawLine(middleX, mHeight * 0.5f, outsideX, mHeight, mProtectionPaint);
        }

        canvas.drawLine(outsideX, 0, middleX, mHeight * 0.5f, mPaint);
        canvas.drawLine(middleX, mHeight * 0.5f, outsideX, mHeight, mPaint);
        canvas.restore();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // TODO: read the gesture length from the nav controller.
        mGestureLength = getWidth();
    }

    private void setLegProgress(float progress) {
        mLegProgress = progress;
        invalidate();
    }

    private float getLegProgress() {
        return mLegProgress;
    }

    private void setDragProgress(float dragProgress) {
        mDragProgress = dragProgress;
        invalidate();
    }

    private float getDragProgress() {
        return mDragProgress;
    }

    private void hide() {
        animate().alpha(0f).setDuration(ANIM_DURATION_MS)
                .withEndAction(() -> setVisibility(GONE));
    }

    /**
     * Updates the UI based on the motion events passed in device co-ordinates
     */
    public void handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN : {
                mDragSlopPassed = false;
                mEndAnimator.cancel();
                mLegAnimator.cancel();
                animate().cancel();
                setLegProgress(0f);
                setDragProgress(0f);
                mStartX = event.getX();
                setVisibility(VISIBLE);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                handleNewSwipePoint(event.getX());
                break;
            }
            // Fall through
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                hide();
                break;
            }
        }
    }

    private void handleNewSwipePoint(float x) {
        float dist = MathUtils.abs(x - mStartX);

        // Apply a haptic on drag slop passed
        if (!mDragSlopPassed && dist > mSwipeThreshold) {
            mDragSlopPassed = true;
            mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
            mLastSlopHapticTime = SystemClock.uptimeMillis();
            setAlpha(1f);
        }

        setDragProgress(MathUtils.constrainedMap(
                0, 1.0f,
                0, mGestureLength * TRACK_LENGTH_MULTIPLIER,
                dist));

        if (dist < mGestureLength) {
            float calculatedLegProgress = MathUtils.constrainedMap(
                    0f, POINTEDNESS_BEFORE_SNAP_RATIO,
                    mGestureLength * START_POINTING_RATIO, mGestureLength,
                    dist);

            // Blend animated value with drag calculated value, allow the gesture to continue
            // while the animation is playing with jump cuts in the animation.
            setLegProgress(MathUtils.lerp(calculatedLegProgress, mLegProgress, mDragProgress));

            if (mGestureDetected) {
                mGestureDetected = false;

                mLegAnimator.setFloatValues(POINTEDNESS_BEFORE_SNAP_RATIO);
                mLegAnimator.start();
            }
        } else {
            if (!mGestureDetected) {
                // Prevent another haptic if it was just used
                if (SystemClock.uptimeMillis() - mLastSlopHapticTime > HAPTIC_TIMEOUT_MS) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                mGestureDetected = true;

                mLegAnimator.setFloatValues(1f);
                mLegAnimator.start();
            }
        }
    }

    private float dp(float dp) {
        return mDensity * dp;
    }

    /**
     * Adjust the rect to conform the the actual visible bounding box of the arrow.
     *
     * @param samplingRect the existing bounding box in screen coordinates, to be modified
     */
    public void adjustRectToBoundingBox(Rect samplingRect) {
        // TODO: adjust this. For now we take the complete rect
    }
}
