package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;

/** Checks the personal fork's published release in the background and caches badge state. */
final class ReleaseUpdater {
    static final String REPOSITORY = "v1tal9pw/tv_launcher_apple";
    static final String GITHUB_URL = "https://github.com/" + REPOSITORY;
    private static final String API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final long AUTO_CHECK_INTERVAL_MS = 3L * 60 * 60 * 1000;
    private static final long FAILURE_RETRY_MS = 15L * 60 * 1000;
    private static final String LAST_CHECK = "release_last_check";
    private static final String CACHED_CODE = "release_available_code";
    private static final String CACHED_URL = "release_available_url";
    private static final String CACHED_SHA = "release_available_sha256";
    private static final String CACHED_NOTES = "release_available_notes";
    private static volatile long retryAfterMs;
    private static volatile boolean checking;

    static final class Release {
        final int code;
        final String url, sha256, notes;
        Release(int code, String url, String sha256, String notes) {
            this.code = code; this.url = url; this.sha256 = sha256; this.notes = notes;
        }
    }

    static String prompt(Context context, Release release) {
        String message = context.getString(R.string.update_prompt, release.code);
        return release.notes.isEmpty() ? message
                : message + "\n\n" + context.getString(R.string.update_changes) + "\n" + release.notes;
    }

    static int tagCode(String tag) {
        if (tag == null || !tag.matches("v[1-9][0-9]{0,8}")) return -1;
        try { return Integer.parseInt(tag.substring(1)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    static boolean isUpdateNewer(int availableCode, int installedCode) {
        return availableCode > 0 && installedCode > 0 && availableCode > installedCode;
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
                return new Release(code, url, digest.substring(7).toLowerCase(java.util.Locale.ROOT),
                        limitedNotes(json.optString("body", "")));
        }
        return null;
    }

    static String limitedNotes(String body) {
        String notes = body == null ? "" : body.trim();
        return notes.length() <= 3000 ? notes : notes.substring(0, 3000).trim() + "…";
    }

    static boolean hasAvailableUpdate(Context context) {
        return cachedUpdate(context) != null;
    }

    static Release cachedUpdate(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = FeaturePreferences.prefs(context);
        int code = prefs.getInt(CACHED_CODE, 0);
        if (code <= 0) return null;
        int installed = installedVersionCode(context);
        String url = prefs.getString(CACHED_URL, "");
        String sha = prefs.getString(CACHED_SHA, "");
        String notes = prefs.getString(CACHED_NOTES, "");
        if (!isUpdateNewer(code, installed)
                || url == null || !url.startsWith("https://github.com/" + REPOSITORY + "/releases/download/")
                || sha == null || !sha.matches("[0-9a-f]{64}")) {
            clearCachedUpdate(prefs);
            return null;
        }
        return new Release(code, url, sha, notes == null ? "" : notes);
    }

    private static int installedVersionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void saveCachedUpdate(SharedPreferences prefs, Release release) {
        if (release == null) {
            clearCachedUpdate(prefs);
        } else {
            prefs.edit()
                    .putInt(CACHED_CODE, release.code)
                    .putString(CACHED_URL, release.url)
                    .putString(CACHED_SHA, release.sha256)
                    .putString(CACHED_NOTES, release.notes)
                    .apply();
        }
    }

    private static void clearCachedUpdate(SharedPreferences prefs) {
        prefs.edit()
                .remove(CACHED_CODE)
                .remove(CACHED_URL)
                .remove(CACHED_SHA)
                .remove(CACHED_NOTES)
                .apply();
    }

    static void check(Activity activity, Consumer<Release> available) {
        check(activity, false, (release, checked) -> {
            if (release != null) available.accept(release);
        });
    }

    /** Automatic periodic background check; notifies the caller whenever a check completes. */
    static void checkAuto(Activity activity, Consumer<Release> completed) {
        check(activity, false, (release, checked) -> {
            if (checked && completed != null) completed.accept(release);
        });
    }

    static void checkNow(Activity activity, BiConsumer<Release, Boolean> completed) {
        check(activity, true, completed);
    }

    private static void check(Activity activity, boolean force, BiConsumer<Release, Boolean> completed) {
        SharedPreferences prefs = FeaturePreferences.prefs(activity);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(LAST_CHECK, 0);
        if (!shouldCheck(last, now, force)) return;
        if (!force && (checking || now < retryAfterMs)) return;
        checking = true;
        Context context = activity.getApplicationContext();
        FeatureNetwork.WORKER.execute(() -> {
            Release release = null;
            boolean checked = false;
            try {
                release = fetch(context);
                checked = true;
                prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
                saveCachedUpdate(prefs, release);
                retryAfterMs = 0;
            } catch (Exception ignored) {
                /* Offline Home remains usable; retry later instead of skipping a full interval. */
                retryAfterMs = System.currentTimeMillis() + FAILURE_RETRY_MS;
            } finally {
                checking = false;
            }
            Release result = release;
            boolean succeeded = checked;
            new Handler(Looper.getMainLooper()).post(() -> completed.accept(result, succeeded));
        });
    }

    static boolean shouldCheck(long last, long now, boolean force) {
        return force || last <= 0 || now < last || now - last >= AUTO_CHECK_INTERVAL_MS;
    }

    private static Release fetch(Context context) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "TvHome-Updater");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Release unavailable");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 128 * 1024) throw new IOException("Release response too large");
                    bytes.write(buffer, 0, n);
                }
            }
            int installed = installedVersionCode(context);
            return parse(new JSONObject(bytes.toString("UTF-8")), installed);
        } finally { connection.disconnect(); }
    }

    private ReleaseUpdater() { }
}
