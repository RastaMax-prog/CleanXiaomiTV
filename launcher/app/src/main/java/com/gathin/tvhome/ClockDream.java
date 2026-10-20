package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow.
 * Four corners: clock, date and weather top left; the photo credit top right; the song playing bottom
 * left; a thikr bottom right (dream_athkar, res/values/athkar.xml), a new one every minute. Every minute
 * the clock also shifts a few pixels and the glow changes colour, so nothing burns in.
 * With dream_photos on, Bing's photos of the day replace the glow, a new one every few minutes with a
 * slow zoom; they are kept in the cache directory, so the screensaver still has photos offline.
 * The weather line uses the home screen's weather settings. Network work runs on one background thread.
 * With now_playing on, the song a music app is playing shows in the top left corner (NowPlaying).
 * With dream_prayer on, today's prayer times sit under the weather, the next one lit (Prayer).
 * With dream_analog on, an analog clock (AnalogClockView) takes the digital clock's place.
 * dream_athkar, dream_prayer and dream_analog can be switched from the home screen's Settings menu (Config.setToggle).
 */
public class ClockDream extends DreamService {
    private static final String TAG = "TvHome.Dream";

    private static final long MOVE_MS = 60_000L;
    /** A new photo every this many clock moves. */
    private static final int PHOTO_EVERY = 3;
    private static final long WEATHER_MS = 30 * MOVE_MS;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private View content;
    private View ambient;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private View athkarBox;
    private TextView thikr, thikrSource;
    /** "text|source" items, or null when dream_athkar is off. */
    private String[] athkar;
    private int thikrIndex = -1;

    private boolean photosOn;
    private ImageView photoA, photoB;
    private boolean frontIsA;
    private View scrim;
    private TextView caption;
    private final List<DailyPhoto.Photo> photos = new ArrayList<>();
    private int photoIndex = -1;
    private int ticks;
    private ObjectAnimator zoom;

    private View nowBox;
    private ImageView nowArt;
    private TextView nowSong, nowArtist;
    private NowPlaying nowPlaying;
    private String nowArtKey;
    private boolean nowArtIsCover;

    private TextView weather;
    private String weatherUrl, weatherPlace;
    private long weatherAt = -WEATHER_MS;

    private View prayerBox;
    private LinearLayout prayerRow;
    private TextView prayerLeft;
    private String prayerLat, prayerLon;
    private int prayerMethod;
    private Prayer.Day prayerDay;
    private long prayerAt = -5 * MOVE_MS;
    private android.graphics.Typeface font;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            nextThikr();
            shiftGlow();
            if (photosOn && ++ticks % PHOTO_EVERY == 0) nextPhoto();
            updateWeather();
            updatePrayer();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        Config cfg = Config.load(this);

