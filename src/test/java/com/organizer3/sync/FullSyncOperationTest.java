package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.Volume;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FullSyncOperation — verifies that a full sync clears existing data,
 * scans all partitions, saves titles/videos, and resolves actresses correctly.
 */
class FullSyncOperationTest {

    private TitleRepository titleRepo;
    private VideoRepository videoRepo;
    private ActressRepository actressRepo;
    private VolumeRepository volumeRepo;
    private IndexLoader indexLoader;
    private VolumeFileSystem fs;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;

    private static final VolumeConfig VOLUME = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora");

    @BeforeEach
    void setUp() {
        titleRepo = mock(TitleRepository.class);
        videoRepo = mock(VideoRepository.class);
        actressRepo = mock(ActressRepository.class);
        volumeRepo = mock(VolumeRepository.class);
        indexLoader = mock(IndexLoader.class);
        fs = mock(VolumeFileSystem.class);
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));

        when(volumeRepo.findById("a")).thenReturn(Optional.empty());
        when(indexLoader.load("a")).thenReturn(VolumeIndex.empty("a"));
        // Default: title save returns the title with an id
        when(titleRepo.save(any(Title.class))).thenAnswer(inv -> {
            Title t = inv.getArgument(0);
            return t.toBuilder().id(1L).build();
        });
    }

    // --- Clears data before scanning ---

    @Test
    void clearsExistingRecordsBeforeScanning() throws IOException {
        VolumeStructureDef structure = conventionalStructure();
        when(fs.exists(any())).thenReturn(false); // empty partitions

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        // Videos cleared before titles (FK ordering)
        var order = inOrder(videoRepo, titleRepo);
        order.verify(videoRepo).deleteByVolume("a");
        order.verify(titleRepo).deleteByVolume("a");
    }

    // --- Unstructured partition scanning ---

    @Test
    void scansUnstructuredPartitionsAndSavesTitles() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "conventional",
                List.of(new PartitionDef("queue", "queue")),
                null // no stars
        );

        Path queueRoot = Path.of("/queue");
        Path titleDir = queueRoot.resolve("ABP-001");
        when(fs.exists(queueRoot)).thenReturn(true);
        when(fs.listDirectory(queueRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        // Title folder contents (one video file)
        Path videoFile = titleDir.resolve("ABP-001.mp4");
        when(fs.listDirectory(titleDir)).thenReturn(List.of(videoFile));
        when(fs.isDirectory(videoFile)).thenReturn(false);

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        verify(titleRepo).save(argThat(t -> "ABP-001".equals(t.getCode()) && "queue".equals(t.getPartitionId())));
        verify(videoRepo).save(argThat(v -> "ABP-001.mp4".equals(v.getFilename())));
    }

    @Test
    void skipsNonExistentPartition() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "conventional",
                List.of(new PartitionDef("queue", "queue")),
                null
        );
        when(fs.exists(Path.of("/queue"))).thenReturn(false);

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        assertTrue(output.toString().contains("[skip]"));
        verify(titleRepo, never()).save(any());
    }

    // --- Structured partition scanning (tiered stars) ---

    @Test
    void scansTieredStarsAndResolvesActresses() throws IOException {
        VolumeStructureDef structure = conventionalStructure();

        Path starsRoot = Path.of("/stars");
        Path libraryRoot = starsRoot.resolve("library");
        Path actressDir = libraryRoot.resolve("Aya Sazanami");
        Path titleDir = actressDir.resolve("ABP-001");
        Path videoFile = titleDir.resolve("ABP-001.mp4");

        when(fs.exists(Path.of("/queue"))).thenReturn(false);
        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.exists(libraryRoot)).thenReturn(true);
        when(fs.listDirectory(libraryRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of(videoFile));
        when(fs.isDirectory(videoFile)).thenReturn(false);

        Actress aya = Actress.builder().id(42L).canonicalName("Aya Sazanami").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        verify(titleRepo).save(argThat(t ->
                "ABP-001".equals(t.getCode()) && t.getActressId() == 42L && "stars/library".equals(t.getPartitionId())));
    }

    @Test
    void createsNewActressWhenNotFoundDuringStarsScan() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "conventional",
                List.of(),
                new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
        );

        Path libraryRoot = Path.of("/stars/library");
        Path actressDir = libraryRoot.resolve("New Actress");
        Path titleDir = actressDir.resolve("XYZ-001");

        when(fs.exists(libraryRoot)).thenReturn(true);
        when(fs.listDirectory(libraryRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of()); // no videos

        when(actressRepo.resolveByName("New Actress")).thenReturn(Optional.empty());
        Actress created = Actress.builder().id(99L).canonicalName("New Actress").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.save(any(Actress.class))).thenReturn(created);

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        verify(actressRepo).save(argThat(a -> "New Actress".equals(a.getCanonicalName())));
    }

    // --- Flat stars scanning ---

    @Test
    void scansFlatStarsLayout() throws IOException {
        // stars-flat: no tier sub-folders, actress dirs sit directly under stars/
        VolumeStructureDef structure = new VolumeStructureDef(
                "stars-flat",
                List.of(),
                new StructuredPartitionDef("stars", List.of()) // empty partitions = flat
        );

        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Marin Yakuno");
        Path titleDir = actressDir.resolve("IPZZ-679");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        Actress marin = Actress.builder().id(7L).canonicalName("Marin Yakuno").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Marin Yakuno")).thenReturn(Optional.of(marin));

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        verify(titleRepo).save(argThat(t ->
                "IPZZ-679".equals(t.getCode()) && t.getActressId() == 7L && "stars".equals(t.getPartitionId())));
    }

    // --- Volume record and finalize ---

    @Test
    void createsVolumeRecordIfNotExists() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef("queue", List.of(), null);
        when(volumeRepo.findById("a")).thenReturn(Optional.empty());

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        verify(volumeRepo).save(argThat(v -> "a".equals(v.getId())));
    }

    @Test
    void skipsVolumeRecordCreationIfAlreadyExists() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef("queue", List.of(), null);
        when(volumeRepo.findById("a")).thenReturn(Optional.of(new Volume("a", "conventional")));

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        verify(volumeRepo, never()).save(any());
    }

    @Test
    void finalizeSyncUpdatesTimestampAndReloadsIndex() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef("queue", List.of(), null);

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        verify(volumeRepo).updateLastSyncedAt(eq("a"), any(LocalDateTime.class));
        verify(indexLoader).load("a");
        assertNotNull(ctx.getIndex());
    }

    @Test
    void printsSyncCompleteStats() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef("queue", List.of(), null);

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        assertTrue(output.toString().contains("Sync complete."));
    }

    // --- Actress inference from queue folder names ---

    @Test
    void infersActressFromQueueFolderName() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "queue", List.of(new PartitionDef("queue", "fresh")), null);

        Path queueRoot = Path.of("/fresh");
        Path titleDir = queueRoot.resolve("Marin Yakuno (IPZZ-679)");
        when(fs.exists(queueRoot)).thenReturn(true);
        when(fs.listDirectory(queueRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        Actress marin = Actress.builder().id(7L).canonicalName("Marin Yakuno").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Marin Yakuno")).thenReturn(Optional.of(marin));

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        verify(titleRepo).save(argThat(t -> t.getActressId() != null && t.getActressId() == 7L));
    }

    // --- Video subdirectory scanning ---

    @Test
    void scansVideoSubdirectory() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "queue", List.of(new PartitionDef("queue", "fresh")), null);

        Path queueRoot = Path.of("/fresh");
        Path titleDir = queueRoot.resolve("ABP-001");
        Path videoSubdir = titleDir.resolve("video");
        Path videoFile = videoSubdir.resolve("ABP-001.mkv");

        when(fs.exists(queueRoot)).thenReturn(true);
        when(fs.listDirectory(queueRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of(videoSubdir));
        when(fs.isDirectory(videoSubdir)).thenReturn(true);
        when(fs.exists(videoSubdir)).thenReturn(true);
        when(fs.listDirectory(videoSubdir)).thenReturn(List.of(videoFile));
        when(fs.isDirectory(videoFile)).thenReturn(false);

        FullSyncOperation op = new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
        op.execute(VOLUME, structure, fs, ctx, io);

        verify(videoRepo).save(argThat(v -> "ABP-001.mkv".equals(v.getFilename())));
    }

    // --- Helpers ---

    private VolumeStructureDef conventionalStructure() {
        return new VolumeStructureDef(
                "conventional",
                List.of(new PartitionDef("queue", "queue")),
                new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
        );
    }
}
