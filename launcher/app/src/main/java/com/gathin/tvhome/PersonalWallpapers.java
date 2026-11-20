package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private, validated local wallpaper files, synced with their original source files when verifiable. */
final class PersonalWallpapers {
    static final String PREFIX = "personal:";
    static final String COAST_VIDEO = "coast-video";
    private static final String PRIVATE_DIR = "personal-wallpapers";
    private static final String META_PREFS = "personal_wallpapers_meta";
    private static final String KEY_URI = "uri:";
    private static final String KEY_PATH = "path:";
    private static final String KEY_PERSISTED = "persisted:";
    private static final String KEY_LABEL = "label:";
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    static boolean validName(String name) {
        return name != null && name.matches("[a-f0-9-]{36}\\.(jpg|mp4)");
    }

    static String extractName(String choice) {
        if (choice == null || !choice.startsWith(PREFIX)) return null;
        String name = choice.substring(PREFIX.length());
        return validName(name) ? name : null;
    }

    static boolean isUserImported(String choice) {
        return extractName(choice) != null;
    }

    static boolean tryPersistReadPermission(Context context, Uri uri, int intentFlags) {
        if (context == null || uri == null) return false;
        if ("file".equalsIgnoreCase(uri.getScheme())) return true;
        if (!"content".equalsIgnoreCase(uri.getScheme())) return false;
        int mode = intentFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (mode == 0) mode = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        ContentResolver resolver = context.getContentResolver();
        try {
            resolver.takePersistableUriPermission(uri, mode);
        } catch (SecurityException | IllegalArgumentException ignored) { }
        return hasPersistedReadPermission(resolver, uri);
    }

    static synchronized File file(Context context, String choice) {
        if (COAST_VIDEO.equals(choice)) {
            if (context == null) return null;
            File video = new File(context.getFilesDir(), "coast-motion-v2.mp4");
            try {
                extract(context, "coast-motion.mp4", video);
                extract(context, "coast-motion-poster.jpg", poster(video));
                return existsOnDisk(video) ? video : null;
            } catch (java.io.IOException unavailable) { return null; }
        }
        String name = extractName(choice);
        if (name == null || context == null) return null;
        File stored = new File(privateDir(context), name);
        if (!existsOnDisk(stored) || (video(choice) && !existsOnDisk(poster(stored)))
                || !sourceExists(context, name)) {
            deleteInternal(context, name);
            return null;
        }
        return stored;
    }

    static boolean video(String choice) {
        return COAST_VIDEO.equals(choice) || choice != null && choice.startsWith(PREFIX) && choice.endsWith(".mp4");
    }

    private static void extract(Context context, String asset, File output) throws java.io.IOException {
        if (output.isFile() && output.length() > 0) return;
        File temporary = new File(output.getPath() + ".tmp");
        try {
            try (InputStream in = context.getAssets().open(asset);
                 FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            if (!temporary.renameTo(output)) throw new java.io.IOException("Wallpaper extraction failed");
        } finally { temporary.delete(); }
    }

    static File poster(File video) { return new File(video.getPath() + ".poster.jpg"); }

    private static File privateDir(Context context) {
        return new File(context.getFilesDir(), PRIVATE_DIR);
    }

    private static SharedPreferences meta(Context context) {
        return context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE);
    }

