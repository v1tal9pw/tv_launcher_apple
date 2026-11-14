package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;

/** Checks the personal fork's published release when Home opens, at most once a day. */
final class ReleaseUpdater {
    static final String REPOSITORY = "idkwhormiloveumybabe/tv_launcher_apple";
    private static final String API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String LAST_CHECK = "release_last_check";

    static final class Release {
        final int code;
        final String url, sha256;
        Release(int code, String url, String sha256) {
            this.code = code; this.url = url; this.sha256 = sha256;
        }
    }

    static int tagCode(String tag) {
        if (tag == null || !tag.matches("v[1-9][0-9]{0,8}")) return -1;
        try { return Integer.parseInt(tag.substring(1)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    static Release parse(JSONObject json, int installedCode) {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null;
        int code = tagCode(json.optString("tag_name"));
        if (code <= installedCode) return null;
        JSONArray assets = json.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !asset.optString("name").equals("tvhome-" + code + ".apk")) continue;
            String url = asset.optString("browser_download_url");
            String digest = asset.optString("digest");
            if (url.startsWith("https://github.com/" + REPOSITORY + "/releases/download/")
                    && digest.matches("sha256:[0-9a-fA-F]{64}")
                    && asset.optLong("size") > 0 && asset.optLong("size") <= 5L * 1024 * 1024)
                return new Release(code, url, digest.substring(7).toLowerCase(java.util.Locale.ROOT));
        }
        return null;
    }

    static void check(Activity activity, Consumer<Release> available) {
        SharedPreferences prefs = FeaturePreferences.prefs(activity);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(LAST_CHECK, 0);
        if (last > 0 && now >= last && now - last < DAY_MS) return;
        prefs.edit().putLong(LAST_CHECK, now).apply();
        FeatureNetwork.WORKER.execute(() -> {
            try {
                HttpsURLConnection connection = (HttpsURLConnection) new URL(API).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "TvHome-Updater");
                try {
                    if (connection.getResponseCode() != 200) return;
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (InputStream in = connection.getInputStream()) {
                        byte[] buffer = new byte[4096]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (bytes.size() + n > 128 * 1024) return;
                            bytes.write(buffer, 0, n);
                        }
                    }
                    int installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
                    Release release = parse(new JSONObject(bytes.toString("UTF-8")), installed);
                    if (release != null) new Handler(Looper.getMainLooper()).post(() -> available.accept(release));
                } finally { connection.disconnect(); }
            } catch (Exception ignored) { /* Offline Home remains usable. */ }
        });
    }

    private ReleaseUpdater() { }
}
