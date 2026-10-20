package com.gathin.tvhome;

import android.content.Context;
import android.text.format.DateFormat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Today's prayer times for the weather coordinates, from Aladhan (free, no account or key; only the
 * coordinates, the date and the calculation method are sent), the same source as the Prayer Times module.
 * One request a day: the answer is kept in files/prayer.json, so a restart or a short outage costs nothing.
 * Used by the pill under the clock (home_prayer) and by the screensaver (dream_prayer).
 */
final class Prayer {
    private static final String TAG = "TvHome.Prayer";
    private static final String FILE = "prayer.json";
    /** Aladhan's keys, in the order of the day. Sunrise is shown but is not a prayer. */
    static final String[] KEYS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final int SUNRISE = 1;
    /** For this long after a prayer's time, it is still the one shown, as "now". */
    static final int NOW_MINUTES = 15;

    /** One day's times, minutes after midnight in KEYS order. */
    static final class Day {
        final String key;
        final int[] minutes;

        Day(String key, int[] minutes) {
            this.key = key;
            this.minutes = minutes;
        }
    }

    /** What to show at a given minute: the prayer, and the minutes until it (negative while it is "now"). */
    static final class Next {
        final int index;
        final int minutesLeft;
        final boolean tomorrow;

        Next(int index, int minutesLeft, boolean tomorrow) {
            this.index = index;
            this.minutesLeft = minutesLeft;
            this.tomorrow = tomorrow;
        }
    }

    private Prayer() {}

    /** Which day and place a set of times is for; a different key means fetch again. */
    static String key(String latitude, String longitude, int method) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date())
                + '|' + latitude + '|' + longitude + '|' + method;
    }

    /** The saved times if they are for this key, else null. Cheap enough for the main thread. */
    static Day cached(Context ctx, String key) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.isFile() || f.length() > 4096) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            JSONObject o = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            if (!key.equals(o.optString("key"))) return null;
            JSONArray a = o.getJSONArray("minutes");
            if (a.length() != KEYS.length) return null;
            int[] m = new int[KEYS.length];
            for (int i = 0; i < m.length; i++) m[i] = a.getInt(i);
            return new Day(key, m);
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches today's times and saves them; null when offline or the answer is unexpected. Background thread only. */
    static Day fetch(Context ctx, String latitude, String longitude, int method) {
        String key = key(latitude, longitude, method);
        String url = "https://api.aladhan.com/v1/timings/"
                + new SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).format(new Date())
                + "?latitude=" + latitude + "&longitude=" + longitude + "&method=" + method;
        try {
            JSONObject t = new JSONObject(new String(DailyPhoto.fetch(url), StandardCharsets.UTF_8))
                    .getJSONObject("data").getJSONObject("timings");
            int[] m = new int[KEYS.length];
            JSONArray a = new JSONArray();
            for (int i = 0; i < KEYS.length; i++) {
                String v = t.getString(KEYS[i]).trim();   // "05:12", sometimes "05:12 (+01)"
                m[i] = Integer.parseInt(v.substring(0, 2)) * 60 + Integer.parseInt(v.substring(3, 5));
                if (m[i] < 0 || m[i] >= 24 * 60) throw new IllegalArgumentException("time " + v);
                a.put(m[i]);
            }
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(new JSONObject().put("key", key).put("minutes", a).toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(ctx.getFilesDir(), FILE))) Log.w(TAG, "could not save " + FILE);
            return new Day(key, m);
        } catch (Exception e) {
            Log.w(TAG, "fetch: " + e);
            return null;
        }
    }

    /** The prayer to show now: the current one for NOW_MINUTES after its time, else the next; after Isha, tomorrow's Fajr. */
    static Next next(Day day) {
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int[] m = day.minutes;
        for (int i = 0; i < KEYS.length; i++) {
            if (i == SUNRISE) continue;
            if (now >= m[i] && now < m[i] + NOW_MINUTES) return new Next(i, m[i] - now, false);
            if (m[i] > now) return new Next(i, m[i] - now, false);
        }
        int fajr = m[0] + 24 * 60;   // today's Fajr stands in for tomorrow's, a minute out at most
        return new Next(0, fajr - now, true);
    }

    static String name(Context ctx, int index) {
        switch (index) {
            case 0: return ctx.getString(R.string.prayer_fajr);
            case 1: return ctx.getString(R.string.prayer_sunrise);
            case 2: return ctx.getString(R.string.prayer_dhuhr);
            case 3: return ctx.getString(R.string.prayer_asr);
            case 4: return ctx.getString(R.string.prayer_maghrib);
            default: return ctx.getString(R.string.prayer_isha);
        }
    }

    /** "17:55" or "5:55 PM", following the TV's 12/24 hour setting. */
    static String time(Context ctx, int minutes) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        return DateFormat.format(DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm a", c).toString();
    }

    /** "in 2 h 24 min", "in 8 min" or "now". */
    static String countdown(Context ctx, Next n) {
        if (n.minutesLeft <= 0) return ctx.getString(R.string.prayer_now);
        String left = n.minutesLeft >= 60
                ? ctx.getString(R.string.prayer_hours_minutes, n.minutesLeft / 60, n.minutesLeft % 60)
                : ctx.getString(R.string.prayer_minutes, n.minutesLeft);
        return ctx.getString(R.string.prayer_in, left);
    }
}
