package com.gathin.tvhome;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class FolderNamerTest {
    private static final int UNDEF = ApplicationInfo.CATEGORY_UNDEFINED;

    @Test
    public void classifiesTvVideoApps() {
        assertEquals(R.string.folder_cat_video, FolderNamer.classify("com.teamsmart.videomanager.tv", "SmartTube", UNDEF, false));
        assertEquals(R.string.folder_cat_video, FolderNamer.classify("ru.kinopoisk", "Кинопоиск", UNDEF, false));
        assertEquals(R.string.folder_cat_video, FolderNamer.classify("top.rootu.lampa", "Lampa", UNDEF, false));
        assertEquals(R.string.folder_cat_video, FolderNamer.classify("com.netflix.ninja", "Netflix", UNDEF, false));
        assertEquals(R.string.folder_cat_video, FolderNamer.classify("ar.tvplayer.tv", "TiviMate", UNDEF, false));
        assertEquals(R.string.folder_cat_video, FolderNamer.classify("com.vk.vkvideo", "VK Видео", UNDEF, false));
    }

    @Test
    public void classifiesMusicAndDistinguishesYouTubeMusic() {
        assertEquals(R.string.folder_cat_music, FolderNamer.classify("com.spotify.tv.android", "Spotify", UNDEF, false));
        assertEquals(R.string.folder_cat_music, FolderNamer.classify("com.google.android.apps.youtube.music", "YouTube Music", UNDEF, false));
        assertEquals(R.string.folder_cat_music, FolderNamer.classify("ru.yandex.music", "Яндекс Музыка", UNDEF, false));
    }

    @Test
    public void classifiesGamesVpnBrowsersUtilitiesStores() {
        assertEquals(R.string.folder_cat_games, FolderNamer.classify("com.valvesoftware.steamlink", "Steam Link", UNDEF, false));
        assertEquals(R.string.folder_cat_games, FolderNamer.classify("com.unknown.arcade", "Arcade", UNDEF, true));
        assertEquals(R.string.folder_cat_vpn, FolderNamer.classify("org.amnezia.awg", "AmneziaWG", UNDEF, false));
        assertEquals(R.string.folder_cat_vpn, FolderNamer.classify("com.wireguard.android", "WireGuard", UNDEF, false));
        assertEquals(R.string.folder_cat_browsers, FolderNamer.classify("com.phlox.tvwebbrowser", "TV Bro", UNDEF, false));
        assertEquals(R.string.folder_cat_browsers, FolderNamer.classify("com.tcl.browser", "BrowseHere", UNDEF, false));
        assertEquals(R.string.folder_cat_utilities, FolderNamer.classify("com.lonelycatgames.Xplore", "X-plore", UNDEF, false));
        assertEquals(R.string.folder_cat_utilities, FolderNamer.classify("com.esaba.downloader", "Downloader", UNDEF, false));
        assertEquals(R.string.folder_cat_stores, FolderNamer.classify("com.android.vending", "Google Play Store", UNDEF, false));
        assertEquals(R.string.folder_cat_stores, FolderNamer.classify("ru.vk.store", "RuStore", UNDEF, false));
    }

    @Test
    public void classifiesSportsSocialPhotosSmartHomeAndAndroidCategories() {
        assertEquals(R.string.folder_cat_news, FolderNamer.classify("ru.okkosport", "Okko Спорт", UNDEF, false));
        assertEquals(R.string.folder_cat_social, FolderNamer.classify("org.telegram.messenger", "Telegram", UNDEF, false));
        assertEquals(R.string.folder_cat_photos, FolderNamer.classify("com.google.android.apps.photos", "Photos", UNDEF, false));
        assertEquals(R.string.folder_cat_smart_home, FolderNamer.classify("io.homeassistant.companion.android", "Home Assistant", UNDEF, false));
        assertEquals(R.string.folder_cat_video, FolderNamer.classify("com.example.custom", "Custom", ApplicationInfo.CATEGORY_VIDEO, false));
        assertEquals(R.string.folder_cat_music, FolderNamer.classify("com.example.custom", "Custom", ApplicationInfo.CATEGORY_AUDIO, false));
        assertEquals(R.string.folder_cat_maps, FolderNamer.classify("com.example.custom", "Custom", ApplicationInfo.CATEGORY_MAPS, false));
        assertEquals(R.string.folder_default_name, FolderNamer.classify("com.example.unknown", "Something", UNDEF, false));
    }
}
