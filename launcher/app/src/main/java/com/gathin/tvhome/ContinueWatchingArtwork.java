package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Poster thumbnails and Apple TV-style full-screen backdrops; memory-only cache, no persistent history. */
final class ContinueWatchingArtwork {
    private static final int THUMB_MAX_DIM = 512;
    private static final int FULL_MAX_DIM = 1920;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final int FULL_CACHE_BYTES = (int) Math.max(8 * 1024 * 1024L,
            Math.min(20 * 1024 * 1024L, Runtime.getRuntime().maxMemory() / 10L));
    private static final LruCache<String, Bitmap> FULL_CACHE = new LruCache<String, Bitmap>(FULL_CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final LruCache<String, Bitmap> BLUR_CACHE = new LruCache<String, Bitmap>(1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32), new ThreadPoolExecutor.DiscardPolicy());
    static { WORKER.allowCoreThreadTimeOut(true); }
    private static String key(ContinueWatchingRepository.Item item) { return item.pkg + '\n' + item.artwork; }
    static Bitmap cached(ContinueWatchingRepository.Item item) { return CACHE.get(key(item)); }
    static Bitmap cachedFull(ContinueWatchingRepository.Item item) { return FULL_CACHE.get(key(item)); }
    static Bitmap cachedBlur(ContinueWatchingRepository.Item item) { return BLUR_CACHE.get(key(item)); }
    static void clearCache() { CACHE.evictAll(); FULL_CACHE.evictAll(); BLUR_CACHE.evictAll(); }

