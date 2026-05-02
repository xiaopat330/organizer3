package com.organizer3.avatars;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CustomAvatarStoreTest {

    @TempDir Path tempDir;

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Creates a plain square JPEG of the given size. */
    private byte[] squareJpeg(int size) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", buf);
        return buf.toByteArray();
    }

    /** Creates a non-square JPEG. */
    private byte[] rectJpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", buf);
        return buf.toByteArray();
    }

    /** Creates a plain square PNG of the given size. */
    private byte[] squarePng(int size) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "png", buf);
        return buf.toByteArray();
    }

    // ── save — happy path ────────────────────────────────────────────────────

    @Test
    void save_writes150x150_jpeg() throws Exception {
        byte[] jpeg = squareJpeg(150);
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        String rel = store.save(42L, jpeg);

        assertEquals("actress-custom-avatars/42.jpg", rel);
        assertTrue(Files.exists(tempDir.resolve(rel)));
    }

    @Test
    void save_acceptsPng_reEncodesToJpeg() throws Exception {
        byte[] png = squarePng(200);
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        String rel = store.save(7L, png);

        assertEquals("actress-custom-avatars/7.jpg", rel);
        byte[] stored = Files.readAllBytes(tempDir.resolve(rel));
        // JPEG magic bytes: FF D8
        assertEquals((byte) 0xFF, stored[0]);
        assertEquals((byte) 0xD8, stored[1]);
    }

    @Test
    void save_downscalesAbove600() throws Exception {
        byte[] jpeg = squareJpeg(800);
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        String rel = store.save(1L, jpeg);

        byte[] stored = Files.readAllBytes(tempDir.resolve(rel));
        BufferedImage result = ImageIO.read(new java.io.ByteArrayInputStream(stored));
        assertEquals(600, result.getWidth());
        assertEquals(600, result.getHeight());
    }

    @Test
    void save_doesNotUpscaleBelow600() throws Exception {
        byte[] jpeg = squareJpeg(300);
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        String rel = store.save(2L, jpeg);

        byte[] stored = Files.readAllBytes(tempDir.resolve(rel));
        BufferedImage result = ImageIO.read(new java.io.ByteArrayInputStream(stored));
        assertEquals(300, result.getWidth());
        assertEquals(300, result.getHeight());
    }

    @Test
    void save_atomicWrite_noTmpFileLeftBehind() throws Exception {
        byte[] jpeg = squareJpeg(200);
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        store.save(5L, jpeg);

        Path dir = tempDir.resolve("actress-custom-avatars");
        long tmpCount = Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        assertEquals(0, tmpCount);
    }

    @Test
    void save_overwritesExistingFile() throws Exception {
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        store.save(3L, squareJpeg(200));
        long firstSize = Files.size(tempDir.resolve("actress-custom-avatars/3.jpg"));

        store.save(3L, squareJpeg(300));
        long secondSize = Files.size(tempDir.resolve("actress-custom-avatars/3.jpg"));

        // Different source sizes → different output sizes (not guaranteed identical)
        assertTrue(Files.exists(tempDir.resolve("actress-custom-avatars/3.jpg")));
        // Verify re-written (sizes differ for different input)
        assertNotEquals(firstSize, secondSize);
    }

    // ── save — rejection ─────────────────────────────────────────────────────

    @Test
    void save_rejectsNonSquare() throws Exception {
        byte[] rect = rectJpeg(400, 300);
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        assertThrows(IllegalArgumentException.class, () -> store.save(10L, rect));
    }

    @Test
    void save_rejectsTooSmall() throws Exception {
        byte[] tiny = squareJpeg(100);
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        assertThrows(IllegalArgumentException.class, () -> store.save(10L, tiny));
    }

    @Test
    void save_rejectsEmptyBytes() {
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        assertThrows(IllegalArgumentException.class, () -> store.save(10L, new byte[0]));
    }

    @Test
    void save_rejectsUnreadableBytes() {
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        assertThrows(IllegalArgumentException.class, () -> store.save(10L, new byte[]{1, 2, 3}));
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_returnsTrueWhenFileExists() throws Exception {
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        store.save(20L, squareJpeg(150));

        boolean deleted = store.delete(20L);

        assertTrue(deleted);
        assertFalse(Files.exists(tempDir.resolve("actress-custom-avatars/20.jpg")));
    }

    @Test
    void delete_idempotent_returnsFalseWhenMissing() {
        CustomAvatarStore store = new CustomAvatarStore(tempDir);
        assertFalse(store.delete(999L));
        assertFalse(store.delete(999L));
    }

    // ── encodeJpeg ────────────────────────────────────────────────────────────

    @Test
    void encodeJpeg_producesValidJpeg() throws Exception {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        byte[] jpeg = CustomAvatarStore.encodeJpeg(img, 0.88f);

        assertNotNull(jpeg);
        assertTrue(jpeg.length > 0);
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
    }

    @Test
    void encodeJpeg_handlesArgbInput() throws Exception {
        BufferedImage argb = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        byte[] jpeg = CustomAvatarStore.encodeJpeg(argb, 0.88f);
        assertNotNull(jpeg);
        assertTrue(jpeg.length > 0);
    }
}
