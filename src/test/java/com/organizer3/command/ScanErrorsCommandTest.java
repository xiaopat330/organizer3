package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import com.organizer3.smb.VolumeConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScanErrorsCommandTest {

    private ActressRepository actressRepo;
    private VolumeFileSystem fs;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;
    private ScanErrorsCommand cmd;

    private static final VolumeConfig VOL =
            new VolumeConfig("pandora", "//pandora/jav_A", "conventional", null, null);

    private static final VolumeStructureDef STRUCTURE = new VolumeStructureDef(
            "conventional",
            List.of(),
            new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
    );

    private static final Path LIBRARY_ROOT = Path.of("/stars/library");

    @BeforeEach
    void setUp() throws IOException {
        actressRepo = mock(ActressRepository.class);
        fs = mock(VolumeFileSystem.class);
        VolumeConnection connection = mock(VolumeConnection.class);
        when(connection.isConnected()).thenReturn(true);
        when(connection.fileSystem()).thenReturn(fs);

        ctx = new SessionContext();
        ctx.setMountedVolume(VOL);
        ctx.setActiveConnection(connection);

        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
        cmd = new ScanErrorsCommand(actressRepo, new ErrorScanService());

        OrganizerConfig cfg = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(VOL), List.of(STRUCTURE), List.of());
        AppConfig.initializeForTest(cfg);

        // Default: library tier exists and is empty
        when(fs.exists(LIBRARY_ROOT)).thenReturn(true);
        when(fs.listDirectory(LIBRARY_ROOT)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    @Test
    void commandName_isScanErrors() {
        assertEquals("scan errors", cmd.name());
    }

    @Test
    void notConnected_printsError() {
        ctx.setActiveConnection(null);
        cmd.execute(new String[]{"scan errors"}, ctx, io);
        assertTrue(output.toString().contains("No volume mounted"));
    }

    @Test
    void collectionsVolume_scansUnstructuredPartitions() throws IOException {
        // Collections volume: no structuredPartition, titles named "Actress (CODE-123)"
        VolumeConfig collVol = new VolumeConfig("coll", "//nas/collections", "collections", null, null);
        VolumeStructureDef collStructure = new VolumeStructureDef(
                "collections", List.of(new PartitionDef("main", "main")), null);
        OrganizerConfig cfg = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(collVol), List.of(collStructure), List.of());
        AppConfig.initializeForTest(cfg);
        ctx.setMountedVolume(collVol);

        Actress a = actress(1, "Aoi Sola");
        when(actressRepo.findAll()).thenReturn(List.of(a));

        Path partRoot = Path.of("/main");
        // Title folder with typo in the actress name prefix
        Path titleFolder = partRoot.resolve("Aoy Sola (STAR-001)");
        when(fs.exists(partRoot)).thenReturn(true);
        when(fs.listDirectory(partRoot)).thenReturn(List.of(titleFolder));
        when(fs.isDirectory(titleFolder)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("[TYPO]"), "Should detect typo from collections title folder name");
        assertTrue(out.contains("Aoy Sola"), "Should show extracted actress name");
        assertTrue(out.contains("Aoi Sola"), "Should show DB match");
        // Path shown should be the title folder, not an actress folder
        assertTrue(out.contains("//nas/collections/main/Aoy Sola (STAR-001)/"),
                "Should show title folder SMB path");
    }

    @Test
    void collectionsVolume_variousIsSkipped() throws IOException {
        VolumeConfig collVol = new VolumeConfig("coll", "//nas/collections", "collections", null, null);
        VolumeStructureDef collStructure = new VolumeStructureDef(
                "collections", List.of(new PartitionDef("main", "main")), null);
        OrganizerConfig cfg = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(collVol), List.of(collStructure), List.of());
        AppConfig.initializeForTest(cfg);
        ctx.setMountedVolume(collVol);

        when(actressRepo.findAll()).thenReturn(List.of());

        Path partRoot = Path.of("/main");
        Path titleFolder = partRoot.resolve("Various (DCX-137)");
        when(fs.exists(partRoot)).thenReturn(true);
        when(fs.listDirectory(partRoot)).thenReturn(List.of(titleFolder));
        when(fs.isDirectory(titleFolder)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        // "Various" should be filtered; no actress names found → "No actress names found"
        assertTrue(output.toString().contains("No actress names found"),
                "Should skip 'Various' folders and report no names");
    }

    @Test
    void collectionsVolume_multipleActressesInOneFolder() throws IOException {
        VolumeConfig collVol = new VolumeConfig("coll", "//nas/collections", "collections", null, null);
        VolumeStructureDef collStructure = new VolumeStructureDef(
                "collections", List.of(new PartitionDef("main", "main")), null);
        OrganizerConfig cfg = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(collVol), List.of(collStructure), List.of());
        AppConfig.initializeForTest(cfg);
        ctx.setMountedVolume(collVol);

        Actress a = actress(1, "Yuna Shiina");
        Actress b = actress(2, "Aoi Sola");
        when(actressRepo.findAll()).thenReturn(List.of(a, b));

        Path partRoot = Path.of("/main");
        // Folder with two actresses, both matching DB exactly
        Path titleFolder = partRoot.resolve("Yuna Shiina, Aoi Sola (HMN-100)");
        when(fs.exists(partRoot)).thenReturn(true);
        when(fs.listDirectory(partRoot)).thenReturn(List.of(titleFolder));
        when(fs.isDirectory(titleFolder)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        // Both names match → no issues
        assertTrue(output.toString().contains("No issues found"),
                "Both names match DB — no issues expected");
    }

    @Test
    void allExactMatch_reportsNoIssues() throws IOException {
        Actress a = actress(1, "Yuna Shiina");
        when(actressRepo.findAll()).thenReturn(List.of(a));

        Path actressDir = LIBRARY_ROOT.resolve("Yuna Shiina");
        when(fs.listDirectory(LIBRARY_ROOT)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        assertTrue(output.toString().contains("No issues found"));
    }

    @Test
    void detectedSwap_isReported() throws IOException {
        when(actressRepo.findAll()).thenReturn(List.of(actress(1, "Yuna Shiina")));

        Path actressDir = LIBRARY_ROOT.resolve("Shiina Yuna");
        when(fs.listDirectory(LIBRARY_ROOT)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("[SWAP]"), "Should report SWAP");
        assertTrue(out.contains("Shiina Yuna"), "Should show folder name");
        assertTrue(out.contains("Yuna Shiina"), "Should show DB match");
    }

    @Test
    void detectedTypo_isReported() throws IOException {
        when(actressRepo.findAll()).thenReturn(List.of(actress(1, "Aoi Sola")));

        Path actressDir = LIBRARY_ROOT.resolve("Aoy Sola");
        when(fs.listDirectory(LIBRARY_ROOT)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("[TYPO]"), "Should report TYPO");
        assertTrue(out.contains("Aoy Sola"), "Should show folder name");
        assertTrue(out.contains("Aoi Sola"), "Should show DB match");
    }

    @Test
    void unknownFolder_isReported() throws IOException {
        when(actressRepo.findAll()).thenReturn(List.of(actress(1, "Yuna Shiina")));

        // No DB actress is close to this name
        Path actressDir = LIBRARY_ROOT.resolve("Completely Different Name Here");
        when(fs.listDirectory(LIBRARY_ROOT)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        assertTrue(output.toString().contains("[UNKNOWN]"), "Should report UNKNOWN");
    }

    @Test
    void smbPath_isCorrect() throws IOException {
        when(actressRepo.findAll()).thenReturn(List.of(actress(1, "Yuna Shiina")));

        Path actressDir = LIBRARY_ROOT.resolve("Shiina Yuna");
        when(fs.listDirectory(LIBRARY_ROOT)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        // smbBase="//pandora/jav_A", fsPath="/stars/library/Shiina Yuna", trailing "/"
        assertTrue(output.toString().contains("//pandora/jav_A/stars/library/Shiina Yuna/"),
                "Should show full SMB path with trailing slash");
    }

    @Test
    void tierNotFound_isSkipped() throws IOException {
        when(actressRepo.findAll()).thenReturn(List.of());
        when(fs.exists(LIBRARY_ROOT)).thenReturn(false);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("[skip]"), "Should show skip message for missing tier");
    }

    @Test
    void summaryCounts_areCorrect() throws IOException {
        List<Actress> db = List.of(
                actress(1, "Yuna Shiina"),
                actress(2, "Aoi Sola")
        );
        when(actressRepo.findAll()).thenReturn(db);

        // Three folders: 1 exact, 1 swap, 1 typo
        Path exact  = LIBRARY_ROOT.resolve("Yuna Shiina");
        Path swap   = LIBRARY_ROOT.resolve("Shiina Yuna");
        Path typo   = LIBRARY_ROOT.resolve("Aoy Sola");
        when(fs.listDirectory(LIBRARY_ROOT)).thenReturn(List.of(exact, swap, typo));
        when(fs.isDirectory(exact)).thenReturn(true);
        when(fs.isDirectory(swap)).thenReturn(true);
        when(fs.isDirectory(typo)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        String out = output.toString();
        // Scanned 3 folders · 1 swap · 1 typo · 0 unknown
        assertTrue(out.contains("3 folders"), "Should show total scanned");
        assertTrue(out.contains("1 swap(s)"), "Should show swap count");
        assertTrue(out.contains("1 typo(s)"), "Should show typo count");
        assertTrue(out.contains("0 unknown(s)"), "Should show unknown count");
    }

    @Test
    void exhibitionVolume_scansStarsDirectly() throws IOException {
        // Exhibition: structuredPartition exists but partitions list is empty
        // Actress folders live at stars/ directly, no tier sub-folders
        VolumeConfig exhibVol = new VolumeConfig("qnap", "//qnap2/jav", "exhibition", null, null);
        VolumeStructureDef exhibStructure = new VolumeStructureDef(
                "exhibition",
                List.of(),
                new StructuredPartitionDef("stars", List.of())  // empty partitions
        );
        OrganizerConfig cfg = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(exhibVol), List.of(exhibStructure), List.of());
        AppConfig.initializeForTest(cfg);
        ctx.setMountedVolume(exhibVol);

        Actress a = actress(1, "Yuna Shiina");
        when(actressRepo.findAll()).thenReturn(List.of(a));

        Path starsRoot = Path.of("/stars");
        Path correctFolder  = starsRoot.resolve("Yuna Shiina");
        Path swappedFolder  = starsRoot.resolve("Shiina Yuna");
        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(correctFolder, swappedFolder));
        when(fs.isDirectory(correctFolder)).thenReturn(true);
        when(fs.isDirectory(swappedFolder)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("[SWAP]"), "Should detect swap in exhibition actress folder");
        assertTrue(out.contains("//qnap2/jav/stars/Shiina Yuna/"),
                "SMB path should point to actress folder under stars/");
    }

    @Test
    void exhibitionVolume_skipsTopLevelMetadataFolders() throws IOException {
        VolumeConfig exhibVol = new VolumeConfig("qnap", "//qnap2/jav", "exhibition", null, null);
        VolumeStructureDef exhibStructure = new VolumeStructureDef(
                "exhibition", List.of(), new StructuredPartitionDef("stars", List.of()));
        OrganizerConfig cfg = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(exhibVol), List.of(exhibStructure), List.of());
        AppConfig.initializeForTest(cfg);
        ctx.setMountedVolume(exhibVol);

        when(actressRepo.findAll()).thenReturn(List.of());

        Path starsRoot = Path.of("/stars");
        Path tempFolder = starsRoot.resolve("temp");
        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(tempFolder));
        when(fs.isDirectory(tempFolder)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        // "temp" is filtered; no actress names found
        assertTrue(output.toString().contains("No actress names found"),
                "Should skip 'temp' folder and report no names");
    }

    @Test
    void sortPoolVolume_scansRootAndLater() throws IOException {
        // Sort-pool: empty unstructuredPartitions and no structuredPartition
        // Scans / (excluding __ prefix) and /__later
        VolumeConfig poolVol = new VolumeConfig("pool", "//pandora/pool", "sort_pool", null, null);
        VolumeStructureDef poolStructure = new VolumeStructureDef("sort_pool", List.of(), null);
        OrganizerConfig cfg = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(poolVol), List.of(poolStructure), List.of());
        AppConfig.initializeForTest(cfg);
        ctx.setMountedVolume(poolVol);

        Actress a = actress(1, "Yuna Shiina");
        when(actressRepo.findAll()).thenReturn(List.of(a));

        Path root = Path.of("/");
        Path laterPath = Path.of("/__later");

        // Root: one title folder with a swap, one __ folder to skip
        Path titleFolder = root.resolve("Shiina Yuna (ABP-001)");
        Path skipFolder  = root.resolve("__covers");
        when(fs.listDirectory(root)).thenReturn(List.of(titleFolder, skipFolder));
        when(fs.isDirectory(titleFolder)).thenReturn(true);
        when(fs.isDirectory(skipFolder)).thenReturn(true);

        // __later: one code-only folder (no actress name)
        Path laterTitle = laterPath.resolve("ABP-002");
        when(fs.exists(laterPath)).thenReturn(true);
        when(fs.listDirectory(laterPath)).thenReturn(List.of(laterTitle));
        when(fs.isDirectory(laterTitle)).thenReturn(true);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("[SWAP]"), "Should detect swap from root title folder name");
        assertTrue(out.contains("//pandora/pool/Shiina Yuna (ABP-001)/"),
                "SMB path should point to the title folder in pool root");
    }

    @Test
    void sortPoolVolume_laterNotFound_isSkipped() throws IOException {
        VolumeConfig poolVol = new VolumeConfig("pool", "//pandora/pool", "sort_pool", null, null);
        VolumeStructureDef poolStructure = new VolumeStructureDef("sort_pool", List.of(), null);
        OrganizerConfig cfg = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(poolVol), List.of(poolStructure), List.of());
        AppConfig.initializeForTest(cfg);
        ctx.setMountedVolume(poolVol);

        when(actressRepo.findAll()).thenReturn(List.of());
        when(fs.listDirectory(Path.of("/"))).thenReturn(List.of());
        when(fs.exists(Path.of("/__later"))).thenReturn(false);

        cmd.execute(new String[]{"scan errors"}, ctx, io);

        assertTrue(output.toString().contains("[skip]"),
                "Should show skip message for missing __later");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Actress actress(long id, String canonicalName) {
        return Actress.builder()
                .id(id)
                .canonicalName(canonicalName)
                .tier(Actress.Tier.LIBRARY)
                .build();
    }
}
