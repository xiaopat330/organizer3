package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.covers.CoverPath;
import com.organizer3.filesystem.VolumeFileSystem;
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
import com.organizer3.sync.scanner.ConventionalScanner;
import com.organizer3.sync.scanner.ScannerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for the catastrophic-flagging guard in
 * {@code AbstractSyncOperation.pruneOrphanedTitlesAndCovers()}.
 *
 * <p>Verifies that when the enriched-orphan count would exceed {@code max(50, total/10)},
 * the entire prune step is refused (no covers deleted, no deleteOrphaned call).
 */
class PruneOrphanedTitlesAndCoversGuardTest {

    private TitleRepository titleRepo;
    private VolumeRepository volumeRepo;
    private TitleActressRepository titleActressRepo;
    private StringWriter output;
    private CommandIO io;
    private SyncIdentityMatcher identityMatcher;

    private static final VolumeConfig CONVENTIONAL_VOLUME =
            new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);

    @BeforeEach
    void setUp() {
        titleRepo        = mock(TitleRepository.class);
        volumeRepo       = mock(VolumeRepository.class);
        titleActressRepo = mock(TitleActressRepository.class);
        output           = new StringWriter();
        io               = new PlainCommandIO(new PrintWriter(output));

        identityMatcher = mock(SyncIdentityMatcher.class);
        when(volumeRepo.findById(anyString())).thenReturn(Optional.empty());
        when(titleRepo.findOrCreateByCode(any())).thenAnswer(inv ->
                ((com.organizer3.model.Title) inv.getArgument(0)).toBuilder().id(99L).build());
    }

    private FullSyncOperation newOp() throws IOException {
        var tmp = Files.createTempDirectory("prune-guard-test");
        ScannerRegistry reg = new ScannerRegistry(Map.of("conventional", new ConventionalScanner()));
        return new FullSyncOperation(reg, titleRepo,
                mock(VideoRepository.class), mock(ActressRepository.class),
                volumeRepo, mock(TitleLocationRepository.class),
                titleActressRepo, mock(IndexLoader.class),
                mock(com.organizer3.db.TitleEffectiveTagsService.class),
                mock(com.organizer3.db.ActressCompaniesService.class),
                new CoverPath(tmp), null, identityMatcher);
    }

    private VolumeStructureDef emptyStructure() {
        return new VolumeStructureDef("conventional",
                List.of(new PartitionDef("queue", "queue")), null);
    }

    /**
     * When enriched orphan count would exceed the flagging threshold, the prune step
     * must be refused: deleteOrphaned must NOT be called, and a warning must be logged.
     */
    @Test
    void flaggingGuard_refusesPruneWhenEnrichedOrphansExceedThreshold() throws Exception {
        int total = 1000;
        // Threshold = max(50, 1000/10) = 100. Flag 101 enriched orphans → must refuse.
        int enrichedOrphans = 101;
        var orphanRef = new TitleRepository.OrphanedTitleRef("ABP", "ABP-00001");

        when(titleRepo.findOrphanedTitles()).thenReturn(List.of(orphanRef));
        when(titleRepo.countAll()).thenReturn(total);
        when(titleRepo.countOrphansWithEnrichment()).thenReturn(enrichedOrphans);

        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        when(fs.exists(any())).thenReturn(false);

        newOp().execute(CONVENTIONAL_VOLUME, emptyStructure(), fs, new SessionContext(), io);

        verify(titleRepo, never()).deleteOrphaned();
        String out = output.toString();
        assertTrue(out.contains("⚠"), "warning must be printed");
        assertTrue(out.contains("flagged"), "message must mention flagging");
    }

    /**
     * When enriched orphan count is exactly at the threshold, the prune step must proceed.
     */
    @Test
    void flaggingGuard_allowsPruneAtThreshold() throws Exception {
        int total = 1000;
        int threshold = com.organizer3.repository.jdbi.JdbiTitleRepository.orphanFlagThreshold(total); // 100
        var orphanRef = new TitleRepository.OrphanedTitleRef("ABP", "ABP-00001");

        when(titleRepo.findOrphanedTitles()).thenReturn(List.of(orphanRef));
        when(titleRepo.countAll()).thenReturn(total);
        when(titleRepo.countOrphansWithEnrichment()).thenReturn(threshold); // exactly at threshold
        when(titleRepo.deleteOrphaned()).thenReturn(
                new TitleRepository.OrphanPruneResult(1, 0));

        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        when(fs.exists(any())).thenReturn(false);

        newOp().execute(CONVENTIONAL_VOLUME, emptyStructure(), fs, new SessionContext(), io);

        verify(titleRepo, atLeastOnce()).deleteOrphaned();
    }

    /**
     * When there are no orphans at all, the flagging guard is not consulted and
     * countOrphansWithEnrichment() is never called — no unnecessary DB query.
     */
    @Test
    void flaggingGuard_skippedWhenNoOrphans() throws Exception {
        when(titleRepo.findOrphanedTitles()).thenReturn(List.of());

        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        when(fs.exists(any())).thenReturn(false);

        newOp().execute(CONVENTIONAL_VOLUME, emptyStructure(), fs, new SessionContext(), io);

        verify(titleRepo, never()).countOrphansWithEnrichment();
        verify(titleRepo, never()).deleteOrphaned();
    }
}
