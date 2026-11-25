package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ContinueWatchingArtworkTest {
    @Test
    public void fullScreenBackdropKeepsFullHdUnscaled() {
        assertEquals(1, ContinueWatchingArtwork.computeSampleSize(1920, 1080, 1920));
        assertEquals(1, ContinueWatchingArtwork.computeSampleSize(1280, 720, 1920));
    }

    @Test
    public void fullScreenBackdropDownsamplesFourKToFullHd() {
        assertEquals(2, ContinueWatchingArtwork.computeSampleSize(3840, 2160, 1920));
        assertEquals(2, ContinueWatchingArtwork.computeSampleSize(2560, 1440, 1920));
    }

    @Test
    public void thumbnailDownsamplesToCardBudget() {
        assertEquals(4, ContinueWatchingArtwork.computeSampleSize(1920, 1080, 512));
        assertEquals(1, ContinueWatchingArtwork.computeSampleSize(480, 270, 512));
    }

    @Test
    public void invalidDimensionsReturnSafeDefault() {
        assertEquals(1, ContinueWatchingArtwork.computeSampleSize(0, 1080, 1920));
        assertEquals(1, ContinueWatchingArtwork.computeSampleSize(-10, -10, 512));
    }

    @Test
    public void highResUrlUpgradesKinopoiskAndYandexCdn() {
        assertEquals(
                "https://cdnrrviubnyuntopbez5.svc.cdn.yandex.net/get-ott/2385704/2a0000019ead263d57c93257c876321e4d62/1920x1080",
                ContinueWatchingArtwork.highResUrl(
                        "https://cdnrrviubnyuntopbez5.svc.cdn.yandex.net/get-ott/2385704/2a0000019ead263d57c93257c876321e4d62/460x690"));
        assertEquals(
                "https://avatars.mds.yandex.net/get-kinopoisk-image/123/abc/1920x1080",
                ContinueWatchingArtwork.highResUrl(
                        "https://avatars.mds.yandex.net/get-kinopoisk-image/123/abc/x300"));
    }

    @Test
    public void highResUrlUpgradesYoutubeAndTmdbAndLeavesOthersUntouched() {
        assertEquals(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
                ContinueWatchingArtwork.highResUrl("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"));
        assertEquals(
                "https://image.tmdb.org/t/p/w1280/poster.jpg",
                ContinueWatchingArtwork.highResUrl("https://image.tmdb.org/t/p/w500/poster.jpg"));
        assertEquals(
                "content://com.example/poster/1",
                ContinueWatchingArtwork.highResUrl("content://com.example/poster/1"));
    }
}
