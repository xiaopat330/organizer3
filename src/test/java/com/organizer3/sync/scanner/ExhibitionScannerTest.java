package com.organizer3.sync.scanner;

import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
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

class ExhibitionScannerTest {

    private VolumeFileSystem fs;
    private PlainCommandIO io;
    private VolumeStructureDef structure;

    @BeforeEach
    void setUp() {
        fs = mock(VolumeFileSystem.class);
        io = new PlainCommandIO(new PrintWriter(new StringWriter()));
        structure = new VolumeStructureDef("exhibition", List.of(),
                new StructuredPartitionDef("stars", List.of()));
    }

    // --- Direct title discovery ---

    @Test
    void discoversDirectTitles() throws IOException {
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Julia");
        Path titleDir = actressDir.resolve("Julia (MIDE-517)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        assertEquals("Julia", results.get(0).actressNames().get(0));
        assertEquals("stars", results.get(0).partitionId());
        assertEquals(Actress.Tier.LIBRARY, results.get(0).actressTier());
        assertEquals(titleDir, results.get(0).path());
    }

    @Test
    void discoversDemosaicedTitleWithSuffix() throws IOException {
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Yua Mikami");
        Path titleDir = actressDir.resolve("Yua Mikami - Demosaiced (SNIS-786)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        assertEquals(titleDir, results.get(0).path());
    }

    @Test
    void discoversCodeWithVariantSuffix() throws IOException {
        // e.g. (SSIS-509_4K), (SNIS-986-AI)
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Yua Mikami");
        Path title4K = actressDir.resolve("Yua Mikami (SSIS-509_4K)");
        Path titleAI = actressDir.resolve("Yua Mikami - Demosaiced (SNIS-986-AI)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(title4K, titleAI));
        when(fs.isDirectory(title4K)).thenReturn(true);
        when(fs.isDirectory(titleAI)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(2, results.size());
    }

    // --- Paren-code folders (no actress prefix) ---

    @Test
    void handlesParenCodeFolders() throws IOException {
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Aika");
        Path parenTitle = actressDir.resolve("(BLK-162)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(parenTitle));
        when(fs.isDirectory(parenTitle)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        assertEquals("Aika", results.get(0).actressNames().get(0));
        assertEquals(parenTitle, results.get(0).path());
    }

    // --- Subfolder recursion ---

    @Test
    void recursesIntoSubfolders() throws IOException {
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Sora Shiina");
        Path favDir = actressDir.resolve("favorites");
        Path directTitle = actressDir.resolve("Sora Shiina (WANZ-575)");
        Path subTitle = favDir.resolve("Sora Shiina (HND-305)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(directTitle, favDir));
        when(fs.isDirectory(directTitle)).thenReturn(true);
        when(fs.isDirectory(favDir)).thenReturn(true);
        when(fs.listDirectory(favDir)).thenReturn(List.of(subTitle));
        when(fs.isDirectory(subTitle)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(d -> d.path().equals(directTitle)));
        assertTrue(results.stream().anyMatch(d -> d.path().equals(subTitle)));
        // Both attributed to the actress folder, not the subfolder
        assertTrue(results.stream().allMatch(d -> "Sora Shiina".equals(d.actressNames().get(0))));
    }

    @Test
    void recursesIntoUnderscorePrefixedSubfolder() throws IOException {
        // _favorites is a real subfolder name on QNAP
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Mana Sakura");
        Path underscoreFav = actressDir.resolve("_favorites");
        Path titleInSub = underscoreFav.resolve("Mana Sakura (STAR-753)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(underscoreFav));
        when(fs.isDirectory(underscoreFav)).thenReturn(true);
        when(fs.listDirectory(underscoreFav)).thenReturn(List.of(titleInSub));
        when(fs.isDirectory(titleInSub)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        assertEquals("Mana Sakura", results.get(0).actressNames().get(0));
        assertEquals(titleInSub, results.get(0).path());
    }

    @Test
    void recursesIntoWeirdlyNamedSubfolders() throws IOException {
        // Real examples: "#Rio_Hamasaki, part 1", "a12738"
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Rio Hamasaki");
        Path weirdDir = actressDir.resolve("#Rio_Hamasaki, part 1");
        Path titleInSub = weirdDir.resolve("Rio Hamasaki (AKB-009)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(weirdDir));
        when(fs.isDirectory(weirdDir)).thenReturn(true);
        when(fs.listDirectory(weirdDir)).thenReturn(List.of(titleInSub));
        when(fs.isDirectory(titleInSub)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        assertEquals("Rio Hamasaki", results.get(0).actressNames().get(0));
    }

    @Test
    void recursesMultipleLevelsDeep() throws IOException {
        // subfolder → sub-subfolder → title
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Actress");
        Path sub1 = actressDir.resolve("group1");
        Path sub2 = sub1.resolve("subgroup");
        Path deepTitle = sub2.resolve("Actress (ABC-123)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(sub1));
        when(fs.isDirectory(sub1)).thenReturn(true);
        when(fs.listDirectory(sub1)).thenReturn(List.of(sub2));
        when(fs.isDirectory(sub2)).thenReturn(true);
        when(fs.listDirectory(sub2)).thenReturn(List.of(deepTitle));
        when(fs.isDirectory(deepTitle)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        assertEquals(deepTitle, results.get(0).path());
    }

    // --- Skip behavior ---

    @Test
    void skipsCoverFolders() throws IOException {
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Eimi Fukada");
        Path coversDir = actressDir.resolve("covers");
        Path titleDir = actressDir.resolve("Eimi Fukada (MIAA-085)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(coversDir, titleDir));
        when(fs.isDirectory(coversDir)).thenReturn(true);
        when(fs.isDirectory(titleDir)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        verify(fs, never()).listDirectory(coversDir);
    }

    @Test
    void skipsAllCoverFolderVariants() throws IOException {
        // Real: "covers", "_covers", "cover" all observed on QNAP
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Kaho Kasumi");
        Path covers = actressDir.resolve("covers");
        Path _covers = actressDir.resolve("_covers");
        Path cover = actressDir.resolve("cover");
        Path titleDir = actressDir.resolve("Kaho Kasumi (WANZ-305)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(covers, _covers, cover, titleDir));
        when(fs.isDirectory(covers)).thenReturn(true);
        when(fs.isDirectory(_covers)).thenReturn(true);
        when(fs.isDirectory(cover)).thenReturn(true);
        when(fs.isDirectory(titleDir)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        verify(fs, never()).listDirectory(covers);
        verify(fs, never()).listDirectory(_covers);
        verify(fs, never()).listDirectory(cover);
    }

    @Test
    void skipsTempAtTopLevel() throws IOException {
        Path starsRoot = Path.of("/stars");
        Path tempDir = starsRoot.resolve("temp");
        Path actressDir = starsRoot.resolve("Julia");
        Path titleDir = actressDir.resolve("Julia (MIDE-517)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(tempDir, actressDir));
        when(fs.isDirectory(tempDir)).thenReturn(true);
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        verify(fs, never()).listDirectory(tempDir);
    }

    // --- False positive prevention ---

    @Test
    void doesNotTreatNonParenCodeFolderAsTitle() throws IOException {
        // "xxx-av.com-21090-FHD" has "com-21090" which matches a loose JAV code pattern
        // but has no parenthesized code — should be recursed into, not treated as a title
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Serina Hayakawa");
        Path weirdDir = actressDir.resolve("xxx-av.com-21090-FHD");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(weirdDir));
        when(fs.isDirectory(weirdDir)).thenReturn(true);
        when(fs.listDirectory(weirdDir)).thenReturn(List.of()); // nothing inside

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        // Should recurse into it (treating as subfolder) and find nothing — not index it as a title
        assertEquals(0, results.size());
        verify(fs).listDirectory(weirdDir); // proves it was recursed into
    }

    // --- Loose files ignored ---

    @Test
    void ignoresLooseFilesInActressFolder() throws IOException {
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Eimi Fukada");
        Path looseImage = actressDir.resolve("miaa051pl.jpg");
        Path titleDir = actressDir.resolve("Eimi Fukada (MIAA-051)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(looseImage, titleDir));
        when(fs.isDirectory(looseImage)).thenReturn(false);
        when(fs.isDirectory(titleDir)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
        assertEquals(titleDir, results.get(0).path());
    }

    @Test
    void ignoresLooseFilesAtTopLevel() throws IOException {
        // "files.txt" and "to add.txt" exist at stars/ root
        Path starsRoot = Path.of("/stars");
        Path looseFile = starsRoot.resolve("files.txt");
        Path actressDir = starsRoot.resolve("Julia");
        Path titleDir = actressDir.resolve("Julia (MIDE-517)");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(looseFile, actressDir));
        when(fs.isDirectory(looseFile)).thenReturn(false);
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertEquals(1, results.size());
    }

    // --- Empty / missing cases ---

    @Test
    void returnsEmptyWhenStarsRootMissing() throws IOException {
        when(fs.exists(Path.of("/stars"))).thenReturn(false);

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertTrue(results.isEmpty());
    }

    @Test
    void handlesEmptyActressFolder() throws IOException {
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Empty Actress");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of());

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertTrue(results.isEmpty());
    }

    @Test
    void handlesEmptySubfolder() throws IOException {
        Path starsRoot = Path.of("/stars");
        Path actressDir = starsRoot.resolve("Actress");
        Path emptySubfolder = actressDir.resolve("meh");

        when(fs.exists(starsRoot)).thenReturn(true);
        when(fs.listDirectory(starsRoot)).thenReturn(List.of(actressDir));
        when(fs.isDirectory(actressDir)).thenReturn(true);
        when(fs.listDirectory(actressDir)).thenReturn(List.of(emptySubfolder));
        when(fs.isDirectory(emptySubfolder)).thenReturn(true);
        when(fs.listDirectory(emptySubfolder)).thenReturn(List.of());

        List<DiscoveredTitle> results = new ExhibitionScanner().scan(structure, fs, io);

        assertTrue(results.isEmpty());
    }
}
