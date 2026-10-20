package com.newtube.mobile.ui.browse;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/** Dimmed cover overlay with four dancing bars: shown over the art of the track that is playing. */
public final class EqualizerView extends View {
    private static final int BARS = 4;
    private static final float[] SPEED = {1.0f, 1.6f, 1.2f, 1.9f};
    private static final float[] OFFSET = {0f, 1.7f, 3.1f, 4.4f};

    private final Paint mScrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    @Nullable
    private ValueAnimator mAnimator;
    private float mPhase;
    private boolean mAnimate;

    public EqualizerView(Context context) {
        this(context, null);
    }

    public EqualizerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mScrim.setColor(0x99000000);
        mBar.setColor(0xFFFFFFFF);
    }

    /** Bars move while {@code playing}; a paused track keeps them still. */
    public void setPlaying(boolean playing) {
        mAnimate = playing;
        updateAnimator();
        invalidate();
    }

    private void updateAnimator() {
        boolean run = mAnimate && isAttachedToWindow() && getVisibility() == VISIBLE;
        if (run && mAnimator == null) {
            mAnimator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            mAnimator.setDuration(1100);
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.setInterpolator(new LinearInterpolator());
            mAnimator.addUpdateListener(a -> {
                mPhase = (float) a.getAnimatedValue();
                invalidate();
            });
            mAnimator.start();
        } else if (!run && mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        updateAnimator();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        canvas.drawRect(0, 0, w, h, mScrim);

        float area = Math.min(w, h) * 0.5f;
        float barW = area / (BARS * 2 - 1);
        float left = (w - area) / 2f;
        float bottom = (h + area) / 2f;
        for (int i = 0; i < BARS; i++) {
            float level = mAnimate
                    ? 0.5f + 0.5f * (float) Math.sin(mPhase * SPEED[i] + OFFSET[i])
                    : 0.35f + 0.1f * i;
            float barH = area * (0.2f + 0.8f * level);
            float x = left + i * barW * 2;
            mRect.set(x, bottom - barH, x + barW, bottom);
            canvas.drawRoundRect(mRect, barW / 2f, barW / 2f, mBar);
        }
    }
}
