package com.organizer3.utilities.covers;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
        var pv = service.preview();
        assertEquals(0, pv.count());
    }

    @Test
    void findsOrphansAndSkipsMatched() throws IOException {
        // One orphan (no title match), one matched (title exists), one non-image file to skip.
        writeCover("ABP", "ABP-00123.jpg", "orphan-bytes");
        writeCover("ABP", "ABP-00200.jpg", "matched-bytes");
        writeCover("ABP", "readme.txt", "ignored");

        Map<String, Title> byCode = new HashMap<>();
        byCode.put("ABP-00200", Title.builder().id(1L).code("ABP-00200").label("ABP").baseCode("ABP-00200").build());
        when(titleRepo.findByCode(anyString())).thenAnswer(inv -> Optional.ofNullable(byCode.get(inv.getArgument(0))));

        var pv = service.preview();
        assertEquals(1, pv.count());
        var row = pv.rows().get(0);
        assertEquals("ABP", row.label());
        assertEquals("ABP-00123.jpg", row.filename());
        assertTrue(pv.totalBytes() > 0);
    }

    @Test
    void deleteRemovesOrphansAndReturnsCounts() throws IOException {
        writeCover("ABP", "ABP-00123.jpg", "data-123");
        writeCover("XYZ", "XYZ-00001.png", "data-x");
        when(titleRepo.findByCode(anyString())).thenReturn(Optional.empty()); // everything orphaned

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
        Map<String, Title> byCode = new HashMap<>();
        byCode.put("ABP-00200", Title.builder().id(1L).code("ABP-00200").label("ABP").baseCode("ABP-00200").build());
        when(titleRepo.findByCode(anyString())).thenAnswer(inv -> Optional.ofNullable(byCode.get(inv.getArgument(0))));

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
