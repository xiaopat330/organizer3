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
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import com.organizer3.sync.scanner.CollectionsScanner;
import com.organizer3.sync.scanner.ConventionalScanner;
import com.organizer3.sync.scanner.ExhibitionScanner;
import com.organizer3.sync.scanner.QueueScanner;
import com.organizer3.sync.scanner.ScannerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FullSyncOperation — verifies that a full sync clears existing data,
 * delegates to the appropriate scanner, persists discovered titles/videos, resolves
 * actresses correctly, and links cast to the title_actresses junction table.
 */
class FullSyncOperationTest {

    private TitleRepository titleRepo;
    private VideoRepository videoRepo;
    private ActressRepository actressRepo;
    private VolumeRepository volumeRepo;
    private TitleLocationRepository titleLocationRepo;
    private TitleActressRepository titleActressRepo;
    private IndexLoader indexLoader;
    private VolumeFileSystem fs;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;
    private ScannerRegistry scannerRegistry;

    private static final VolumeConfig CONVENTIONAL_VOLUME = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);
    private static final VolumeConfig QUEUE_VOLUME        = new VolumeConfig("unsorted", "//pandora/jav_unsorted", "queue", "pandora", null);
    private static final VolumeConfig EXHIBITION_VOLUME   = new VolumeConfig("qnap", "//qnap2/jav", "exhibition", "qnap2", null);
    private static final VolumeConfig COLLECTIONS_VOLUME  = new VolumeConfig("collections", "//pandora/jav_collections", "collections", "pandora", null);

    @BeforeEach
    void setUp() {
        titleRepo        = mock(TitleRepository.class);
        videoRepo        = mock(VideoRepository.class);
        actressRepo      = mock(ActressRepository.class);
        volumeRepo       = mock(VolumeRepository.class);
        titleLocationRepo = mock(TitleLocationRepository.class);
        titleActressRepo = mock(TitleActressRepository.class);
        indexLoader      = mock(IndexLoader.class);
        fs               = mock(VolumeFileSystem.class);
        ctx              = new SessionContext();
        output           = new StringWriter();
        io               = new PlainCommandIO(new PrintWriter(output));

        scannerRegistry = new ScannerRegistry(Map.of(
                "conventional", new ConventionalScanner(),
                "queue",        new QueueScanner(),
                "exhibition",   new ExhibitionScanner(),
                "collections",  new CollectionsScanner()
        ));

        when(volumeRepo.findById(anyString())).thenReturn(Optional.empty());
        when(indexLoader.load(anyString())).thenReturn(VolumeIndex.empty("a"));
        // Default: findOrCreateByCode returns the title with a generated id
        when(titleRepo.findOrCreateByCode(any(Title.class))).thenAnswer(inv -> {
            Title t = inv.getArgument(0);
            return t.toBuilder().id(1L).build();
        });
    }

    private FullSyncOperation newOp() {
        return new FullSyncOperation(scannerRegistry, titleRepo, videoRepo, actressRepo,
                volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                mock(com.organizer3.db.TitleEffectiveTagsService.class), mock(com.organizer3.db.ActressCompaniesService.class));
    }

    // --- Clears data before scanning ---

    @Test
    void clearsExistingRecordsBeforeScanning() throws IOException {
        VolumeStructureDef structure = conventionalStructure();
        when(fs.exists(any())).thenReturn(false);

        newOp().execute(CONVENTIONAL_VOLUME, structure, fs, ctx, io);

        var order = inOrder(videoRepo, titleLocationRepo);
        order.verify(videoRepo).deleteByVolume("a");
        order.verify(titleLocationRepo).deleteByVolume("a");
    }

    // --- Unstructured partition scanning ---

    @Test
    void scansUnstructuredPartitionsAndSavesTitles() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "conventional",
                List.of(new PartitionDef("queue", "queue")),
                null
        );

        Path queueRoot = Path.of("/queue");
        Path titleDir  = queueRoot.resolve("ABP-001");
        when(fs.exists(queueRoot)).thenReturn(true);
        when(fs.listDirectory(queueRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        Path videoFile = titleDir.resolve("ABP-001.mp4");
        when(fs.listDirectory(titleDir)).thenReturn(List.of(videoFile));
        when(fs.isDirectory(videoFile)).thenReturn(false);

        newOp().execute(CONVENTIONAL_VOLUME, structure, fs, ctx, io);

        verify(titleRepo).findOrCreateByCode(argThat(t -> "ABP-001".equals(t.getCode())));
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

        newOp().execute(CONVENTIONAL_VOLUME, structure, fs, ctx, io);

        assertTrue(output.toString().contains("[skip]"));
        verify(titleRepo, never()).findOrCreateByCode(any());
    }

    // --- Structured partition scanning (tiered stars) ---

    @Test
    void scansTieredStarsAndResolvesActresses() throws IOException {
        VolumeStructureDef structure = conventionalStructure();

        Path starsRoot   = Path.of("/stars");
        Path libraryRoot = starsRoot.resolve("library");
        Path actressDir  = libraryRoot.resolve("Aya Sazanami");
        Path titleDir    = actressDir.resolve("ABP-001");
        Path videoFile   = titleDir.resolve("ABP-001.mp4");

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

        newOp().execute(CONVENTIONAL_VOLUME, structure, fs, ctx, io);

        verify(titleRepo).findOrCreateByCode(argThat(t ->
                "ABP-001".equals(t.getCode()) && t.getActressId() == 42L));
        // Single actress → also linked in junction table
        verify(titleActressRepo).linkAll(1L, List.of(42L));
    }

    @Test
    void createsNewActressWhenNotFoundDuringStarsScan() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "conventional", List.of(),
                new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
        );

        Path libraryRoot = Path.of("/stars/library");
        Path actressDir  = libraryRoot.resolve("New Actress");
        Path titleDir    = actressDir.resolve("XYZ-001");

        when(fs.exists(libraryRoot)).thenReturn(true);
        when(fs.listDirectory(libraryRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        when(actressRepo.resolveByName("New Actress")).thenReturn(Optional.empty());
        Actress created = Actress.builder().id(99L).canonicalName("New Actress").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.save(any(Actress.class))).thenReturn(created);

        newOp().execute(CONVENTIONAL_VOLUME, structure, fs, ctx, io);

        verify(actressRepo).save(argThat(a -> "New Actress".equals(a.getCanonicalName())));
    }

    @Test
    void promotesTierWhenActressFoundInHigherPartition() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "conventional", List.of(),
                new StructuredPartitionDef("stars", List.of(new PartitionDef("goddess", "goddess")))
        );

        Path goddessRoot = Path.of("/stars/goddess");
        Path actressDir  = goddessRoot.resolve("Yua Mikami");
        Path titleDir    = actressDir.resolve("SSIS-001");

        when(fs.exists(goddessRoot)).thenReturn(true);
        when(fs.listDirectory(goddessRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        // Actress already exists with LIBRARY tier (e.g., from alias import)
        Actress yua = Actress.builder().id(5L).canonicalName("Yua Mikami").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Yua Mikami")).thenReturn(Optional.of(yua));

        newOp().execute(CONVENTIONAL_VOLUME, structure, fs, ctx, io);

        // Should promote from LIBRARY to GODDESS
        verify(actressRepo).updateTier(5L, Actress.Tier.GODDESS);
    }

    @Test
    void doesNotDemoteTierWhenActressFoundInLowerPartition() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "conventional", List.of(),
                new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
        );

        Path libraryRoot = Path.of("/stars/library");
        Path actressDir  = libraryRoot.resolve("Yua Mikami");
        Path titleDir    = actressDir.resolve("SSIS-002");

        when(fs.exists(libraryRoot)).thenReturn(true);
        when(fs.listDirectory(libraryRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        // Actress already exists with GODDESS tier
        Actress yua = Actress.builder().id(5L).canonicalName("Yua Mikami").tier(Actress.Tier.GODDESS).build();
        when(actressRepo.resolveByName("Yua Mikami")).thenReturn(Optional.of(yua));

        newOp().execute(CONVENTIONAL_VOLUME, structure, fs, ctx, io);

        // Should NOT demote — updateTier should not be called
        verify(actressRepo, never()).updateTier(anyLong(), any());
    }

    // --- Exhibition scanning ---

    @Test
    void scansFlatStarsLayout() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "exhibition", List.of(),
                new StructuredPartitionDef("stars", List.of())
        );

        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Marin Yakuno");
        Path titleDir   = actressDir.resolve("Marin Yakuno (IPZZ-679)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        Actress marin = Actress.builder().id(7L).canonicalName("Marin Yakuno").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Marin Yakuno")).thenReturn(Optional.of(marin));

        newOp().execute(EXHIBITION_VOLUME, structure, fs, ctx, io);

        verify(titleRepo).findOrCreateByCode(argThat(t ->
                "IPZZ-679".equals(t.getCode()) && t.getActressId() == 7L));
        verify(titleActressRepo).linkAll(1L, List.of(7L));
    }

    @Test
    void starsFlatRecursesIntoSubfolders() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "exhibition", List.of(),
                new StructuredPartitionDef("stars", List.of())
        );

        Path starsRoot    = Path.of("/stars");
        Path actressDir   = starsRoot.resolve("Sora Shiina");
        Path favoritesDir = actressDir.resolve("favorites");
        Path titleDirect  = actressDir.resolve("Sora Shiina (WANZ-575)");
        Path titleInSub   = favoritesDir.resolve("Sora Shiina (HND-305)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDirect, favoritesDir));
        when(fs.isDirectory(titleDirect)).thenReturn(true);
        when(fs.isDirectory(favoritesDir)).thenReturn(true);
        when(fs.listDirectory(favoritesDir)).thenReturn(List.of(titleInSub));
        when(fs.isDirectory(titleInSub)).thenReturn(true);
        when(fs.listDirectory(titleDirect)).thenReturn(List.of());
        when(fs.listDirectory(titleInSub)).thenReturn(List.of());

        Actress sora = Actress.builder().id(10L).canonicalName("Sora Shiina").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Sora Shiina")).thenReturn(Optional.of(sora));

        newOp().execute(EXHIBITION_VOLUME, structure, fs, ctx, io);

        verify(titleRepo).findOrCreateByCode(argThat(t -> "WANZ-575".equals(t.getCode())));
        verify(titleRepo).findOrCreateByCode(argThat(t -> "HND-305".equals(t.getCode())));
    }

    @Test
    void starsFlatSkipsCoverFolders() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "exhibition", List.of(),
                new StructuredPartitionDef("stars", List.of())
        );

        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Eimi Fukada");
        Path coversDir  = actressDir.resolve("covers");
        Path titleDir   = actressDir.resolve("Eimi Fukada (MIAA-085)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(coversDir, titleDir));
        when(fs.isDirectory(coversDir)).thenReturn(true);
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        Actress eimi = Actress.builder().id(20L).canonicalName("Eimi Fukada").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Eimi Fukada")).thenReturn(Optional.of(eimi));

        newOp().execute(EXHIBITION_VOLUME, structure, fs, ctx, io);

        verify(titleRepo, times(1)).findOrCreateByCode(any());
        verify(titleRepo).findOrCreateByCode(argThat(t -> "MIAA-085".equals(t.getCode())));
    }

    @Test
    void starsFlatSkipsTempTopLevel() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "exhibition", List.of(),
                new StructuredPartitionDef("stars", List.of())
        );

        Path starsRoot  = Path.of("/stars");
        Path tempDir    = starsRoot.resolve("temp");
        Path actressDir = starsRoot.resolve("Julia");
        Path titleDir   = actressDir.resolve("Julia (MIDE-517)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(tempDir, actressDir));
        when(fs.isDirectory(tempDir)).thenReturn(true);
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        Actress julia = Actress.builder().id(30L).canonicalName("Julia").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Julia")).thenReturn(Optional.of(julia));

        newOp().execute(EXHIBITION_VOLUME, structure, fs, ctx, io);

        verify(fs, never()).listDirectory(tempDir);
        verify(titleRepo, times(1)).findOrCreateByCode(any());
    }

    // --- Queue scanning ---

    @Test
    void scansQueueVolume() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "queue", List.of(new PartitionDef("queue", "fresh")), null);

        Path queueRoot = Path.of("/fresh");
        Path titleDir  = queueRoot.resolve("Marin Yakuno (IPZZ-679)");
        when(fs.exists(queueRoot)).thenReturn(true);
        when(fs.listDirectory(queueRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        Actress marin = Actress.builder().id(7L).canonicalName("Marin Yakuno").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Marin Yakuno")).thenReturn(Optional.of(marin));

        newOp().execute(QUEUE_VOLUME, structure, fs, ctx, io);

        verify(titleRepo).findOrCreateByCode(argThat(t -> t.getActressId() != null && t.getActressId() == 7L));
    }

    // --- Collections scanning ---

    @Test
    void scansCollectionsVolumeWithMultiActressTitles() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "collections",
                List.of(new PartitionDef("duos", "duos")),
                null
        );

        Path duosRoot = Path.of("/duos");
        Path titleDir = duosRoot.resolve("Aika, Yui Hatano (HMN-102)");

        when(fs.exists(duosRoot)).thenReturn(true);
        when(fs.listDirectory(duosRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        Actress aika = Actress.builder().id(1L).canonicalName("Aika").tier(Actress.Tier.LIBRARY).build();
        Actress yui  = Actress.builder().id(2L).canonicalName("Yui Hatano").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Aika")).thenReturn(Optional.of(aika));
        when(actressRepo.resolveByName("Yui Hatano")).thenReturn(Optional.of(yui));

        newOp().execute(COLLECTIONS_VOLUME, structure, fs, ctx, io);

        // actress_id must be null (multi-actress, no single filing actress)
        verify(titleRepo).findOrCreateByCode(argThat(t ->
                "HMN-102".equals(t.getCode()) && t.getActressId() == null));
        // Both actresses linked in junction table
        verify(titleActressRepo).linkAll(1L, List.of(1L, 2L));
    }

    @Test
    void collectionsVariousTitleHasNullActressAndNoJunctionRows() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "collections",
                List.of(new PartitionDef("archive", "archive")),
                null
        );

        Path archiveRoot = Path.of("/archive");
        Path titleDir    = archiveRoot.resolve("Various (DCX-137)");

        when(fs.exists(archiveRoot)).thenReturn(true);
        when(fs.listDirectory(archiveRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        newOp().execute(COLLECTIONS_VOLUME, structure, fs, ctx, io);

        verify(titleRepo).findOrCreateByCode(argThat(t ->
                "DCX-137".equals(t.getCode()) && t.getActressId() == null));
        // Empty cast — linkAll called with empty list (no junction rows)
        verify(titleActressRepo).linkAll(1L, List.of());
        verify(actressRepo, never()).resolveByName(any());
    }

    @Test
    void collectionsSkipsCoversSubfolder() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "collections",
                List.of(new PartitionDef("archive", "archive")),
                null
        );

        Path archiveRoot = Path.of("/archive");
        Path coversDir   = archiveRoot.resolve("__covers");
        Path titleDir    = archiveRoot.resolve("Aika, Yui Hatano (HMN-102)");

        when(fs.exists(archiveRoot)).thenReturn(true);
        when(fs.listDirectory(archiveRoot)).thenReturn(List.of(coversDir, titleDir));
        when(fs.isDirectory(coversDir)).thenReturn(true);
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        Actress aika = Actress.builder().id(1L).canonicalName("Aika").tier(Actress.Tier.LIBRARY).build();
        Actress yui  = Actress.builder().id(2L).canonicalName("Yui Hatano").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName("Aika")).thenReturn(Optional.of(aika));
        when(actressRepo.resolveByName("Yui Hatano")).thenReturn(Optional.of(yui));

        newOp().execute(COLLECTIONS_VOLUME, structure, fs, ctx, io);

        verify(titleRepo, times(1)).findOrCreateByCode(any());
    }

    // --- Junction table: deleteOrphaned called after titleRepo.deleteOrphaned ---

    @Test
    void callsDeleteOrphanedOnBothReposAfterSync() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef("queue", List.of(), null);

        newOp().execute(QUEUE_VOLUME, structure, fs, ctx, io);

        var order = inOrder(titleRepo, titleActressRepo);
        order.verify(titleRepo).deleteOrphaned();
        order.verify(titleActressRepo).deleteOrphaned();
    }

    // --- Volume record and finalize ---

    @Test
    void createsVolumeRecordIfNotExists() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef("queue", List.of(), null);

        newOp().execute(QUEUE_VOLUME, structure, fs, ctx, io);

        verify(volumeRepo).save(argThat(v -> "unsorted".equals(v.getId())));
    }

    @Test
    void skipsVolumeRecordCreationIfAlreadyExists() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef("queue", List.of(), null);
        when(volumeRepo.findById("unsorted")).thenReturn(Optional.of(new Volume("unsorted", "queue")));

        newOp().execute(QUEUE_VOLUME, structure, fs, ctx, io);

        verify(volumeRepo, never()).save(any());
    }

    @Test
    void finalizeSyncUpdatesTimestampAndReloadsIndex() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef("queue", List.of(), null);

        newOp().execute(QUEUE_VOLUME, structure, fs, ctx, io);

        verify(volumeRepo).updateLastSyncedAt(eq("unsorted"), any(LocalDateTime.class));
        verify(indexLoader).load("unsorted");
        assertNotNull(ctx.getIndex());
    }

    @Test
    void printsSyncCompleteStats() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef("queue", List.of(), null);

        newOp().execute(QUEUE_VOLUME, structure, fs, ctx, io);

        assertTrue(output.toString().contains("Sync complete."));
    }

    // --- Video subdirectory scanning ---

    @Test
    void scansVideoSubdirectory() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "queue", List.of(new PartitionDef("queue", "fresh")), null);

        Path queueRoot  = Path.of("/fresh");
        Path titleDir   = queueRoot.resolve("ABP-001");
        Path videoSubdir = titleDir.resolve("video");
        Path videoFile   = videoSubdir.resolve("ABP-001.mkv");

        when(fs.exists(queueRoot)).thenReturn(true);
        when(fs.listDirectory(queueRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of(videoSubdir));
        when(fs.isDirectory(videoSubdir)).thenReturn(true);
        when(fs.exists(videoSubdir)).thenReturn(true);
        when(fs.listDirectory(videoSubdir)).thenReturn(List.of(videoFile));
        when(fs.isDirectory(videoFile)).thenReturn(false);

        newOp().execute(QUEUE_VOLUME, structure, fs, ctx, io);

        verify(videoRepo).save(argThat(v -> "ABP-001.mkv".equals(v.getFilename())));
    }

    @Test
    void scansH265Subdirectory() throws IOException {
        VolumeStructureDef structure = new VolumeStructureDef(
                "queue", List.of(new PartitionDef("queue", "fresh")), null);

        Path queueRoot   = Path.of("/fresh");
        Path titleDir    = queueRoot.resolve("ABP-001");
        Path h265Subdir  = titleDir.resolve("h265");
        Path videoFile   = h265Subdir.resolve("ABP-001.mkv");

        when(fs.exists(queueRoot)).thenReturn(true);
        when(fs.listDirectory(queueRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of(h265Subdir));
        when(fs.isDirectory(h265Subdir)).thenReturn(true);
        when(fs.exists(h265Subdir)).thenReturn(true);
        when(fs.listDirectory(h265Subdir)).thenReturn(List.of(videoFile));
        when(fs.isDirectory(videoFile)).thenReturn(false);

        newOp().execute(QUEUE_VOLUME, structure, fs, ctx, io);

        verify(videoRepo).save(argThat(v -> "ABP-001.mkv".equals(v.getFilename())));
    }

    // --- helpers ---

    private VolumeStructureDef conventionalStructure() {
        return new VolumeStructureDef(
                "conventional",
                List.of(new PartitionDef("queue", "queue")),
                new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
        );
    }
}
