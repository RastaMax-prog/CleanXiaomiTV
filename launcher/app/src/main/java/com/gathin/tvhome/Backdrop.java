package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, blurred, slightly more colourful copy of the full-screen picture behind the launcher. Glass
 * surfaces (GlassDrawable, DockDecoration) paint the part of it that lies behind them, which looks like
 * a frosted pane over the photo without blurring anything per frame: the blur happens once per picture.
 */
final class Backdrop {
    /** Width of the blurred copy; it is stretched back over the screen with filtering. */
    private static final int WIDTH = 240;

    private Bitmap blurred;
    private int screenW, screenH;
    private final List<View> users = new ArrayList<>();

    Bitmap bitmap() {
        return blurred;
    }

    int screenWidth() {
        return screenW;
    }

    int screenHeight() {
        return screenH;
    }

    /** Views whose glass should be redrawn when the picture changes. */
    void register(View v) {
        if (!users.contains(v)) users.add(v);
    }

    /** Main thread: takes a blurred copy made by blur(), and redraws the glass. */
    void set(Bitmap blurredCopy, int screenWidth, int screenHeight) {
        blurred = blurredCopy;
        screenW = screenWidth;
        screenH = screenHeight;
        for (View v : users) v.invalidate();
    }

    /** Any thread: a WIDTH-wide, blurred and more saturated copy of the picture, as it is shown centre-cropped. */
    static Bitmap blur(Bitmap src, int screenWidth, int screenHeight) {
        int w = WIDTH, h = Math.max(1, Math.round(WIDTH * (float) screenHeight / screenWidth));
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        // Centre-crop the source like the ImageView that shows it.
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dx = (w - src.getWidth() * scale) / 2f, dy = (h - src.getHeight() * scale) / 2f;
        c.translate(dx, dy);
        c.scale(scale, scale);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.35f);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(src, 0, 0, p);

        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, w, h, 4, true);
            boxBlur(px, w, h, 4, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        // The glass veil (a little darker, then a little lighter) is baked in here, once, instead of
        // being two more full blends over every glass surface on every frame.
        Canvas veil = new Canvas(small);
        veil.drawColor(0x33000000);
        veil.drawColor(0x14FFFFFF);
        return small;
    }

    /** One horizontal or vertical box blur pass of the given radius, in place. */
    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] line = new int[len];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = line[Math.max(0, Math.min(len - 1, i))];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            int n = 2 * r + 1;
            for (int i = 0; i < len; i++) {
                px[horizontal ? l * w + i : i * w + l] = 0xFF000000 | (sr / n) << 16 | (sg / n) << 8 | (sb / n);
                int out = line[Math.max(0, i - r)], in = line[Math.min(len - 1, i + r + 1)];
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }
}
