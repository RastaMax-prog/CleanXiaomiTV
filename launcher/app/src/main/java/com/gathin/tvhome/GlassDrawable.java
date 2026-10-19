package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * "Liquid glass" for pills, cards and buttons: the blurred backdrop behind the view, a light tint, a
 * soft sheen on the upper half and a bright rim that fades downwards. Without a backdrop (no photo or
 * wallpaper) it falls back to the plain translucent pill the launcher always had.
 */
final class GlassDrawable extends Drawable {
    private final View owner;
    private final Backdrop backdrop;
    private final float radius, density;
    /** Darkening over the photo: light for buttons and cards, heavier for menus full of text. */
    private final int veil;
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];

    GlassDrawable(View owner, Backdrop backdrop, float radiusPx, float density) {
        this(owner, backdrop, radiusPx, density, 0x33000000);
    }

    GlassDrawable(View owner, Backdrop backdrop, float radiusPx, float density, int veil) {
        this.veil = veil;
        this.owner = owner;
        this.backdrop = backdrop;
        this.radius = radiusPx;
        this.density = density;
        backdrop.register(owner);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        rect.set(b);
        owner.getLocationOnScreen(loc);
        draw(c, rect, Math.min(radius, rect.height() / 2f), backdrop, loc[0] - b.left, loc[1] - b.top, density, veil);
    }

    /**
     * Glass in rect (canvas coordinates). screenX/screenY: where the canvas origin sits on the screen,
     * so the right part of the backdrop shows through.
     */
    static void draw(Canvas c, RectF rect, float radius, Backdrop backdrop, float screenX, float screenY, float density) {
        draw(c, rect, radius, backdrop, screenX, screenY, density, 0x33000000);
    }

    static void draw(Canvas c, RectF rect, float radius, Backdrop backdrop, float screenX, float screenY,
                     float density, int veil) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap bmp = backdrop.bitmap();
        if (bmp != null && backdrop.screenWidth() > 0) {
            BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(backdrop.screenWidth() / (float) bmp.getWidth(), backdrop.screenHeight() / (float) bmp.getHeight());
            m.postTranslate(-screenX, -screenY);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            p.setFilterBitmap(true);
            c.drawRoundRect(rect, radius, radius, p);
            p.setShader(null);
            // A neutral veil keeps white text readable on bright photos.
            p.setColor(veil);
            c.drawRoundRect(rect, radius, radius, p);
            p.setColor(0x14FFFFFF);
            c.drawRoundRect(rect, radius, radius, p);
        } else {
            p.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x2EFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, radius, radius, p);
        }
        // Sheen: light falling on the upper half of the glass.
        p.setShader(new LinearGradient(0, rect.top, 0, rect.top + rect.height() * 0.6f,
                0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, p);
        // Rim: bright at the top edge, faint at the bottom.
        float sw = Math.max(1f, 1.2f * density);
        RectF edge = new RectF(rect);
        edge.inset(sw / 2f, sw / 2f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x8CFFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(edge, radius, radius, p);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
