package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports and imports all user launcher preferences (grid, appearance, dock/folder layout,
 * app customizations, remote button mappings) as a compact JSON file.
 */
final class SettingsBackup {
    static final int SCHEMA_VERSION = 1;
    static final String DEFAULT_FILE_NAME = "v1tal9-tv-backup.json";
    private static final int MAX_BACKUP_BYTES = 2 * 1024 * 1024;

    static final class Payload {
        final Map<String, Object> settings;
        final Map<String, Object> features;
        final JSONObject layout;

        Payload(Map<String, Object> settings, Map<String, Object> features, JSONObject layout) {
            this.settings = settings;
            this.features = features;
            this.layout = layout;
        }
    }

    private SettingsBackup() { }

    static File localBackupFile(Context context) {
        File dir = context.getExternalFilesDir("backups");
        if (dir == null) dir = new File(context.getFilesDir(), "backups");
        return new File(dir, DEFAULT_FILE_NAME);
    }

    static boolean hasLocalBackup(Context context) {
        File file = localBackupFile(context);
        return file.isFile() && file.length() > 0;
    }

    static boolean isTransientKey(String key) {
        return key == null
                || key.startsWith("release_")
                || key.startsWith("weather_cache")
                || key.equals("weather_last_updated")
                || key.equals("settings_changed_at");
    }

