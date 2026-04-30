package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.command.ActressMergeService;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.shell.SessionContext;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RenameActressToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private ActressMergeService mergeService;
    private RenameActressTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));

        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        session = mock(SessionContext.class);
        when(session.getMountedVolumeId()).thenReturn(null);
        when(session.getActiveConnection()).thenReturn(null);
        mergeService = new ActressMergeService(jdbi, locationRepo, actressRepo);
        tool = new RenameActressTool(jdbi, session, actressRepo, mergeService);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── dry-run ──────────────────────────────────────────────────────────────

    @Test
    void dryRunDoesNotMutateDb() {
        long id = actressRepo.save(mk("Yua Mikami")).getId();

        var r = (RenameActressTool.Result) tool.call(args(id, "Yua Mikarni", true, false));

        assertTrue(r.dryRun());
        // DB not changed
        assertEquals("Yua Mikami", actressRepo.findById(id).orElseThrow().getCanonicalName());
        assertTrue(actressRepo.findAliases(id).isEmpty());
    }

    // ── execution — DB side ──────────────────────────────────────────────────

    @Test
    void executeUpdatesCanonicalName() {
        long id = actressRepo.save(mk("Yua Mikami")).getId();

        tool.call(args(id, "Yua Mikarni", false, false));

        assertEquals("Yua Mikarni", actressRepo.findById(id).orElseThrow().getCanonicalName());
    }

    @Test
    void executeAddsOldNameAsAlias() {
        long id = actressRepo.save(mk("Yua Mikami")).getId();

        tool.call(args(id, "Yua Mikarni", false, false));

        List<String> aliases = actressRepo.findAliases(id).stream()
                .map(ActressAlias::aliasName).toList();
        assertTrue(aliases.contains("Yua Mikami"),
                "old canonical name must be added as alias");
    }

    @Test
    void newNameResolvableViaResolveByName() {
        long id = actressRepo.save(mk("Yua Mikami")).getId();

        tool.call(args(id, "Yua Mikarni", false, false));

        // New canonical name resolves
        assertTrue(actressRepo.resolveByName("Yua Mikarni").isPresent());
        // Old name still resolves via alias
        assertEquals(id, actressRepo.resolveByName("Yua Mikami").orElseThrow().getId());
    }

    // ── validation ───────────────────────────────────────────────────────────

    @Test
    void rejectsSameName() {
        long id = actressRepo.save(mk("Yua Mikami")).getId();
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(id, "yua mikami", false, false)));
    }

    @Test
    void rejectsNameTakenByAnotherActress() {
        long id  = actressRepo.save(mk("Actress A")).getId();
        long id2 = actressRepo.save(mk("Actress B")).getId();
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(id, "Actress B", false, false)));
    }

    @Test
    void rejectsNameAlreadyAliasOfAnotherActress() {
        long id  = actressRepo.save(mk("Actress A")).getId();
        long id2 = actressRepo.save(mk("Actress B")).getId();
        actressRepo.saveAlias(new ActressAlias(id2, "Formerly Known As"));
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(id, "Formerly Known As", false, false)));
    }

    @Test
    void rejectsMissingActress() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(9999L, "Whatever", false, false)));
    }

    // ── false-positive guard ─────────────────────────────────────────────────

    @Test
    void renameDoesNotTouchOtherActresses() {
        long target    = actressRepo.save(mk("Actress A")).getId();
        long unrelated = actressRepo.save(mk("Actress B")).getId();
        actressRepo.saveAlias(new ActressAlias(unrelated, "Alias B"));

        tool.call(args(target, "Actress A Renamed", false, false));

        assertEquals("Actress B", actressRepo.findById(unrelated).orElseThrow().getCanonicalName());
        List<String> bAliases = actressRepo.findAliases(unrelated).stream()
                .map(ActressAlias::aliasName).toList();
        assertTrue(bAliases.contains("Alias B"), "unrelated actress's aliases must survive");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Actress mk(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static ObjectNode args(long actressId, String newName, boolean dryRun, boolean renameDisk) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_id",        actressId);
        n.put("new_canonical_name", newName);
        n.put("dry_run",           dryRun);
        n.put("rename_disk",       renameDisk);
        return n;
    }
}
