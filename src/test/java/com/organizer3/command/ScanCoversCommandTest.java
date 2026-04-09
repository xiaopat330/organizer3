package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.covers.CoverPath;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Volume;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.scanner.ConventionalScanner;
import com.organizer3.sync.scanner.ScannerRegistry;
import com.organizer3.sync.scanner.SortPoolScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScanCoversCommandTest {

    private static final VolumeConfig CONVENTIONAL_VOL = new VolumeConfig(
            "a", "//pandora/jav_A", "conventional", "pandora", null);

    private static final VolumeConfig SORT_POOL_VOL = new VolumeConfig(
            "pool", "//pandora/jav_unsorted/_done", "sort_pool", "pandora", null);

    private static final VolumeStructureDef CONVENTIONAL_STRUCTURE = new VolumeStructureDef(
            "conventional",
            List.of(new PartitionDef("queue", "queue")),
            new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
    );

    private static final VolumeStructureDef SORT_POOL_STRUCTURE = new VolumeStructureDef(
            "sort_pool", List.of(), null
    );

    @TempDir
    Path tempDir;

    private TitleRepository titleRepo;
    private VolumeRepository volumeRepo;
    private CoverPath coverPath;
    private ScannerRegistry scannerRegistry;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;
    private VolumeFileSystem fs;
    private VolumeConnection connection;

    @BeforeEach
    void setUp() {
        AppConfig.initializeForTest(new OrganizerConfig(
                null, null, null, null, null, null, List.of(), List.of(CONVENTIONAL_VOL, SORT_POOL_VOL),
                List.of(CONVENTIONAL_STRUCTURE, SORT_POOL_STRUCTURE),
                List.of()
        ));
        scannerRegistry = new ScannerRegistry(Map.of(
                "conventional", new ConventionalScanner(),
                "sort_pool",    new SortPoolScanner()
        ));
        titleRepo = mock(TitleRepository.class);
        volumeRepo = mock(VolumeRepository.class);
        coverPath = new CoverPath(tempDir);
        fs = mock(VolumeFileSystem.class);
        connection = mock(VolumeConnection.class);
        when(connection.isConnected()).thenReturn(true);
        when(connection.fileSystem()).thenReturn(fs);
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    private Title starsTitle(String code, String baseCode, String label, Path path) {
        return Title.builder()
                .id(1L).code(code).baseCode(baseCode).label(label)
                .actressId(1L)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("a").partitionId("stars/library")
                        .path(path)
                        .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now())
                        .build()))
                .build();
    }

    private Volume syncedVolume() {
        Volume v = new Volume("a", "conventional");
        v.setLastSyncedAt(LocalDateTime.now());
        return v;
    }

    @Test
    void noVolumeMounted_printsError() {
        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        cmd.execute(new String[]{"sync covers"}, ctx, io);
        assertTrue(output.toString().contains("No volume mounted"));
    }

    @Test
    void volumeNotSynced_printsError() {
        ctx.setMountedVolume(CONVENTIONAL_VOL);
        ctx.setActiveConnection(connection);
        Volume unsyncedVol = new Volume("a", "conventional");
        when(volumeRepo.findById("a")).thenReturn(Optional.of(unsyncedVol));

        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        cmd.execute(new String[]{"sync covers"}, ctx, io);
        assertTrue(output.toString().contains("has not been synced"));
    }

    @Test
    void collectsCoverImage() throws IOException {
        ctx.setMountedVolume(CONVENTIONAL_VOL);
        ctx.setActiveConnection(connection);
        when(volumeRepo.findById("a")).thenReturn(Optional.of(syncedVolume()));

        Path titlePath = Path.of("/stars/library/Actress/ABP-123");
        Title title = starsTitle("ABP-123", "ABP-00123", "ABP", titlePath);
        when(titleRepo.findByVolume("a")).thenReturn(List.of(title));

        Path imagePath = titlePath.resolve("cover.jpg");
        when(fs.listDirectory(titlePath)).thenReturn(List.of(imagePath));
        when(fs.isDirectory(imagePath)).thenReturn(false);
        when(fs.openFile(imagePath)).thenReturn(new ByteArrayInputStream("fake-jpg".getBytes()));

        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        cmd.execute(new String[]{"sync covers"}, ctx, io);

        Path expectedCover = coverPath.resolve(title, "jpg");
        assertTrue(Files.exists(expectedCover));
        assertEquals("fake-jpg", Files.readString(expectedCover));
        assertTrue(output.toString().contains("collected: 1"));
    }

    @Test
    void skipsExistingCovers() throws IOException {
        ctx.setMountedVolume(CONVENTIONAL_VOL);
        ctx.setActiveConnection(connection);
        when(volumeRepo.findById("a")).thenReturn(Optional.of(syncedVolume()));

        Path titlePath = Path.of("/stars/library/Actress/ABP-123");
        Title title = starsTitle("ABP-123", "ABP-00123", "ABP", titlePath);
        when(titleRepo.findByVolume("a")).thenReturn(List.of(title));

        // Pre-create the cover
        Path existingCover = coverPath.resolve(title, "jpg");
        Files.createDirectories(existingCover.getParent());
        Files.writeString(existingCover, "already here");

        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        cmd.execute(new String[]{"sync covers"}, ctx, io);

        assertTrue(output.toString().contains("Skipped (existing): 1"));
        verifyNoInteractions(fs);
    }

    @Test
    void handlesNoImageInFolder() throws IOException {
        ctx.setMountedVolume(CONVENTIONAL_VOL);
        ctx.setActiveConnection(connection);
        when(volumeRepo.findById("a")).thenReturn(Optional.of(syncedVolume()));

        Path titlePath = Path.of("/stars/library/Actress/ABP-123");
        Title title = starsTitle("ABP-123", "ABP-00123", "ABP", titlePath);
        when(titleRepo.findByVolume("a")).thenReturn(List.of(title));

        // Only video files, no images
        Path videoPath = titlePath.resolve("ABP-123.mp4");
        when(fs.listDirectory(titlePath)).thenReturn(List.of(videoPath));
        when(fs.isDirectory(videoPath)).thenReturn(false);

        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        cmd.execute(new String[]{"sync covers"}, ctx, io);

        assertTrue(output.toString().contains("No image: 1"));
    }

    @Test
    void filtersOutUnrecognizedPartitionTitles() throws IOException {
        ctx.setMountedVolume(CONVENTIONAL_VOL);
        ctx.setActiveConnection(connection);
        when(volumeRepo.findById("a")).thenReturn(Optional.of(syncedVolume()));

        // A title in an unrecognised partition (e.g. collections) should be skipped
        Title collectionsTitle = Title.builder()
                .id(2L).code("XYZ-456").baseCode("XYZ-00456").label("XYZ")
                .locations(List.of(TitleLocation.builder()
                        .titleId(2L).volumeId("a").partitionId("collections")
                        .path(Path.of("/collections/XYZ-456"))
                        .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now())
                        .build()))
                .build();
        when(titleRepo.findByVolume("a")).thenReturn(List.of(collectionsTitle));

        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        cmd.execute(new String[]{"sync covers"}, ctx, io);

        assertTrue(output.toString().contains("No eligible titles"));
    }

    @Test
    void collectsCoverImageFromQueuePartition() throws IOException {
        ctx.setMountedVolume(CONVENTIONAL_VOL);
        ctx.setActiveConnection(connection);
        when(volumeRepo.findById("a")).thenReturn(Optional.of(syncedVolume()));

        Path titlePath = Path.of("/queue/ABP-200");
        Title queueTitle = Title.builder()
                .id(2L).code("ABP-200").baseCode("ABP-00200").label("ABP")
                .locations(List.of(TitleLocation.builder()
                        .titleId(2L).volumeId("a").partitionId("queue")
                        .path(titlePath)
                        .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now())
                        .build()))
                .build();
        when(titleRepo.findByVolume("a")).thenReturn(List.of(queueTitle));

        Path imagePath = titlePath.resolve("cover.jpg");
        when(fs.listDirectory(titlePath)).thenReturn(List.of(imagePath));
        when(fs.isDirectory(imagePath)).thenReturn(false);
        when(fs.openFile(imagePath)).thenReturn(new ByteArrayInputStream("fake-jpg".getBytes()));

        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        cmd.execute(new String[]{"sync covers"}, ctx, io);

        Path expectedCover = coverPath.resolve(queueTitle, "jpg");
        assertTrue(Files.exists(expectedCover));
        assertTrue(output.toString().contains("collected: 1"));
    }

    @Test
    void collectsCoverImageFromSortPoolPartition() throws IOException {
        ctx.setMountedVolume(SORT_POOL_VOL);
        ctx.setActiveConnection(connection);
        Volume v = new Volume("pool", "sort_pool");
        v.setLastSyncedAt(LocalDateTime.now());
        when(volumeRepo.findById("pool")).thenReturn(Optional.of(v));

        Path titlePath = Path.of("/Yui Hatano (IPX-123)");
        Title poolTitle = Title.builder()
                .id(3L).code("IPX-123").baseCode("IPX-00123").label("IPX")
                .locations(List.of(TitleLocation.builder()
                        .titleId(3L).volumeId("pool").partitionId("pool")
                        .path(titlePath)
                        .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now())
                        .build()))
                .build();
        when(titleRepo.findByVolume("pool")).thenReturn(List.of(poolTitle));

        Path imagePath = titlePath.resolve("cover.jpg");
        when(fs.listDirectory(titlePath)).thenReturn(List.of(imagePath));
        when(fs.isDirectory(imagePath)).thenReturn(false);
        when(fs.openFile(imagePath)).thenReturn(new ByteArrayInputStream("fake-jpg".getBytes()));

        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        cmd.execute(new String[]{"sync covers"}, ctx, io);

        Path expectedCover = coverPath.resolve(poolTitle, "jpg");
        assertTrue(Files.exists(expectedCover));
        assertTrue(output.toString().contains("collected: 1"));
    }

    @Test
    void noScannerRegistered_printsNoScannablePartitions() {
        VolumeConfig collectionsVol = new VolumeConfig(
                "collections", "//pandora/jav_collections", "collections", "pandora", null);
        ctx.setMountedVolume(collectionsVol);
        ctx.setActiveConnection(connection);
        Volume v = new Volume("collections", "collections");
        v.setLastSyncedAt(LocalDateTime.now());
        when(volumeRepo.findById("collections")).thenReturn(Optional.of(v));

        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        cmd.execute(new String[]{"sync covers"}, ctx, io);

        assertTrue(output.toString().contains("no scannable partitions"));
    }

    @Test
    void name_returnsScanCovers() {
        ScanCoversCommand cmd = new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry);
        assertEquals("sync covers", cmd.name());
    }
}
