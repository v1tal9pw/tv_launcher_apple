package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.Future;

import javax.net.ssl.HttpsURLConnection;

/**
 * Fetches current weather from Open-Meteo (no API key required) and shows it
 * in the given TextView.  Coordinates come from FeaturePreferences (the city the
 * user already chose for the sky / race feature).  When no city is saved the
 * widget stays hidden.
 *
 * WMO weather-code → emoji + short description mapping is kept intentionally
 * minimal so it renders well at the small font size used under the clock.
 */
final class WeatherFetcher {
    /** How long a successful response is reused before the next network call. */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L; // 30 min
    /** Minimum gap between two failed retries. */
    private static final long RETRY_MS = 5 * 60 * 1000L; // 5 min

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context context;
    private TextView view;

    private long lastFetchMs;
    private String lastLocation = "";
    private int generation;
    private boolean fetching;
    private Future<?> pending;

    WeatherFetcher(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Attach the TextView that will display the weather line.  Call once after setContentView. */
    void bind(TextView tv) {
        this.view = tv;
    }

    /**
     * Request a refresh.  Safe to call repeatedly (from minuteTick, onResume, network callbacks);
     * the actual network call is skipped when a fresh result is already cached.
     */
    void refresh() {
        if (view == null) return;
        SharedPreferences prefs = FeaturePreferences.prefs(context);
        String lat = prefs.getString("latitude", "");
        String lon = prefs.getString("longitude", "");
        String city = prefs.getString("city", "");
        if (!prefs.getBoolean("weather_enabled", true) || lat.isEmpty() || lon.isEmpty()) {
            if (!lastLocation.isEmpty()) { lastLocation = ""; generation++; fetching = false; }
            view.setVisibility(android.view.View.GONE);
            return;
        }
        String location = lat + "," + lon + "," + city;
        if (!location.equals(lastLocation)) {
            lastLocation = location;
            lastFetchMs = 0;
            generation++;
            fetching = false;
            view.setVisibility(android.view.View.GONE);
        }
        long now = System.currentTimeMillis();
        if (fetching) return;
        if (now - lastFetchMs < CACHE_TTL_MS) return; // still fresh
        fetching = true;
        final int requestGeneration = generation;
        final String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat
                + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,weathercode,windspeed_10m"
                + "&wind_speed_unit=ms"
                + "&forecast_days=1";
        pending = FeatureNetwork.WORKER.submit(() -> {
            String line = null;
            long retryAt;
            try {
                JSONObject json = fetchJson(url);
                JSONObject current = json.optJSONObject("current");
                if (current != null) {
                    double temp = current.optDouble("temperature_2m", Double.NaN);
                    double feels = current.optDouble("apparent_temperature", Double.NaN);
                    int code = current.optInt("weathercode", -1);
                    double wind = current.optDouble("windspeed_10m", Double.NaN);
                    if (!Double.isNaN(temp)) {
                        String emoji = emojiForCode(code);
                        String tempStr = Math.round(temp) + "°";
                        String feelsStr = !Double.isNaN(feels) && Math.abs(feels - temp) >= 2
                                ? "  feels " + Math.round(feels) + "°" : "";
                        String windStr = !Double.isNaN(wind) && wind >= 5
                                ? "  " + Math.round(wind) + " m/s" : "";
                        String cityLabel = city.isEmpty() ? "" : "  ·  " + firstWord(city);
                        line = emoji + "  " + tempStr + feelsStr + windStr + cityLabel;
                    }
                }
                retryAt = System.currentTimeMillis();
            } catch (Exception ignored) {
                // On error keep whatever was shown; retry after RETRY_MS
                retryAt = System.currentTimeMillis() - CACHE_TTL_MS + RETRY_MS;
            }
            final String result = line;
            final long fetchedAt = retryAt;
            ui.post(() -> {
                if (requestGeneration != generation) return;
                fetching = false;
                lastFetchMs = fetchedAt;
                if (view == null) return;
                if (result != null) {
                    view.setText(result);
                    view.setVisibility(android.view.View.VISIBLE);
                }
            });
        });
    }

    /** Cancel any in-flight request (e.g. in onDestroy). */
    void cancel() {
        if (pending != null) pending.cancel(false);
        generation++;
        fetching = false;
        view = null;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** WMO weather interpretation codes → emoji + short description. */
    private static String emojiForCode(int code) {
        if (code < 0)  return "🌡";
        if (code == 0) return "☀️";
        if (code <= 2) return "⛅";
        if (code == 3) return "☁️";
        if (code <= 49) return "🌫";   // fog / depositing rime fog
        if (code <= 59) return "🌧";   // drizzle
        if (code <= 69) return "🌧";   // rain
        if (code <= 79) return "❄️";   // snow / snow grains
        if (code <= 82) return "🌧";   // rain showers
        if (code <= 86) return "🌨";   // snow showers
        if (code <= 99) return "⛈";   // thunderstorm
        return "🌡";
    }

    /** "Moscow, Moscow Oblast, Russia" → "Moscow" */
    private static String firstWord(String city) {
        int comma = city.indexOf(',');
        return comma > 0 ? city.substring(0, comma).trim() : city.trim();
    }

    /** Minimal HTTPS GET that only allows api.open-meteo.com. */
    private static JSONObject fetchJson(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || !"api.open-meteo.com".equals(url.getHost()))
            throw new SecurityException("Unsupported weather host");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "CleanYourTV");
        try {
            if (conn.getResponseCode() != 200)
                throw new java.io.IOException("Weather API unavailable");
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 64 * 1024) throw new java.io.IOException("Response too large");
                    out.write(buf, 0, n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            conn.disconnect();
        }
    }
}
