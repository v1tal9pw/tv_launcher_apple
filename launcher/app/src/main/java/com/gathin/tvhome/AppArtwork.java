package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** High-resolution app artwork loading plus bounded, private copies of user-picked app banners. */
final class AppArtwork {
    static final int CUSTOM_BANNER_MAX_PX = 1024;
    private static final int CUSTOM_BANNER_JPEG_QUALITY = 95;

    static int targetArtworkDensity(int screenDensityDpi) {
        return Math.max(DisplayMetrics.DENSITY_XXXHIGH, screenDensityDpi);
    }

    static int customBannerSampleSize(int width, int height) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > CUSTOM_BANNER_MAX_PX) sample *= 2;
        return sample;
    }

    static Drawable loadHighResBanner(Context context, PackageManager pm, ResolveInfo ri) {
        if (ri == null || ri.activityInfo == null) return null;
        int id = ri.activityInfo.banner;
        if (id == 0 && ri.activityInfo.applicationInfo != null) {
            id = ri.activityInfo.applicationInfo.banner;
        }
        Drawable banner = loadForDensity(context, pm, ri.activityInfo.applicationInfo, id);
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        return enhanceDrawable(banner);
    }

    static Drawable loadHighResIcon(Context context, PackageManager pm, ResolveInfo ri) {
        if (ri == null) return null;
        Drawable icon = null;
        if (ri.activityInfo != null) {
            int id = ri.getIconResource();
            icon = loadForDensity(context, pm, ri.activityInfo.applicationInfo, id);
        }
        if (icon == null) icon = ri.loadIcon(pm);
        return enhanceDrawable(icon);
    }

    static Drawable loadHighResIcon(Context context, PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            Drawable icon = loadForDensity(context, pm, appInfo, appInfo.icon);
            if (icon == null) icon = pm.getApplicationIcon(appInfo);
            return enhanceDrawable(icon);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    private static Drawable loadForDensity(Context context, PackageManager pm, ApplicationInfo appInfo, int resId) {
        if (resId == 0 || appInfo == null) return null;
        try {
            Resources resources = pm.getResourcesForApplication(appInfo);
            int density = targetArtworkDensity(context.getResources().getDisplayMetrics().densityDpi);
            return resources.getDrawableForDensity(resId, density, null);
        } catch (PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    /** Enables bilinear filtering, anti-aliasing and GPU mipmaps so banners and icons stay sharp at any scale. */
    static Drawable enhanceDrawable(Drawable drawable) {
        if (drawable == null) return null;
        try {
            if (drawable instanceof BitmapDrawable) {
                BitmapDrawable bd = (BitmapDrawable) drawable.mutate();
                bd.setFilterBitmap(true);
                bd.setAntiAlias(true);
                Bitmap bmp = bd.getBitmap();
                if (bmp != null && !bmp.isRecycled()
                        && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || bmp.getConfig() != Bitmap.Config.HARDWARE)) {
                    bmp.setHasMipMap(true);
                }
                return bd;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) drawable.mutate();
                enhanceDrawable(adaptive.getBackground());
                enhanceDrawable(adaptive.getForeground());
                return adaptive;
            }
            if (drawable instanceof LayerDrawable) {
                LayerDrawable layers = (LayerDrawable) drawable.mutate();
                for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                    enhanceDrawable(layers.getDrawable(i));
                }
                return layers;
            }
            if (drawable instanceof DrawableWrapper) {
                DrawableWrapper wrapper = (DrawableWrapper) drawable.mutate();
                enhanceDrawable(wrapper.getDrawable());
                return wrapper;
            }
        } catch (RuntimeException ignored) {
        }
        return drawable;
    }

    static Drawable settingsIcon(Context context, ResolveInfo app) {
        return isSettingsPackage(app.activityInfo.packageName, defaultSettingsPackage(context))
                ? enhanceDrawable(context.getDrawable(R.drawable.banner_ios_settings)) : null;
    }

    static String defaultSettingsPackage(Context context) {
        ResolveInfo settings = context.getPackageManager().resolveActivity(
                new Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY);
        return settings != null && settings.activityInfo != null ? settings.activityInfo.packageName : null;
    }

    static boolean isSettingsPackage(String pkg, String defaultSettings) {
        return pkg.equals(defaultSettings) || pkg.equals("com.android.settings") || pkg.equals("com.android.tv.settings")
                || pkg.equals("com.google.android.tv.settings") || pkg.equals("com.tcl.settings")
                || pkg.equals("com.tcl.tv.settings");
    }
    static final String APP_ICON_PREFIX = "app_icon:";
    static boolean isAppIcon(String artwork) { return artwork != null && artwork.startsWith(APP_ICON_PREFIX); }
    static Drawable otherAppIcon(Context context, String artwork) {
        if (!isAppIcon(artwork)) return null;
        String pkg = artwork.substring(APP_ICON_PREFIX.length());
        if (!pkg.matches("[A-Za-z0-9_.]+")) return null;
        return loadHighResIcon(context, context.getPackageManager(), pkg);
    }
    static File file(Context context, String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("Invalid package");
        return new File(new File(context.getFilesDir(), "app-banners"), pkg + ".jpg");
    }
    static void importImage(Context context, String pkg, Uri uri) throws Exception {
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new java.io.IOException("Image is unavailable");
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 8 * 1024 * 1024) throw new java.io.IOException("Choose an image under 8 MB");
                out.write(buffer, 0, n);
            }
            bytes = out.toByteArray();
        }
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new java.io.IOException("This file is not an image");
        options.inSampleSize = customBannerSampleSize(options.outWidth, options.outHeight);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (image == null) throw new java.io.IOException("Image could not be read");
        try {
            File output = file(context, pkg); File parent = output.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Image could not be saved");
            android.util.AtomicFile atomic = new android.util.AtomicFile(output); FileOutputStream stream = atomic.startWrite();
            try {
                if (!image.compress(Bitmap.CompressFormat.JPEG, CUSTOM_BANNER_JPEG_QUALITY, stream)) throw new java.io.IOException("Image could not be saved");
                atomic.finishWrite(stream);
            } catch (Exception e) { atomic.failWrite(stream); throw e; }
        } finally { image.recycle(); }
    }
    static Drawable load(Context context, String pkg) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(file(context, pkg).getPath(), options);
            return bitmap == null ? null : enhanceDrawable(new BitmapDrawable(context.getResources(), bitmap));
        } catch (Exception | OutOfMemoryError ignored) { return null; }
    }
}
