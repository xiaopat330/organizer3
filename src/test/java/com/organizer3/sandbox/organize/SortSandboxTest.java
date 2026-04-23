package com.organizer3.sandbox.organize;

import com.organizer3.config.volume.LibraryConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.organize.AttentionRouter;
import com.organizer3.organize.TitleSorterService;
import com.organizer3.organize.TitleTimestampService;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.sandbox.SandboxTestBase;
import com.organizer3.sandbox.SmbRebasingFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link TitleSorterService} against the real NAS.
 *
 * <p>Uses {@link SmbRebasingFS} to redirect absolute sort paths
 * ({@code /stars/library/...}, {@code /queue/...}, {@code /attention/...}) through
 * {@code methodRunDir} so nothing touches the real library structure.
 *
 * <p>SMB-differentiating: validates that the DB transaction + FS move are actually
 * committed together on a real share — the unit tests use a local tempdir where
 * rename is trivial; here the move must succeed over SMB.
 */
class SortSandboxTest extends SandboxTestBase {

    JdbiActressRepository actressRepo;
    JdbiTitleRepository titleRepo;
    JdbiTitleActressRepository titleActressRepo;
    JdbiTitleLocationRepository locationRepo;
    TitleSorterService svc;
    SmbRebasingFS rebasingFs;
    AttentionRouter attentionRouter;

    @BeforeEach
    void setUpService() {
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);