        content = findViewById(R.id.dreamContent);
        if (cfg.dreamAnalog) {
            findViewById(R.id.dreamAnalog).setVisibility(View.VISIBLE);
            findViewById(R.id.dreamDigital).setVisibility(View.GONE);
        }
        ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);

        athkarBox = findViewById(R.id.dreamAthkar);
        thikr = findViewById(R.id.dreamThikr);
        thikrSource = findViewById(R.id.dreamThikrSource);
        if (cfg.dreamAthkar) {
            athkar = getResources().getStringArray(R.array.athkar);
            if (athkar.length > 0) {
                athkarBox.setVisibility(View.VISIBLE);
                showThikr();
            } else {
                athkar = null;
            }
        }

        photosOn = cfg.dreamPhotos;
        photoA = findViewById(R.id.dreamPhotoA);
        photoB = findViewById(R.id.dreamPhotoB);
        scrim = findViewById(R.id.dreamScrim);
        caption = findViewById(R.id.dreamCaption);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x99000000, 0x40000000, 0x40000000, 0xCC000000}));

        nowBox = findViewById(R.id.dreamNowPlaying);
        nowArt = findViewById(R.id.dreamArt);
        nowSong = findViewById(R.id.dreamSong);
        nowArtist = findViewById(R.id.dreamArtist);
        if (cfg.nowPlaying) nowPlaying = new NowPlaying(this, handler, this::showNowPlaying);

        weather = findViewById(R.id.dreamWeather);
        weatherPlace = cfg.weatherPlace;
        if (!cfg.weatherLatitude.isEmpty() && !cfg.weatherLongitude.isEmpty()) {
            weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + cfg.weatherLatitude
                    + "&longitude=" + cfg.weatherLongitude + "&current=temperature_2m,weather_code,is_day&timezone=auto";
        }

        prayerBox = findViewById(R.id.dreamPrayer);
        prayerRow = findViewById(R.id.dreamPrayerRow);
        prayerLeft = findViewById(R.id.dreamPrayerLeft);
        shade(prayerLeft);
        if (cfg.dreamPrayer && !cfg.weatherLatitude.isEmpty() && !cfg.weatherLongitude.isEmpty()) {
            prayerLat = cfg.weatherLatitude;
            prayerLon = cfg.weatherLongitude;
            prayerMethod = cfg.prayerMethod;
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        if (photosOn) loadPhotoList();
        if (nowPlaying != null) nowPlaying.start();
        updateWeather();
        updatePrayer();
        handler.postDelayed(tick, MOVE_MS);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (nowPlaying != null) nowPlaying.stop();
        if (zoom != null) zoom.cancel();
        photoA.setImageDrawable(null);
        photoB.setImageDrawable(null);
        super.onDetachedFromWindow();
    }

    /** A slow shift of a few pixels, so the clock in its corner never burns in. */
    private void moveClock() {
        float[] d = jitter();
        content.animate().translationX(d[0]).translationY(d[1]).setDuration(3000).start();
    }

    /** The clock stays in the top left corner, a few pixels off its spot, with the glow centred behind it. */
    private void placeClock() {
        float[] d = jitter();
        content.setTranslationX(d[0]);
        content.setTranslationY(d[1]);
        View parent = (View) content.getParent();
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((content.getLeft() + content.getWidth() / 2f) / parent.getWidth(),
                    (content.getTop() + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    /** Up to 6 dp right and down from the corner, never towards the screen edge. */
    private float[] jitter() {
        float j = 6 * getResources().getDisplayMetrics().density;
        return new float[] {random.nextFloat() * j, random.nextFloat() * j};
    }

    private void nextThikr() {
        if (athkar == null) return;
        athkarBox.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            showThikr();
            athkarBox.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random thikr, never the same one twice in a row. */
    private void showThikr() {
        int i = random.nextInt(athkar.length);
        if (athkar.length > 1 && i == thikrIndex) i = (i + 1) % athkar.length;
        thikrIndex = i;
        String item = athkar[i];
        int bar = item.lastIndexOf('|');
        thikr.setText(bar > 0 ? item.substring(0, bar) : item);
        thikrSource.setText(bar > 0 ? item.substring(bar + 1) : "");
        thikrSource.setVisibility(bar > 0 ? View.VISIBLE : View.GONE);
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }

    // ---- photos ---------------------------------------------------------------------------------

    /** Today's list from Bing, or the last list saved when offline; then the first photo. */
    private void loadPhotoList() {
        io.execute(() -> {
            List<DailyPhoto.Photo> list = DailyPhoto.list(this);
            handler.post(() -> {
                photos.clear();
                photos.addAll(list);
                nextPhoto();
            });
        });
    }

    private void nextPhoto() {
        if (photos.isEmpty()) return;
        photoIndex = (photoIndex + 1) % photos.size();
        final DailyPhoto.Photo p = photos.get(photoIndex);
        final int width = getResources().getDisplayMetrics().widthPixels;
        io.execute(() -> {
            Bitmap bmp = DailyPhoto.load(this, p, width);
            if (bmp != null) handler.post(() -> showPhoto(bmp, p.caption()));
        });
    }

    /** Fades the new photo in over the old one and starts a slow zoom; the glow steps aside. */
    private void showPhoto(Bitmap bmp, String text) {
        ImageView front = frontIsA ? photoB : photoA;
        ImageView back = frontIsA ? photoA : photoB;
        frontIsA = !frontIsA;
        front.setImageBitmap(bmp);
        front.bringToFront();
        scrim.bringToFront();
        caption.bringToFront();
        content.bringToFront();
        athkarBox.bringToFront();
        nowBox.bringToFront();
        front.setAlpha(0f);
        front.setScaleX(1f);
        front.setScaleY(1f);
        front.animate().alpha(1f).setDuration(2000).withEndAction(() -> {
            back.setAlpha(0f);
            back.setImageDrawable(null);
        }).start();
        if (zoom != null) zoom.cancel();
        zoom = ObjectAnimator.ofPropertyValuesHolder(front,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f));
        zoom.setDuration(PHOTO_EVERY * MOVE_MS + 5000);
        zoom.setInterpolator(new LinearInterpolator());
        zoom.start();
        scrim.animate().alpha(1f).setDuration(2000).start();
        ambient.animate().alpha(0f).setDuration(2000).start();
        caption.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            caption.setText(text);
            caption.animate().alpha(1f).setDuration(1400).start();
        }).start();
    }

    // ---- now playing ----------------------------------------------------------------------------

    private void showNowPlaying(NowPlaying.Track t) {
        if (t == null) {
            nowBox.setVisibility(View.GONE);
            return;
        }
        nowSong.setText(t.title);
        nowArtist.setText(t.artist);
        nowArtist.setVisibility(t.artist.isEmpty() ? View.GONE : View.VISIBLE);
        // Keep the cover for the same song (apps send their icon for a moment on play/pause).
        String key = t.title + '\u0000' + t.artist;
        boolean sameSong = key.equals(nowArtKey);
        if (t.art != null && (!sameSong || (t.artIsCover && !nowArtIsCover))) {
            nowArt.setImageBitmap(MainActivity.roundedArt(t.art, Math.round(64 * getResources().getDisplayMetrics().density)));
            nowArt.setVisibility(View.VISIBLE);
            nowArtKey = key;
            nowArtIsCover = t.artIsCover;
        } else if (t.art == null && !sameSong) {
            nowArt.setVisibility(View.GONE);
            nowArtKey = key;
            nowArtIsCover = false;
        }
        nowBox.setAlpha(t.playing ? 1f : 0.6f);
        nowBox.setVisibility(View.VISIBLE);
    }

    // ---- prayer times ---------------------------------------------------------------------------

    /** Today's times from the file the home screen saves, or from Aladhan when it has none for today. */
    private void updatePrayer() {
        if (prayerLat == null) return;
        String key = Prayer.key(prayerLat, prayerLon, prayerMethod);
        if (prayerDay == null || !prayerDay.key.equals(key)) {
            Prayer.Day saved = Prayer.cached(this, key);
            if (saved != null) {
                prayerDay = saved;
            } else if (android.os.SystemClock.elapsedRealtime() - prayerAt >= 5 * MOVE_MS) {
                prayerAt = android.os.SystemClock.elapsedRealtime();
                final String lat = prayerLat, lon = prayerLon;
                final int method = prayerMethod;
                io.execute(() -> {
                    Prayer.Day day = Prayer.fetch(this, lat, lon, method);
                    if (day != null) handler.post(() -> {
                        prayerDay = day;
                        showPrayer();
                    });
                });
            }
        }
        showPrayer();
    }

    /** A column per prayer: the name small, the time below; the next one bright, with its countdown under the row. */
    private void showPrayer() {
        if (prayerDay == null) return;
        Prayer.Next next = Prayer.next(prayerDay);
        prayerRow.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < Prayer.KEYS.length; i++) {
            if (i == Prayer.SUNRISE) continue;
            boolean lit = i == next.index;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = dreamText(12, lit ? 600 : 500, lit ? MainActivity.PRAYER_GOLD : 0x73FFFFFF);
            name.setText(Prayer.name(this, i));
            name.setLetterSpacing(0.14f);
            name.setAllCaps(true);
            col.addView(name);
            TextView time = dreamText(22, lit ? 400 : 200, lit ? 0xFFFFFFFF : 0xB3FFFFFF);
            time.setText(Prayer.time(this, prayerDay.minutes[i]));
            time.setPadding(0, Math.round(5 * d), 0, 0);
            col.addView(time);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Math.round(30 * d);
            prayerRow.addView(col, lp);
        }
        prayerLeft.setText(Prayer.name(this, next.index) + "  ·  " + Prayer.countdown(this, next));
        prayerLeft.setTextColor(next.minutesLeft <= Prayer.NOW_MINUTES ? MainActivity.PRAYER_GOLD : 0xA6FFFFFF);
        prayerBox.setVisibility(View.VISIBLE);
    }

    private TextView dreamText(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (font == null) font = getResources().getFont(R.font.outfit);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        shade(t);
        return t;
    }

    /** A soft shadow, so dim text stays readable over a bright part of a photo. */
    private void shade(TextView t) {
        float d = getResources().getDisplayMetrics().density;
        t.setShadowLayer(6 * d, 0, d, 0x99000000);
    }

    // ---- weather --------------------------------------------------------------------------------

    private void updateWeather() {
        if (weatherUrl == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - weatherAt < WEATHER_MS) return;
        weatherAt = now;
        io.execute(() -> {
            String text = null;
            try {
                JSONObject cur = new JSONObject(new String(DailyPhoto.fetch(weatherUrl), StandardCharsets.UTF_8))
                        .getJSONObject("current");
                text = MainActivity.weatherIcon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1)
                        + "  " + Math.round(cur.getDouble("temperature_2m")) + "°"
                        + (weatherPlace.isEmpty() ? "" : "  ·  " + weatherPlace);
            } catch (Exception e) {
                Log.w(TAG, "weather: " + e);
            }
            final String result = text;
            handler.post(() -> {
                if (result == null) {
                    weatherAt -= WEATHER_MS - 5 * MOVE_MS;   // offline: try again in 5 minutes
                    return;
                }
                weather.setText(result);
                weather.setVisibility(View.VISIBLE);
            });
        });
    }
}
