package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CreateActressToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private CreateActressTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        tool = new CreateActressTool(actressRepo, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    void happyPath_minimalArgs() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Kana Mochizuki");

        var r = (CreateActressTool.Result) tool.call(args);

        assertEquals("Kana Mochizuki", r.canonicalName());
        assertEquals("LIBRARY", r.tier());
        assertNull(r.stageName());
        assertTrue(r.aliases().isEmpty());
        assertEquals("created", r.status());
        assertTrue(r.actressId() > 0);
    }

    @Test
    void happyPath_rowPersistedInDb() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Hikaru Mizuki");

        var r = (CreateActressTool.Result) tool.call(args);

        var found = actressRepo.findById(r.actressId());
        assertTrue(found.isPresent());
        assertEquals("Hikaru Mizuki", found.get().getCanonicalName());
        assertEquals(Actress.Tier.LIBRARY, found.get().getTier());
        assertNotNull(found.get().getFirstSeenAt(), "first_seen_at must not be null");
    }

    @Test
    void happyPath_firstSeenAtIsLocalDate() {
        // Verifies the date column stores a YYYY-MM-DD string that LocalDate.parse() can read
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Date Test Actress");

        var r = (CreateActressTool.Result) tool.call(args);

        var found = actressRepo.findById(r.actressId()).orElseThrow();
        LocalDate today = LocalDate.now();
        // first_seen_at should be today (or a valid past date — never future, never datetime format)
        assertFalse(found.getFirstSeenAt().isAfter(today),
                "first_seen_at must not be in the future");
        // Ensure it round-trips without DateTimeParseException (value would be null if it failed)
        assertNotNull(found.getFirstSeenAt());
    }

    @Test
    void happyPath_withStageName() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Momona Asakura");
        args.put("stageName", "朝倉ももな");

        var r = (CreateActressTool.Result) tool.call(args);

        assertEquals("created", r.status());
        assertEquals("朝倉ももな", r.stageName());

        var found = actressRepo.findById(r.actressId()).orElseThrow();
        assertEquals("朝倉ももな", found.getStageName());
    }

    @Test
    void happyPath_withTierOverride() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Tier Override Test");
        args.put("tier", "MINOR");

        var r = (CreateActressTool.Result) tool.call(args);

        assertEquals("MINOR", r.tier());
        var found = actressRepo.findById(r.actressId()).orElseThrow();
        assertEquals(Actress.Tier.MINOR, found.getTier());
    }

    @Test
    void happyPath_tierIsCaseInsensitive() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Tier Case Test");
        args.put("tier", "popular");   // lowercase

        var r = (CreateActressTool.Result) tool.call(args);

        assertEquals("POPULAR", r.tier());
    }

    // ── alias batch insert ───────────────────────────────────────────────────

    @Test
    void aliasesInserted() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Alias Test Actress");
        args.putArray("aliases")
                .add("Alias One")
                .add("Alias Two");

        var r = (CreateActressTool.Result) tool.call(args);

        assertEquals(2, r.aliases().size());
        assertTrue(r.aliases().contains("Alias One"));
        assertTrue(r.aliases().contains("Alias Two"));

        // Verify aliases round-trip via findAliases
        List<ActressAlias> stored = actressRepo.findAliases(r.actressId());
        List<String> storedNames = stored.stream().map(ActressAlias::aliasName).toList();
        assertTrue(storedNames.contains("Alias One"));
        assertTrue(storedNames.contains("Alias Two"));
    }

    @Test
    void aliasesDeduped() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Dedup Alias Test");
        args.putArray("aliases")
                .add("Same Alias")
                .add("Same Alias");

        var r = (CreateActressTool.Result) tool.call(args);

        // Duplicates silently dropped — only one alias stored
        assertEquals(1, r.aliases().size());
        List<ActressAlias> stored = actressRepo.findAliases(r.actressId());
        assertEquals(1, stored.size());
    }

    @Test
    void noAliasesProvided_emptyList() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "No Aliases");
        // no aliases key at all

        var r = (CreateActressTool.Result) tool.call(args);

        assertTrue(r.aliases().isEmpty());
        assertTrue(actressRepo.findAliases(r.actressId()).isEmpty());
    }

    @Test
    void emptyAliasArray_emptyList() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Empty Aliases Array");
        args.putArray("aliases"); // empty array

        var r = (CreateActressTool.Result) tool.call(args);

        assertTrue(r.aliases().isEmpty());
    }

    // ── duplicate rejection ──────────────────────────────────────────────────

    @Test
    void duplicateCanonicalName_returnsError() {
        // Pre-insert an actress
        actressRepo.save(Actress.builder()
                .canonicalName("Already Exists")
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2025, 1, 1))
                .build());

        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Already Exists");

        var r = tool.call(args);

        assertInstanceOf(CreateActressTool.DuplicateResult.class, r,
                "Should return DuplicateResult, not Result");
        var dup = (CreateActressTool.DuplicateResult) r;
        assertTrue(dup.existingId() > 0);
        assertEquals("Already Exists", dup.existingCanonicalName());
        assertNotNull(dup.error());
    }

    @Test
    void duplicateCanonicalName_caseInsensitive() {
        actressRepo.save(Actress.builder()
                .canonicalName("Kana Mochizuki")
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2025, 1, 1))
                .build());

        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "KANA MOCHIZUKI");

        var r = tool.call(args);

        assertInstanceOf(CreateActressTool.DuplicateResult.class, r,
                "Case-insensitive match should also reject as duplicate");
    }

    @Test
    void duplicateCanonicalName_doesNotInsertNewRow() {
        actressRepo.save(Actress.builder()
                .canonicalName("Single Row Test")
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2025, 1, 1))
                .build());

        long countBefore = actressRepo.findAll().stream()
                .filter(a -> a.getCanonicalName().equalsIgnoreCase("Single Row Test"))
                .count();

        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Single Row Test");
        tool.call(args);

        long countAfter = actressRepo.findAll().stream()
                .filter(a -> a.getCanonicalName().equalsIgnoreCase("Single Row Test"))
                .count();

        assertEquals(countBefore, countAfter, "Duplicate call must not insert a new row");
    }

    // ── validation errors ────────────────────────────────────────────────────

    @Test
    void missingCanonicalName_throws() {
        ObjectNode args = M.createObjectNode();
        // No canonicalName key

        assertThrows(IllegalArgumentException.class, () -> tool.call(args));
    }

    @Test
    void unknownTier_throws() {
        ObjectNode args = M.createObjectNode();
        args.put("canonicalName", "Tier Error Test");
        args.put("tier", "MEGASTAR");

        assertThrows(IllegalArgumentException.class, () -> tool.call(args));
    }
}
