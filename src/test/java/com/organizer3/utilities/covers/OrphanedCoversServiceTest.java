package com.organizer3.utilities.covers;

import com.organizer3.covers.CoverPath;
import com.organizer3.repository.TitleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrphanedCoversServiceTest {

    @TempDir Path tmp;
    private CoverPath coverPath;
    private TitleRepository titleRepo;
    private OrphanedCoversService service;

    @BeforeEach
    void setup() throws IOException {
        coverPath = new CoverPath(tmp);
        Files.createDirectories(coverPath.root());
        titleRepo = mock(TitleRepository.class);
        service = new OrphanedCoversService(coverPath, titleRepo);
    }

    @Test
    void previewReturnsEmptyWhenRootMissing() throws IOException {
        Files.delete(coverPath.root());
        when(titleRepo.allBaseCodes()).thenReturn(Set.of());
        var pv = service.preview();
        assertEquals(0, pv.count());
    }

    @Test
    void findsOrphansAndSkipsMatched() throws IOException {
        // Filenames use zero-padded baseCode; DB code field is unpadded — regression for the
        // original bug where findByCode("ABP-00200") never matched code="ABP-200".
        writeCover("ABP", "ABP-00123.jpg", "orphan-bytes");
        writeCover("ABP", "ABP-00200.jpg", "matched-bytes");
        writeCover("ABP", "readme.txt", "ignored");

        // allBaseCodes returns zero-padded base_code values — the real DB field
        when(titleRepo.allBaseCodes()).thenReturn(Set.of("ABP-00200"));

        var pv = service.preview();
        assertEquals(1, pv.count());
        var row = pv.rows().get(0);
        assertEquals("ABP", row.label());
        assertEquals("ABP-00123.jpg", row.filename());
        assertTrue(pv.totalBytes() > 0);
    }

    /**
     * Regression: the original implementation called findByCode(baseCode), which queries
     * the `code` column (unpadded, e.g. "ABP-123"). Cover filenames use the zero-padded
     * base_code (e.g. "ABP-00123"). The mismatch caused every valid cover to look like an
     * orphan, producing a 50K+ false-positive count.
     */
    @Test
    void validCoverNotReportedOrphanWhenCodeIsUnpadded() throws IOException {
        // Cover stored as base_code form; DB has unpadded code but matching base_code
        writeCover("ABP", "ABP-00123.jpg", "bytes");
        // allBaseCodes returns "ABP-00123" (the base_code column value)
        when(titleRepo.allBaseCodes()).thenReturn(Set.of("ABP-00123"));

        var pv = service.preview();
        assertEquals(0, pv.count(), "cover with matching base_code must not be reported as orphan");
    }

    @Test
    void deleteRemovesOrphansAndReturnsCounts() throws IOException {
        writeCover("ABP", "ABP-00123.jpg", "data-123");
        writeCover("XYZ", "XYZ-00001.png", "data-x");
        when(titleRepo.allBaseCodes()).thenReturn(Set.of()); // everything orphaned

        var result = service.delete();
        assertEquals(2, result.deleted());
        assertEquals(0, result.failed());
        assertTrue(result.bytesFreed() > 0);
        assertFalse(Files.exists(coverPath.root().resolve("ABP").resolve("ABP-00123.jpg")));
        assertFalse(Files.exists(coverPath.root().resolve("XYZ").resolve("XYZ-00001.png")));
        // Second preview finds nothing left to do.
        assertEquals(0, service.preview().count());
    }

    @Test
    void deletePreservesMatchedCovers() throws IOException {
        writeCover("ABP", "ABP-00123.jpg", "orphan");
        writeCover("ABP", "ABP-00200.jpg", "kept");
        when(titleRepo.allBaseCodes()).thenReturn(Set.of("ABP-00200"));

        var result = service.delete();
        assertEquals(1, result.deleted());
        assertFalse(Files.exists(coverPath.root().resolve("ABP").resolve("ABP-00123.jpg")));
        assertTrue(Files.exists(coverPath.root().resolve("ABP").resolve("ABP-00200.jpg")));
    }

    private void writeCover(String label, String filename, String content) throws IOException {
        Path dir = coverPath.root().resolve(label);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(filename), content);
    }
}
