package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bing's photos of the day, shared by the home screen (a different one every few hours) and the Clock
 * screensaver (all of them in turn). The list comes from five English-language regions, 15 days back each,
 * with the photos they share counted once: about 20 to 25 photos instead of one region's 8. It is fetched
 * at most once a day and kept, with the photos, in the cache directory, so both still have pictures
 * offline. All methods block: call them off the main thread.
 */
final class DailyPhoto {
    private static final String TAG = "TvHome.Photo";
    /** English captions; regions often swap in a photo of their own. The first is preferred for shared ones. */
    private static final String[] REGIONS = {"en-US", "en-GB", "en-AU", "en-IN", "en-CA"};
    /** Bing keeps 15 days: idx 0 gives days 0 to 7 and idx 7 days 7 to 14; further back repeats them. */
    private static final int[] FROM = {0, 7};
    private static final String LIST = "https://www.bing.com/HPImageArchive.aspx?format=js&n=8";
    private static final int MAX_CACHED = 24;

    static final class Photo {
        final String url, key;
        /** "Grand Canal in Venice, Italy" */
        final String title;
        /** "© Juan Romero/Cavan Images", or empty */
        final String credit;

        Photo(String url, String key, String title, String credit) {
            this.url = url;
            this.key = key;
            this.title = title;
            this.credit = credit;
        }

        /** Both, as one line. */
        String caption() {
            return credit.isEmpty() ? title : title + " (" + credit + ")";
        }
    }

    private DailyPhoto() {
    }

    static File dir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "dream");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /**
     * Today's list, newest first: the one saved today if there is one, else fetched from Bing (and saved),
     * else the last one saved. Empty only offline on a TV that never had a list.
     */
    static List<Photo> list(Context ctx) {
        File saved = new File(dir(ctx), "photos.json");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        JSONObject cached = readJson(saved);
        if (cached != null && today.equals(cached.optString("day"))) return parse(cached);

        // Every region, both pages; a photo the regions share is kept once, from the first that has it.
        Map<String, JSONObject> byName = new LinkedHashMap<>();
        for (String region : REGIONS) {
            for (int from : FROM) {
                try {
                    JSONArray images = new JSONObject(new String(fetch(LIST + "&mkt=" + region + "&idx=" + from),
                            StandardCharsets.UTF_8)).getJSONArray("images");
                    for (int i = 0; i < images.length(); i++) {
                        JSONObject im = images.getJSONObject(i);
                        String name = name(im.getString("urlbase"));
                        if (!byName.containsKey(name)) byName.put(name, im);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "list " + region + "/" + from + ": " + e);
                }
            }
        }
        if (byName.isEmpty()) return cached != null ? parse(cached) : new ArrayList<>();
        List<JSONObject> images = new ArrayList<>(byName.values());
        Collections.sort(images, (a, b) -> b.optString("startdate").compareTo(a.optString("startdate")));
        try {
            JSONObject out = new JSONObject().put("day", today).put("images", new JSONArray(images));
            // Written aside and renamed: Home and the screensaver may both be saving it.
            File tmp = new File(dir(ctx), "photos.json." + android.os.Process.myTid());
            try (FileOutputStream f = new FileOutputStream(tmp)) {
                f.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(saved)) tmp.delete();
            return parse(out);
        } catch (Exception e) {
            Log.w(TAG, "list save: " + e);
            return new ArrayList<>();
        }
    }

    /** "/th?id=OHR.BrownSloth_EN-US123" -> "BrownSloth": the same photo has a different id per region. */
    private static String name(String urlbase) {
        String n = urlbase.substring(urlbase.lastIndexOf('.') + 1);
        int us = n.indexOf('_');
        return us > 0 ? n.substring(0, us) : n;
    }

    private static JSONObject readJson(File f) {
        if (!f.isFile()) return null;
        try {
            return new JSONObject(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Photo> parse(JSONObject list) {
        List<Photo> out = new ArrayList<>();
        JSONArray images = list.optJSONArray("images");
        if (images == null) return out;
        for (int i = 0; i < images.length(); i++) {
            JSONObject im = images.optJSONObject(i);
            if (im == null) continue;
            String base = im.optString("urlbase");
            if (base.isEmpty()) continue;
            String copyright = im.optString("copyright", "");
            String title = copyright, credit = "";
            int paren = copyright.lastIndexOf(" (");
            if (paren > 0 && copyright.endsWith(")")) {
                title = copyright.substring(0, paren);
                credit = copyright.substring(paren + 2, copyright.length() - 1);
            }
            out.add(new Photo("https://www.bing.com" + base + "_1920x1080.jpg",
                    base.replaceAll("[^A-Za-z0-9._-]", "_"), title, credit));
        }
        return out;
    }

    /** The photo from the cache, else downloaded and cached, decoded for this screen width; null on failure. */
    static Bitmap load(Context ctx, Photo p, int screenWidth) {
        File file = new File(dir(ctx), p.key + ".jpg");
        try {
            if (!file.isFile()) {
                byte[] data = fetch(p.url);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                prune(ctx);
            }
            file.setLastModified(System.currentTimeMillis());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenWidth) o.inSampleSize *= 2;
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; fine for photos on a 1 GB TV
            o.inMutable = true;                            // so the home screen can bake its scrim into it
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "photo: " + e);
            file.delete();
            return null;
        }
    }

    /** Keeps the newest MAX_CACHED photos. */
    private static void prune(Context ctx) {
        File[] files = dir(ctx).listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null || files.length <= MAX_CACHED) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = MAX_CACHED; i < files.length; i++) files[i].delete();
    }

    static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = src.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
