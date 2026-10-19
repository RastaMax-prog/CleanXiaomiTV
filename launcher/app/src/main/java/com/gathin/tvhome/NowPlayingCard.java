package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The now playing card above the dock: album art, song with moving bars, artist, a progress bar and
 * previous / play-pause / next buttons on a glass card. OK on a button does that; holding OK opens the
 * music app. Up leaves for the top buttons, Down for the dock (see MainActivity).
 */
final class NowPlayingCard extends LinearLayout {
    interface Exits {
        void up();

        void down();
    }

    private final float density;
    private final Handler handler;
    private final ImageView art;
    private final TextView title, artist;
    private final Progress progress;
    private final ImageView prev, toggle, next;
    private final EqualizerDrawable equalizer;
    private NowPlaying.Track track;
    /** The song the art on screen belongs to, and whether it is the real cover. */
    private String artKey;
    private boolean artIsCover;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (track != null && track.playing && getVisibility() == VISIBLE) handler.postDelayed(this, 1000);
        }
    };

    NowPlayingCard(Context ctx, Backdrop backdrop, Typeface font, Handler handler, Exits exits) {
        super(ctx);
        this.handler = handler;
        density = ctx.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(14), dp(14), dp(18), dp(14));
        setBackground(new GlassDrawable(this, backdrop, dp(26), density));
        setVisibility(GONE);
        setAlpha(0f);

        art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(art, new LayoutParams(dp(72), dp(72)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(VERTICAL);
        texts.setPadding(dp(16), 0, dp(22), 0);
        title = text(ctx, font, 19, 500, 0xFFFFFFFF);
        equalizer = new EqualizerDrawable(density, ColorStateList.valueOf(0xFFFFFFFF));
        equalizer.setBounds(0, 0, equalizer.getIntrinsicWidth(), equalizer.getIntrinsicHeight());
        title.setCompoundDrawablesRelative(null, null, equalizer, null);
        title.setCompoundDrawablePadding(dp(10));
        artist = text(ctx, font, 14, 400, 0xB3FFFFFF);
        artist.setPadding(0, dp(3), 0, 0);
        progress = new Progress(ctx);
        texts.addView(title, new LayoutParams(dp(250), LayoutParams.WRAP_CONTENT));
        texts.addView(artist, new LayoutParams(dp(250), LayoutParams.WRAP_CONTENT));
        LayoutParams plp = new LayoutParams(dp(250), dp(4));
        plp.topMargin = dp(12);
        texts.addView(progress, plp);
        addView(texts);

        prev = button(ctx, backdrop, R.drawable.ic_prev);
        toggle = button(ctx, backdrop, R.drawable.ic_pause);
        next = button(ctx, backdrop, R.drawable.ic_next);
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
        int gap = dp(10);
        for (ImageView b : new ImageView[] {prev, toggle, next}) {
            b.setOnLongClickListener(open);
            b.setOnKeyListener(keys);
            LayoutParams lp = new LayoutParams(dp(52), dp(52));
            lp.leftMargin = b == prev ? 0 : gap;
            addView(b, lp);
        }
    }

    /** Shows the track (dimmed and still while paused), or hides the card for null. */
    void bind(NowPlaying.Track t) {
        track = t;
        handler.removeCallbacks(tick);
        if (t == null) {
            equalizer.stop();
            return;
        }
        title.setText(t.title);
        artist.setText(t.artist);
        artist.setVisibility(t.artist.isEmpty() ? GONE : VISIBLE);
        // Apps like Spotify briefly send their own icon instead of the cover when play/pause changes:
        // keep the cover for the same song, and only take an icon when there is nothing better.
        String key = t.title + '\u0000' + t.artist;
        boolean sameSong = key.equals(artKey);
        if (t.art != null && (!sameSong || (t.artIsCover && !artIsCover))) {
            art.setImageBitmap(MainActivity.roundedArt(t.art, dp(72)));
            art.setVisibility(VISIBLE);
            artKey = key;
            artIsCover = t.artIsCover;
        } else if (t.art == null && !sameSong) {
            art.setVisibility(GONE);
            artKey = key;
            artIsCover = false;
        }
        toggle.setImageResource(t.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (t.playing) equalizer.start();
        else equalizer.stop();
        progress.setVisibility(t.duration > 0 ? VISIBLE : INVISIBLE);
        updateProgress();
        if (t.playing) handler.postDelayed(tick, 1000);
    }

    boolean hasTrack() {
        return track != null;
    }

    /** The button Down from the top bar lands on. */
    View defaultButton() {
        return toggle;
    }

    void stop() {
        handler.removeCallbacks(tick);
        equalizer.stop();
    }

    private void updateProgress() {
        if (track == null || track.duration <= 0) return;
        long pos = track.position();
        progress.set(pos < 0 ? 0f : pos / (float) track.duration);
    }

    private ImageView button(Context ctx, Backdrop backdrop, int icon) {
        ImageView b = new ImageView(ctx);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(0xF5FFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, new GlassDrawable(b, backdrop, dp(26), density));
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
        if (font != null) t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    /** A thin rounded track with a white fill. */
    private static final class Progress extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;

        Progress(Context ctx) {
            super(ctx);
        }

        void set(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            r.set(0, 0, w, h);
            paint.setColor(0x40FFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, paint);
            r.set(0, 0, Math.max(h, w * fraction), h);
            paint.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, paint);
        }
    }
}
