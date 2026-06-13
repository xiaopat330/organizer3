package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.AgeAtReleaseRecomputer;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MergeActressesToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private AgeAtReleaseRecomputer mockRecomputer;
    private MergeActressesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        actressRepo = new JdbiActressRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        mockRecomputer = Mockito.mock(AgeAtReleaseRecomputer.class);
        when(mockRecomputer.recomputeAll()).thenReturn(0);
        tool = new MergeActressesTool(jdbi, actressRepo, new CurationLog(logDir), mockRecomputer);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── dry-run ─────────────────────────────────────────────────────────────

    @Test
    void dryRunReturnsPlanWithoutMutating() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        titleRepo.save(title("ABP-001", from));
        titleRepo.save(title("ABP-002", from));

        var r = (MergeActressesTool.Result) tool.call(args(into, from, true));
        assertTrue(r.dryRun());
        assertTrue(r.plan().summary().contains("Aino Nami"));
        assertTrue(r.plan().summary().contains("Nami Aino"));

        // Mutation side-effects must not have happened
        assertTrue(actressRepo.findById(from).isPresent());
        assertEquals(2, titleRepo.findByActress(from).size());
        assertEquals(0, titleRepo.findByActress(into).size());
    }

    // ── execution ───────────────────────────────────────────────────────────

    @Test
    void executeReassignsTitleActressId() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        titleRepo.save(title("ABP-001", from));
        titleRepo.save(title("ABP-002", from));

        var r = (MergeActressesTool.Result) tool.call(args(into, from, false));
        assertFalse(r.dryRun());

        assertTrue(actressRepo.findById(from).isEmpty(), "from actress should be deleted");
        assertEquals(2, titleRepo.findByActress(into).size(), "titles should now belong to into");
    }

    @Test
    void executeAddsFromCanonicalNameAsAlias() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();

        tool.call(args(into, from, false));

        List<String> aliases = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).toList();
        assertTrue(aliases.contains("Aino Nami"), "from's canonical should become an alias");
    }

    @Test
    void executeMigratesAliasesDedupingCollisions() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        actressRepo.saveAlias(new ActressAlias(from, "Nami"));
        actressRepo.saveAlias(new ActressAlias(from, "Sweet Girl"));
        actressRepo.saveAlias(new ActressAlias(into, "Nami")); // pre-existing on into

        tool.call(args(into, from, false));

        List<String> aliases = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).sorted().toList();
        assertTrue(aliases.contains("Sweet Girl"));
        assertTrue(aliases.contains("Nami"));
        assertEquals(1, aliases.stream().filter("Nami"::equals).count(), "duplicate alias collapsed");
    }

    @Test
    void executeRewritesJunctionRowsDedupingCollisions() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        long otherA = actressRepo.save(mk("Co-star")).getId();

        long t1 = titleRepo.save(title("GROUP-001", null)).getId();
        long t2 = titleRepo.save(title("GROUP-002", null)).getId();
        // t1: both into and from credited — should dedup to a single row under into
        titleActressRepo.linkAll(t1, List.of(into, from, otherA));
        // t2: only from credited — should migrate to into
        titleActressRepo.linkAll(t2, List.of(from));

        tool.call(args(into, from, false));

        List<Long> t1Cast = titleActressRepo.findActressIdsByTitle(t1);
        List<Long> t2Cast = titleActressRepo.findActressIdsByTitle(t2);
        assertTrue(t1Cast.contains(into));
        assertTrue(t1Cast.contains(otherA));
        assertFalse(t1Cast.contains(from), "from removed from t1");
        assertEquals(2, t1Cast.size(), "t1 should have (into, otherA) — no dupe");
        assertEquals(List.of(into), t2Cast, "t2 migrated from -> into");
    }

    // ── flag merge policy ──────────────────────────────────────────────────

    @Test
    void executeMergesFavoriteAndBookmarkAsOr() throws Exception {
        long into = actressRepo.save(mk("Into", false, false)).getId();
        long from = actressRepo.save(mk("From", true, true)).getId();

        tool.call(args(into, from, false));

        Actress merged = actressRepo.findById(into).orElseThrow();
        assertTrue(merged.isFavorite());
        assertTrue(merged.isBookmark());
    }

    @Test
    void executeRejectsStaysTrueOnlyWhenBothRejected() throws Exception {
        long into = actressRepo.save(mk("Into")).getId();
        long from = actressRepo.save(mk("From")).getId();
        actressRepo.toggleRejected(from, true); // only from rejected

        tool.call(args(into, from, false));

        Actress merged = actressRepo.findById(into).orElseThrow();
        assertFalse(merged.isRejected(), "AND policy — merging into non-rejected clears flag");
    }

    @Test
    void mergeFlagsUnitChoosesStrongerGrade() {
        Actress into = Actress.builder().id(1L).canonicalName("i").tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).grade(Actress.Grade.B).build();
        Actress from = Actress.builder().id(2L).canonicalName("f").tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).grade(Actress.Grade.SSS).build();
        assertEquals(Actress.Grade.SSS, MergeActressesTool.mergeFlags(into, from).grade());
    }

    // ── Wave 4B Phase 2 audit (spec §7 Q6) ─────────────────────────────────
    // Confirms merge_actresses already adds the deprecated canonical name as alias, so subsequent
    // syncs can still resolve the old folder name via the alias table.

    @Test
    void q6AuditDeprecatedCanonicalNameIsQueryableAsAliasAfterMerge() throws Exception {
        long into = actressRepo.save(mk("Yua Mikami")).getId();
        long from = actressRepo.save(mk("Mikami Yua")).getId();

        tool.call(args(into, from, false));

        // The deprecated canonical name "Mikami Yua" must be queryable via resolveByName
        var resolved = actressRepo.resolveByName("Mikami Yua");
        assertTrue(resolved.isPresent(), "deprecated name must resolve via alias table");
        assertEquals(into, resolved.get().getId(), "must resolve to the surviving actress");
    }

    // ── actress_companies migration ─────────────────────────────────────────

    @Test
    void executeMigratesActressCompaniesAndCascadesFromRow() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();

        // into already has "Studio A"; from has "Studio A" (dup) + "Studio B" (new)
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actress_companies (actress_id, company) VALUES (?, ?)", into, "Studio A");
            h.execute("INSERT INTO actress_companies (actress_id, company) VALUES (?, ?)", from, "Studio A");
            h.execute("INSERT INTO actress_companies (actress_id, company) VALUES (?, ?)", from, "Studio B");
        });

        tool.call(args(into, from, false));

        List<String> intoCompanies = jdbi.withHandle(h ->
                h.createQuery("SELECT company FROM actress_companies WHERE actress_id = ?")
                        .bind(0, into).mapTo(String.class).list());

        assertTrue(intoCompanies.contains("Studio A"), "into should retain Studio A");
        assertTrue(intoCompanies.contains("Studio B"), "into should gain Studio B from from");
        assertEquals(2, intoCompanies.size(), "no duplicate companies on into");
        assertTrue(actressRepo.findById(from).isEmpty(), "from actress row deleted");
    }

    @Test
    void executeDeletesActressCompaniesEvenWhenForeignKeysOff() throws Exception {
        // Production DB runs with PRAGMA foreign_keys = OFF; merge must not rely on cascade.
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = OFF"));

        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actress_companies (actress_id, company) VALUES (?, ?)", from, "Studio A");
            h.execute("INSERT INTO actress_companies (actress_id, company) VALUES (?, ?)", from, "Studio B");
        });

        tool.call(args(into, from, false));

        long fromRows = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actress_companies WHERE actress_id = ?")
                        .bind(0, from).mapTo(Long.class).one());
        assertEquals(0, fromRows, "from's actress_companies rows must be deleted explicitly (no cascade reliance)");

        List<String> intoCompanies = jdbi.withHandle(h ->
                h.createQuery("SELECT company FROM actress_companies WHERE actress_id = ?")
                        .bind(0, into).mapTo(String.class).list());
        assertTrue(intoCompanies.contains("Studio A"), "into should have migrated Studio A");
        assertTrue(intoCompanies.contains("Studio B"), "into should have migrated Studio B");
        assertEquals(2, intoCompanies.size());

        assertTrue(actressRepo.findById(from).isEmpty(), "from actress row deleted");
    }

    @Test
    void dryRunReportsActressCompaniesMigrationCount() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actress_companies (actress_id, company) VALUES (?, ?)", into, "Studio A");
            h.execute("INSERT INTO actress_companies (actress_id, company) VALUES (?, ?)", from, "Studio A");
            h.execute("INSERT INTO actress_companies (actress_id, company) VALUES (?, ?)", from, "Studio B");
        });

        var r = (MergeActressesTool.Result) tool.call(args(into, from, true));
        assertTrue(r.dryRun());

        long companiesChange = r.plan().changes().stream()
                .filter(c -> "actress_companies".equals(c.table()) && "insert".equals(c.op()))
                .mapToLong(MergeActressesTool.Change::rows)
                .sum();
        assertEquals(1, companiesChange, "dry-run plan should report 1 net-new company for into");

        // Confirm no mutation occurred
        long fromCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actress_companies WHERE actress_id = ?")
                        .bind(0, from).mapTo(Long.class).one());
        assertEquals(2, fromCount, "from's companies must not be touched in dry-run");
    }

    // ── false-positive guard ────────────────────────────────────────────────

    @Test
    void mergeDoesNotTouchUnrelatedActress() {
        long into      = actressRepo.save(mk("Nami Aino")).getId();
        long from      = actressRepo.save(mk("Aino Nami")).getId();
        long unrelated = actressRepo.save(mk("Unrelated Actress")).getId();

        actressRepo.saveAlias(new ActressAlias(unrelated, "Unrelated Alias"));
        long titleId = titleRepo.save(title("UNR-001", unrelated)).getId();
        titleActressRepo.linkAll(titleId, List.of(unrelated));

        tool.call(args(into, from, false));

        // A missing WHERE clause in any delete step would wipe these rows
        assertTrue(actressRepo.findById(unrelated).isPresent(),
                "unrelated actress row must not be deleted");
        List<String> aliases = actressRepo.findAliases(unrelated).stream()
                .map(ActressAlias::aliasName).toList();
        assertTrue(aliases.contains("Unrelated Alias"),
                "unrelated actress's aliases must survive merge");
        assertTrue(titleActressRepo.findActressIdsByTitle(titleId).contains(unrelated),
                "unrelated actress's title credits must survive merge");
    }

    // ── validation ─────────────────────────────────────────────────────────

    @Test
    void rejectsSameIntoAndFrom() throws Exception {
        long id = actressRepo.save(mk("Someone")).getId();
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(id, id, true)));
    }

    @Test
    void rejectsMissingActress() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(9999L, 9998L, true)));
    }

    // ── dropAliases ─────────────────────────────────────────────────────────

    @Test
    void dropAliasesRemovesListedAliasesAfterMerge() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        actressRepo.saveAlias(new ActressAlias(from, "Typo Variant"));
        actressRepo.saveAlias(new ActressAlias(from, "Keep Me"));

        var r = (MergeActressesTool.Result) tool.call(argsWithDrops(into, from, false, "Typo Variant"));
        assertFalse(r.dryRun());
        assertEquals(List.of("Typo Variant"), r.droppedAliases());

        List<String> aliases = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).toList();
        assertFalse(aliases.contains("Typo Variant"));
        assertTrue(aliases.contains("Keep Me"));
        assertTrue(aliases.contains("Aino Nami"), "from-canonical fold still happens");
    }

    @Test
    void dropAliasesIsCaseInsensitive() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        actressRepo.saveAlias(new ActressAlias(from, "Typo Variant"));

        tool.call(argsWithDrops(into, from, false, "typo variant"));

        List<String> aliases = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).toList();
        assertFalse(aliases.contains("Typo Variant"));
    }

    @Test
    void dropAliasesEmptyPreservesBehavior() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        actressRepo.saveAlias(new ActressAlias(from, "Some Alias"));

        var r = (MergeActressesTool.Result) tool.call(args(into, from, false));
        assertTrue(r.droppedAliases().isEmpty());

        List<String> aliases = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).toList();
        assertTrue(aliases.contains("Some Alias"), "no dropAliases → standard merge behavior");
        assertTrue(aliases.contains("Aino Nami"));
    }

    @Test
    void dropAliasesCanDropFromCanonicalIfExplicitlyListed() throws Exception {
        // Explicit power: if the user wants the from-canonical removed, dropAliases honors it.
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();

        var r = (MergeActressesTool.Result) tool.call(argsWithDrops(into, from, false, "Aino Nami"));
        assertEquals(List.of("Aino Nami"), r.droppedAliases());

        List<String> aliases = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).toList();
        assertFalse(aliases.contains("Aino Nami"));
    }

    @Test
    void dropAliasesDryRunReportsPlanWithoutApplying() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        actressRepo.saveAlias(new ActressAlias(from, "Typo Variant"));

        var r = (MergeActressesTool.Result) tool.call(argsWithDrops(into, from, true, "Typo Variant"));
        assertTrue(r.dryRun());
        assertTrue(r.plan().plannedAliasDrops().contains("Typo Variant"));

        // No mutations happened
        assertTrue(actressRepo.findById(from).isPresent(), "from still exists in dry-run");
        // Drop result list also empty in dry-run (since nothing was actually applied)
        assertTrue(r.droppedAliases().isEmpty());
    }

    @Test
    void dropAliasesUnknownEntryIsNoOpNotError() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        actressRepo.saveAlias(new ActressAlias(from, "Real Alias"));

        var r = (MergeActressesTool.Result) tool.call(
                argsWithDrops(into, from, false, "Does Not Exist", "Real Alias"));
        // both real and bogus listed — real should be dropped, bogus reported as not-found
        assertEquals(List.of("Real Alias"), r.droppedAliases());
        assertEquals(List.of("Does Not Exist"), r.dropAliasesNotFound());

        List<String> aliases = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).toList();
        assertFalse(aliases.contains("Real Alias"));
    }

    // ── age_at_release recompute trigger tests ────────────────────────────────

    @Test
    void recomputeCalledExactlyOnceOnLivePath() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();

        tool.call(args(into, from, false));

        verify(mockRecomputer, times(1)).recomputeAll();
    }

    @Test
    void recomputeNotCalledOnDryRun() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();

        tool.call(args(into, from, true));

        verify(mockRecomputer, never()).recomputeAll();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Actress mk(String name) {
        return mk(name, false, false);
    }

    private static Actress mk(String name, boolean favorite, boolean bookmark) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .favorite(favorite)
                .bookmark(bookmark)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title title(String code, Long actressId) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static ObjectNode args(long into, long from, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("into", into);
        n.put("from", from);
        n.put("dryRun", dryRun);
        return n;
    }

    private static ObjectNode argsWithDrops(long into, long from, boolean dryRun, String... drops) {
        ObjectNode n = args(into, from, dryRun);
        var arr = n.putArray("dropAliases");
        for (String d : drops) arr.add(d);
        return n;
    }
}
