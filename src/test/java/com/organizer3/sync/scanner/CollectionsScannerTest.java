package com.organizer3.sync.scanner;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for CollectionsScanner — verifies folder name parsing, actress list extraction,
 * Various/unknown handling, and __covers folder skipping.
 */
class CollectionsScannerTest {

    private VolumeFileSystem fs;
    private CommandIO io;
    private CollectionsScanner scanner;

    private static final VolumeStructureDef STRUCTURE = new VolumeStructureDef(
            "collections",
            List.of(
                    new PartitionDef("archive", "archive"),
                    new PartitionDef("duos",    "duos")
            ),
            null
    );

    @BeforeEach
    void setUp() {
        fs = mock(VolumeFileSystem.class);
        io = new PlainCommandIO(new PrintWriter(new StringWriter()));
        scanner = new CollectionsScanner();
    }

    // --- parseActressNames ---

    @Test
    void parsesTwoActressNames() {
        List<String> names = CollectionsScanner.parseActressNames("Aika, Yui Hatano (HMN-102)");
        assertEquals(List.of("Aika", "Yui Hatano"), names);
    }

    @Test
    void parsesThreeActressNames() {
        List<String> names = CollectionsScanner.parseActressNames(
                "Ai Hoshina, Eimi Fukada, Yui Hatano (PRED-390)");
        assertEquals(List.of("Ai Hoshina", "Eimi Fukada", "Yui Hatano"), names);
    }

    @Test
    void stripsDemosaicedSuffix() {
        List<String> names = CollectionsScanner.parseActressNames(
                "Ai Mukai, Rena Aoi - Demosaiced (MVSD-503)");
        assertEquals(List.of("Ai Mukai", "Rena Aoi"), names);
    }

    @Test
    void variousReturnsEmptyList() {
        assertTrue(CollectionsScanner.parseActressNames("Various (DCX-137)").isEmpty());
    }

    @Test
    void variousDemosaicedReturnsEmptyList() {
        assertTrue(CollectionsScanner.parseActressNames("Various - Demosaiced (MIBD-917)").isEmpty());
    }

    @Test
    void variousCaseInsensitive() {
        assertTrue(CollectionsScanner.parseActressNames("VARIOUS (DCX-137)").isEmpty());
    }

    @Test
    void parsesSingleMononymActress() {
        List<String> names = CollectionsScanner.parseActressNames("Aika (IKUNA-008)");
        assertEquals(List.of("Aika"), names);
    }

    @Test
    void parsesLargeCastCorrectly() {
        List<String> names = CollectionsScanner.parseActressNames(
                "Aika, Ai Sayama, Sumire Mizukawa, Sora Amakawa, Shuri Yamamoto (BMW-302)");
        assertEquals(5, names.size());
        assertEquals("Aika", names.get(0));
        assertEquals("Ai Sayama", names.get(1));
        assertEquals("BMW", names.get(4).isEmpty() ? "" : "BMW"); // just size check
        assertEquals(List.of("Aika", "Ai Sayama", "Sumire Mizukawa", "Sora Amakawa", "Shuri Yamamoto"), names);
    }

    // --- scan ---

    @Test
    void scanDiscoversMultiActressTitles() throws IOException {
        Path archiveRoot = Path.of("/archive");
        Path titleDir = archiveRoot.resolve("Aika, Yui Hatano (HMN-102)");

        when(fs.exists(archiveRoot)).thenReturn(true);
        when(fs.listDirectory(archiveRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.exists(Path.of("/duos"))).thenReturn(false);

        List<DiscoveredTitle> results = scanner.scan(STRUCTURE, fs, io);

        assertEquals(1, results.size());
        DiscoveredTitle dt = results.get(0);
        assertEquals("archive", dt.partitionId());
        assertEquals(List.of("Aika", "Yui Hatano"), dt.actressNames());
    }

    @Test
    void scanProducesEmptyActressNamesForVarious() throws IOException {
        Path archiveRoot = Path.of("/archive");
        Path titleDir = archiveRoot.resolve("Various (DCX-137)");

        when(fs.exists(archiveRoot)).thenReturn(true);
        when(fs.listDirectory(archiveRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.exists(Path.of("/duos"))).thenReturn(false);

        List<DiscoveredTitle> results = scanner.scan(STRUCTURE, fs, io);

        assertEquals(1, results.size());
        assertTrue(results.get(0).actressNames().isEmpty());
    }

    @Test
    void scanSkipsCoversFolder() throws IOException {
        Path archiveRoot = Path.of("/archive");
        Path coversDir = archiveRoot.resolve("__covers");
        Path titleDir = archiveRoot.resolve("Aika, Yui Hatano (HMN-102)");

        when(fs.exists(archiveRoot)).thenReturn(true);
        when(fs.listDirectory(archiveRoot)).thenReturn(List.of(coversDir, titleDir));
        when(fs.isDirectory(coversDir)).thenReturn(true);
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.exists(Path.of("/duos"))).thenReturn(false);

        List<DiscoveredTitle> results = scanner.scan(STRUCTURE, fs, io);

        assertEquals(1, results.size());
        assertEquals("Aika, Yui Hatano (HMN-102)", results.get(0).path().getFileName().toString());
    }

    @Test
    void scanSkipsMissingPartition() throws IOException {
        when(fs.exists(Path.of("/archive"))).thenReturn(false);
        when(fs.exists(Path.of("/duos"))).thenReturn(false);

        List<DiscoveredTitle> results = scanner.scan(STRUCTURE, fs, io);

        assertTrue(results.isEmpty());
    }

    @Test
    void scanAcrossMultiplePartitions() throws IOException {
        Path archiveRoot = Path.of("/archive");
        Path duosRoot = Path.of("/duos");
        Path archiveTitle = archiveRoot.resolve("Ai Hoshina, Eimi Fukada (PRED-159)");
        Path duosTitle = duosRoot.resolve("Aika, Yui Hatano (HMN-102)");

        when(fs.exists(archiveRoot)).thenReturn(true);
        when(fs.listDirectory(archiveRoot)).thenReturn(List.of(archiveTitle));
        when(fs.isDirectory(archiveTitle)).thenReturn(true);
        when(fs.exists(duosRoot)).thenReturn(true);
        when(fs.listDirectory(duosRoot)).thenReturn(List.of(duosTitle));
        when(fs.isDirectory(duosTitle)).thenReturn(true);

        List<DiscoveredTitle> results = scanner.scan(STRUCTURE, fs, io);

        assertEquals(2, results.size());
        assertEquals("archive", results.get(0).partitionId());
        assertEquals("duos", results.get(1).partitionId());
    }
}