    static String serialize(Map<String, ?> settings, Map<String, ?> features, JSONObject layout)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA_VERSION);
        root.put("app", "v1tal9-tv-launcher");
        root.put("exportedAt", System.currentTimeMillis());
        root.put("settings", encodeMap(settings));
        root.put("features", encodeMap(features));
        if (layout != null) root.put("layout", layout);
        return root.toString(2);
    }

    static Payload parse(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) throw new JSONException("Empty backup");
        JSONObject root = new JSONObject(json);
        int schema = root.optInt("schema", -1);
        if (schema != SCHEMA_VERSION) throw new JSONException("Unsupported schema: " + schema);
        JSONObject settingsObj = root.optJSONObject("settings");
        JSONObject featuresObj = root.optJSONObject("features");
        if (settingsObj == null && featuresObj == null) throw new JSONException("Missing settings payload");
        Map<String, Object> settings = decodeMap(settingsObj);
        Map<String, Object> features = decodeMap(featuresObj);
        JSONObject layout = root.optJSONObject("layout");
        if (layout != null && layout.optInt("version", -1) != 2) {
            throw new JSONException("Invalid layout version");
        }
        return new Payload(settings, features, layout);
    }

    private static JSONObject encodeMap(Map<String, ?> source) throws JSONException {
        JSONObject out = new JSONObject();
        if (source == null) return out;
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (isTransientKey(key) || val == null) continue;
            JSONObject typed = new JSONObject();
            if (val instanceof Boolean) {
                typed.put("t", "b").put("v", val);
            } else if (val instanceof Integer) {
                typed.put("t", "i").put("v", val);
            } else if (val instanceof Long) {
                typed.put("t", "l").put("v", val);
            } else if (val instanceof Float) {
                typed.put("t", "f").put("v", ((Float) val).doubleValue());
            } else if (val instanceof String) {
                typed.put("t", "s").put("v", val);
            } else {
                continue;
            }
            out.put(key, typed);
        }
        return out;
    }

    private static Map<String, Object> decodeMap(JSONObject obj) throws JSONException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (obj == null) return out;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isTransientKey(key)) continue;
            JSONObject typed = obj.optJSONObject(key);
            if (typed == null || !typed.has("t") || !typed.has("v")) continue;
            String type = typed.optString("t");
            switch (type) {
                case "b":
                    out.put(key, typed.getBoolean("v"));
                    break;
                case "i":
                    out.put(key, typed.getInt("v"));
                    break;
                case "l":
                    out.put(key, typed.getLong("v"));
                    break;
                case "f":
                    out.put(key, (float) typed.getDouble("v"));
                    break;
                case "s":
                    out.put(key, typed.getString("v"));
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    static String exportToJson(Context context) throws JSONException {
        SharedPreferences settings = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        SharedPreferences features = FeaturePreferences.prefs(context);
        HomeLayout layout = new HomeLayoutStore(context).read();
        JSONObject layoutJson = encodeLayout(layout);
        return serialize(settings.getAll(), features.getAll(), layoutJson);
    }

    private static JSONObject encodeLayout(HomeLayout layout) throws JSONException {
        JSONArray folders = new JSONArray();
        for (HomeLayout.Folder folder : layout.folders.values()) {
            folders.put(new JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("apps", new JSONArray(folder.apps)));
        }
        return new JSONObject()
                .put("version", 2)
                .put("home", new JSONArray(layout.home))
                .put("folders", folders);
    }

    static File exportToLocalFile(Context context) throws IOException, JSONException {
        File target = localBackupFile(context);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create backup directory");
        }
        String json = exportToJson(context);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        return target;
    }

    static void exportToUri(Context context, Uri uri) throws IOException, JSONException {
        String json = exportToJson(context);
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Cannot open destination");
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    static void restoreFromLocalFile(Context context) throws IOException, JSONException {
        File source = localBackupFile(context);
        if (!source.isFile()) throw new IOException("Backup file not found");
        try (FileInputStream in = new FileInputStream(source)) {
            restoreFromStream(context, in);
        }
    }

    static void restoreFromUri(Context context, Uri uri) throws IOException, JSONException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot read backup file");
            restoreFromStream(context, in);
        }
    }

    static void restoreFromStream(Context context, InputStream in) throws IOException, JSONException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (bytes.size() + n > MAX_BACKUP_BYTES) throw new IOException("Backup file too large");
            bytes.write(buf, 0, n);
        }
        restoreFromJson(context, bytes.toString(StandardCharsets.UTF_8.name()));
    }

    static void restoreFromJson(Context context, String json) throws JSONException {
        Payload payload = parse(json);
        applyPrefs(context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE), payload.settings);
        applyPrefs(FeaturePreferences.prefs(context), payload.features);
        if (payload.layout != null) {
            HomeLayout restored = decodeLayout(payload.layout);
            new HomeLayoutStore(context).write(restored);
        }
        Config.markSettingsChanged(context);
    }

    private static HomeLayout decodeLayout(JSONObject json) throws JSONException {
        HomeLayout layout = new HomeLayout();
        JSONArray home = json.optJSONArray("home");
        if (home != null) {
            for (int i = 0; i < home.length(); i++) {
                String k = home.optString(i, "");
                if (!k.isEmpty()) layout.home.add(k);
            }
        }
        JSONArray folders = json.optJSONArray("folders");
        if (folders != null) {
            for (int i = 0; i < folders.length(); i++) {
                JSONObject f = folders.optJSONObject(i);
                if (f == null) continue;
                String id = f.optString("id", "");
                if (!id.startsWith(HomeLayout.FOLDER_PREFIX)) continue;
                HomeLayout.Folder folder = new HomeLayout.Folder(id, f.optString("name", ""));
                JSONArray apps = f.optJSONArray("apps");
                if (apps != null) {
                    for (int j = 0; j < apps.length(); j++) {
                        String a = apps.optString(j, "");
                        if (!a.isEmpty()) folder.apps.add(a);
                    }
                }
                layout.folders.put(id, folder);
            }
        }
        layout.reconcile(new ArrayList<>());
        return layout;
    }

    private static void applyPrefs(SharedPreferences prefs, Map<String, Object> values) {
        SharedPreferences.Editor edit = prefs.edit();
        List<String> existingKeys = new ArrayList<>(prefs.getAll().keySet());
        for (String key : existingKeys) {
            if (!isTransientKey(key)) edit.remove(key);
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object v = entry.getValue();
            if (v instanceof Boolean) edit.putBoolean(key, (Boolean) v);
            else if (v instanceof Integer) edit.putInt(key, (Integer) v);
            else if (v instanceof Long) edit.putLong(key, (Long) v);
            else if (v instanceof Float) edit.putFloat(key, (Float) v);
            else if (v instanceof String) edit.putString(key, (String) v);
        }
        edit.commit();
    }
}
