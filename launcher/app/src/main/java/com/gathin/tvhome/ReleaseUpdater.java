package com.gathin.tvhome;

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
    private static final String CDN_LATEST = GITHUB_URL + "/releases/latest/download/latest.json";
    private static final String API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final long AUTO_CHECK_INTERVAL_MS = 3L * 60 * 60 * 1000;
    private static final long FAILURE_RETRY_MS = 15L * 60 * 1000;
    static final long POWER_ON_DEBOUNCE_MS = 15L * 1000;
    static final long POWER_ON_RETRY_MS = 10L * 1000;
    static final int MAX_POWER_ON_RETRIES = 4;
    private static final String LAST_CHECK = "release_last_check";
    private static final String CACHED_CODE = "release_available_code";
    private static final String CACHED_URL = "release_available_url";
    private static final String CACHED_SHA = "release_available_sha256";
    private static final String CACHED_NOTES = "release_available_notes";
    private static final String NOTIFIED_CODE = "release_notified_code";
    private static final String API_ETAG = "release_api_etag";
    private static volatile long retryAfterMs;
    private static volatile boolean checking;
    private static volatile boolean pendingPowerOnCheck;
    private static volatile long lastPowerOnSuccessMs;
    private static volatile long lastPowerOnAttemptMs;
    private static volatile int powerOnFailures;

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

    static Release validateReleaseAsset(int code, int installedCode, String name, String url,
                                        String sha256, long size, String notes) {
        if (code <= installedCode) return null;
        if (name == null || !name.equals("tvhome-" + code + ".apk")) return null;
        if (url == null || !url.startsWith("https://github.com/" + REPOSITORY + "/releases/download/")) return null;
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) return null;
        if (size <= 0 || size > 5L * 1024 * 1024) return null;
        return new Release(code, url, sha256.toLowerCase(java.util.Locale.ROOT), limitedNotes(notes));
    }

    static Release parseLatestJson(JSONObject json, int installedCode) {
        int code = tagCode(json.optString("tag_name"));
        if (code <= 0) return null;
        return validateReleaseAsset(
                code,
                installedCode,
                json.optString("name"),
                json.optString("url"),
                json.optString("sha256"),
                json.optLong("size"),
                json.optString("notes", ""));
    }

    static Release parse(JSONObject json, int installedCode) {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null;
        int code = tagCode(json.optString("tag_name"));
        if (code <= installedCode) return null;
        JSONArray assets = json.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String digest = asset.optString("digest");
            if (digest == null || !digest.startsWith("sha256:")) continue;
            Release validated = validateReleaseAsset(
                    code,
                    installedCode,
                    asset.optString("name"),
                    asset.optString("browser_download_url"),
                    digest.substring(7),
                    asset.optLong("size"),
                    json.optString("body", ""));
            if (validated != null) return validated;
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

    static boolean shouldShowUpdateBanner(int availableCode, int lastNotifiedCode) {
        return availableCode > 0 && availableCode > lastNotifiedCode;
    }

    static Release pendingBannerRelease(Context context) {
        Release cached = cachedUpdate(context);
        if (cached == null) return null;
        int notified = FeaturePreferences.prefs(context).getInt(NOTIFIED_CODE, 0);
        return shouldShowUpdateBanner(cached.code, notified) ? cached : null;
    }

    static void markUpdateNotified(Context context, int code) {
        if (context == null || code <= 0) return;
        FeaturePreferences.prefs(context).edit().putInt(NOTIFIED_CODE, code).apply();
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

    static void check(Context context, Consumer<Release> available) {
        check(context, false, false, (release, checked) -> {
            if (release != null && available != null) available.accept(release);
        }, null);
    }

    /** Automatic periodic background check; notifies the caller whenever a check completes. */
    static void checkAuto(Context context, Consumer<Release> completed) {
        if (pendingPowerOnCheck) {
            retryPendingPowerOnCheck(context, completed);
            return;
        }
        check(context, false, false, (release, checked) -> {
            if (checked && completed != null) completed.accept(release);
        }, null);
    }

    /** Background check on TV power-on (boot or wake from standby); bypasses the 3-hour cooldown. */
    static void checkOnPowerOn(Context context, Consumer<Release> completed) {
        checkOnPowerOn(context, completed, null);
    }

    static void checkOnPowerOn(Context context, Consumer<Release> completed, Runnable onFinished) {
        if (context == null) {
            if (onFinished != null) onFinished.run();
            return;
        }
        long now = System.currentTimeMillis();
        if (!shouldRunPowerOnCheck(lastPowerOnSuccessMs, now, checking)) {
            if (onFinished != null) onFinished.run();
            return;
        }
        pendingPowerOnCheck = true;
        powerOnFailures = 0;
        lastPowerOnAttemptMs = now;
        check(context, true, true, (release, checked) -> {
            if (checked && completed != null) completed.accept(release);
        }, onFinished);
    }

    /** Retries a deferred power-on check as soon as Wi-Fi or Ethernet comes up after TV wake/boot. */
    static void retryPendingPowerOnCheck(Context context, Consumer<Release> completed) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        if (!shouldRetryPowerOn(pendingPowerOnCheck, powerOnFailures, lastPowerOnAttemptMs, now, checking)) {
            return;
        }
        lastPowerOnAttemptMs = now;
        check(context, true, true, (release, checked) -> {
            if (checked && completed != null) completed.accept(release);
        }, null);
    }

    static void checkNow(Context context, BiConsumer<Release, Boolean> completed) {
        check(context, true, false, completed, null);
    }

    private static void check(Context context, boolean force, boolean powerOn,
                              BiConsumer<Release, Boolean> completed, Runnable onFinished) {
        if (context == null) {
            if (onFinished != null) onFinished.run();
            return;
        }
        Context app = context.getApplicationContext();
        SharedPreferences prefs = FeaturePreferences.prefs(app);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(LAST_CHECK, 0);
        if (!shouldCheck(last, now, force)) {
            if (onFinished != null) onFinished.run();
            return;
        }
        if ((!force || powerOn) && checking) {
            if (onFinished != null) onFinished.run();
            return;
        }
        if (!force && now < retryAfterMs) {
            if (onFinished != null) onFinished.run();
            return;
        }
        checking = true;
        FeatureNetwork.WORKER.execute(() -> {
            Release release = null;
            boolean checked = false;
            try {
                release = fetch(app);
                checked = true;
                long doneAt = System.currentTimeMillis();
                prefs.edit().putLong(LAST_CHECK, doneAt).apply();
                saveCachedUpdate(prefs, release);
                retryAfterMs = 0;
                pendingPowerOnCheck = false;
                powerOnFailures = 0;
                lastPowerOnSuccessMs = doneAt;
            } catch (Exception ignored) {
                /* Offline Home remains usable; power-on checks retry as soon as the network returns. */
                long failedAt = System.currentTimeMillis();
                if (powerOn) {
                    powerOnFailures++;
                    lastPowerOnAttemptMs = failedAt;
                    if (powerOnFailures >= MAX_POWER_ON_RETRIES) {
                        pendingPowerOnCheck = false;
                    }
                }
                retryAfterMs = failedAt + FAILURE_RETRY_MS;
            } finally {
                checking = false;
                if (onFinished != null) onFinished.run();
            }
            if (completed != null) {
                Release result = release;
                boolean succeeded = checked;
                new Handler(Looper.getMainLooper()).post(() -> completed.accept(result, succeeded));
            }
        });
    }

    static boolean shouldCheck(long last, long now, boolean force) {
        return force || last <= 0 || now < last || now - last >= AUTO_CHECK_INTERVAL_MS;
    }

    static boolean shouldRunPowerOnCheck(long lastSuccessMs, long nowMs, boolean inFlight) {
        return !inFlight && (lastSuccessMs <= 0 || nowMs < lastSuccessMs || nowMs - lastSuccessMs >= POWER_ON_DEBOUNCE_MS);
    }

    static boolean shouldRetryPowerOn(boolean pending, int failures, long lastAttemptMs, long nowMs, boolean inFlight) {
        if (!pending || inFlight || failures >= MAX_POWER_ON_RETRIES) return false;
        return lastAttemptMs <= 0 || nowMs < lastAttemptMs || nowMs - lastAttemptMs >= POWER_ON_RETRY_MS;
    }

    static boolean isAllowedCdnUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            String host = new URL(url).getHost().toLowerCase(java.util.Locale.ROOT);
            return host.equals("github.com")
                    || host.equals("objects.githubusercontent.com")
                    || host.endsWith(".githubusercontent.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Release fetch(Context context) throws Exception {
        int installed = installedVersionCode(context);
        try {
            return fetchFromCdn(installed);
        } catch (Exception cdnFailure) {
            return fetchFromApi(context, installed);
        }
    }

    /** Rate-limit-free CDN manifest (releases/latest/download/latest.json). */
    private static Release fetchFromCdn(int installed) throws Exception {
        String current = CDN_LATEST;
        for (int hops = 0; hops < 5; hops++) {
            if (!isAllowedCdnUrl(current)) throw new IOException("Untrusted CDN redirect");
            HttpsURLConnection connection = (HttpsURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "TvHome-Updater");
            connection.setRequestProperty("Cache-Control", "no-cache");
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String next = connection.getHeaderField("Location");
                    if (next == null || next.isEmpty()) throw new IOException("Missing redirect location");
                    current = new URL(new URL(current), next).toString();
                    continue;
                }
                if (code != 200) throw new IOException("CDN manifest unavailable: " + code);
                String body = readLimitedUtf8(connection.getInputStream());
                JSONObject json = new JSONObject(body);
                if (tagCode(json.optString("tag_name")) <= 0) throw new IOException("Invalid CDN manifest");
                return parseLatestJson(json, installed);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many CDN redirects");
    }

    /** Fallback to GitHub REST API using ETag conditional requests (304 consumes 0 rate-limit quota). */
    private static Release fetchFromApi(Context context, int installed) throws Exception {
        SharedPreferences prefs = FeaturePreferences.prefs(context);
        String savedEtag = prefs.getString(API_ETAG, "");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "TvHome-Updater");
        if (savedEtag != null && !savedEtag.isEmpty()) {
            connection.setRequestProperty("If-None-Match", savedEtag);
        }
        try {
            int code = connection.getResponseCode();
            if (code == 304) {
                return cachedUpdate(context);
            }
            if (code != 200) throw new IOException("Release unavailable: " + code);
            String etag = connection.getHeaderField("ETag");
            if (etag != null && !etag.trim().isEmpty()) {
                prefs.edit().putString(API_ETAG, etag.trim()).apply();
            }
            String body = readLimitedUtf8(connection.getInputStream());
            return parse(new JSONObject(body), installed);
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimitedUtf8(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = stream) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (bytes.size() + n > 128 * 1024) throw new IOException("Release response too large");
                bytes.write(buffer, 0, n);
            }
        }
        return bytes.toString("UTF-8");
    }

    private ReleaseUpdater() { }
}
