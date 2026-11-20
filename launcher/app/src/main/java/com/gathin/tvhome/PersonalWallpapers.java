package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Private, validated local wallpaper files. The document provider is needed only during import. */
final class PersonalWallpapers {
    static final String PREFIX = "personal:";
    static final String COAST_VIDEO = "coast-video";
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    static synchronized File file(Context context, String choice) {
        if (COAST_VIDEO.equals(choice)) {
            File video = new File(context.getFilesDir(), "coast-motion-v1.mp4");
            try {
                extract(context, "coast-motion.mp4", video);
                extract(context, "scenes/01-volcanic-coast.jpg", poster(video));
                return video;
            } catch (java.io.IOException unavailable) { return null; }
        }
        if (choice == null || !choice.startsWith(PREFIX)) return null;
        String name = choice.substring(PREFIX.length());
        if (!name.matches("[a-f0-9-]{36}\\.(jpg|mp4)")) return null;
        return new File(new File(context.getFilesDir(), "personal-wallpapers"), name);
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

    static List<File> list(Context context) {
        List<File> result = new ArrayList<>();
        File[] files = new File(context.getFilesDir(), "personal-wallpapers").listFiles();
        if (files != null) for (File file : files)
            if (file.getName().matches("[a-f0-9-]{36}\\.(jpg|mp4)")) result.add(file);
        result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }

    static String importFile(Context context, Uri uri, boolean video) throws Exception {
        File directory = new File(context.getFilesDir(), "personal-wallpapers");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Storage unavailable");
        File output = new File(directory, UUID.randomUUID() + (video ? ".mp4" : ".jpg"));
        File temporary = new File(output.getPath() + ".tmp");
        File poster = poster(output);
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
            return PREFIX + output.getName();
        } finally {
            temporary.delete();
            if (!complete) { output.delete(); poster.delete(); }
        }
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
