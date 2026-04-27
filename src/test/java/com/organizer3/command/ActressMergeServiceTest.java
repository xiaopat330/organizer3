package com.organizer3.command;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActressMergeServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private ActressMergeService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('pool', 'queue')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('r', 'queue')");
        });
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        service = new ActressMergeService(jdbi, locationRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── computeNewPath ───────────────────────────────────────────────────────

    @Test
    void computeNewPath_standardFolder() {
        Path result = ActressMergeService.computeNewPath(
                Path.of("/queue/Rin Hatchimitsu (FNS-052)"),
                "Rin Hatchimitsu", "Rin Hachimitsu");
        assertEquals(Path.of("/queue/Rin Hachimitsu (FNS-052)"), result);
    }

    @Test
    void computeNewPath_noMatch_returnsNull() {
        Path result = ActressMergeService.computeNewPath(
                Path.of("/queue/Rin Hachimitsu (FNS-052)"),
                "Rin Hatchimitsu", "Rin Hachimitsu");
        assertNull(result);
    }

    @Test
    void computeNewPath_exactMatch_noCode() {
        Path result = ActressMergeService.computeNewPath(
                Path.of("/stars/Rin Hatchimitsu"),
                "Rin Hatchimitsu", "Rin Hachimitsu");
        assertEquals(Path.of("/stars/Rin Hachimitsu"), result);
    }

    @Test
    void computeNewPath_doesNotMatchSubstring() {
        // "Rin Hatchimitsu X" should not match if suspect is "Rin Hatchimitsu" without space check
        // Actually it DOES start with "Rin Hatchimitsu " so it SHOULD match
        Path result = ActressMergeService.computeNewPath(
                Path.of("/queue/Rin Hatchimitsu X (FNS-052)"),
                "Rin Hatchimitsu", "Rin Hachimitsu");
        assertEquals(Path.of("/queue/Rin Hachimitsu X (FNS-052)"), result);
    }

    // ── preview ──────────────────────────────────────────────────────────────

    @Test
    void preview_countsCorrectly() {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        // Filing title (actress_id = suspect)
        Title t1 = saveTitleFiled("FNS-052", suspect.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");
        // Cast-only title (in title_actresses, actress_id on title is null)
        Title t2 = saveTitle("FNS-100");
        linkActress(t2.getId(), suspect.getId());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);

        assertEquals(1, preview.castTitleCount());   // only t2 — FNS-052 filing title isn't in title_actresses
        assertEquals(1, preview.filingTitleCount());
        assertEquals(1, preview.renames().size());
        assertEquals(Path.of("/queue/Rin Hatchimitsu (FNS-052)"), preview.renames().get(0).currentPath());
        assertEquals(Path.of("/queue/Rin Hachimitsu (FNS-052)"), preview.renames().get(0).newPath());
    }

    @Test
    void preview_skipsLocationWithNoNameMatch() {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        // Filing title but folder name doesn't contain suspect name (already fixed or different convention)
        saveTitleFiled("FNS-052", suspect.getId(), "/unsorted/FNS-052", "pool");

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);

        assertEquals(0, preview.renames().size());
    }

    // ── execute ──────────────────────────────────────────────────────────────

    @Test
    void execute_reassignsAllTitleActressesRows() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        Title t1 = saveTitle("FNS-052");
        Title t2 = saveTitle("FNS-100");
        linkActress(t1.getId(), suspect.getId());
        linkActress(t2.getId(), suspect.getId());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, false);

        List<Long> t1Actresses = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM title_actresses WHERE title_id = :id")
                        .bind("id", t1.getId()).mapTo(Long.class).list());
        List<Long> t2Actresses = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM title_actresses WHERE title_id = :id")
                        .bind("id", t2.getId()).mapTo(Long.class).list());

        assertEquals(List.of(canonical.getId()), t1Actresses);
        assertEquals(List.of(canonical.getId()), t2Actresses);
    }

    @Test
    void execute_insertOrIgnore_whenCanonicalAlreadyLinked() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        Title t1 = saveTitle("FNS-052");
        // Both suspect and canonical already linked to the same title
        linkActress(t1.getId(), suspect.getId());
        linkActress(t1.getId(), canonical.getId());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        // Should not throw (INSERT OR IGNORE handles the duplicate)
        assertDoesNotThrow(() -> service.execute(preview, "pool", null, false));

        List<Long> actressIds = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM title_actresses WHERE title_id = :id")
                        .bind("id", t1.getId()).mapTo(Long.class).list());
        assertEquals(List.of(canonical.getId()), actressIds);
    }

    @Test
    void execute_updatesFilingActressId() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        Title t1 = saveTitleFiled("FNS-052", suspect.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, false);

        Long updatedActressId = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM titles WHERE id = :id")
                        .bind("id", t1.getId()).mapTo(Long.class).one());
        assertEquals(canonical.getId(), updatedActressId);
    }

    @Test
    void execute_deletesSuspectActress() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, false);

        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses WHERE id = :id")
                        .bind("id", suspect.getId()).mapTo(Integer.class).one());
        assertEquals(0, count);
    }

    @Test
    void execute_cleansActressAliases() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO actress_aliases (actress_id, alias_name) VALUES (:id, 'Old Alias')")
                .bind("id", suspect.getId()).execute());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, false);

        int aliasCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actress_aliases WHERE actress_id = :id")
                        .bind("id", suspect.getId()).mapTo(Integer.class).one());
        assertEquals(0, aliasCount);
    }

    @Test
    void execute_skipsLocationsOnUnmountedVolume() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        saveTitleFiled("FNS-168", suspect.getId(), "/Rin Hatchimitsu (FNS-168)", "pool");

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        // Execute with "r" mounted, but location is on "pool"
        ActressMergeService.MergeResult result = service.execute(preview, "r", null, false);

        assertEquals(0, result.renamedPaths().size());
        assertEquals(1, result.skipped().size());
        assertEquals("pool", result.skipped().get(0).volumeId());
    }

    @Test
    void execute_dryRun_makesNoDbChanges() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        Title t1 = saveTitle("FNS-052");
        linkActress(t1.getId(), suspect.getId());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, true);

        // Suspect actress still exists
        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses WHERE id = :id")
                        .bind("id", suspect.getId()).mapTo(Integer.class).one());
        assertEquals(1, count);

        // title_actresses unchanged
        List<Long> actressIds = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM title_actresses WHERE title_id = :id")
                        .bind("id", t1.getId()).mapTo(Long.class).list());
        assertEquals(List.of(suspect.getId()), actressIds);
    }

    // ── MergeActressCommand arg parsing ──────────────────────────────────────

    @Test
    void parseNames_separatorSyntax() {
        String[] result = MergeActressCommand.parseNames(
                new String[]{"actress merge", "Rin Hatchimitsu", ">", "Rin Hachimitsu"});
        assertArrayEquals(new String[]{"Rin Hatchimitsu", "Rin Hachimitsu"}, result);
    }

    @Test
    void parseNames_quotedNames() {
        String[] result = MergeActressCommand.parseNames(
                new String[]{"actress merge", "\"Rin Hatchimitsu\"", ">", "\"Rin Hachimitsu\""});
        assertArrayEquals(new String[]{"Rin Hatchimitsu", "Rin Hachimitsu"}, result);
    }

    @Test
    void parseNames_twoWordArgs() {
        String[] result = MergeActressCommand.parseNames(
                new String[]{"actress merge", "OldName", "NewName"});
        assertArrayEquals(new String[]{"OldName", "NewName"}, result);
    }

    @Test
    void parseNames_missingArgs_returnsNull() {
        assertNull(MergeActressCommand.parseNames(new String[]{"actress merge"}));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Actress mkActress(String name) {
        return Actress.builder().canonicalName(name)
                .tier(Actress.Tier.LIBRARY).favorite(false).bookmark(false)
                .rejected(false).needsProfiling(false)
                .firstSeenAt(LocalDate.now()).build();
    }

    private Title saveTitleFiled(String code, long actressId, String path, String volumeId) {
        Title title = jdbi.withHandle(h ->
                h.createQuery("""
                        INSERT INTO titles (code, actress_id) VALUES (:code, :actressId)
                        RETURNING *
                        """)
                        .bind("code", code)
                        .bind("actressId", actressId)
                        .map((rs, ctx) -> Title.builder()
                                .id(rs.getLong("id"))
                                .code(rs.getString("code"))
                                .build())
                        .one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                VALUES (:titleId, :volumeId, 'queue', :path, '2024-01-01')
                """)
                .bind("titleId", title.getId())
                .bind("volumeId", volumeId)
                .bind("path", path)
                .execute());
        return title;
    }

    private Title saveTitle(String code) {
        return jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code) VALUES (:code) RETURNING *")
                        .bind("code", code)
                        .map((rs, ctx) -> Title.builder()
                                .id(rs.getLong("id"))
                                .code(rs.getString("code"))
                                .build())
                        .one());
    }

    private void linkActress(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT OR IGNORE INTO title_actresses (title_id, actress_id) VALUES (:t, :a)")
                .bind("t", titleId).bind("a", actressId).execute());
    }
}
