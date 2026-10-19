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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bing's photos of the day, shared by the home screen (today's photo behind everything) and the Clock
 * screensaver (all of them in turn). The list and the photos are kept in the cache directory, so both
 * still have pictures offline. All methods block: call them off the main thread.
 */
final class DailyPhoto {
    private static final String TAG = "TvHome.Photo";
    /** The last 8 photos of the day; urlbase + "_1920x1080.jpg" is the full HD image. */
    private static final String LIST = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US";
    private static final int MAX_CACHED = 10;

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

    /** Today's list from Bing (saved for offline use), or the last saved list; newest first. */
    static List<Photo> list(Context ctx) {
        File saved = new File(dir(ctx), "list.json");
        String json = null;
        try {
            json = new String(fetch(LIST), StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(saved)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "list: " + e);
            try {
                json = new String(readAll(new FileInputStream(saved)), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        List<Photo> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray images = new JSONObject(json).getJSONArray("images");
            for (int i = 0; i < images.length(); i++) {
                JSONObject im = images.getJSONObject(i);
                String base = im.getString("urlbase");
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
        } catch (Exception e) {
            Log.w(TAG, "list parse: " + e);
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
