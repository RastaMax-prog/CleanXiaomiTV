package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.PorterDuff;
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
 * soft sheen on the upper half and a thin even edge. Without a backdrop (no photo or
 * wallpaper) it falls back to the plain translucent pill the launcher always had.
 */
final class GlassDrawable extends Drawable {
    private final View owner;
    private final Backdrop backdrop;
    private final float radius, density;
    /** Extra darkening over the baked-in veil: none for buttons and cards, some for menus full of text. */
    private final int veil;
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];

    GlassDrawable(View owner, Backdrop backdrop, float radiusPx, float density) {
        this(owner, backdrop, radiusPx, density, 0);
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
    /** The pills' and panels' edge before the glass: an even 1dp line, 22% white. */
    static final int EDGE = 0x38FFFFFF;
    /** The dock band's edge before the glass: an even 1dp line, 12% white. */
    static final int DOCK_EDGE = 0x1FFFFFFF;

    static void draw(Canvas c, RectF rect, float radius, Backdrop backdrop, float screenX, float screenY, float density) {
        draw(c, rect, radius, backdrop, screenX, screenY, density, 0, EDGE);
    }

    static void draw(Canvas c, RectF rect, float radius, Backdrop backdrop, float screenX, float screenY,
                     float density, int veil) {
        draw(c, rect, radius, backdrop, screenX, screenY, density, veil, EDGE);
    }

    static void draw(Canvas c, RectF rect, float radius, Backdrop backdrop, float screenX, float screenY,
                     float density, int veil, int edgeColor) {
        // One fill for the body of the glass: the blurred photo (veil already baked in) and the sheen on
        // its upper half, composed in a single shader so the GPU blends this area once, not four times.
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Shader sheen = new LinearGradient(0, rect.top, 0, rect.top + rect.height() * 0.6f,
                0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        Shader body;
        Bitmap bmp = backdrop.bitmap();
        if (bmp != null && backdrop.screenWidth() > 0) {
            BitmapShader photo = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(backdrop.screenWidth() / (float) bmp.getWidth(), backdrop.screenHeight() / (float) bmp.getHeight());
            m.postTranslate(-screenX, -screenY);
            photo.setLocalMatrix(m);
            body = new ComposeShader(photo, sheen, PorterDuff.Mode.SRC_OVER);
            p.setFilterBitmap(true);
        } else {
            body = new ComposeShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x2EFFFFFF, 0x14FFFFFF,
                    Shader.TileMode.CLAMP), sheen, PorterDuff.Mode.SRC_OVER);
        }
        p.setShader(body);
        c.drawRoundRect(rect, radius, radius, p);
        if (veil != 0 && bmp != null) {
            p.setShader(null);
            p.setColor(veil);
            c.drawRoundRect(rect, radius, radius, p);
        }
        // Edge: the same even line the pills and dock band had before the glass.
        float sw = Math.max(1f, density);
        RectF edge = new RectF(rect);
        edge.inset(sw / 2f, sw / 2f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setShader(null);
        p.setColor(edgeColor);
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
