package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * What is playing in any music app (Spotify, YouTube Music, ...), from Android's media sessions.
 * Needs notification access for MediaListener (granted at install); without it, start() does nothing.
 * Reports the playing session first, else a paused one, else nothing.
 */
final class NowPlaying {
    private static final String TAG = "TvHome.NowPlaying";
    /** A song paused longer than this is no longer shown: you've moved on to something else. */
    private static final long PAUSED_FOR_MS = 3 * 60_000L;

    static final class Track {
        final String title, artist;
        final Bitmap art;
        final boolean playing;
        final MediaController controller;
        /** Song length in ms, or 0 when the app doesn't say. */
        final long duration;
        /** True when art is the song's cover; false when it is only the app's icon (DISPLAY_ICON). */
        final boolean artIsCover;

        Track(String title, String artist, Bitmap art, boolean artIsCover, boolean playing, MediaController controller,
              long duration) {
            this.title = title;
            this.artist = artist;
            this.art = art;
            this.artIsCover = artIsCover;
            this.playing = playing;
            this.controller = controller;
            this.duration = duration;
        }

        /** Where playback is now, in ms, extrapolated from the session's last update; -1 when unknown. */
        long position() {
            PlaybackState s = controller.getPlaybackState();
            if (s == null || s.getPosition() < 0) return -1;
            long pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING) {
                pos += (long) ((android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
            return duration > 0 ? Math.min(pos, duration) : pos;
        }
    }

    interface Listener {
        /** Called on the handler's thread; null when nothing is playing or paused. */
        void onNowPlaying(Track track);
    }

    private final Context ctx;
    private final Handler handler;
    private final Listener listener;
    private final ComponentName component;
    private MediaSessionManager msm;
    private final List<MediaController> controllers = new ArrayList<>();

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publish();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish();
        }

        @Override
        public void onSessionDestroyed() {
            publish();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::track;
    /** Re-checks when a paused song runs out its PAUSED_FOR_MS, since no callback fires then. */
    private final Runnable recheck = this::publish;

    NowPlaying(Context ctx, Handler handler, Listener listener) {
        this.ctx = ctx;
        this.handler = handler;
        this.listener = listener;
        this.component = new ComponentName(ctx, MediaListener.class);
    }

    static boolean hasAccess(Context ctx) {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(ctx, MediaListener.class).flattenToString());
    }

    void start() {
        if (msm != null || !hasAccess(ctx)) return;
        try {
            msm = ctx.getSystemService(MediaSessionManager.class);
            msm.addOnActiveSessionsChangedListener(sessions, component, handler);
            track(msm.getActiveSessions(component));
        } catch (SecurityException e) {
            Log.w(TAG, "no access: " + e.getMessage());
            msm = null;
        }
    }

    void stop() {
        handler.removeCallbacks(recheck);
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        msm = null;
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers.clear();
    }

    private void track(List<MediaController> list) {
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers.clear();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(callback, handler);
                controllers.add(c);
            }
        }
        publish();
    }

    private void publish() {
        handler.removeCallbacks(recheck);
        MediaController best = null;
        boolean bestPlaying = false;
        long pausedLeft = -1;   // ms until the paused candidate expires
        for (MediaController c : controllers) {
            PlaybackState s = c.getPlaybackState();
            if (s == null || c.getMetadata() == null) continue;
            int st = s.getState();
            boolean playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
            boolean paused = st == PlaybackState.STATE_PAUSED;
            if (paused) {
                long left = PAUSED_FOR_MS - (android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime());
                if (left <= 0) paused = false;   // paused too long ago: gone
                else if (best == null) pausedLeft = left;
            }
            if (playing && !bestPlaying) {
                best = c;
                bestPlaying = true;
            } else if (paused && best == null) {
                best = c;
            }
        }
        if (best == null) {
            listener.onNowPlaying(null);
            return;
        }
        if (!bestPlaying && pausedLeft > 0) handler.postDelayed(recheck, pausedLeft + 500);
        MediaMetadata m = best.getMetadata();
        String title = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title.isEmpty()) {
            listener.onNowPlaying(null);
            return;
        }
        String artist = first(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        boolean cover = art != null;
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        listener.onNowPlaying(new Track(title, artist, art, cover, bestPlaying, best,
                Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION))));
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return "";
    }

    /** Play or pause. */
    static void toggle(Track t) {
        if (t.playing) t.controller.getTransportControls().pause();
        else t.controller.getTransportControls().play();
    }

    static void previous(Track t) {
        t.controller.getTransportControls().skipToPrevious();
    }

    static void next(Track t) {
        t.controller.getTransportControls().skipToNext();
    }

    /** The app's own player screen, or failing that its launch activity. */
    static void open(Context ctx, Track t) {
        PendingIntent pi = t.controller.getSessionActivity();
        try {
            if (pi != null) {
                pi.send();
                return;
            }
        } catch (PendingIntent.CanceledException ignored) {
        }
        String pkg = t.controller.getPackageName();
        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
