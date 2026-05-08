package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListActressLocationsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private ListActressLocationsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection  = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi        = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-b', 'conventional')");
        });
        actressRepo  = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);
        tool = new ListActressLocationsTool(actressRepo, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── actress with titles on 2 volumes ─────────────────────────────────────

    @Test
    void returnsPerVolumeSummaryAcrossTwoVolumes() throws Exception {
        long aid = actressRepo.save(actress("Nami Aino")).getId();
        long t1  = titleRepo.save(title("ABP-001", aid)).getId();
        long t2  = titleRepo.save(title("ABP-002", aid)).getId();
        locationRepo.save(loc(t1, "vol-a", "/stars/library/Nami Aino/Nami Aino (ABP-001)"));
        locationRepo.save(loc(t2, "vol-b", "/stars/library/Nami Aino/Nami Aino (ABP-002)"));

        var result = (ListActressLocationsTool.Result) tool.call(args(aid));

        assertEquals("Nami Aino", result.canonicalName());
        assertEquals(2, result.perVolume().size());

        ListActressLocationsTool.PerVolume volA = findVol(result, "vol-a");
        assertEquals("/stars/library/Nami Aino", volA.parentFolderPath());
        assertTrue(volA.parentFolderMatchesCanonical());
        assertEquals(1, volA.titleCount());
        assertEquals("ABP-001", volA.titles().get(0).code());
        assertTrue(volA.titles().get(0).folderMatchesCanonical());

        ListActressLocationsTool.PerVolume volB = findVol(result, "vol-b");
        assertEquals(1, volB.titleCount());
        assertTrue(volB.parentFolderMatchesCanonical());
    }

    // ── actress with mixed-parent titles on 1 volume ──────────────────────────

    @Test
    void returnsMixedParentFolders() throws Exception {
        long aid = actressRepo.save(actress("Shion Fujimoto")).getId();
        long t1  = titleRepo.save(title("MIDE-001", aid)).getId();
        long t2  = titleRepo.save(title("MIDE-002", aid)).getId();
        // t1 in canonical folder, t2 in misspelled folder
        locationRepo.save(loc(t1, "vol-a", "/stars/minor/Shion Fujimoto/Shion Fujimoto (MIDE-001)"));
        locationRepo.save(loc(t2, "vol-a", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-002)"));

        var result = (ListActressLocationsTool.Result) tool.call(args(aid));

        assertEquals(1, result.perVolume().size());
        ListActressLocationsTool.PerVolume vol = result.perVolume().get(0);
        assertEquals(2, vol.titleCount());

        // parentFolderPath reflects first parent seen; titles may vary in folderMatchesCanonical
        List<ListActressLocationsTool.TitleEntry> titles = vol.titles();
        boolean hasMatchTrue  = titles.stream().anyMatch(ListActressLocationsTool.TitleEntry::folderMatchesCanonical);
        boolean hasMatchFalse = titles.stream().anyMatch(t -> !t.folderMatchesCanonical());
        assertTrue(hasMatchTrue,  "at least one title should be in canonical folder");
        assertTrue(hasMatchFalse, "at least one title should be in misnamed folder");
    }

    // ── parent folder match is case-insensitive ───────────────────────────────

    @Test
    void parentFolderMatchIsCaseInsensitive() throws Exception {
        long aid = actressRepo.save(actress("Nami Aino")).getId();
        long t1  = titleRepo.save(title("TST-001", aid)).getId();
        // Folder uses different casing
        locationRepo.save(loc(t1, "vol-a", "/stars/library/nami aino/TST-001"));

        var result = (ListActressLocationsTool.Result) tool.call(args(aid));

        ListActressLocationsTool.PerVolume vol = findVol(result, "vol-a");
        assertTrue(vol.parentFolderMatchesCanonical(), "case-insensitive match should pass");
    }

    // ── actress with no on-disk titles ────────────────────────────────────────

    @Test
    void returnsEmptyPerVolumeForActressWithNoTitles() throws Exception {
        long aid = actressRepo.save(actress("Lonely Actress")).getId();

        var result = (ListActressLocationsTool.Result) tool.call(args(aid));

        assertEquals("Lonely Actress", result.canonicalName());
        assertTrue(result.perVolume().isEmpty(), "no titles → empty perVolume");
    }

    // ── aliases included in response ─────────────────────────────────────────

    @Test
    void includesAliasesInResponse() throws Exception {
        long aid = actressRepo.save(actress("Nami Aino")).getId();
        actressRepo.saveAlias(new ActressAlias(aid, "Aino Nami"));

        var result = (ListActressLocationsTool.Result) tool.call(args(aid));

        assertTrue(result.aliases().contains("Aino Nami"));
    }

    // ── unknown actress id ────────────────────────────────────────────────────

    @Test
    void throwsForUnknownActressId() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(99999L)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Actress actress(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static com.organizer3.model.Title title(String code, long actressId) {
        return com.organizer3.model.Title.builder()
                .code(code)
                .baseCode(code)
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static com.organizer3.model.TitleLocation loc(long titleId, String volumeId, String path) {
        return com.organizer3.model.TitleLocation.builder()
                .titleId(titleId)
                .volumeId(volumeId)
                .partitionId("p")
                .path(java.nio.file.Path.of(path))
                .lastSeenAt(LocalDate.now())
                .build();
    }

    private static ObjectNode args(long actressId) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_id", actressId);
        return n;
    }

    private static ListActressLocationsTool.PerVolume findVol(ListActressLocationsTool.Result result, String volumeId) {
        return result.perVolume().stream()
                .filter(v -> v.volumeId().equals(volumeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Volume not found: " + volumeId));
    }
}
