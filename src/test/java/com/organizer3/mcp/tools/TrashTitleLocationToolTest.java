package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrashTitleLocationToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiVideoRepository videoRepo;
    private SessionContext session;
    private InMemoryFS fs;
    private TrashTitleLocationTool tool;
    private CurationLog curationLog;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));

        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);
        videoRepo    = new JdbiVideoRepository(jdbi);
        curationLog  = new CurationLog(tempDir);

        fs = new InMemoryFS();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null));
        session.setActiveConnection(new FakeConnection(fs));

        OrganizerConfig config = makeConfig("pandora", "_trash");
        Clock fixed = Clock.fixed(Instant.parse("2026-04-19T10:00:00Z"), ZoneOffset.UTC);
        tool = new TrashTitleLocationTool(session, jdbi, config, videoRepo, curationLog, fixed);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── 1. dryRun returns plan without trashing/deleting ─────────────────────

    @Test
    void dryRunReturnsPlanWithoutTrashing() {
        long tid = titleRepo.save(title("MIDE-123")).getId();
        locationRepo.save(location(tid, "a", "/queue/MIDE-123"));
        Video v = videoRepo.save(video(tid, "mide123.mkv", "/queue/MIDE-123/video/mide123.mkv"));
        fs.file(v.getPath().toString());

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/MIDE-123", true));
        assertEquals("dry-run", r.status());
        assertTrue(r.dryRun());
        assertEquals(1, r.plannedVideos().size());
        assertTrue(r.trashed().isEmpty());
        // file untouched
        assertTrue(fs.exists(v.getPath()));
        // DB row still there
        assertEquals(1, locationCount(tid));
    }

    // ── 2. Live run trashes videos, deletes folder, drops title_location row ──

    @Test
    void liveRunTrashesVideosAndDropsLocationRow() {
        long tid = titleRepo.save(title("PRED-001")).getId();
        locationRepo.save(location(tid, "a", "/queue/PRED-001"));
        Video v = videoRepo.save(video(tid, "pred001.mkv", "/queue/PRED-001/video/pred001.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/PRED-001");
        fs.dir("/queue/PRED-001/video");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/PRED-001", false));
        assertEquals("ok", r.status());
        assertFalse(r.dryRun());
        assertEquals(1, r.trashed().size());
        assertTrue(r.failed().isEmpty());

        // file moved to trash
        assertFalse(fs.exists(v.getPath()), "original video removed");
        assertTrue(fs.exists(Path.of("/_trash/queue/PRED-001/video/pred001.mkv")), "video in trash");

        // DB: video row deleted, location row deleted
        assertTrue(videoRepo.findById(v.getId()).isEmpty(), "video DB row deleted");
        assertEquals(0, locationCount(tid));
    }

    // ── 3. Refuses when no matching title_location ────────────────────────────

    @Test
    void refusesWhenNoMatchingLocation() {
        titleRepo.save(title("MIDE-456")).getId();
        // no location row inserted

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/MIDE-456", false));
        assertEquals("refused", r.status());
        assertNotNull(r.error());
        assertTrue(r.error().contains("no active title_location"));
    }

    // ── 4. Refuses when non-noise files remain after trashing videos ──────────

    @Test
    void refusesWhenNonNoiseFilesRemain() {
        long tid = titleRepo.save(title("SKY-001")).getId();
        locationRepo.save(location(tid, "a", "/queue/SKY-001"));
        // No videos in DB, so none will be trashed
        // But folder has a non-noise file
        fs.file("/queue/SKY-001/cover-high-res.mkv");
        fs.dir("/queue/SKY-001");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/SKY-001", false));
        assertEquals("partial", r.status());
        assertNotNull(r.error());
        assertTrue(r.error().contains("non-noise files remain"));
    }

    // ── 5. Cleans up noise/sidecars before deleting folder ───────────────────

    @Test
    void cleansUpNoiseFilesBeforeDeletingFolder() {
        long tid = titleRepo.save(title("STAR-001")).getId();
        locationRepo.save(location(tid, "a", "/queue/STAR-001"));
        // Only noise files remain (no videos)
        fs.file("/queue/STAR-001/Thumbs.db");
        fs.file("/queue/STAR-001/.DS_Store");
        fs.dir("/queue/STAR-001");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/STAR-001", false));
        assertEquals("ok", r.status());
        // noise files deleted
        assertFalse(fs.exists(Path.of("/queue/STAR-001/Thumbs.db")));
        assertFalse(fs.exists(Path.of("/queue/STAR-001/.DS_Store")));
        // location row dropped
        assertEquals(0, locationCount(tid));
    }

    // ── 6. Refuses disallowed prefix ─────────────────────────────────────────

    @Test
    void refusesDisallowedPrefix() {
        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/unknown_tier/SomeName (LABEL-001)", false));
        assertEquals("refused", r.status());
        assertTrue(r.error().contains("not allowed"));
    }

    // ── 6b. Extended allowlist: new/duos/__later are accepted ────────────────

    @Test
    void allowsNewPrefix() {
        long tid = titleRepo.save(title("NEW-001")).getId();
        locationRepo.save(location(tid, "a", "/new/Foo (NEW-001)"));
        fs.dir("/new/Foo (NEW-001)");
        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/new/Foo (NEW-001)", false));
        assertEquals("ok", r.status(), "expected /new/... to be accepted; error=" + r.error());
    }

    @Test
    void allowsDuosPrefix() {
        long tid = titleRepo.save(title("DUO-001")).getId();
        locationRepo.save(location(tid, "a", "/duos/Foo (DUO-001)"));
        fs.dir("/duos/Foo (DUO-001)");
        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/duos/Foo (DUO-001)", false));
        assertEquals("ok", r.status(), "expected /duos/... to be accepted; error=" + r.error());
    }

    @Test
    void allowsLaterPrefix() {
        long tid = titleRepo.save(title("LATE-001")).getId();
        locationRepo.save(location(tid, "a", "/__later/Foo (LATE-001)"));
        fs.dir("/__later/Foo (LATE-001)");
        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/__later/Foo (LATE-001)", false));
        assertEquals("ok", r.status(), "expected /__later/... to be accepted; error=" + r.error());
    }

    @Test
    void refusesTrashPrefix() {
        // Sanity check: trashing inside /_trash must remain refused (not in allowlist).
        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/_trash/anything", false));
        assertEquals("refused", r.status());
        assertTrue(r.error().contains("not allowed"),
                "expected 'not allowed' refusal; got: " + r.error());
    }

    // ── 7. Refuses protected tier root ───────────────────────────────────────

    @Test
    void refusesProtectedTierRoot() {
        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue", false));
        assertEquals("refused", r.status());
        assertTrue(r.error().contains("protected tier root"));
    }

    // ── 8. Refuses path traversal ─────────────────────────────────────────────

    @Test
    void refusesPathTraversal() {
        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/../attention/Name", false));
        assertEquals("refused", r.status());
        assertTrue(r.error().contains(".."));
    }

    // ── 9. Refuses volume mismatch ────────────────────────────────────────────

    @Test
    void refusesVolumeMismatch() {
        var r = (TrashTitleLocationTool.Result) tool.call(args("other_vol", "/queue/Name (LAB-001)", false));
        assertEquals("refused", r.status());
        assertTrue(r.error().contains("mismatch"));
    }

    // ── 10. Surfaces orphaned title when last location goes ───────────────────

    @Test
    void surfacesOrphanedTitleWhenLastLocationGone() {
        long tid = titleRepo.save(title("ABC-001")).getId();
        locationRepo.save(location(tid, "a", "/queue/ABC-001"));
        Video v = videoRepo.save(video(tid, "abc001.mkv", "/queue/ABC-001/video/abc001.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/ABC-001");
        fs.dir("/queue/ABC-001/video");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/ABC-001", false));
        assertEquals("ok", r.status());
        assertTrue(r.titleOrphaned(), "title should be reported as orphaned");
    }

    @Test
    void doesNotSurfaceOrphanedWhenOtherLocationsRemain() {
        long tid = titleRepo.save(title("ABC-002")).getId();
        // Two locations — we trash one
        locationRepo.save(location(tid, "a", "/queue/ABC-002"));
        // Simulate a second location row (different path) via raw SQL
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (:tid, 'a', 'default', '/stars/a/ABC-002', '2026-01-01')")
                .bind("tid", tid).execute());
        Video v = videoRepo.save(video(tid, "abc002.mkv", "/queue/ABC-002/video/abc002.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/ABC-002");
        fs.dir("/queue/ABC-002/video");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/ABC-002", false));
        assertEquals("ok", r.status());
        assertFalse(r.titleOrphaned(), "title still has another location");
    }

    // ── 11. Orphan actress detection (flag only, no cascade) ──────────────────

    @Test
    void trashOnlyLocation_orphansSingleActress_flagOnly() {
        long tid = titleRepo.save(title("ORF-001")).getId();
        locationRepo.save(location(tid, "a", "/queue/ORF-001"));
        Video v = videoRepo.save(video(tid, "orf001.mkv", "/queue/ORF-001/video/orf001.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/ORF-001"); fs.dir("/queue/ORF-001/video");

        long aId = seedActress("Yui Takshiro");
        seedAlias(aId, "TakshiroYui");
        linkActress(tid, aId);

        var r = (TrashTitleLocationTool.Result) tool.call(argsWithCascade("a", "/queue/ORF-001", false, false));
        assertEquals("ok", r.status());
        assertEquals(1, r.orphanedActresses().size(), "actress reported as orphaned");
        assertEquals(aId, r.orphanedActresses().get(0).id());
        assertEquals("Yui Takshiro", r.orphanedActresses().get(0).canonicalName());
        assertEquals(0, r.cascadedActresses(), "no cascade fired");

        // actress + alias rows still in DB
        assertEquals(1, countWhere("actresses", "id", aId));
        assertEquals(1, countWhere("actress_aliases", "actress_id", aId));
    }

    // ── 12. Orphan actress detection (cascade=true) ───────────────────────────

    @Test
    void trashOnlyLocation_orphansSingleActress_cascade() {
        long tid = titleRepo.save(title("ORF-002")).getId();
        locationRepo.save(location(tid, "a", "/queue/ORF-002"));
        Video v = videoRepo.save(video(tid, "orf002.mkv", "/queue/ORF-002/video/orf002.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/ORF-002"); fs.dir("/queue/ORF-002/video");

        long aId = seedActress("Ghost Star");
        seedAlias(aId, "GhostStarAlias");
        seedCompany(aId, "GhostCorp");
        linkActress(tid, aId);

        var r = (TrashTitleLocationTool.Result) tool.call(argsWithCascade("a", "/queue/ORF-002", false, true));
        assertEquals("ok", r.status());
        assertEquals(1, r.orphanedActresses().size());
        assertEquals(1, r.cascadedActresses());

        // all rows gone
        assertEquals(0, countWhere("actresses", "id", aId));
        assertEquals(0, countWhere("actress_aliases", "actress_id", aId));
        assertEquals(0, countWhere("actress_companies", "actress_id", aId));
        assertEquals(0, countWhere("title_actresses", "actress_id", aId));
    }

    // ── 13. Other locations exist → no orphan ─────────────────────────────────

    @Test
    void trashOneLocation_otherLocationsExist_noOrphan() {
        long tid = titleRepo.save(title("ORF-003")).getId();
        locationRepo.save(location(tid, "a", "/queue/ORF-003"));
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (:tid, 'a', 'default', '/stars/a/ORF-003', '2026-01-01')")
                .bind("tid", tid).execute());
        Video v = videoRepo.save(video(tid, "orf003.mkv", "/queue/ORF-003/video/orf003.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/ORF-003"); fs.dir("/queue/ORF-003/video");

        long aId = seedActress("Multi Loc");
        linkActress(tid, aId);

        var r = (TrashTitleLocationTool.Result) tool.call(argsWithCascade("a", "/queue/ORF-003", false, true));
        assertEquals("ok", r.status());
        assertTrue(r.orphanedActresses().isEmpty());
        assertEquals(0, r.cascadedActresses());
        assertEquals(1, countWhere("actresses", "id", aId));
    }

    // ── 14. Actress has other titles with surviving locations → no orphan ────

    @Test
    void trashLocation_actressHasOtherTitles_noOrphan() {
        long tA = titleRepo.save(title("ORF-004")).getId();
        long tB = titleRepo.save(title("ORF-005")).getId();
        locationRepo.save(location(tA, "a", "/queue/ORF-004"));
        locationRepo.save(location(tB, "a", "/queue/ORF-005"));
        Video v = videoRepo.save(video(tA, "orf004.mkv", "/queue/ORF-004/video/orf004.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/ORF-004"); fs.dir("/queue/ORF-004/video");

        long aId = seedActress("Two Titles");
        linkActress(tA, aId);
        linkActress(tB, aId);

        var r = (TrashTitleLocationTool.Result) tool.call(argsWithCascade("a", "/queue/ORF-004", false, true));
        assertEquals("ok", r.status());
        assertTrue(r.orphanedActresses().isEmpty(), "actress still has title B with surviving location");
        assertEquals(1, countWhere("actresses", "id", aId));
        // ghost link to A is retained — that's fine
        assertEquals(2, countTitleActresses(aId));
    }

    // ── 15. Mixed: some actresses orphaned, others not ────────────────────────

    @Test
    void trashLocation_multipleActresses_someOrphanedSomeNot() {
        long tA = titleRepo.save(title("ORF-006")).getId();
        long tB = titleRepo.save(title("ORF-007")).getId();
        locationRepo.save(location(tA, "a", "/queue/ORF-006"));
        locationRepo.save(location(tB, "a", "/queue/ORF-007"));
        Video v = videoRepo.save(video(tA, "orf006.mkv", "/queue/ORF-006/video/orf006.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/ORF-006"); fs.dir("/queue/ORF-006/video");

        long aX = seedActress("Only On A");
        long aY = seedActress("On A And B");
        linkActress(tA, aX);
        linkActress(tA, aY);
        linkActress(tB, aY);

        var r = (TrashTitleLocationTool.Result) tool.call(argsWithCascade("a", "/queue/ORF-006", false, true));
        assertEquals("ok", r.status());
        assertEquals(1, r.orphanedActresses().size());
        assertEquals(aX, r.orphanedActresses().get(0).id());
        assertEquals(1, r.cascadedActresses());

        // X gone, Y survives
        assertEquals(0, countWhere("actresses", "id", aX));
        assertEquals(1, countWhere("actresses", "id", aY));
    }

    // ── 16. Cascade does not touch unrelated actresses ────────────────────────

    @Test
    void cascadePreservesAliasesAndCompaniesForSurvivingActresses() {
        long tA = titleRepo.save(title("ORF-008")).getId();
        long tB = titleRepo.save(title("ORF-009")).getId();
        locationRepo.save(location(tA, "a", "/queue/ORF-008"));
        locationRepo.save(location(tB, "a", "/queue/ORF-009"));
        Video v = videoRepo.save(video(tA, "orf008.mkv", "/queue/ORF-008/video/orf008.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/ORF-008"); fs.dir("/queue/ORF-008/video");

        long orphan   = seedActress("To Be Cascaded");
        long survivor = seedActress("Untouched Star");
        seedAlias(orphan, "OrphanAlias");
        seedAlias(survivor, "SurvivorAlias");
        seedCompany(survivor, "SurvivorCorp");
        linkActress(tA, orphan);
        linkActress(tB, survivor);

        var r = (TrashTitleLocationTool.Result) tool.call(argsWithCascade("a", "/queue/ORF-008", false, true));
        assertEquals("ok", r.status());
        assertEquals(1, r.cascadedActresses());

        // Survivor & its dependents untouched
        assertEquals(1, countWhere("actresses", "id", survivor));
        assertEquals(1, countWhere("actress_aliases", "actress_id", survivor));
        assertEquals(1, countWhere("actress_companies", "actress_id", survivor));
        assertEquals(1, countTitleActresses(survivor));
    }

    // ── Fix #3: root-level path normalization ────────────────────────────────

    /**
     * Pool volumes store title folders at volume root (e.g. /Various (JUR-031)).
     * Path.of("/Various (JUR-031)").getName(0) returns "Various (JUR-031)" — not a prefix —
     * so the old prefix check would refuse it. The fix bypasses the allowlist for single-segment
     * paths (the folder IS its own root).
     */
    @Test
    void fix3_rootLevelLocationTrashesSuccessfully() {
        long tid = titleRepo.save(title("JUR-031")).getId();
        locationRepo.save(location(tid, "a", "/Various (JUR-031)"));
        fs.dir("/Various (JUR-031)");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/Various (JUR-031)", false));
        assertEquals("ok", r.status(), "root-level pool path should be trashable; error=" + r.error());
        assertEquals(0, locationCount(tid), "location row should be dropped");
    }

    @Test
    void fix3_rootLevelWithVideoTrashesSuccessfully() {
        long tid = titleRepo.save(title("MIAB-088")).getId();
        locationRepo.save(location(tid, "a", "/Hikaru Minazuki (MIAB-088)"));
        Video v = videoRepo.save(video(tid, "miab088.mkv", "/Hikaru Minazuki (MIAB-088)/video/miab088.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/Hikaru Minazuki (MIAB-088)");
        fs.dir("/Hikaru Minazuki (MIAB-088)/video");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/Hikaru Minazuki (MIAB-088)", false));
        assertEquals("ok", r.status(), "root-level path with video should trash cleanly; error=" + r.error());
        assertEquals(1, r.trashed().size());
    }

    /**
     * Fix #3 false-positive guard: a 2-segment path with a non-allowlisted prefix must
     * still be refused. The relaxation applies only to single-segment (depth-1) paths.
     */
    @Test
    void fix3_deepPathWithNonAllowedPrefixIsStillRefused() {
        var r = (TrashTitleLocationTool.Result) tool.call(
                args("a", "/random_tier/Foo (LAB-001)", false));
        assertEquals("refused", r.status(),
                "deep path under non-allowlisted prefix must still be refused");
        assertTrue(r.error().contains("not allowed"));
    }

    // ── Fix #4: force flag for unregistered videos ───────────────────────────

    /**
     * When force=false (default) and a video file is not in the videos table, the tool
     * returns partial — preserving the current safety behavior.
     */
    @Test
    void fix4_unregisteredVideoBlocksTrashByDefault() {
        long tid = titleRepo.save(title("DLDSS-137")).getId();
        locationRepo.save(location(tid, "a", "/queue/DLDSS-137"));
        // file exists on disk but is NOT registered in videos table
        fs.file("/queue/DLDSS-137/dldss137.mp4");
        fs.dir("/queue/DLDSS-137");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/DLDSS-137", false));
        assertEquals("partial", r.status(), "unregistered video without force should return partial");
        assertTrue(r.error().contains("non-noise files remain"), "error should name the blocking file");
        // file still present
        assertTrue(fs.exists(Path.of("/queue/DLDSS-137/dldss137.mp4")), "unregistered video untouched");
    }

    /**
     * When force=true, an unregistered video (no DB row) is trashed by extension and the
     * folder is fully cleaned up.
     */
    @Test
    void fix4_forceTrashes_unregisteredVideo() {
        long tid = titleRepo.save(title("DLDSS-138")).getId();
        locationRepo.save(location(tid, "a", "/queue/DLDSS-138"));
        // unregistered video on disk
        fs.file("/queue/DLDSS-138/dldss138.mp4");
        fs.dir("/queue/DLDSS-138");

        ObjectNode argsNode = args("a", "/queue/DLDSS-138", false);
        argsNode.put("force", true);
        var r = (TrashTitleLocationTool.Result) tool.call(argsNode);

        assertEquals("ok", r.status(), "force=true should trash unregistered video; error=" + r.error());
        assertFalse(fs.exists(Path.of("/queue/DLDSS-138/dldss138.mp4")), "file should be moved to trash");
        assertTrue(fs.exists(Path.of("/_trash/queue/DLDSS-138/dldss138.mp4")), "file should appear in trash");
        assertEquals(0, locationCount(tid), "location row dropped");
    }

    /**
     * force=true should trash both a registered video (via folderService.trashVideo) and an
     * unregistered one (via force path), leaving the folder fully clean.
     */
    @Test
    void fix4_forceTrashes_mixedRegisteredAndUnregisteredVideos() {
        long tid = titleRepo.save(title("DLDSS-139")).getId();
        locationRepo.save(location(tid, "a", "/queue/DLDSS-139"));
        Video registered = videoRepo.save(video(tid, "dldss139.mkv", "/queue/DLDSS-139/video/dldss139.mkv"));
        fs.file(registered.getPath().toString());
        fs.dir("/queue/DLDSS-139");
        fs.dir("/queue/DLDSS-139/video");
        // unregistered extra video
        fs.file("/queue/DLDSS-139/dldss139_bonus.mp4");

        ObjectNode argsNode = args("a", "/queue/DLDSS-139", false);
        argsNode.put("force", true);
        var r = (TrashTitleLocationTool.Result) tool.call(argsNode);

        assertEquals("ok", r.status(), "force=true with mixed videos should succeed; error=" + r.error());
        assertFalse(fs.exists(registered.getPath()), "registered video removed");
        assertFalse(fs.exists(Path.of("/queue/DLDSS-139/dldss139_bonus.mp4")), "unregistered video removed");
    }

    // ── Fix #7: Thumbs.db sidecar regression test ───────────────────────────

    /**
     * Regression test: Thumbs.db (mixed case, Windows-origin) must be treated as noise and
     * deleted during the cleanup pass. The NOISE_NAMES check uses toLowerCase(), so both
     * "Thumbs.db" and "thumbs.db" match. This test guards against regressions that would
     * leave the file behind and cause a partial result.
     */
    @Test
    void fix7_thumbsDbIsDeletedAsNoise() {
        long tid = titleRepo.save(title("CLASSIC-001")).getId();
        locationRepo.save(location(tid, "a", "/queue/CLASSIC-001"));
        // Registered video + Thumbs.db sidecar (common on Windows-written volumes)
        Video v = videoRepo.save(video(tid, "classic001.mkv", "/queue/CLASSIC-001/video/classic001.mkv"));
        fs.file(v.getPath().toString());
        fs.file("/queue/CLASSIC-001/Thumbs.db");
        fs.dir("/queue/CLASSIC-001");
        fs.dir("/queue/CLASSIC-001/video");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/CLASSIC-001", false));
        assertEquals("ok", r.status(), "Thumbs.db should be treated as noise; error=" + r.error());
        assertFalse(fs.exists(Path.of("/queue/CLASSIC-001/Thumbs.db")), "Thumbs.db should be deleted");
        assertEquals(0, locationCount(tid), "location row dropped");
    }

    @Test
    void fix7_thumbsDbLowercaseIsDeletedAsNoise() {
        long tid = titleRepo.save(title("CLASSIC-002")).getId();
        locationRepo.save(location(tid, "a", "/queue/CLASSIC-002"));
        fs.file("/queue/CLASSIC-002/thumbs.db");
        fs.dir("/queue/CLASSIC-002");

        var r = (TrashTitleLocationTool.Result) tool.call(args("a", "/queue/CLASSIC-002", false));
        assertEquals("ok", r.status(), "thumbs.db (lowercase) should be treated as noise; error=" + r.error());
        assertFalse(fs.exists(Path.of("/queue/CLASSIC-002/thumbs.db")));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static Title title(String code) {
        return Title.builder().code(code)
                .baseCode(code.toUpperCase())
                .label(code.split("-")[0]).seqNum(1).build();
    }

    private static TitleLocation location(long titleId, String volumeId, String path) {
        return TitleLocation.builder()
                .titleId(titleId)
                .volumeId(volumeId)
                .partitionId("default")
                .path(Path.of(path))
                .lastSeenAt(LocalDate.now())
                .build();
    }

    private static Video video(long titleId, String filename, String path) {
        return Video.builder()
                .titleId(titleId).volumeId("a")
                .filename(filename).path(Path.of(path))
                .sizeBytes(500_000_000L)
                .lastSeenAt(LocalDate.now()).build();
    }

    private static OrganizerConfig makeConfig(String serverId, String trashFolder) {
        ServerConfig srv = new ServerConfig(serverId, "u", "p", null, trashFolder, null);
        VolumeConfig vol = new VolumeConfig("a", "//pandora/jav_A", "conventional", serverId, null);
        return new OrganizerConfig(
                "Test", "./data", 500, 500, 500, 8, 5, 47,
                List.of(srv), List.of(vol), List.of(), List.of(), null);
    }

    private static ObjectNode args(String volumeId, String path, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        n.put("path",     path);
        n.put("dryRun",   dryRun);
        return n;
    }

    private static ObjectNode argsWithCascade(String volumeId, String path, boolean dryRun, boolean cascade) {
        ObjectNode n = args(volumeId, path, dryRun);
        n.put("cascadeOrphanActresses", cascade);
        return n;
    }

    private long seedActress(String canonical) {
        return jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO actresses (canonical_name, tier, first_seen_at, is_sentinel)
                VALUES (?, 'LIBRARY', '2024-01-01', 0)
                """)
                .bind(0, canonical)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void seedAlias(long actressId, String alias) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO actress_aliases (actress_id, alias_name) VALUES (?, ?)")
                .bind(0, actressId).bind(1, alias).execute());
    }

    private void seedCompany(long actressId, String company) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO actress_companies (actress_id, company) VALUES (?, ?)")
                .bind(0, actressId).bind(1, company).execute());
    }

    private void linkActress(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)")
                .bind(0, titleId).bind(1, actressId).execute());
    }

    private int countWhere(String table, String col, long value) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + col + " = :v")
                .bind("v", value).mapTo(Integer.class).one());
    }

    private int countTitleActresses(long actressId) {
        return countWhere("title_actresses", "actress_id", actressId);
    }

    private int locationCount(long titleId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM title_locations WHERE title_id = :tid")
                .bind("tid", titleId)
                .mapTo(Integer.class)
                .one());
    }

    // ── in-memory helpers ─────────────────────────────────────────────────────

    private static final class FakeConnection implements VolumeConnection {
        private final com.organizer3.filesystem.VolumeFileSystem fs;
        FakeConnection(com.organizer3.filesystem.VolumeFileSystem fs) { this.fs = fs; }
        @Override public com.organizer3.filesystem.VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }

    static final class InMemoryFS implements com.organizer3.filesystem.VolumeFileSystem {
        private final Map<Path, Boolean> nodes    = new HashMap<>();
        private final Map<Path, byte[]>  contents = new HashMap<>();

        void file(String p) {
            Path path = Path.of(p);
            nodes.put(path, false);
            Path parent = path.getParent();
            while (parent != null) { nodes.put(parent, true); parent = parent.getParent(); }
        }

        void dir(String p) {
            nodes.put(Path.of(p), true);
        }

        @Override public List<Path> listDirectory(Path path) {
            List<Path> out = new ArrayList<>();
            for (Path p : nodes.keySet()) {
                Path pp = p.getParent();
                if (pp != null && pp.equals(path)) out.add(p);
            }
            return out;
        }
        @Override public List<Path> walk(Path root) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)       { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path)  { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public long size(Path path)            throws IOException { throw new IOException("n/a"); }
        @Override public void move(Path source, Path destination) {
            Boolean kind = nodes.remove(source);
            if (kind != null) nodes.put(destination, kind);
            byte[] body = contents.remove(source);
            if (body != null) contents.put(destination, body);
        }
        @Override public void rename(Path path, String newName) { move(path, path.resolveSibling(newName)); }
        @Override public void createDirectories(Path path) {
            Path cur = path;
            while (cur != null && !cur.equals(Path.of("/"))) {
                nodes.put(cur, true);
                cur = cur.getParent();
            }
        }
        @Override public void writeFile(Path path, byte[] body) {
            nodes.put(path, false);
            contents.put(path, body);
        }
        @Override public com.organizer3.filesystem.FileTimestamps getTimestamps(Path p) {
            return new com.organizer3.filesystem.FileTimestamps(null, null, null);
        }
        @Override public void setTimestamps(Path p, java.time.Instant c, java.time.Instant m) {}
        @Override public void delete(Path path) throws IOException {
            nodes.remove(path);
            contents.remove(path);
        }
    }
}
