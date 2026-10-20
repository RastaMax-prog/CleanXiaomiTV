package com.majnioui.tv.channels;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live channels from free streams the broadcasters publish (see ChannelList), like a TV's own channel
 * app: it opens on the last channel, full screen. OK opens the channel list on the left (Up/Down to
 * move, Left/Right to jump between groups, OK to watch, Back to close); without it, Up/Down and
 * CH+/CH- switch channels and Back leaves. The list is a ListView, so only the rows on screen exist,
 * however many channels there are.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Channels";
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
    private final LruCache<String, Bitmap> logos = new LruCache<>(60);
    private Typeface font;
    private int accent;
    private SharedPreferences prefs;

    private List<ChannelList.Channel> channels = new ArrayList<>();
    /** List rows: a String is a group header, an Integer the index of a channel. */
    private final List<Object> rows = new ArrayList<>();
    private int current = -1;

    private FrameLayout root;
    private SurfaceView surface;
    private TextView message;
    private LinearLayout bar;
    private ImageView barLogo;
    private TextView barName, barGroup;
    private LinearLayout menu;
    private ListView list;
    private TextView menuStatus;
    private final RowAdapter adapter = new RowAdapter();
    private ExoPlayer player;
    private final Runnable hideBar = () -> bar.animate().alpha(0f).setDuration(400).start();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);
        prefs = getSharedPreferences("channels", MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        message = text(22, 400, 0xCCFFFFFF);
        message.setGravity(Gravity.CENTER);
        root.addView(message, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        buildBar();
        buildMenu();
        setContentView(root);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setChannels(ChannelList.load(this));
        if (ChannelList.refreshDue(this)) {
            io.execute(() -> {
                List<ChannelList.Channel> fresh = ChannelList.refresh(this);
                if (fresh != null) main.post(() -> setChannels(fresh));
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (channels.isEmpty()) return;
        int last = indexOf(prefs.getString("last", ""));
        play(last >= 0 ? last : 0);
        if (last < 0) openMenu();   // first time: show what there is
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /** New list (bundled, saved or refreshed): rebuild the rows, keep the channel that is playing. */
    private void setChannels(List<ChannelList.Channel> fresh) {
        String playingId = current >= 0 && current < channels.size() ? channels.get(current).id : null;
        channels = fresh;
        rows.clear();
        String group = null;
        for (int i = 0; i < channels.size(); i++) {
            if (!channels.get(i).group.equals(group)) {
                group = channels.get(i).group;
                rows.add(group);
            }
            rows.add(i);
        }
        if (playingId != null) current = indexOf(playingId);
        menuStatus.setText(channels.size() + " channels");
        adapter.notifyDataSetChanged();
    }

    private int indexOf(String id) {
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).id.equals(id)) return i;
        return -1;
    }

    // ---- channel list (side menu) ---------------------------------------------------------------

    private void buildMenu() {
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(28), dp(36), dp(20), dp(24));
        menu.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF20B0D10, 0xE60B0D10, 0xB30B0D10}));
        menu.setVisibility(View.GONE);

        TextView title = text(14, 500, 0x99FFFFFF);
        title.setLetterSpacing(0.22f);
        title.setText("CHANNELS");
        menu.addView(title);
        menuStatus = text(13, 400, 0x66FFFFFF);
        menuStatus.setPadding(0, dp(4), 0, dp(14));
        menu.addView(menuStatus);

        list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setItemsCanFocus(false);
        list.setDrawSelectorOnTop(false);
        GradientDrawable sel = new GradientDrawable();
        sel.setCornerRadius(dp(12));
        sel.setColor(0xF5FFFFFF);
        list.setSelector(sel);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Object row = rows.get(position);
            if (row instanceof Integer) {
                play((Integer) row);
                closeMenu();
            }
        });
        list.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                jumpGroup(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            return false;
        });
        menu.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView hint = text(12, 400, 0x59FFFFFF);
        hint.setText("◀ ▶ groups   ·   OK watch   ·   Back close");
        hint.setPadding(0, dp(12), 0, 0);
        menu.addView(hint);

        root.addView(menu, new FrameLayout.LayoutParams(dp(400), -1, Gravity.START));
    }

    private boolean menuOpen() {
        return menu.getVisibility() == View.VISIBLE;
    }

    private void openMenu() {
        if (channels.isEmpty()) return;
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.setTranslationX(-dp(40));
        menu.animate().alpha(1f).translationX(0f).setDuration(180).start();
        main.removeCallbacks(hideBar);
        bar.setAlpha(0f);
        adapter.notifyDataSetChanged();
        list.requestFocus();
        int row = rows.indexOf(current >= 0 ? current : 0);
        list.setSelectionFromTop(Math.max(0, row), dp(140));
    }

    private void closeMenu() {
        menu.animate().alpha(0f).translationX(-dp(40)).setDuration(150)
                .withEndAction(() -> menu.setVisibility(View.GONE)).start();
    }

    /** Selects the first channel of the next (+1) or previous (-1) group. */
    private void jumpGroup(int dir) {
        int pos = list.getSelectedItemPosition();
        if (pos < 0) pos = 0;
        int header = -1;
        if (dir > 0) {
            for (int i = pos + 1; i < rows.size(); i++) if (rows.get(i) instanceof String) { header = i; break; }
        } else {
            int own = pos;
            while (own > 0 && !(rows.get(own) instanceof String)) own--;
            for (int i = own - 1; i >= 0; i--) if (rows.get(i) instanceof String) { header = i; break; }
        }
        if (header >= 0 && header + 1 < rows.size()) list.setSelectionFromTop(header + 1, dp(60));
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? 0 : 1;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position) instanceof Integer;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Object row = rows.get(position);
            if (row instanceof String) {
                TextView h = convert instanceof TextView ? (TextView) convert : text(13, 500, 0x99FFFFFF);
                String ar = ARABIC.get(row);
                h.setText(((String) row).toUpperCase(Locale.ROOT) + (ar == null ? "" : "  ·  " + ar));
                h.setLetterSpacing(0.12f);
                h.setPadding(dp(12), dp(position == 0 ? 4 : 18), 0, dp(8));
                return h;
            }
            RowHolder holder;
            if (convert != null && convert.getTag() instanceof RowHolder) {
                holder = (RowHolder) convert.getTag();
            } else {
                holder = new RowHolder();
                convert = holder.build();
            }
            holder.bind((Integer) row);
            return convert;
        }
    }

    /** number · logo · name, and a dot on the channel that is playing. */
    private final class RowHolder {
        TextView number, name;
        ImageView logo;
        View playing;
        String logoUrl;

        View build() {
            LinearLayout r = new LinearLayout(MainActivity.this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), dp(8), dp(14), dp(8));
            r.setMinimumHeight(dp(56));
            ColorStateList fg = new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}},
                    new int[] {0xFF111111, 0xFFFFFFFF});
            ColorStateList dim = new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}},
                    new int[] {0x99111111, 0x80FFFFFF});
            number = text(14, 500, 0);
            number.setTextColor(dim);
            number.setGravity(Gravity.END);
            r.addView(number, new LinearLayout.LayoutParams(dp(26), -2));
            FrameLayout logoBox = new FrameLayout(MainActivity.this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(0x1FFFFFFF);
            logoBox.setBackground(bg);
            logo = new ImageView(MainActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logoBox.addView(logo, new FrameLayout.LayoutParams(-1, -1));
            logoBox.setPadding(dp(5), dp(4), dp(5), dp(4));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(68), dp(40));
            llp.leftMargin = dp(12);
            llp.rightMargin = dp(14);
            r.addView(logoBox, llp);
            name = text(17, 500, 0);
            name.setTextColor(fg);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            playing = new View(MainActivity.this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(accent);
            playing.setBackground(dot);
            r.addView(playing, new LinearLayout.LayoutParams(dp(9), dp(9)));
            r.setTag(this);
            return r;
        }

        void bind(int index) {
            ChannelList.Channel c = channels.get(index);
            number.setText(String.valueOf(index + 1));
            name.setText(c.name);
            playing.setVisibility(index == current ? View.VISIBLE : View.INVISIBLE);
            if (!c.logo.equals(logoUrl)) {
                logoUrl = c.logo;
                logo.setImageDrawable(null);
                final String want = c.logo;
                loadLogo(want, bmp -> {
                    if (want.equals(logoUrl)) logo.setImageBitmap(bmp);
                });
            }
        }
    }

    // ---- player ---------------------------------------------------------------------------------

    private void buildBar() {
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
        bar.setAlpha(0f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        blp.setMargins(dp(48), 0, 0, dp(40));
        root.addView(bar, blp);
    }

    private void play(int index) {
        if (channels.isEmpty()) return;
        index = (index % channels.size() + channels.size()) % channels.size();
        current = index;
        final ChannelList.Channel c = channels.get(index);
        prefs.edit().putString("last", c.id).apply();
        adapter.notifyDataSetChanged();
        message.setText("Loading…");
        message.setVisibility(View.VISIBLE);
        if (!menuOpen()) showBar(c, index);

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
                message.setText(c.name + " isn't available right now.\nUp / Down for another channel, OK for the list.");
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
                    String head = new String(ChannelList.fetch(c.url), StandardCharsets.UTF_8).trim();
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
        int w = root.getWidth(), h = root.getHeight();
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (menuOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeMenu();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                openMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                play(current - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                play(current + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                if (current >= 0) showBar(channels.get(current), current);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
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
                while (o.outWidth / (o.inSampleSize * 2) >= 240) o.inSampleSize *= 2;
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

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
