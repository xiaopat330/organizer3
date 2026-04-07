package com.organizer3.command;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PruneCoversCommandTest {

    @TempDir
    Path tempDir;

    private TitleRepository titleRepo;
    private CoverPath coverPath;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;

    @BeforeEach
    void setUp() {
        titleRepo = mock(TitleRepository.class);
        coverPath = new CoverPath(tempDir);
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
    }

    private Title title(String baseCode) {
        return Title.builder()
                .id(1L).code(baseCode).baseCode(baseCode).label("ABP")
                .actressId(1L)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("a").partitionId("stars/library")
                        .path(Path.of("/stars/library/Actress/" + baseCode))
                        .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now()).build()))
                .build();
    }

    @Test
    void noCoversDirectory_printsNothing() {
        PruneCoversCommand cmd = new PruneCoversCommand(titleRepo, coverPath);
        cmd.execute(new String[]{"prune-covers"}, ctx, io);
        assertTrue(output.toString().contains("No covers directory"));
    }

    @Test
    void prunesOrphanedCovers() throws IOException {
        Path labelDir = coverPath.root().resolve("ABP");
        Files.createDirectories(labelDir);
        Files.writeString(labelDir.resolve("ABP-00123.jpg"), "orphaned");
        Files.writeString(labelDir.resolve("ABP-00456.jpg"), "kept");

        when(titleRepo.findByBaseCode("ABP-00123")).thenReturn(List.of());
        when(titleRepo.findByBaseCode("ABP-00456")).thenReturn(List.of(title("ABP-00456")));

        PruneCoversCommand cmd = new PruneCoversCommand(titleRepo, coverPath);
        cmd.execute(new String[]{"prune-covers"}, ctx, io);

        assertFalse(Files.exists(labelDir.resolve("ABP-00123.jpg")));
        assertTrue(Files.exists(labelDir.resolve("ABP-00456.jpg")));
        assertTrue(output.toString().contains("Pruned: 1"));
        assertTrue(output.toString().contains("Kept: 1"));
    }

    @Test
    void keepsAllWhenNoneOrphaned() throws IOException {
        Path labelDir = coverPath.root().resolve("ABP");
        Files.createDirectories(labelDir);
        Files.writeString(labelDir.resolve("ABP-00123.jpg"), "data");

        when(titleRepo.findByBaseCode("ABP-00123")).thenReturn(List.of(title("ABP-00123")));

        PruneCoversCommand cmd = new PruneCoversCommand(titleRepo, coverPath);
        cmd.execute(new String[]{"prune-covers"}, ctx, io);

        assertTrue(Files.exists(labelDir.resolve("ABP-00123.jpg")));
        assertTrue(output.toString().contains("Pruned: 0"));
        assertTrue(output.toString().contains("Kept: 1"));
    }

    @Test
    void ignoresNonImageFiles() throws IOException {
        Path labelDir = coverPath.root().resolve("ABP");
        Files.createDirectories(labelDir);
        Files.writeString(labelDir.resolve("ABP-00123.txt"), "not an image");

        PruneCoversCommand cmd = new PruneCoversCommand(titleRepo, coverPath);
        cmd.execute(new String[]{"prune-covers"}, ctx, io);

        assertTrue(Files.exists(labelDir.resolve("ABP-00123.txt")));
        assertTrue(output.toString().contains("Pruned: 0"));
    }

    @Test
    void name_returnsPruneCovers() {
        PruneCoversCommand cmd = new PruneCoversCommand(titleRepo, coverPath);
        assertEquals("prune-covers", cmd.name());
    }
}
