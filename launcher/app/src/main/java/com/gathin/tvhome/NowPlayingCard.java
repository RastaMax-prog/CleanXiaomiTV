package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The now playing pill under the top buttons, the same height as the prayer pill: the cover as a circle
 * in the pill's round end, the song with moving bars, the artist, and previous / play-pause / next. The
 * glass takes on the cover's colour, strongest behind it and fading towards the buttons, so cover and pill
 * read as one. OK on a button does that; holding OK
 * opens the music app. Up leaves for the top buttons, Down for the dock (see MainActivity).
 */
final class NowPlayingCard extends LinearLayout {
    interface Exits {
        void up();

        void down();
    }

    /** The cover sits ART_INSET from the top, bottom and left: a circle concentric with the pill's end. */
    private static final int HEIGHT = 38, ART_INSET = 4, ART = HEIGHT - 2 * ART_INSET, BUTTON = 30;

    private final float density;
    private final ImageView art;
    /** The cover's colour across the glass; transparent without a cover. */
    private final GradientDrawable tint;
    private final TextView title, artist;
    private final ImageView prev, toggle, next;
    private final EqualizerDrawable equalizer;
    private NowPlaying.Track track;
    /** The song the art on screen belongs to, and whether it is the real cover. */
    private String artKey;
    private boolean artIsCover;

    NowPlayingCard(Context ctx, Backdrop backdrop, Typeface font, Exits exits) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(HEIGHT));
        setPadding(dp(ART_INSET), 0, dp(4), 0);
        tint = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0, 0});
        tint.setCornerRadius(dp(HEIGHT / 2));
        setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                new GlassDrawable(this, backdrop, dp(HEIGHT / 2), density), tint}));
        setVisibility(GONE);
        setAlpha(0f);

        art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LayoutParams alp = new LayoutParams(dp(ART), dp(ART));
        alp.rightMargin = dp(10);
        addView(art, alp);

        title = text(ctx, font, 14, 500, 0xFFFFFFFF);
        title.setMaxWidth(dp(170));
        equalizer = new EqualizerDrawable(density, ColorStateList.valueOf(0xFFFFFFFF));
        equalizer.setBounds(0, 0, equalizer.getIntrinsicWidth() * 3 / 4, equalizer.getIntrinsicHeight() * 3 / 4);
        title.setCompoundDrawablesRelative(equalizer, null, null, null);
        title.setCompoundDrawablePadding(dp(8));
        addView(title);
        artist = text(ctx, font, 13, 400, 0xA6FFFFFF);
        artist.setMaxWidth(dp(130));
        artist.setPadding(dp(8), 0, 0, 0);
        addView(artist);

        prev = button(ctx, R.drawable.ic_prev);
        toggle = button(ctx, R.drawable.ic_pause);
        next = button(ctx, R.drawable.ic_next);
        prev.setOnClickListener(v -> {
            if (track != null) NowPlaying.previous(track);
        });
        toggle.setOnClickListener(v -> {
            if (track != null) NowPlaying.toggle(track);
        });
        next.setOnClickListener(v -> {
            if (track != null) NowPlaying.next(track);
        });
        View.OnLongClickListener open = v -> {
            if (track != null) NowPlaying.open(getContext(), track);
            return true;
        };
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    exits.up();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    exits.down();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return v == prev;    // the left end stays put
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return v == next;    // and so does the right end
                default:
                    return false;
            }
        };
        for (ImageView b : new ImageView[] {prev, toggle, next}) {
            b.setOnLongClickListener(open);
            b.setOnKeyListener(keys);
            LayoutParams lp = new LayoutParams(dp(BUTTON), dp(BUTTON));
            lp.leftMargin = dp(b == prev ? 12 : 2);
            addView(b, lp);
        }
    }

    /** Shows the track (dimmed and still while paused). */
    void bind(NowPlaying.Track t) {
        track = t;
        if (t == null) {
            equalizer.stop();
            return;
        }
        title.setText(t.title);
        artist.setText(t.artist.isEmpty() ? "" : "·  " + t.artist);
        artist.setVisibility(t.artist.isEmpty() ? GONE : VISIBLE);
        // Apps like Spotify briefly send their own icon instead of the cover when play/pause changes:
        // keep the cover for the same song, and only take an icon when there is nothing better.
        String key = t.title + '\u0000' + t.artist;
        boolean sameSong = key.equals(artKey);
        if (t.art != null && (!sameSong || (t.artIsCover && !artIsCover))) {
            art.setImageBitmap(MainActivity.roundedArt(t.art, dp(ART), 0.5f));
            art.setVisibility(VISIBLE);
            setTint(coverColor(t.art));
            artKey = key;
            artIsCover = t.artIsCover;
        } else if (t.art == null && !sameSong) {
            art.setVisibility(GONE);
            setTint(0);
            artKey = key;
            artIsCover = false;
        }
        toggle.setImageResource(t.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (t.playing) equalizer.start();
        else equalizer.stop();
    }

    boolean hasTrack() {
        return track != null;
    }

    /** The button Down from the top bar lands on. */
    View defaultButton() {
        return toggle;
    }

    void stop() {
        equalizer.stop();
    }

    /** The cover's colour, from left to right: strong behind the cover, gone by the buttons. */
    private void setTint(int color) {
        int c = color & 0x00FFFFFF;
        tint.setColors(color == 0 ? new int[] {0, 0}
                : new int[] {0x8C000000 | c, 0x40000000 | c, 0x0D000000 | c});
    }

    /**
     * A colour for the glass from the cover: its average, made a little more vivid and kept mid-bright,
     * so a dark or washed-out cover still gives a visible tint and a bright one doesn't glare.
     */
    private static int coverColor(android.graphics.Bitmap art) {
        android.graphics.Bitmap one = android.graphics.Bitmap.createScaledBitmap(art, 1, 1, true);
        int avg = one.getPixel(0, 0);
        if (one != art) one.recycle();
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(avg, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.4f);
        hsv[2] = Math.max(0.45f, Math.min(0.75f, hsv[2]));
        return android.graphics.Color.HSVToColor(hsv);
    }

    private ImageView button(Context ctx, int icon) {
        ImageView b = new ImageView(ctx);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = dp(8);
        b.setPadding(p, p, p, p);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(0xF5FFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, new ColorDrawable(0x00000000));   // unfocused: the icon on the pill's glass
        b.setBackground(bg);
        b.setImageTintList(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {0xFF111111, 0xFFFFFFFF}));
        return b;
    }

    private static TextView text(Context ctx, Typeface font, float sp, int weight, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        // Outfit is a variable font: set the weight on its axis, as the layouts do, so the song reads like
        // the prayer pill beside it.
        if (font != null) t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
