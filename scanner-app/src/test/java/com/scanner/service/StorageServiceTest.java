package com.scanner.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StorageServiceTest {
    @TempDir Path temp;
    StorageService service() throws IOException {
        Files.createDirectories(temp.resolve("images"));
        Files.createDirectories(temp.resolve("reports"));
        return new StorageService(true, false, false,
            temp.resolve("images").toString(), temp.resolve("reports").toString(),
            temp.resolve("captures").toString());
    }

    @Test void browseKeepsEachPurposeInsideItsRoot() throws Exception {
        var storage = service();
        var folder = Files.createDirectory(temp.resolve("images/Mặt trước PO01"));
        assertEquals(folder.toString(), storage.browse(folder.toString(), "images").get("current"));
        assertEquals("", storage.browse(temp.resolve("images").toString(), "images").get("parent"));
        assertThrows(AccessDeniedException.class, () -> storage.browse(temp.resolve("reports").toString(), "images"));
        assertThrows(AccessDeniedException.class, () -> storage.saveDirectory(folder.toString()));
        assertThrows(AccessDeniedException.class, () -> storage.browse(temp.resolve("images/../reports").toString(), "images"));
        assertThrows(IllegalArgumentException.class, () -> storage.browse("", "unknown"));
    }

    @Test void onlyRegularImageFilesCanBeRead() throws Exception {
        var storage = service();
        var img = Files.write(temp.resolve("images/a.jpg"), new byte[]{1});
        var secret = Files.writeString(temp.resolve("images/password.txt"), "secret");
        Files.createDirectory(temp.resolve("images/folder.jpg"));
        assertEquals(java.util.List.of(img), storage.images(temp.resolve("images").toString()));
        assertThrows(AccessDeniedException.class, () -> storage.image(secret.toString()));
        assertThrows(AccessDeniedException.class, () -> storage.image(temp.resolve("reports/a.jpg").toString()));
    }

    @Test void shareRootLabelCanBeListedWithoutConnectingToNas() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var storage = new StorageService(true, false, false,
            "\\\\192.168.1.8\\daiduongtelecom", "\\\\192.168.1.8\\filebaocao",
            temp.resolve("captures").toString());
        var result = storage.browse("", "reports");
        assertTrue(result.get("entries").toString().contains("name=filebaocao"));
    }

    @Test void configuredRootCanBeSelectedEvenWhenItHasNoChildren() throws Exception {
        var storage = service();
        var result = storage.browse(temp.resolve("reports").toString(), "reports");
        assertEquals(temp.resolve("reports").toString(), result.get("current"));
        assertEquals("", result.get("parent"));
        assertEquals(java.util.List.of(), result.get("entries"));
    }

    @Test void selectingMissingFolderDoesNotCreateItInNasMode() throws Exception {
        var storage = service();
        var missing = temp.resolve("reports/missing");
        assertThrows(IOException.class, () -> storage.saveDirectory(missing.toString()));
        assertFalse(Files.exists(missing));
    }

    @Test void reportsCanBeSavedOutsideNasWhenEnabled() throws Exception {
        Path anywhere = Files.createDirectory(temp.resolve("anywhere"));
        var storage = new StorageService(true, true, false,
            temp.resolve("images").toString(), temp.resolve("reports").toString(),
            temp.resolve("captures").toString());
        assertEquals(anywhere, storage.saveDirectory(anywhere.toString()));
        Path destination = storage.destination(anywhere.toString(), "TrcSC_123.docx");
        storage.saveNew(destination, new byte[]{1, 2});
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(destination));
        assertThrows(AccessDeniedException.class,
            () -> storage.image(anywhere.resolve("TrcSC_123.docx").toString()));
    }

    @Test void imagesCanBeReadOutsideNasWhenEnabled() throws Exception {
        Path anywhere = Files.createDirectory(temp.resolve("external-images"));
        Path image = Files.write(anywhere.resolve("chosen.jpg"), new byte[]{1});
        Path nonImage = Files.writeString(anywhere.resolve("notes.txt"), "not an image");
        var storage = new StorageService(true, false, true,
            temp.resolve("images").toString(), temp.resolve("reports").toString(),
            temp.resolve("captures").toString());

        assertEquals(java.util.List.of(image), storage.images(anywhere.toString()));
        assertEquals(image, storage.image(image.toString()));
        assertThrows(AccessDeniedException.class, () -> storage.image(nonImage.toString()));
    }

    @Test void unrestrictedStorageCanUseAnyReadableOrWritableFolder() throws Exception {
        Path anywhere = Files.createDirectory(temp.resolve("anywhere-on-machine"));
        var storage = new StorageService(false, false, false, "", "",
            temp.resolve("captures").toString());

        assertEquals(anywhere, storage.saveDirectory(anywhere.toString()));
        assertEquals(anywhere.toString(), storage.browse(anywhere.toString(), "reports").get("current"));
        Path image = Files.write(anywhere.resolve("scan.jpg"), new byte[]{1});
        assertEquals(image, storage.image(image.toString()));
    }

    @Test void capturedImagesAreSavedWithoutOverwrite() throws Exception {
        var storage = service();
        Path first = storage.saveCapture(2, "SN 123/45", new byte[]{1}, "jpg");
        Path second = storage.saveCapture(2, "SN 123/45", new byte[]{2}, "jpg");
        assertNotEquals(first, second);
        assertTrue(Files.exists(first) && Files.exists(second));
        assertTrue(first.getFileName().toString().contains("slot2"));
        assertTrue(first.getFileName().toString().contains("SN_123_45"));
        assertThrows(IllegalArgumentException.class, () -> storage.saveCapture(1, "", new byte[]{1}, "txt"));
    }

    @Test void noOverwriteEvenWhenTwoRequestsSaveTogether() throws Exception {
        var storage = service();
        Path dest = storage.destination(temp.resolve("reports").toString(), "TrcSC_123.docx");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> save = () -> {
                start.await();
                try { storage.saveNew(dest, new byte[]{1, 2, 3}); return true; }
                catch (FileAlreadyExistsException expected) { return false; }
            };
            var a = pool.submit(save);
            var b = pool.submit(save);
            start.countDown();
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        }
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(dest));
        assertThrows(FileAlreadyExistsException.class, () -> storage.saveNew(dest, new byte[]{9}));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(dest));
        assertThrows(IllegalArgumentException.class,
            () -> storage.destination(temp.resolve("reports").toString(), "../outside.docx"));
    }

    @Test void symlinkCannotEscapeAllowedRoot() throws Exception {
        var storage = service();
        var outside = Files.createDirectory(temp.resolve("outside"));
        var link = temp.resolve("images/link");
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Máy kiểm thử không cho tạo symbolic link: " + e.getMessage());
        }
        assertThrows(AccessDeniedException.class, () -> storage.directory(link.toString(), "images"));
    }
}
