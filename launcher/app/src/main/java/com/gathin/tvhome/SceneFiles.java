package com.gathin.tvhome;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** Bundled, offline 4K Apple-style scenes. Extract once so the cropped wallpaper decoder handles every source. */
final class SceneFiles {
    static final String DEFAULT_SCENE = "01-apple-aurora.webp";
    static final String AURORA_ASSET = "scenes/" + DEFAULT_SCENE;
    private static final String DIR_NAME = "scenes-v4-apple-4k";

    static synchronized List<File> images(Context context) {
        cleanupLegacy(new File(context.getFilesDir(), "scenes-v3-fhd"));
        File dir = new File(context.getFilesDir(), DIR_NAME);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return java.util.Collections.emptyList();
            String[] names = context.getAssets().list("scenes");
            if (names != null) for (String name : names) {
                if (!name.matches("[a-zA-Z0-9-]+\\.webp")) continue;
                File output = new File(dir, name);
                if (output.isFile() && output.length() > 0) continue;
                File temporary = new File(dir, name + ".tmp");
                try (InputStream in = context.getAssets().open("scenes/" + name);
                     FileOutputStream out = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                if (!temporary.renameTo(output)) temporary.delete();
            }
        } catch (Exception ignored) { }
        return WallpaperRotation.images(dir);
    }

    private static void cleanupLegacy(File legacyDir) {
        if (!legacyDir.isDirectory()) return;
        File[] old = legacyDir.listFiles();
        if (old != null) for (File file : old) file.delete();
        legacyDir.delete();
    }

    static File find(Context context, String name) {
        List<File> files = images(context);
        File exact = WallpaperRotation.find(files, name);
        return exact != null ? exact : WallpaperRotation.find(files, name.replaceFirst("\\.jpg$", ".webp"));
    }

    static String label(String filename) {
        String name = filename.replaceFirst("^[0-9]+-", "").replaceFirst("\\.[^.]+$", "").replace('-', ' ');
        return name.isEmpty() ? filename : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
