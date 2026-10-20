package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** Ring chart of the cache split: one arc per category, small gaps between them. */
public final class StorageDonutView extends View {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private long[] mValues = new long[0];
    private int[] mColors = new int[0];

    public StorageDonutView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.BUTT);
    }

    public void setData(long[] values, int[] colors) {
        mValues = values;
        mColors = colors;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float stroke = Math.min(getWidth(), getHeight()) * 0.17f;
        mPaint.setStrokeWidth(stroke);
        float inset = stroke / 2f + 2f;
        mRect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        long total = 0;
        int parts = 0;
        for (long v : mValues) {
            total += v;
            if (v > 0) {
                parts++;
            }
        }
        if (total <= 0) {
            mPaint.setColor(0x33808080);
            canvas.drawArc(mRect, 0, 360, false, mPaint);
            return;
        }
        float gap = parts > 1 ? 3f : 0f;
        float start = -90f;
        for (int i = 0; i < mValues.length; i++) {
            if (mValues[i] <= 0) {
                continue;
            }
            float sweep = 360f * mValues[i] / total;
            mPaint.setColor(mColors[i]);
            canvas.drawArc(mRect, start + gap / 2f, Math.max(0.5f, sweep - gap), false, mPaint);
            start += sweep;
        }
    }
}