    private static boolean existsOnDisk(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    static synchronized int pruneMissing(Context context) {
        if (context == null) return 0;
        int removed = 0;
        Set<String> present = new HashSet<>();
        File[] files = privateDir(context).listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (!validName(name)) continue;
                boolean isVideo = name.endsWith(".mp4");
                if (!existsOnDisk(file) || (isVideo && !existsOnDisk(poster(file)))
                        || !sourceExists(context, name)) {
                    deleteInternal(context, name);
                    removed++;
                } else {
                    present.add(name);
                }
            }
        }
        SharedPreferences prefs = meta(context);
        SharedPreferences.Editor cleanup = null;
        for (String key : prefs.getAll().keySet()) {
            String owner = metaOwner(key);
            if (owner != null && !present.contains(owner)) {
                if (cleanup == null) cleanup = prefs.edit();
                cleanup.remove(key);
            }
        }
        if (cleanup != null) cleanup.apply();
        return removed;
    }

    static synchronized List<File> listExisting(Context context) {
        List<File> result = new ArrayList<>();
        if (context == null) return result;
        File[] files = privateDir(context).listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (validName(name) && existsOnDisk(file)
                        && (!name.endsWith(".mp4") || existsOnDisk(poster(file)))) {
                    result.add(file);
                }
            }
        }
        result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }

    static synchronized List<File> list(Context context) {
        pruneMissing(context);
        return listExisting(context);
    }

    static synchronized boolean delete(Context context, String choice) {
        String name = extractName(choice);
        if (name == null || context == null) return false;
        return deleteInternal(context, name);
    }

    private static boolean deleteInternal(Context context, String name) {
        File stored = new File(privateDir(context), name);
        stored.delete();
        poster(stored).delete();
        SharedPreferences prefs = meta(context);
        String uriString = prefs.getString(KEY_URI + name, null);
        prefs.edit()
                .remove(KEY_URI + name)
                .remove(KEY_PATH + name)
                .remove(KEY_PERSISTED + name)
                .remove(KEY_LABEL + name)
                .apply();
        if (uriString != null && uriString.startsWith("content://")) {
            boolean stillReferenced = false;
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (entry.getKey().startsWith(KEY_URI) && uriString.equals(entry.getValue())) {
                    stillReferenced = true;
                    break;
                }
            }
            if (!stillReferenced) {
                try {
                    context.getContentResolver().releasePersistableUriPermission(
                            Uri.parse(uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException | IllegalArgumentException ignored) { }
            }
        }
        return true;
    }

    static String displayName(Context context, String choice) {
        String name = extractName(choice);
        if (name == null || context == null) return null;
        return meta(context).getString(KEY_LABEL + name, null);
    }

    static String label(Context context, String choice) {
        if (COAST_VIDEO.equals(choice)) return context.getString(R.string.personal_wallpaper_coast);
        String name = extractName(choice);
        String fallback = context.getString(video(choice)
                ? R.string.personal_wallpaper_video : R.string.personal_wallpaper_photo);
        if (name == null || context == null) return fallback;
        String saved = meta(context).getString(KEY_LABEL + name, null);
        if (saved != null && !saved.isEmpty()) return saved;
        File stored = new File(privateDir(context), name);
        return !stored.isFile() ? fallback : fallback + " · " + java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(new java.util.Date(stored.lastModified()));
    }

    static String importFile(Context context, Uri uri, boolean video) throws Exception {
        return importFile(context, uri, video, false);
    }

    static String importFile(Context context, Uri uri, boolean video, boolean persisted) throws Exception {
        File directory = privateDir(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Storage unavailable");
        String fileName = UUID.randomUUID() + (video ? ".mp4" : ".jpg");
        File output = new File(directory, fileName);
        File temporary = new File(output.getPath() + ".tmp");
        File poster = poster(output);
        String displayName = queryDisplayName(context, uri);
        String uriString = uri == null ? null : uri.toString();
        String storagePath = resolveStoragePath(uriString);
        boolean complete = false;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(temporary)) {
                if (in == null) throw new java.io.IOException("File unavailable");
                byte[] buffer = new byte[8192];
                long size = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    size += n;
                    if (size > MAX_BYTES) throw new java.io.IOException("File exceeds 200 MB");
                    out.write(buffer, 0, n);
                }
            }
            if (video) {
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                try {
                    metadata.setDataSource(temporary.getPath());
                    int width = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    int height = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    String frameRate = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                    if (!acceptedVideo(width, height, frameRate))
                        throw new java.io.IOException("Choose a video up to Full HD / 30 fps");
                    Bitmap preview = android.os.Build.VERSION.SDK_INT >= 27
                            ? metadata.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    Math.min(1280, width), Math.min(720, height))
                            : metadata.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (preview == null) throw new java.io.IOException("Video preview unavailable");
                    try (FileOutputStream out = new FileOutputStream(poster)) {
                        if (!preview.compress(Bitmap.CompressFormat.JPEG, 90, out))
                            throw new java.io.IOException("Preview write failed");
                    } finally { preview.recycle(); }
                } finally { metadata.release(); }
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(temporary.getPath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                    throw new java.io.IOException("Image unavailable");
            }
            if (!temporary.renameTo(output)) throw new java.io.IOException("Import failed");
            complete = true;
            SharedPreferences.Editor editor = meta(context).edit();
            if (uriString != null) editor.putString(KEY_URI + fileName, uriString);
            if (storagePath != null) editor.putString(KEY_PATH + fileName, storagePath);
            editor.putBoolean(KEY_PERSISTED + fileName, persisted);
            if (displayName != null) editor.putString(KEY_LABEL + fileName, displayName);
            editor.apply();
            return PREFIX + output.getName();
        } finally {
            temporary.delete();
            if (!complete) { output.delete(); poster.delete(); }
        }
    }

    private static boolean sourceExists(Context context, String name) {
        SharedPreferences prefs = meta(context);
        String uriString = prefs.getString(KEY_URI + name, null);
        String path = prefs.getString(KEY_PATH + name, null);
        boolean persisted = prefs.getBoolean(KEY_PERSISTED + name, false);
        if (uriString == null && path == null) return true;
        if (uriString != null) {
            Uri uri = Uri.parse(uriString);
            String scheme = uri.getScheme();
            if ("file".equalsIgnoreCase(scheme)) {
                String filePath = uri.getPath();
                return localFileExists(filePath == null ? null : new File(filePath));
            }
            if ("content".equalsIgnoreCase(scheme)) {
                ContentResolver resolver = context.getContentResolver();
                try (AssetFileDescriptor afd = resolver.openAssetFileDescriptor(uri, "r")) {
                    return afd != null;
                } catch (FileNotFoundException | IllegalArgumentException missing) {
                    return false;
                } catch (SecurityException denied) {
                    if (persisted && !hasPersistedReadPermission(resolver, uri)) return false;
                    return path == null || localFileExists(new File(path));
                } catch (Exception ignored) {
                    return true;
                }
            }
        }
        return path == null || localFileExists(new File(path));
    }

    private static boolean hasPersistedReadPermission(ContentResolver resolver, Uri uri) {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.isReadPermission() && uri.equals(permission.getUri())) return true;
        }
        return false;
    }

    private static String queryDisplayName(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            return path == null ? null : cleanDisplayName(new File(path).getName());
        }
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String cleaned = cleanDisplayName(cursor.getString(index));
                    if (cleaned != null) return cleaned;
                }
            }
        } catch (Exception ignored) { }
        String path = resolveStoragePath(uri.toString());
        return path == null ? null : cleanDisplayName(new File(path).getName());
    }

    private static String metaOwner(String key) {
        for (String prefix : new String[]{KEY_URI, KEY_PATH, KEY_PERSISTED, KEY_LABEL}) {
            if (key.startsWith(prefix)) return key.substring(prefix.length());
        }
        return null;
    }

    static boolean localFileExists(File file) {
        if (file == null) return true;
        if (file.isFile() && file.length() > 0) return true;
        File dir = file.getParentFile();
        for (int depth = 0; depth < 3 && dir != null; depth++, dir = dir.getParentFile()) {
            if (dir.isDirectory()) {
                // Only treat the file as deleted when we can actually inspect an enclosing directory;
                // under Android Scoped Storage unreadable directories hide files that still exist.
                return dir.list() == null;
            }
        }
        return true;
    }

    static String resolveStoragePath(String uriString) {
        if (uriString == null || uriString.isEmpty()) return null;
        if (uriString.startsWith("file://")) {
            String decoded = percentDecode(uriString.substring("file://".length()));
            return decoded.startsWith("/") ? decoded : null;
        }
        String marker = "://com.android.externalstorage.documents/document/";
        int idx = uriString.indexOf(marker);
        if (idx >= 0) {
            String docId = percentDecode(uriString.substring(idx + marker.length()));
            int query = docId.indexOf('?');
            if (query >= 0) docId = docId.substring(0, query);
            int colon = docId.indexOf(':');
            if (colon <= 0) return null;
            String volume = docId.substring(0, colon);
            String relative = docId.substring(colon + 1);
            if (relative.isEmpty() || relative.contains("..")) return null;
            if ("primary".equals(volume)) return "/storage/emulated/0/" + relative;
            if (volume.matches("[A-Za-z0-9._-]+")) return "/storage/" + volume + "/" + relative;
        }
        return null;
    }

    static String cleanDisplayName(String raw) {
        if (raw == null) return null;
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) return null;
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    private static String percentDecode(String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '%' && i + 2 < input.length()) {
                int hi = Character.digit(input.charAt(i + 1), 16);
                int lo = Character.digit(input.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(utf8, 0, utf8.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static boolean acceptedVideo(int width, int height, String frameRate) {
        if (width <= 0 || height <= 0 || Math.max(width, height) > 1920 || (long) width * height > 1920L * 1080)
            return false;
        if (frameRate == null || frameRate.isEmpty()) return true;
        try {
            double rate = Double.parseDouble(frameRate);
            return Double.isFinite(rate) && rate > 0 && rate <= 30.1;
        } catch (NumberFormatException invalid) { return false; }
    }

    private PersonalWallpapers() { }
}
