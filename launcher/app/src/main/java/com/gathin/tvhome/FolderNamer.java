package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Locale;

/** Suggests a localized folder name automatically from an app's category, package, or title. */
final class FolderNamer {
    private FolderNamer() { }

    static String suggest(Context context, String packageName, String label) {
        int category = ApplicationInfo.CATEGORY_UNDEFINED;
        boolean isGame = false;
        if (context != null && packageName != null && !packageName.isEmpty()) {
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
                category = info.category;
                @SuppressWarnings("deprecation")
                boolean legacyGame = (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
                isGame = legacyGame || category == ApplicationInfo.CATEGORY_GAME;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }
        int resId = classify(packageName, label, category, isGame);
        return context.getString(resId);
    }

    static int classify(String packageName, String label, int appCategory, boolean isGame) {
        String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        String name = label == null ? "" : label.toLowerCase(Locale.ROOT);

        // 1. Explicit TV-oriented package/keyword classification for categories Android often leaves UNDEFINED.
        if (matchesAny(pkg, name,
                "vpn", "wireguard", "amnezia", "openvpn", "outline", "v2ray", "xray", "hiddify",
                "nekobox", "singbox", "sing-box", "clash", "tailscale", "zerotier", "adguard",
                "byedpi", "goodbyedpi", "blokada", "protonvpn", "surfshark", "nordvpn", "shadowsocks",
                "proxy", "tunnel", "tun2socks", "dnscrypt")) {
            return R.string.folder_cat_vpn;
        }
        if (matchesAny(pkg, name,
                "com.android.vending", "aptoide", "rustore", "aurora.store", "fdroid", "f-droid",
                "neostore", "appgallery", "play store", "play маркет", "магазин")) {
            return R.string.folder_cat_stores;
        }
        if (matchesAny(pkg, name,
                "browser", "chrome", "firefox", "opera", "brave", "puffin", "tvbro", "tvwebbrowser",
                "browsehere", "openbrowser", "edge", "браузер")) {
            return R.string.folder_cat_browsers;
        }
        if (matchesAny(pkg, name,
                "homeassistant", "smarthome", "mihome", "frigate", "tinycam", "ipcam",
                "google.android.apps.chromecast.app", "умный дом")) {
            return R.string.folder_cat_smart_home;
        }
        if (isGame || appCategory == ApplicationInfo.CATEGORY_GAME || matchesAny(pkg, name,
                "play.games", "steamlink", "valvesoftware.steam", "limelight", "moonlight",
                "geforcenow", "xbox", "retroarch", "ppsspp", "dolphinemu", "duckstation",
                "aethersx2", "remoteplay", "roblox", "minecraft", "emulator", "игр")) {
            return R.string.folder_cat_games;
        }
        if (matchesAny(pkg, name,
                "music", "spotify", "soundcloud", "deezer", "tidal", "tunein", "shazam",
                "zvuk", "aimp", "poweramp", "foobar", "radio", "podcast", "audiobook",
                "музык", "радио", "звук")) {
            return R.string.folder_cat_music;
        }
        if (matchesAny(pkg, name,
                "matchtv", "espn", "setanta", "eurosport", "okkosport", "sport", "news",
                "спорт", "новост")) {
            return R.string.folder_cat_news;
        }
        if (matchesAny(pkg, name,
                "youtube", "smarttube", "videomanager.tv", "kinopoisk", "ru.ivi", "okko", "ru.more.play",
                "ru.rt.video", "wink", "mtstv", "kion", "tnt_online", "ru.start", "megogo", "netflix",
                "disney", "amazonvideo", "primevideo", "hbo", "wbd.stream", "apple.atve", "plex",
                "jellyfin", "emby", "xbmc.kodi", "kodi", "videolan.vlc", "courville.nova", "mxtech",
                "gtvbox.videoplayer", "vimu", "lampa", "lazymedia", "hdrezka", "rezka", "torrserve",
                "tivimate", "ottnavigator", "ottplay", "televizo", "rutube", "vkvideo", "twitch",
                "crunchyroll", "stremio", "iptv", "smotreshka", "tricolor", "videoplayer", "cinema",
                "кино", "фильм", "видео", "сериал", "телевид", "плейер", "плеер")) {
            return R.string.folder_cat_video;
        }
        if (matchesAny(pkg, name,
                "telegram", "whatsapp", "discord", "viber", "skype", "zoom", "vkontakte",
                "instagram", "tiktok", "messenger", "facebook", "twitter", "threads",
                "соцсет", "чат")) {
            return R.string.folder_cat_social;
        }
        if (matchesAny(pkg, name,
                "photos", "gallery", "photoprints", "photodream", "screensaver",
                "фото", "галере")) {
            return R.string.folder_cat_photos;
        }
        if (matchesAny(pkg, name,
                "file", "explorer", "commander", "xplore", "mixplorer", "anexplorer", "sendfiles",
                "downloader", "speedtest", "aida64", "cpuz", "buttonmapper", "tvquickactions",
                "cleaner", "boost", "tcl.guard", "termux", "adb", "apk", "installer", "backup",
                "settings", "calculator", "clock", "calendar", "проводник", "менеджер", "утилит",
                "инструмент", "очистк", "настройк")) {
            return R.string.folder_cat_utilities;
        }

        // 2. Fallback to Android's declared ApplicationInfo.category (API 26+).
        switch (appCategory) {
            case ApplicationInfo.CATEGORY_VIDEO: return R.string.folder_cat_video;
            case ApplicationInfo.CATEGORY_AUDIO: return R.string.folder_cat_music;
            case ApplicationInfo.CATEGORY_GAME: return R.string.folder_cat_games;
            case ApplicationInfo.CATEGORY_IMAGE: return R.string.folder_cat_photos;
            case ApplicationInfo.CATEGORY_SOCIAL: return R.string.folder_cat_social;
            case ApplicationInfo.CATEGORY_NEWS: return R.string.folder_cat_news;
            case ApplicationInfo.CATEGORY_MAPS: return R.string.folder_cat_maps;
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:
            case ApplicationInfo.CATEGORY_ACCESSIBILITY:
                return R.string.folder_cat_utilities;
            default:
                return R.string.folder_default_name;
        }
    }

    private static boolean matchesAny(String pkg, String name, String... tokens) {
        for (String token : tokens) {
            if (pkg.contains(token) || name.contains(token)) return true;
        }
        return false;
    }
}
