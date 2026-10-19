package com.majnioui.tv.channels;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The channels: which ones, their groups and order come from res/raw/channels.m3u. Their links and
 * logos are refreshed once a day from public iptv-org lists (res/values/config.xml, sources), matched
 * by tvg-id, so a channel that moves to a new link keeps working. The merged list is saved, so the app
 * starts with the last good links and still works when the lists can't be reached.
 */
final class ChannelList {
    private static final String TAG = "Channels";
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final String SAVED = "channels.m3u";
    private static final Pattern ATTR = Pattern.compile("([a-z-]+)=\"([^\"]*)\"");

    static final class Channel {
        final String id, name, group;
        String logo, url, userAgent, referrer;

        Channel(String id, String name, String group, String logo, String url, String userAgent, String referrer) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.logo = logo;
            this.url = url;
            this.userAgent = userAgent;
            this.referrer = referrer;
        }
    }

    private ChannelList() {
    }

    /** The saved list if there is one, else the bundled one. Fast; safe on the main thread. */
    static List<Channel> load(Context ctx) {
        File saved = new File(ctx.getFilesDir(), SAVED);
        try {
            if (saved.isFile()) {
                List<Channel> list = parse(new String(readAll(new FileInputStream(saved)), StandardCharsets.UTF_8));
                if (!list.isEmpty()) return list;
            }
        } catch (Exception e) {
            Log.w(TAG, "saved list: " + e);
        }
        return bundled(ctx);
    }

    static List<Channel> bundled(Context ctx) {
        try (InputStream in = ctx.getResources().openRawResource(R.raw.channels)) {
            return parse(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "bundled list: " + e);
            return new ArrayList<>();
        }
    }

    static boolean refreshDue(Context ctx) {
        File saved = new File(ctx.getFilesDir(), SAVED);
        return !saved.isFile() || System.currentTimeMillis() - saved.lastModified() > DAY_MS;
    }

    /**
     * New links and logos for the bundled channels from the public lists; saves and returns the result,
     * or null when no list could be fetched. Blocking: run off the main thread.
     */
    static List<Channel> refresh(Context ctx) {
        Map<String, Channel> fresh = new HashMap<>();
        boolean any = false;
        for (String src : ctx.getResources().getStringArray(R.array.sources)) {
            try {
                for (Channel c : parse(new String(fetch(src), StandardCharsets.UTF_8))) {
                    if (!c.id.isEmpty() && !fresh.containsKey(c.id)) fresh.put(c.id, c);
                }
                any = true;
            } catch (Exception e) {
                Log.w(TAG, "source " + src + ": " + e);
            }
        }
        if (!any) return null;
        List<Channel> list = bundled(ctx);
        for (Channel c : list) {
            Channel f = fresh.get(c.id);
            if (f == null || f.url.isEmpty()) continue;   // not listed today: keep the bundled link
            c.url = f.url;
            c.userAgent = f.userAgent;
            c.referrer = f.referrer;
            if (!f.logo.isEmpty()) c.logo = f.logo;
        }
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), SAVED))) {
            out.write(write(list).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "save: " + e);
        }
        return list;
    }

    /** M3U with #EXTINF attributes (tvg-id, tvg-logo, group-title) and optional #EXTVLCOPT headers. */
    static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (!l.startsWith("#EXTINF")) continue;
            int comma = l.lastIndexOf("\",");
            comma = comma >= 0 ? comma + 1 : l.indexOf(',');
            if (comma < 0) continue;
            String name = l.substring(comma + 1).trim();
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher m = ATTR.matcher(l.substring(0, comma));
            while (m.find()) attrs.put(m.group(1), m.group(2));
            String ua = null, ref = null, url = "";
            for (int j = i + 1; j < lines.length && j < i + 8; j++) {
                String x = lines[j].trim();
                if (x.startsWith("#EXTVLCOPT:http-user-agent=")) ua = x.substring(27);
                else if (x.startsWith("#EXTVLCOPT:http-referrer=")) ref = x.substring(25);
                else if (x.startsWith("#EXTINF")) break;
                else if (!x.isEmpty() && !x.startsWith("#")) {
                    url = x;
                    break;
                }
            }
            out.add(new Channel(value(attrs, "tvg-id"), name, value(attrs, "group-title"),
                    value(attrs, "tvg-logo"), url, ua, ref));
        }
        return out;
    }

    private static String write(List<Channel> list) {
        StringBuilder b = new StringBuilder("#EXTM3U\n");
        for (Channel c : list) {
            b.append("#EXTINF:-1 tvg-id=\"").append(c.id).append("\" tvg-logo=\"").append(c.logo)
                    .append("\" group-title=\"").append(c.group).append("\",").append(c.name).append('\n');
            if (c.userAgent != null) b.append("#EXTVLCOPT:http-user-agent=").append(c.userAgent).append('\n');
            if (c.referrer != null) b.append("#EXTVLCOPT:http-referrer=").append(c.referrer).append('\n');
            b.append(c.url).append('\n');
        }
        return b.toString();
    }

    private static String value(Map<String, String> attrs, String key) {
        String v = attrs.get(key);
        return v == null ? "" : v.trim();
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

    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = src.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
