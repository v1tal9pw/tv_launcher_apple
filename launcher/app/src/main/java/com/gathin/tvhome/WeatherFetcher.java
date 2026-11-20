package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.net.URLEncoder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
    private static final long RETRY_MS = 60 * 1000L; // 5 min

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context context;
    private TextView view;

    private long nextFetchMs;
    private boolean lastFailed;
    private String lastLocation = "";
    private int generation;
    private boolean fetching;
    private Future<?> pending;

    WeatherFetcher(Context context) {
        Configuration system = new Configuration(context.getResources().getConfiguration());
        system.setLocales(Resources.getSystem().getConfiguration().getLocales());
        this.context = context.getApplicationContext().createConfigurationContext(system);
    }

    /** Attach the TextView that will display the weather line.  Call once after setContentView. */
    void bind(TextView tv) {
        this.view = tv;
    }

    void networkAvailable() {
        if (lastFailed) nextFetchMs = 0;
        refresh();
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
        String city = prefs.getBoolean("weather_city", true) ? prefs.getString("city", "") : "";
        boolean details = prefs.getBoolean("weather_details", true);
        view.setTextSize(prefs.getInt("weather_size", 16));
        if (!prefs.getBoolean("weather_enabled", true) || lat.isEmpty() || lon.isEmpty()) {
            if (!lastLocation.isEmpty()) { lastLocation = ""; generation++; fetching = false; }
            view.setText(context.getString(R.string.weather_choose_city));
            view.setVisibility(prefs.getBoolean("weather_enabled", true)
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            return;
        }
        String location = lat + "," + lon + "," + city + details + context.getResources().getConfiguration().getLocales().toLanguageTags();
        if (!location.equals(lastLocation)) {
            lastLocation = location;
            nextFetchMs = 0;
            generation++;
            fetching = false;
            view.setText(context.getString(R.string.weather_loading));
            view.setVisibility(android.view.View.VISIBLE);
        }
        long now = SystemClock.elapsedRealtime();
        if (fetching) return;
        if (now < nextFetchMs) return;
        fetching = true;
        final int requestGeneration = generation;
        final String url;
        try { url = requestUrl(lat, lon); }
        catch (IllegalArgumentException invalid) {
            fetching = false;
            view.setText(context.getString(R.string.weather_choose_city));
            view.setVisibility(android.view.View.VISIBLE);
            return;
        }
        pending = FeatureNetwork.WORKER.submit(() -> {
            String line = null;
            long retryAt;
            try {
                JSONObject json = fetchJson(url);
                JSONObject current = json.optJSONObject("current");
                if (current != null) {
                    double temp = current.optDouble("temperature_2m", Double.NaN);
                    double feels = current.optDouble("apparent_temperature", Double.NaN);
                    int code = current.optInt("weather_code", current.optInt("weathercode", -1));
                    double wind = current.optDouble("wind_speed_10m", current.optDouble("windspeed_10m", Double.NaN));
                    if (!Double.isNaN(temp)) {
                        String emoji = emojiForCode(code);
                        String tempStr = Math.round(temp) + "°";
                        String feelsStr = details && !Double.isNaN(feels) && Math.abs(feels - temp) >= 2
                                ? "  " + context.getString(R.string.weather_feels, Math.round(feels)) : "";
                        String windStr = details && !Double.isNaN(wind) && wind >= 5
                                ? "  " + context.getString(R.string.weather_wind, Math.round(wind)) : "";
                        String cityLabel = city.isEmpty() ? "" : "  ·  " + localizedCity(prefs, city, lat, lon);
                        line = emoji + "  " + tempStr + feelsStr + windStr + cityLabel;
                    }
                }
                if (line == null) throw new java.io.IOException("Missing current weather");
                retryAt = SystemClock.elapsedRealtime() + CACHE_TTL_MS;
            } catch (Exception error) {
                Log.w("TvHomeWeather", "Weather refresh failed: " + error.getClass().getSimpleName());
                retryAt = SystemClock.elapsedRealtime() + RETRY_MS;
            }
            final String result = line;
            final long fetchedAt = retryAt;
            ui.post(() -> {
                if (requestGeneration != generation) return;
                fetching = false;
                nextFetchMs = fetchedAt;
                lastFailed = result == null;
                if (view == null) return;
                if (result != null) {
                    view.setText(result);
                    view.setVisibility(android.view.View.VISIBLE);
                } else {
                    view.setText(context.getString(R.string.weather_unavailable));
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

    private String localizedCity(SharedPreferences prefs, String city, String latitude, String longitude) {
        String language = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
        String key = latitude + "," + longitude + "," + city + "," + language;
        if (key.equals(prefs.getString("weather_city_locale_key", "")))
            return prefs.getString("weather_city_localized", firstWord(city));
        try {
            org.json.JSONArray cities = FeatureNetwork.get(
                    "https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(firstWord(city), "UTF-8")
                    + "&count=10&language=" + language + "&format=json").optJSONArray("results");
            if (cities != null) for (int i = 0; i < cities.length(); i++) {
                JSONObject found = cities.getJSONObject(i);
                if (Math.abs(found.optDouble("latitude", Double.NaN) - Double.parseDouble(latitude)) < .05
                        && Math.abs(found.optDouble("longitude", Double.NaN) - Double.parseDouble(longitude)) < .05) {
                    String name = found.getString("name");
                    prefs.edit().putString("weather_city_locale_key", key)
                            .putString("weather_city_localized", name).apply();
                    return name;
                }
            }
        } catch (Exception unavailable) {
            Log.w("TvHomeWeather", "City translation unavailable");
        }
        return firstWord(city);
    }

    static String requestUrl(String latitude, String longitude) {
        double lat = Double.parseDouble(latitude), lon = Double.parseDouble(longitude);
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
            throw new IllegalArgumentException("Invalid coordinates");
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
                + "&wind_speed_unit=ms&forecast_days=1";
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
                throw new java.io.IOException("Weather HTTP " + conn.getResponseCode());
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
