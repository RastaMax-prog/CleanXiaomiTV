package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/**
 * Three bouncing bars for the now playing pill. Running: the bars move; stopped: they rest low.
 * Coloured by a ColorStateList for the pill's state (dark when focused, white otherwise).
 */
final class EqualizerDrawable extends Drawable implements Animatable {
    private static final int BARS = 3;
    /** Each bar's speed and phase, so they don't move together. */
    private static final float[] SPEED = {1.0f, 1.45f, 0.8f};
    private static final float[] PHASE = {0f, 1.7f, 3.1f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final int width, height;
    private final ValueAnimator clock = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI * 20));
    private ColorStateList colors;
    private float t;

    EqualizerDrawable(float density, ColorStateList colors) {
        this.width = Math.round(16 * density);
        this.height = Math.round(14 * density);
        this.colors = colors;
        clock.setDuration(20 * 1800L);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidateSelf();
        });
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        float w = b.width(), h = b.height();
        float barW = w / (BARS * 1.6f);
        float gap = (w - barW * BARS) / (BARS - 1);
        paint.setColor(colors.getColorForState(getState(), colors.getDefaultColor()));
        for (int i = 0; i < BARS; i++) {
            float level = clock.isRunning()
                    ? 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(t * SPEED[i] + PHASE[i]))
                    : 0.25f;
            float x = b.left + i * (barW + gap);
            bar.set(x, b.bottom - h * level, x + barW, b.bottom);
            c.drawRoundRect(bar, barW / 2, barW / 2, paint);
        }
    }

    @Override
    public void start() {
        if (!clock.isRunning()) clock.start();
    }

    @Override
    public void stop() {
        clock.cancel();
        invalidateSelf();
    }

    @Override
    public boolean isRunning() {
        return clock.isRunning();
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        invalidateSelf();
        return true;
    }

    @Override
    public void setTintList(ColorStateList tint) {
        if (tint != null) colors = tint;
        invalidateSelf();
    }

    @Override
    public int getIntrinsicWidth() {
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