        rebasingFs = new SmbRebasingFS(fs, methodRunDir);
        Clock fixed = Clock.fixed(Instant.parse("2026-04-23T00:00:00Z"), ZoneOffset.UTC);
        attentionRouter = new AttentionRouter(rebasingFs, testVolume.id(), fixed);
        svc = new TitleSorterService(titleRepo, actressRepo, titleActressRepo, locationRepo,
                LibraryConfig.DEFAULTS, new TitleTimestampService());
    }

    @Test
    void happyPath_titlesToLibraryTier_folderMovedAndDbUpdated() throws Exception {
        // Seed actress with 3 titles — exactly at the star=3 threshold → "library" tier
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        titleRepo.save(mkTitle("ABP-002", aid));
        titleRepo.save(mkTitle("ABP-003", aid));

        // Create the source folder on the NAS via the rebasing FS
        rebasingFs.createDirectories(Path.of("/queue/Ai Haneda (ABP-001)"));
        rebasingFs.writeFile(Path.of("/queue/Ai Haneda (ABP-001)/abp001pl.jpg"), new byte[]{1});
        saveLocation(t.getId(), "/queue/Ai Haneda (ABP-001)", "queue");

        TitleSorterService.Result result = svc.sort(rebasingFs, volumeNoLetters(), attentionRouter, jdbi, "ABP-001", false);

        assertEquals(TitleSorterService.Outcome.SORTED, result.outcome(),
                "Expected SORTED but got " + result.outcome() + ": " + result.reason());

        // Verify folder moved on the real NAS
        assertTrue(rebasingFs.exists(Path.of("/stars/library/Ai Haneda/Ai Haneda (ABP-001)")),
                "Folder should exist at stars/library/Ai Haneda/");
        assertFalse(rebasingFs.exists(Path.of("/queue/Ai Haneda (ABP-001)")),
                "Folder should no longer be in queue");

        // Verify DB updated
        TitleLocation updated = locationRepo.findByTitle(t.getId()).get(0);
        assertEquals("/stars/library/Ai Haneda/Ai Haneda (ABP-001)", updated.getPath().toString());
        assertEquals("library", updated.getPartitionId());
    }

    @Test
    void actresslessTitle_routedToAttention() throws Exception {
        Title t = titleRepo.save(mkTitle("ABP-999", null));  // no actress
        rebasingFs.createDirectories(Path.of("/queue/ABP-999"));
        rebasingFs.writeFile(Path.of("/queue/ABP-999/abp999pl.jpg"), new byte[]{1});
        saveLocation(t.getId(), "/queue/ABP-999", "queue");

        TitleSorterService.Result result = svc.sort(rebasingFs, volumeNoLetters(), attentionRouter, jdbi, "ABP-999", false);

        assertEquals(TitleSorterService.Outcome.ROUTED_TO_ATTENTION, result.outcome());
        assertTrue(rebasingFs.exists(Path.of("/attention/ABP-999")),
                "Folder should be in attention/");
        assertFalse(rebasingFs.exists(Path.of("/queue/ABP-999")),
                "Folder should no longer be in queue");
    }

    @Test
    void belowStarThreshold_skipped() throws Exception {
        // Only 2 titles — below star=3 in DEFAULTS → SKIPPED (stays in queue)
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        titleRepo.save(mkTitle("ABP-002", aid));

        rebasingFs.createDirectories(Path.of("/queue/Ai Haneda (ABP-001)"));
        saveLocation(t.getId(), "/queue/Ai Haneda (ABP-001)", "queue");

        TitleSorterService.Result result = svc.sort(rebasingFs, volumeNoLetters(), attentionRouter, jdbi, "ABP-001", false);

        assertEquals(TitleSorterService.Outcome.SKIPPED, result.outcome());
        assertTrue(result.reason().contains("below star threshold"),
                "Reason should mention threshold, got: " + result.reason());
        assertTrue(rebasingFs.exists(Path.of("/queue/Ai Haneda (ABP-001)")),
                "Folder should remain in queue when skipped");
    }

    @Test
    void dryRun_returnsWouldSort_noSideEffects() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        titleRepo.save(mkTitle("ABP-002", aid));
        titleRepo.save(mkTitle("ABP-003", aid));

        rebasingFs.createDirectories(Path.of("/queue/Ai Haneda (ABP-001)"));
        rebasingFs.writeFile(Path.of("/queue/Ai Haneda (ABP-001)/abp001pl.jpg"), new byte[]{1});
        saveLocation(t.getId(), "/queue/Ai Haneda (ABP-001)", "queue");

        TitleSorterService.Result result = svc.sort(rebasingFs, volumeNoLetters(), attentionRouter, jdbi, "ABP-001", true);

        assertEquals(TitleSorterService.Outcome.WOULD_SORT, result.outcome());
        assertTrue(rebasingFs.exists(Path.of("/queue/Ai Haneda (ABP-001)")),
                "Dry run must not move the folder");
        assertFalse(rebasingFs.exists(Path.of("/stars/library/Ai Haneda/Ai Haneda (ABP-001)")),
                "Dry run must not create destination");

        // DB must remain unchanged
        TitleLocation loc = locationRepo.findByTitle(t.getId()).get(0);
        assertEquals("queue", loc.getPartitionId(), "DB partition must remain 'queue' after dry run");
    }

    @Test
    void collision_routedToAttention() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        titleRepo.save(mkTitle("ABP-002", aid));
        titleRepo.save(mkTitle("ABP-003", aid));

        rebasingFs.createDirectories(Path.of("/queue/Ai Haneda (ABP-001)"));
        saveLocation(t.getId(), "/queue/Ai Haneda (ABP-001)", "queue");

        // Pre-create the sort destination to force a collision
        rebasingFs.createDirectories(Path.of("/stars/library/Ai Haneda/Ai Haneda (ABP-001)"));

        TitleSorterService.Result result = svc.sort(rebasingFs, volumeNoLetters(), attentionRouter, jdbi, "ABP-001", false);

        assertEquals(TitleSorterService.Outcome.ROUTED_TO_ATTENTION, result.outcome(),
                "Collision should route to attention");
        assertTrue(rebasingFs.exists(Path.of("/attention/Ai Haneda (ABP-001)")),
                "Folder should be in attention/ after collision");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** VolumeConfig with no letter restriction — any actress name passes coversName(). */
    private VolumeConfig volumeNoLetters() {
        return new VolumeConfig(testVolume.id(), testVolume.smbPath(), testVolume.structureType(),
                testVolume.server(), testVolume.group(), null);
    }

    private static Actress mkActress(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title mkTitle(String code, Long actressId) {
        return Title.builder()
                .code(code)
                .baseCode(code.split("-")[0])
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private void saveLocation(long titleId, String volumePath, String partition) {
        locationRepo.save(TitleLocation.builder()
                .titleId(titleId)
                .volumeId(testVolume.id())
                .partitionId(partition)
                .path(Path.of(volumePath))
                .lastSeenAt(LocalDate.of(2024, 1, 2))
                .addedDate(LocalDate.of(2024, 1, 2))
                .build());
    }
}
