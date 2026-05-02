package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.ActressCompaniesService;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.javdb.draft.DraftSyncObserver;
import com.organizer3.javdb.draft.DraftTitleRepository;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import com.organizer3.sync.scanner.ScannerRegistry;
import com.organizer3.sync.scanner.QueueScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the sync hook behaviour introduced in Phase 5 (Draft Mode):
 * when sync rediscovers an existing title, the {@link TitleSyncObserver} is fired;
 * for new titles it is not; and observer failures do not block sync.
 */
class SyncHookTest {

    private TitleRepository titleRepo;
    private VideoRepository videoRepo;
    private ActressRepository actressRepo;
    private VolumeRepository volumeRepo;
    private TitleLocationRepository titleLocationRepo;
    private TitleActressRepository titleActressRepo;
    private IndexLoader indexLoader;
    private VolumeFileSystem fs;
    private SessionContext ctx;
    private CommandIO io;
    private SyncIdentityMatcher identityMatcher;
    private TitleSyncObserver observer;

    @TempDir
    Path tmpDataDir;

    private static final VolumeConfig VOLUME =
            new VolumeConfig("unsorted", "//server/queue", "queue", "server", null);

    private static final VolumeStructureDef STRUCTURE = new VolumeStructureDef(
            "queue",
            List.of(new PartitionDef("queue", "queue")),
            null
    );

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
        io               = new PlainCommandIO(new PrintWriter(new StringWriter()));
        identityMatcher  = mock(SyncIdentityMatcher.class);
        observer         = mock(TitleSyncObserver.class);

        when(volumeRepo.findById(anyString())).thenReturn(Optional.empty());
        when(indexLoader.load(anyString())).thenReturn(VolumeIndex.empty("unsorted"));
        when(titleRepo.countAll()).thenReturn(1);
        when(titleRepo.findOrphanedTitles()).thenReturn(List.of());
        // Default: actress resolution returns a stub actress so sync doesn't NPE.
        Actress stubActress = Actress.builder().id(1L).canonicalName("Test Actress")
                .tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.resolveByName(anyString())).thenReturn(Optional.of(stubActress));
        when(actressRepo.save(any())).thenReturn(stubActress);
    }

    private PartitionSyncOperation newOp() {
        return new PartitionSyncOperation(
                List.of("queue"), titleRepo, videoRepo, actressRepo, volumeRepo,
                titleLocationRepo, titleActressRepo, indexLoader,
                mock(TitleEffectiveTagsService.class), mock(ActressCompaniesService.class),
                new CoverPath(tmpDataDir), null, identityMatcher, observer);
    }

    /**
     * Sets up the filesystem mock so that the partition root contains exactly one
     * title folder whose name is the raw code (no actress prefix, no extra subdirs).
     * This keeps the sync path simple: no actress resolution, no video subdirs.
     */
    private void stubSingleTitleFolder(String code) throws IOException {
        Path titleFolder = Path.of("/queue/" + code);
        when(fs.exists(any())).thenReturn(true);
        when(fs.listDirectory(Path.of("/queue"))).thenReturn(List.of(titleFolder));
        when(fs.listDirectory(titleFolder)).thenReturn(List.of());  // no videos / subdirs
        when(fs.isDirectory(titleFolder)).thenReturn(true);
        when(fs.isDirectory(Path.of("/queue"))).thenReturn(true);
    }

    // ── hook fires for existing title ─────────────────────────────────────────

    @Test
    void hook_firedForExistingTitle() throws IOException {
        // findByCode returns a value → isNewTitle = false
        Title existingTitle = Title.builder().id(42L).code("ABCD-001").baseCode("ABCD")
                .label("ABCD").seqNum(1).build();
        when(titleRepo.findByCode("ABCD-001")).thenReturn(Optional.of(existingTitle));
        when(titleRepo.findOrCreateByCode(any())).thenReturn(existingTitle);
        stubSingleTitleFolder("ABCD-001");

        newOp().execute(VOLUME, STRUCTURE, fs, ctx, io);

        verify(observer).onTitleSyncWrite(42L);
    }

    // ── hook NOT fired for new title ──────────────────────────────────────────

    @Test
    void hook_notFiredForNewTitle() throws IOException {
        // findByCode returns empty → isNewTitle = true
        Title newTitle = Title.builder().id(99L).code("ABCD-001").baseCode("ABCD")
                .label("ABCD").seqNum(1).build();
        when(titleRepo.findByCode("ABCD-001")).thenReturn(Optional.empty());
        when(titleRepo.findOrCreateByCode(any())).thenReturn(newTitle);
        stubSingleTitleFolder("ABCD-001");

        newOp().execute(VOLUME, STRUCTURE, fs, ctx, io);

        verify(observer, never()).onTitleSyncWrite(anyLong());
    }

    // ── hook NOT fired when partition is empty ────────────────────────────────

    @Test
    void hook_notFiredWhenPartitionIsEmpty() throws IOException {
        when(fs.exists(any())).thenReturn(true);
        when(fs.listDirectory(any())).thenReturn(List.of());

        newOp().execute(VOLUME, STRUCTURE, fs, ctx, io);

        verify(observer, never()).onTitleSyncWrite(anyLong());
    }

    // ── observer failure is non-fatal ─────────────────────────────────────────

    @Test
    void hook_failureDoesNotBlockSync() throws IOException {
        Title existingTitle = Title.builder().id(77L).code("ABCD-001").baseCode("ABCD")
                .label("ABCD").seqNum(1).build();
        when(titleRepo.findByCode("ABCD-001")).thenReturn(Optional.of(existingTitle));
        when(titleRepo.findOrCreateByCode(any())).thenReturn(existingTitle);
        stubSingleTitleFolder("ABCD-001");
        // Make the observer throw to simulate a transient failure.
        doThrow(new RuntimeException("simulated observer failure"))
                .when(observer).onTitleSyncWrite(anyLong());

        // Sync must complete normally — no exception propagates.
        assertDoesNotThrow(() -> newOp().execute(VOLUME, STRUCTURE, fs, ctx, io));

        // titleLocationRepo.save must still have been called (sync proceeded).
        verify(titleLocationRepo, atLeastOnce()).save(any());
    }

    // ── DraftSyncObserver integration: sets upstream_changed ─────────────────

    @Test
    void draftSyncObserver_callsSetUpstreamChanged() {
        DraftTitleRepository draftRepo = mock(DraftTitleRepository.class);
        DraftSyncObserver draftObserver = new DraftSyncObserver(draftRepo);

        draftObserver.onTitleSyncWrite(55L);

        verify(draftRepo).setUpstreamChanged(eq(55L), anyString());
    }
}
