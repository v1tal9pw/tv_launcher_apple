package com.gathin.tvhome;

import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PersonalWallpapersTest {
    @Test public void invalidNamesCannotEscapePrivateStorage() {
        for (String value : new String[]{"personal:../../video.mp4", "personal:/video.mp4", "personal:bad.mp4", null}) {
            assertNull(PersonalWallpapers.file(null, value));
            assertFalse(PersonalWallpapers.isUserImported(value));
            assertFalse(PersonalWallpapers.delete(null, value));
        }
        assertFalse(PersonalWallpapers.isUserImported(PersonalWallpapers.COAST_VIDEO));
        assertFalse(PersonalWallpapers.delete(null, PersonalWallpapers.COAST_VIDEO));
        assertTrue(PersonalWallpapers.isUserImported("personal:123e4567-e89b-12d3-a456-426614174000.jpg"));
        assertTrue(PersonalWallpapers.isUserImported("personal:123e4567-e89b-12d3-a456-426614174000.mp4"));
        assertTrue(PersonalWallpapers.video(PersonalWallpapers.COAST_VIDEO));
        assertFalse(PersonalWallpapers.video("personal:image.jpg"));
    }

    @Test public void resolvesStoragePathsFromFileAndDocumentUris() {
        assertEquals("/storage/emulated/0/Download/my photo+1.jpg",
                PersonalWallpapers.resolveStoragePath(
                        "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fmy%20photo+1.jpg"));
        assertEquals("/storage/1A2B-3C4D/Movies/clip.mp4",
                PersonalWallpapers.resolveStoragePath(
                        "content://com.android.externalstorage.documents/document/1A2B-3C4D%3AMovies%2Fclip.mp4"));
        assertEquals("/storage/emulated/0/Pictures/Обои 4K.jpg",
                PersonalWallpapers.resolveStoragePath(
                        "file:///storage/emulated/0/Pictures/%D0%9E%D0%B1%D0%BE%D0%B8%204K.jpg"));
        assertNull(PersonalWallpapers.resolveStoragePath(
                "content://com.android.externalstorage.documents/document/primary%3A..%2Fsecret.jpg"));
        assertNull(PersonalWallpapers.resolveStoragePath(
                "content://com.android.providers.media.documents/document/image%3A42"));
    }

    @Test public void cleansDisplayNamesSafely() {
        assertEquals("sunset.jpg", PersonalWallpapers.cleanDisplayName("  /path/to/sunset.jpg  "));
        assertEquals("my clip.mp4", PersonalWallpapers.cleanDisplayName("C:\\Users\\tv\\my   clip.mp4"));
        assertNull(PersonalWallpapers.cleanDisplayName("   "));
        assertNull(PersonalWallpapers.cleanDisplayName(null));
    }

    @Test public void localFileExistsDetectsDeletedAndEmptyFiles() throws Exception {
        File dir = Files.createTempDirectory("personal-wallpapers-test").toFile();
        File file = new File(dir, "wall.jpg");
        try {
            assertFalse(PersonalWallpapers.localFileExists(file));
            assertTrue(file.createNewFile());
            assertFalse(PersonalWallpapers.localFileExists(file));
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(new byte[]{1, 2, 3, 4});
            }
            assertTrue(PersonalWallpapers.localFileExists(file));
            assertTrue(file.delete());
            assertFalse(PersonalWallpapers.localFileExists(file));
        } finally {
            file.delete();
            dir.delete();
        }
    }

    @Test public void videoLimitsAcceptPortraitAndLandscapeWithoutOversizedFrames() {
        assertTrue(PersonalWallpapers.acceptedVideo(1920, 1080, "29.97"));
        assertTrue(PersonalWallpapers.acceptedVideo(720, 1280, "24"));
        assertTrue(PersonalWallpapers.acceptedVideo(1280, 720, null));
        assertFalse(PersonalWallpapers.acceptedVideo(3840, 2160, "24"));
        assertFalse(PersonalWallpapers.acceptedVideo(1920, 1920, "24"));
        assertFalse(PersonalWallpapers.acceptedVideo(1920, 1080, "60"));
        assertFalse(PersonalWallpapers.acceptedVideo(0, 1080, "24"));
        assertFalse(PersonalWallpapers.acceptedVideo(1920, 1080, "NaN"));
        assertFalse(PersonalWallpapers.acceptedVideo(1920, 1080, "bad"));
    }

    @Test public void personalCollectionAndUserPhotoHelpersDistinguishPhotosVideosAndCollection() {
        String photo = "personal:123e4567-e89b-12d3-a456-426614174000.jpg";
        String video = "personal:123e4567-e89b-12d3-a456-426614174000.mp4";
        assertTrue(PersonalWallpapers.isCollection(PersonalWallpapers.COLLECTION));
        assertFalse(PersonalWallpapers.isCollection(photo));
        assertFalse(PersonalWallpapers.isUserImported(PersonalWallpapers.COLLECTION));
        assertFalse(PersonalWallpapers.isUserPhoto(PersonalWallpapers.COLLECTION));
        assertFalse(PersonalWallpapers.video(PersonalWallpapers.COLLECTION));
        assertFalse(PersonalWallpapers.delete(null, PersonalWallpapers.COLLECTION));

        assertTrue(PersonalWallpapers.isUserPhoto(photo));
        assertFalse(PersonalWallpapers.isUserPhoto(video));
        assertFalse(PersonalWallpapers.isUserPhoto(PersonalWallpapers.COAST_VIDEO));
        assertFalse(PersonalWallpapers.isUserPhoto(null));
    }

    @Test public void listPhotosInFiltersAndOrdersUserPhotosForRotation() throws Exception {
        File dir = Files.createTempDirectory("personal-photos-rotation").toFile();
        File photoA = new File(dir, "11111111-1111-1111-1111-111111111111.jpg");
        File photoB = new File(dir, "22222222-2222-2222-2222-222222222222.jpg");
        File video = new File(dir, "33333333-3333-3333-3333-333333333333.mp4");
        File poster = new File(dir, "33333333-3333-3333-3333-333333333333.mp4.poster.jpg");
        File empty = new File(dir, "44444444-4444-4444-4444-444444444444.jpg");
        File stray = new File(dir, "not-a-uuid.jpg");
        try {
            for (File f : new File[]{photoA, photoB, video, poster, stray}) {
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(new byte[]{1, 2, 3});
                }
            }
            assertTrue(empty.createNewFile());
            assertTrue(photoB.setLastModified(1_000_000L));
            assertTrue(photoA.setLastModified(2_000_000L));

            java.util.List<File> photos = PersonalWallpapers.listPhotosIn(dir);
            assertEquals(2, photos.size());
            assertEquals(photoB.getName(), photos.get(0).getName());
            assertEquals(photoA.getName(), photos.get(1).getName());

            // Verify WallpaperRotation rotates smoothly across the personal photo list.
            assertEquals(photoA, WallpaperRotation.next(photos, photoB.getName()));
            assertEquals(photoB, WallpaperRotation.next(photos, photoA.getName()));
        } finally {
            for (File f : new File[]{photoA, photoB, video, poster, empty, stray}) f.delete();
            dir.delete();
        }
    }
}
