package com.majnioui.tv.channels;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live channels from free streams the broadcasters publish (see ChannelList). A grid of channels by
 * group; OK plays one full screen. While playing, Up/Down and CH+/CH- switch channels, OK shows the
 * channel bar, Back returns to the grid.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Channels";
    private static final int PER_ROW = 6;
    private static final long BAR_MS = 4000;
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    /** Group titles in the bundled list, with their Arabic names. */
    private static final Map<String, String> ARABIC = new HashMap<>();

    static {
        ARABIC.put("Morocco", "المغرب");
        ARABIC.put("News", "الأخبار");
        ARABIC.put("Entertainment", "الترفيه");
        ARABIC.put("Documentary", "الوثائقيات");
        ARABIC.put("Religious", "القنوات الدينية");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> logos = new LruCache<>(80);
    private Typeface font;
    private int accent;
    private SharedPreferences prefs;

    private List<ChannelList.Channel> channels;
    private final Map<ChannelList.Channel, View> tiles = new HashMap<>();
    private ScrollView browse;
    private LinearLayout grid;
    private TextView status;

    private FrameLayout playerView;
    private SurfaceView surface;
    private LinearLayout bar;
    private ImageView barLogo;
    private TextView barName, barGroup, message;
    private ExoPlayer player;
    private int current = -1;
    private final Runnable hideBar = () -> bar.animate().alpha(0f).setDuration(400).start();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);
        prefs = getSharedPreferences("channels", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(accent & 0x00FFFFFF) | 0x55000000, 0xFF050607, 0xFF050607}));

        browse = new ScrollView(this);
        browse.setFillViewport(true);
        browse.setVerticalScrollBarEnabled(false);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(80), dp(44), dp(80), dp(56));
        browse.addView(grid);
        root.addView(browse, new FrameLayout.LayoutParams(-1, -1));

        buildPlayer(root);
        setContentView(root);

        channels = ChannelList.load(this);
        render();
        if (ChannelList.refreshDue(this)) {
            io.execute(() -> {
                List<ChannelList.Channel> fresh = ChannelList.refresh(this);
                if (fresh != null) main.post(() -> {
                    channels = fresh;
                    if (current < 0) render();
                });
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---- grid -----------------------------------------------------------------------------------

    private void render() {
        grid.removeAllViews();
        tiles.clear();
        TextView title = text(18, 400, 0xB3FFFFFF);
        title.setLetterSpacing(0.25f);
        title.setText("CHANNELS");
        grid.addView(title);
        status = text(16, 400, 0x80FFFFFF);
        status.setPadding(0, dp(6), 0, 0);
        status.setText(channels.size() + " channels · free streams published by the broadcasters");
        grid.addView(status);

        String lastId = prefs.getString("last", "");
        View focus = null;
        String group = null;
        LinearLayout row = null;
        int inRow = 0;
        for (int i = 0; i < channels.size(); i++) {
            ChannelList.Channel c = channels.get(i);
            if (!c.group.equals(group)) {
                group = c.group;
                TextView h = text(22, 500, 0xFFFFFFFF);
                String ar = ARABIC.get(group);
                h.setText(ar == null ? group : group + "  ·  " + ar);
                h.setPadding(0, dp(30), 0, dp(14));
                grid.addView(h);
                row = null;
            }
            if (row == null || inRow == PER_ROW) {
                row = new LinearLayout(this);
                row.setPadding(0, 0, 0, dp(18));
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
                inRow = 0;
            }
            View tile = tile(c, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(inRow == 0 ? 0 : dp(9), 0, dp(9), 0);
            row.addView(tile, lp);
            inRow++;
            tiles.put(c, tile);
            if (focus == null && c.id.equals(lastId)) focus = tile;
        }
        // Fill the last row of each group so tiles keep their width.
        for (int g = 0; g < grid.getChildCount(); g++) {
            View v = grid.getChildAt(g);
            if (v instanceof LinearLayout && ((LinearLayout) v).getChildCount() < PER_ROW
                    && ((LinearLayout) v).getChildCount() > 0) {
                LinearLayout r = (LinearLayout) v;
                while (r.getChildCount() < PER_ROW) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                    lp.setMargins(dp(9), 0, dp(9), 0);
                    r.addView(new View(this), lp);
                }
            }
        }
        View first = focus != null ? focus : (channels.isEmpty() ? null : tiles.get(channels.get(0)));
        if (first != null) first.requestFocus();
    }

    private View tile(ChannelList.Channel c, int index) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setClickable(true);

        FrameLayout card = new FrameLayout(this);
        card.setBackground(cardBackground());
        card.setDuplicateParentStateEnabled(true);
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        TextView fallback = text(16, 500, 0xFFFFFFFF);
        fallback.setGravity(Gravity.CENTER);
        fallback.setText(c.name);
        card.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        t.addView(card, new LinearLayout.LayoutParams(-1, dp(84)));

        TextView name = text(14, 400, 0xB3FFFFFF);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setText(c.name);
        name.setPadding(0, dp(8), 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(-1, -2));

        loadLogo(c.logo, bmp -> {
            logo.setImageBitmap(bmp);
            fallback.setVisibility(View.GONE);
        });
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(150).start();
            name.setTextColor(has ? 0xFFFFFFFF : 0xB3FFFFFF);
        });
        t.setOnClickListener(v -> play(index));
        return t;
    }

    // ---- player ---------------------------------------------------------------------------------

    private void buildPlayer(FrameLayout root) {
        playerView = new FrameLayout(this);
        playerView.setBackgroundColor(0xFF000000);
        playerView.setVisibility(View.GONE);
        surface = new SurfaceView(this);
        playerView.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        message = text(22, 400, 0xCCFFFFFF);
        message.setGravity(Gravity.CENTER);
        playerView.addView(message, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(16), dp(28), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0xCC0B0D10);
        bar.setBackground(bg);
        barLogo = new ImageView(this);
        barLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bar.addView(barLogo, new LinearLayout.LayoutParams(dp(96), dp(54)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(18), 0, 0, 0);
        barName = text(24, 500, 0xFFFFFFFF);
        barGroup = text(15, 400, 0xA6FFFFFF);
        texts.addView(barName);
        texts.addView(barGroup);
        bar.addView(texts);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        blp.setMargins(dp(48), 0, 0, dp(40));
        playerView.addView(bar, blp);

        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
    }

    private void play(int index) {
        if (channels.isEmpty()) return;
        index = (index % channels.size() + channels.size()) % channels.size();
        current = index;
        ChannelList.Channel c = channels.get(index);
        prefs.edit().putString("last", c.id).apply();

        playerView.setVisibility(View.VISIBLE);
        browse.setVisibility(View.INVISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        message.setText("Loading…");
        message.setVisibility(View.VISIBLE);
        showBar(c, index);

        if (player != null) player.release();
        Map<String, String> headers = new HashMap<>();
        if (c.referrer != null) headers.put("Referer", c.referrer);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(c.userAgent != null ? c.userAgent : DEFAULT_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000)
                .setDefaultRequestProperties(headers);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) message.setVisibility(View.GONE);
                else if (state == Player.STATE_BUFFERING) {
                    message.setText("Loading…");
                    message.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                fitSurface(size);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.w(TAG, c.name + ": " + e.getErrorCodeName());
                message.setText(c.name + " isn't available right now.\nUp / Down for another channel.");
                message.setVisibility(View.VISIBLE);
            }
        });
        final ExoPlayer p = player;
        // Links without .m3u8 or .mpd: look at the first bytes to tell DASH from HLS.
        String lower = c.url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains(".mpd")) {
            start(p, c.url, null);
        } else {
            io.execute(() -> {
                String mime = MimeTypes.APPLICATION_M3U8;
                try {
                    // DASH manifests are XML; HLS playlists start with #EXTM3U.
                    String head = new String(ChannelList.fetch(c.url), java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (head.startsWith("<")) mime = MimeTypes.APPLICATION_MPD;
                } catch (Exception ignored) {
                }
                final String m = mime;
                main.post(() -> {
                    if (player == p) start(p, c.url, m);
                });
            });
        }
    }

    private static void start(ExoPlayer p, String url, String mime) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url))
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET).build());
        if (mime != null) item.setMimeType(mime);
        p.setMediaItem(item.build());
        p.prepare();
        p.setPlayWhenReady(true);
    }

    /** Letterbox the video in the screen at its own aspect ratio. */
    private void fitSurface(VideoSize size) {
        if (size.width == 0 || size.height == 0) return;
        float aspect = size.width * size.pixelWidthHeightRatio / size.height;
        int w = playerView.getWidth(), h = playerView.getHeight();
        if (w == 0 || h == 0) return;
        int vw = w, vh = Math.round(w / aspect);
        if (vh > h) {
            vh = h;
            vw = Math.round(h * aspect);
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    private void showBar(ChannelList.Channel c, int index) {
        barName.setText(c.name);
        String ar = ARABIC.get(c.group);
        barGroup.setText((index + 1) + "  ·  " + c.group + (ar == null ? "" : "  ·  " + ar));
        barLogo.setImageDrawable(null);
        loadLogo(c.logo, barLogo::setImageBitmap);
        bar.animate().cancel();
        bar.setAlpha(1f);
        main.removeCallbacks(hideBar);
        main.postDelayed(hideBar, BAR_MS);
    }

    private void stopPlayback() {
        if (player != null) {
            player.release();
            player = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (playerView.getVisibility() == View.VISIBLE) {
            playerView.setVisibility(View.GONE);
            browse.setVisibility(View.VISIBLE);
            if (current >= 0 && current < channels.size()) {
                View t = tiles.get(channels.get(current));
                if (t != null) t.requestFocus();
            }
        }
        current = -1;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (playerView.getVisibility() == View.VISIBLE) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    play(current - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    play(current + 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    showBar(channels.get(current), current);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    stopPlayback();
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- logos ----------------------------------------------------------------------------------

    private interface BitmapCallback {
        void onBitmap(Bitmap bmp);
    }

    /** From memory, then the cache directory, then the network. */
    private void loadLogo(String url, BitmapCallback cb) {
        if (url == null || url.isEmpty()) return;
        Bitmap hit = logos.get(url);
        if (hit != null) {
            cb.onBitmap(hit);
            return;
        }
        io.execute(() -> {
            File dir = new File(getCacheDir(), "logos");
            if (!dir.isDirectory()) dir.mkdirs();
            File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
            try {
                if (!f.isFile()) {
                    byte[] data = ChannelList.fetch(url);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data);
                    }
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= 320) o.inSampleSize *= 2;
                Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
                if (bmp == null) {
                    f.delete();
                    return;
                }
                main.post(() -> {
                    logos.put(url, bmp);
                    cb.onBitmap(bmp);
                });
            } catch (Exception e) {
                Log.w(TAG, "logo " + url + ": " + e);
            }
        });
    }

    // ---- helpers --------------------------------------------------------------------------------

    private StateListDrawable cardBackground() {
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(16));
        on.setColor(0xFFFFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(dp(16));
        off.setColor(0x1FFFFFFF);
        off.setStroke(dp(1), 0x26FFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        return bg;
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