    static int computeSampleSize(int width, int height, int maxDim) {
        if (width <= 0 || height <= 0) return 1;
        int limit = Math.max(256, maxDim);
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / sample > limit) sample *= 2;
        return sample;
    }

    static String highResUrl(String url) {
        if (url == null || !url.startsWith("https://")) return url;
        if (url.contains(".yandex.net/") || url.contains(".yandex.ru/") || url.contains("kinopoisk.ru/")) {
            int query = url.indexOf('?');
            String base = query >= 0 ? url.substring(0, query) : url;
            String suffix = query >= 0 ? url.substring(query) : "";
            String upgraded = base.replaceFirst("/(?:\\d+x\\d+|x\\d+|\\d+x)$", "/1920x1080");
            if (!upgraded.equals(base)) return upgraded + suffix;
        }
        if (url.contains("i.ytimg.com/vi/") || url.contains("img.youtube.com/vi/")) {
            String upgraded = url.replaceFirst("/(?:default|mqdefault|hqdefault|sddefault)(\\.jpg|\\.webp)(\\?.*)?$", "/maxresdefault$1$2");
            if (!upgraded.equals(url)) return upgraded;
        }
        if (url.contains("image.tmdb.org/t/p/")) {
            String upgraded = url.replaceFirst("/t/p/w\\d+/", "/t/p/w1280/");
            if (!upgraded.equals(url)) return upgraded;
        }
        return url;
    }

    static void load(Context context, ContinueWatchingRepository.Item item, Consumer<Bitmap> ready) {
        Bitmap saved = cached(item);
        if (saved != null) { ready.accept(saved); return; }
        if (item.artwork.isEmpty()) { ready.accept(null); return; }
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            Bitmap bitmap = cached(item);
            if (bitmap == null) {
                byte[] bytes = fetchBytes(app, item.pkg, item.artwork);
                if (bytes != null) bitmap = decode(bytes, THUMB_MAX_DIM, Bitmap.Config.RGB_565);
            }
            if (bitmap != null) CACHE.put(key(item), bitmap);
            Bitmap result = bitmap; new Handler(Looper.getMainLooper()).post(() -> ready.accept(result));
        });
    }

    static void loadFull(Context context, ContinueWatchingRepository.Item item, Consumer<Bitmap> ready) {
        Bitmap saved = cachedFull(item);
        if (saved != null) { ready.accept(saved); return; }
        if (item.artwork.isEmpty()) { ready.accept(null); return; }
        Context app = context.getApplicationContext();
        int maxDim = maxBackdropDim(app);
        WORKER.execute(() -> {
            Bitmap bitmap = cachedFull(item);
            if (bitmap == null) {
                String preferred = highResUrl(item.artwork);
                byte[] bytes = !preferred.equals(item.artwork) ? fetchBytes(app, item.pkg, preferred) : null;
                if (bytes != null) bitmap = decode(bytes, maxDim, Bitmap.Config.ARGB_8888);
                if (bitmap == null) {
                    bytes = fetchBytes(app, item.pkg, item.artwork);
                    if (bytes != null) bitmap = decode(bytes, maxDim, Bitmap.Config.ARGB_8888);
                }
                if (bitmap != null && bytes != null && cached(item) == null) {
                    Bitmap thumb = decode(bytes, THUMB_MAX_DIM, Bitmap.Config.RGB_565);
                    if (thumb != null) CACHE.put(key(item), thumb);
                }
            }
            if (bitmap != null) {
                FULL_CACHE.put(key(item), bitmap);
                if (cachedBlur(item) == null) {
                    android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
                    int bw = 120;
                    int bh = Math.max(1, bw * dm.heightPixels / Math.max(1, dm.widthPixels));
                    Bitmap blurred = FolderBackdrop.fromBitmapCenterCrop(bitmap, bw, bh, 4);
                    if (blurred != null) {
                        blurred.prepareToDraw();
                        BLUR_CACHE.put(key(item), blurred);
                    }
                }
            }
            Bitmap result = bitmap; new Handler(Looper.getMainLooper()).post(() -> ready.accept(result));
        });
    }

    private static int maxBackdropDim(Context context) {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenMax = Math.max(dm.widthPixels, dm.heightPixels);
        return Math.max(1280, Math.min(FULL_MAX_DIM, screenMax));
    }

    private static byte[] fetchBytes(Context context, String pkg, String artworkUri) {
        HttpURLConnection connection = null;
        try {
            Uri uri = Uri.parse(artworkUri); String scheme = uri.getScheme();
            InputStream input;
            if ("content".equals(scheme) || "android.resource".equals(scheme)) {
                if ("content".equals(scheme)) {
                    android.content.pm.ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
                    if (provider == null || !pkg.equals(provider.packageName)) return null;
                } else if (!pkg.equals(uri.getAuthority())) return null;
                input = context.getContentResolver().openInputStream(uri);
            } else if ("https".equals(scheme)) {
                URL url = new URL(artworkUri);
                for (int redirect = 0; ; redirect++) {
                    if (!url.getProtocol().equals("https") || url.getUserInfo() != null) return null;
                    connection = (HttpURLConnection) url.openConnection(); connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(4000); connection.setReadTimeout(4000);
                    connection.setRequestProperty("User-Agent", "CleanYourTV");
                    int response = connection.getResponseCode();
                    if (response == 200) break;
                    if (response < 300 || response > 399 || redirect >= 2) return null;
                    String location = connection.getHeaderField("Location"); if (location == null) return null;
                    URL next = new URL(url, location); connection.disconnect(); connection = null; url = next;
                }
                if (connection.getContentLengthLong() > MAX_BYTES) return null;
                input = connection.getInputStream();
            } else return null;
            try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) return null;
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > MAX_BYTES) return null;
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } catch (Exception | OutOfMemoryError ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private static Bitmap decode(byte[] bytes, int maxDim, Bitmap.Config config) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || (long) options.outWidth * options.outHeight > 40_000_000) return null;
            options.inSampleSize = computeSampleSize(options.outWidth, options.outHeight, maxDim);
            options.inJustDecodeBounds = false; options.inPreferredConfig = config;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception | OutOfMemoryError ignored) { return null; }
    }
}
