package com.organizer3.javdb.draft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DraftCoverScratchStoreTest {

    @TempDir
    Path dataDir;

    private DraftCoverScratchStore store() {
        return new DraftCoverScratchStore(dataDir);
    }

    // ── write + read ───────────────────────────────────────────────────────────

    @Test
    void write_createsDirectoryAndFile() throws Exception {
        DraftCoverScratchStore s = store();
        s.write(1L, new byte[]{1, 2, 3});

        Path scratchDir = dataDir.resolve("_sandbox").resolve("draft_covers");
        assertTrue(Files.isDirectory(scratchDir), "scratch dir must be created");
        assertTrue(Files.exists(scratchDir.resolve("1.jpg")), "cover file must exist");
    }

    @Test
    void read_returnsWrittenBytes() throws Exception {
        DraftCoverScratchStore s = store();
        byte[] expected = {10, 20, 30, 40};
        s.write(42L, expected);

        Optional<byte[]> result = s.read(42L);
        assertTrue(result.isPresent());
        assertArrayEquals(expected, result.get());
    }

    @Test
    void read_emptyWhenFileAbsent() throws Exception {
        Optional<byte[]> result = store().read(999L);
        assertTrue(result.isEmpty());
    }

    // ── overwrite is idempotent ────────────────────────────────────────────────

    @Test
    void write_overwritesExistingFile() throws Exception {
        DraftCoverScratchStore s = store();
        s.write(7L, new byte[]{1});
        s.write(7L, new byte[]{2, 3});

        Optional<byte[]> result = s.read(7L);
        assertTrue(result.isPresent());
        assertArrayEquals(new byte[]{2, 3}, result.get());
    }

    // ── exists ─────────────────────────────────────────────────────────────────

    @Test
    void exists_falseBeforeWrite() {
        assertFalse(store().exists(1L));
    }

    @Test
    void exists_trueAfterWrite() throws Exception {
        DraftCoverScratchStore s = store();
        s.write(5L, new byte[]{0});
        assertTrue(s.exists(5L));
    }

    @Test
    void exists_falseAfterDelete() throws Exception {
        DraftCoverScratchStore s = store();
        s.write(5L, new byte[]{0});
        s.delete(5L);
        assertFalse(s.exists(5L));
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Test
    void delete_isNoOpWhenAbsent() {
        assertDoesNotThrow(() -> store().delete(999L));
    }

    @Test
    void delete_removesFile() throws Exception {
        DraftCoverScratchStore s = store();
        s.write(3L, new byte[]{7});
        s.delete(3L);
        assertTrue(s.read(3L).isEmpty());
    }

    // ── openStream ─────────────────────────────────────────────────────────────

    @Test
    void openStream_returnsDataMatchingWrite() throws Exception {
        DraftCoverScratchStore s = store();
        byte[] payload = {99, 88, 77};
        s.write(11L, payload);

        Optional<InputStream> maybeStream = s.openStream(11L);
        assertTrue(maybeStream.isPresent());
        try (InputStream is = maybeStream.get()) {
            assertArrayEquals(payload, is.readAllBytes());
        }
    }

    @Test
    void openStream_emptyWhenAbsent() throws Exception {
        Optional<InputStream> result = store().openStream(999L);
        assertTrue(result.isEmpty());
    }

    // ── multiple drafts are isolated ───────────────────────────────────────────

    @Test
    void multipleIdsAreIsolated() throws Exception {
        DraftCoverScratchStore s = store();
        s.write(1L, new byte[]{1});
        s.write(2L, new byte[]{2});

        assertArrayEquals(new byte[]{1}, s.read(1L).orElseThrow());
        assertArrayEquals(new byte[]{2}, s.read(2L).orElseThrow());

        s.delete(1L);
        assertTrue(s.read(1L).isEmpty());
        assertTrue(s.read(2L).isPresent());
    }

    // ── no leftover tmp file on success ───────────────────────────────────────

    @Test
    void write_leavesNoTmpFile() throws Exception {
        DraftCoverScratchStore s = store();
        s.write(99L, new byte[]{5});
        Path tmp = dataDir.resolve("_sandbox").resolve("draft_covers").resolve("99.jpg.tmp");
        assertFalse(Files.exists(tmp), "tmp file must be cleaned up after successful write");
    }

    // ── coverPath helper ───────────────────────────────────────────────────────

    @Test
    void coverPath_returnsExpectedLocation() {
        Path p = store().coverPath(17L);
        assertEquals("17.jpg", p.getFileName().toString());
        assertTrue(p.toString().contains("draft_covers"));
    }
}
