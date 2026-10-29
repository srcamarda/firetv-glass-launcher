package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bing's image of the day as the wallpaper. Uses the same JSON endpoint as bing.com's own
 * home page (no API key). The image is saved as files/bing.jpg; HomeActivity shows it
 * dimmed, with the photo credit in a corner. Only www.bing.com is contacted.
 */
final class BingWallpaper {

    static final String FILE = "bing.jpg";
    private static final String TAG = "GlassBing";
    private static final String HOST = "https://www.bing.com";

    static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** True when the saved image is missing, from another day, or for another day offset. */
    static boolean isStale(Context context) {
        SharedPreferences sp = GlassPrefs.get(context);
        return !new File(context.getFilesDir(), FILE).isFile()
                || !today().equals(sp.getString(GlassPrefs.BING_FETCHED_DAY, ""))
                || sp.getInt(GlassPrefs.BING_IDX, 0) != sp.getInt(GlassPrefs.BING_FETCHED_IDX, -1);
    }

    /** Downloads the image for the current day offset. Blocking: call off the main thread. */
    static boolean fetch(Context context) {
        SharedPreferences sp = GlassPrefs.get(context);
        int idx = sp.getInt(GlassPrefs.BING_IDX, 0);
        try {
            String mkt = URLEncoder.encode(Locale.getDefault().toLanguageTag(), "UTF-8");
            String json = new String(get(HOST + "/HPImageArchive.aspx?format=js&n=1&idx=" + idx + "&mkt=" + mkt), "UTF-8");
            JSONObject img = new JSONObject(json).getJSONArray("images").getJSONObject(0);
            String url = HOST + img.getString("url"); // e.g. /th?id=OHR.Name_PT-BR123_1920x1080.jpg&...
            String credit = img.optString("copyright", "");

            File file = new File(context.getFilesDir(), FILE);
            if (!url.equals(sp.getString(GlassPrefs.BING_URL, "")) || !file.isFile()) {
                byte[] data = get(url);
                File tmp = new File(context.getFilesDir(), FILE + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (!tmp.renameTo(file)) throw new IllegalStateException("rename failed");
            }
            sp.edit()
                    .putString(GlassPrefs.BING_URL, url)
                    .putString(GlassPrefs.BING_CREDIT, credit)
                    .putString(GlassPrefs.BING_FETCHED_DAY, today())
                    .putInt(GlassPrefs.BING_FETCHED_IDX, idx)
                    .apply();
            Log.i(TAG, "saved idx=" + idx + " " + url);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "fetch failed", e);
            return false;
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) GlassLauncher");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + c.getResponseCode() + " for " + url);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private BingWallpaper() {}
}
