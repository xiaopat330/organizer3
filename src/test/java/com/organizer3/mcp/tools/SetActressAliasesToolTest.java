package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

class SetActressAliasesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiActressRepository actressRepo;
    private SetActressAliasesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        tool = new SetActressAliasesTool(actressRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void dryRunReturnsPlanWithoutCommitting() {
        long id = actress("Aya Sazanami");
        actressRepo.saveAlias(new ActressAlias(id, "Old Alias"));

        var r = (SetActressAliasesTool.Result) tool.call(args(id, List.of("New Alias"), true));

        assertTrue(r.ok());
        assertTrue(r.dryRun());
        assertEquals(List.of("Old Alias"), r.aliasesBefore());
        assertEquals(List.of("New Alias"), r.aliasesAfter());
        // DB unchanged
        assertEquals(List.of("Old Alias"),
                actressRepo.findAliases(id).stream().map(ActressAlias::aliasName).toList());
    }

    @Test
    void executeReplacesAliases() {
        long id = actress("Aya Sazanami");
        actressRepo.saveAlias(new ActressAlias(id, "Old Alias"));

        var r = (SetActressAliasesTool.Result) tool.call(args(id, List.of("New Alias 1", "New Alias 2"), false));

        assertTrue(r.ok());
        assertFalse(r.dryRun());
        List<String> stored = actressRepo.findAliases(id).stream()
                .map(ActressAlias::aliasName).sorted().toList();
        assertEquals(List.of("New Alias 1", "New Alias 2"), stored);
    }

    @Test
    void clearAllAliasesWithEmptyList() {
        long id = actress("Aya Sazanami");
        actressRepo.saveAlias(new ActressAlias(id, "Old Alias"));

        tool.call(args(id, List.of(), false));

        assertTrue(actressRepo.findAliases(id).isEmpty());
    }

    @Test
    void conflictWhenAliasIsCanonicalNameOfAnotherActress() {
        long id  = actress("Aya Sazanami");
        actress("Hibiki Otsuki");

        var r = (SetActressAliasesTool.Result) tool.call(args(id, List.of("Hibiki Otsuki"), false));

        assertFalse(r.ok());
        assertFalse(r.conflicts().isEmpty());
        assertTrue(r.conflicts().get(0).contains("Hibiki Otsuki"));
        assertTrue(actressRepo.findAliases(id).isEmpty(), "no aliases committed on conflict");
    }

    @Test
    void conflictWhenAliasIsOwnedByAnotherActress() {
        long id1 = actress("Aya Sazanami");
        long id2 = actress("Hibiki Otsuki");
        actressRepo.saveAlias(new ActressAlias(id2, "Eri Ando"));

        var r = (SetActressAliasesTool.Result) tool.call(args(id1, List.of("Eri Ando"), false));

        assertFalse(r.ok());
        assertTrue(r.conflicts().get(0).contains("Eri Ando"));
    }

    @Test
    void resolveByNameWorks() {
        long id = actress("Aya Sazanami");

        var r = (SetActressAliasesTool.Result) tool.call(argsByName("Aya Sazanami", List.of("A-chan"), false));

        assertTrue(r.ok());
        assertEquals(id, r.actressId());
        assertEquals(List.of("A-chan"),
                actressRepo.findAliases(id).stream().map(ActressAlias::aliasName).toList());
    }

    @Test
    void defaultsToDryRun() {
        long id = actress("Aya Sazanami");
        ObjectNode a = M.createObjectNode();
        a.put("id", id);
        a.putArray("aliases").add("Test");

        var r = (SetActressAliasesTool.Result) tool.call(a);

        assertTrue(r.dryRun());
        assertTrue(actressRepo.findAliases(id).isEmpty(), "no commit on default dry-run");
    }

    @Test
    void rejectsMissingActress() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(9999L, List.of(), false)));
    }

    @Test
    void rejectsMissingAliasesField() {
        long id = actress("Aya Sazanami");
        ObjectNode a = M.createObjectNode();
        a.put("id", id);
        assertThrows(IllegalArgumentException.class, () -> tool.call(a));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long actress(String name) {
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now())
                .build()).getId();
    }

    private ObjectNode args(long id, List<String> aliases, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("id", id);
        n.put("dryRun", dryRun);
        ArrayNode arr = n.putArray("aliases");
        aliases.forEach(arr::add);
        return n;
    }

    private ObjectNode argsByName(String name, List<String> aliases, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("name", name);
        n.put("dryRun", dryRun);
        ArrayNode arr = n.putArray("aliases");
        aliases.forEach(arr::add);
        return n;
    }
}
