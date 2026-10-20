package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;

/**
 * The screensaver's analog clock (dream_analog): a faint dial with twelve marks, thin white hour and
 * minute hands and a gold seconds hand, redrawn once a second while it is on screen. Drawn in software
 * so the hands can cast a soft shadow, which keeps them readable over a bright photo; it is small, so
 * that costs little.
 */
final class AnalogClockView extends View {
    private final float density;
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint second = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Calendar now = Calendar.getInstance();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            // On the next whole second, so the seconds hand steps in time with the real clock.
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    AnalogClockView(Context ctx) {
        this(ctx, null);
    }

    public AnalogClockView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // shadows on lines need software drawing before Android 9
        face.setColor(0x33000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * density);
        ring.setColor(0x59FFFFFF);
        mark.setStrokeCap(Paint.Cap.ROUND);
        hand.setStrokeCap(Paint.Cap.ROUND);
        hand.setColor(0xF2FFFFFF);
        hand.setShadowLayer(4 * density, 0, density, 0x80000000);
        second.setStrokeCap(Paint.Cap.ROUND);
        second.setColor(MainActivity.PRAYER_GOLD);
        second.setStrokeWidth(1.4f * density);
        second.setShadowLayer(3 * density, 0, density, 0x66000000);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 2 * density;
        c.drawCircle(cx, cy, r, face);
        c.drawCircle(cx, cy, r, ring);

        for (int i = 0; i < 12; i++) {
            boolean quarter = i % 3 == 0;
            mark.setStrokeWidth((quarter ? 2.6f : 1.6f) * density);
            mark.setColor(quarter ? 0xF2FFFFFF : 0x8CFFFFFF);
            double a = Math.toRadians(i * 30);
            float outer = r - 7 * density, inner = outer - (quarter ? 0.13f : 0.07f) * r;
            c.drawLine(cx + (float) Math.sin(a) * inner, cy - (float) Math.cos(a) * inner,
                    cx + (float) Math.sin(a) * outer, cy - (float) Math.cos(a) * outer, mark);
        }

        now.setTimeInMillis(System.currentTimeMillis());
        int h = now.get(Calendar.HOUR), m = now.get(Calendar.MINUTE), s = now.get(Calendar.SECOND);
        hand.setStrokeWidth(4.5f * density);
        line(c, cx, cy, (h + m / 60f) * 30f, 0.5f * r, 0.06f * r, hand);
        hand.setStrokeWidth(2.8f * density);
        line(c, cx, cy, (m + s / 60f) * 6f, 0.76f * r, 0.08f * r, hand);
        line(c, cx, cy, s * 6f, 0.84f * r, 0.16f * r, second);

        c.drawCircle(cx, cy, 4 * density, hand);
        c.drawCircle(cx, cy, 1.8f * density, second);
    }

    /** A hand at angle degrees from 12 o'clock, length forward and tail behind the centre. */
    private static void line(Canvas c, float cx, float cy, float degrees, float length, float tail, Paint p) {
        double a = Math.toRadians(degrees);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        c.drawLine(cx - dx * tail, cy - dy * tail, cx + dx * length, cy + dy * length, p);
    }
}
