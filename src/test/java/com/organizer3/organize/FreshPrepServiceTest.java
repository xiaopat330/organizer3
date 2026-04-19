package com.organizer3.organize;

import com.organizer3.config.volume.MediaConfig;
import com.organizer3.config.volume.NormalizeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FreshPrepServiceTest {

    private FakeFS fs;
    private FreshPrepService svc;

    @BeforeEach
    void setUp() {
        fs  = new FakeFS();
        NormalizeConfig n = new NormalizeConfig(
                List.of("hhd800.com@", "foo.com@", "-h264", "-1080P"),
                List.of(new NormalizeConfig.Replace("FC2-PPV", "FC2PPV"),
                        new NormalizeConfig.Replace("FC2PPV ", "FC2PPV-")));
        svc = new FreshPrepService(n, MediaConfig.DEFAULTS);
    }

    // ── planOne algorithm cases ─────────────────────────────────────────────

    @Test
    void basicPrefixStrip_h265Hint() {
        fs.mkdir("/fresh");
        var p = svc.planOne(Path.of("/fresh"), "hhd800.com@JUR-717-h265.mkv");
        assertEquals("/fresh/(JUR-717)",                           p.targetFolder());
        assertEquals("/fresh/(JUR-717)/h265",                      p.targetSubfolder());
        assertEquals("/fresh/(JUR-717)/h265/JUR-717-h265.mkv",     p.targetVideoPath());
        assertEquals("JUR-717",                                     p.code());
        assertEquals("h265",                                        p.subfolderName());
    }

    @Test
    void digitPrefixLabel() {
        var p = svc.planOne(Path.of("/fresh"), "hhd800.com@300MIUM-1353-h265.mkv");
        assertEquals("/fresh/(300MIUM-1353)",                       p.targetFolder());
        assertEquals("/fresh/(300MIUM-1353)/h265",                  p.targetSubfolder());
        assertEquals("/fresh/(300MIUM-1353)/h265/300MIUM-1353-h265.mkv", p.targetVideoPath());
    }

    @Test
    void suffixAfterEncoding_movesAdjacentToCodeInFolder() {
        // critical ordering case: _4K lives after -h265 in the input
        var p = svc.planOne(Path.of("/fresh"), "foo.com@ONED-999-h265_4K.mp4");
        assertEquals("/fresh/(ONED-999_4K)",                        p.targetFolder());
        assertEquals("h265",                                        p.subfolderName());
        assertEquals("/fresh/(ONED-999_4K)/h265/ONED-999-h265_4K.mp4", p.targetVideoPath());
    }

    @Test
    void suffixBeforeEncoding() {
        var p = svc.planOne(Path.of("/fresh"), "foo.com@ONED-999_U-h265.mkv");
        assertEquals("/fresh/(ONED-999_U)",                         p.targetFolder());
        assertEquals("/fresh/(ONED-999_U)/h265/ONED-999_U-h265.mkv", p.targetVideoPath());
    }

    @Test
    void noEncodingHint_usesVideoSubfolder() {
        var p = svc.planOne(Path.of("/fresh"), "hhd800.com@ABC-123.mp4");
        assertEquals("/fresh/(ABC-123)",                            p.targetFolder());
        assertEquals("video",                                       p.subfolderName());
        assertEquals("/fresh/(ABC-123)/video/ABC-123.mp4",          p.targetVideoPath());
    }

    @Test
    void pairedFilesProducePairedFolders() {
        var a = svc.planOne(Path.of("/fresh"), "hhd800.com@JUR-717-h265_a.mkv");
        var b = svc.planOne(Path.of("/fresh"), "hhd800.com@JUR-717-h265_b.mkv");
        assertEquals("/fresh/(JUR-717_A)", a.targetFolder());
        assertEquals("/fresh/(JUR-717_B)", b.targetFolder());
    }

    @Test
    void fc2PpvReplacelist_applied() {
        var p = svc.planOne(Path.of("/fresh"), "FC2-PPV-1234567-h265.mp4");
        assertEquals("/fresh/(FC2PPV-1234567)",                     p.targetFolder());
        assertEquals("/fresh/(FC2PPV-1234567)/h265/FC2PPV-1234567-h265.mp4", p.targetVideoPath());
    }

    @Test
    void lowercaseCodeIsUppercased_remainderPreservesCase() {
        var p = svc.planOne(Path.of("/fresh"), "hhd800.com@jur-717-h265.mkv");
        // code region uppercased, encoding + ext preserved as-is
        assertEquals("/fresh/(JUR-717)",                            p.targetFolder());
        assertEquals("/fresh/(JUR-717)/h265/JUR-717-h265.mkv",      p.targetVideoPath());
    }

    @Test
    void encodingTokenIsNotStrippedEvenIfInRemovelist() {
        // removelist includes "-h264" — must not be applied to encoding tokens
        var p = svc.planOne(Path.of("/fresh"), "hhd800.com@XYZ-001-h264.mp4");
        assertEquals("/fresh/(XYZ-001)",                            p.targetFolder());
        // h264 hint preserved in the video filename
        assertEquals("/fresh/(XYZ-001)/video/XYZ-001-h264.mp4",     p.targetVideoPath());
    }

    @Test
    void unparseable_returnsNull() {
        assertNull(svc.planOne(Path.of("/fresh"), "Thumbs.db"));
        assertNull(svc.planOne(Path.of("/fresh"), "random-garbage-no-code.mp4"));
    }

    // ── suffix-label exception codes: 1PON / CARIB kept literal ─────────────

    @Test
    void suffixLabel_1PON_keptLiteral_h265() {
        var p = svc.planOne(Path.of("/fresh"), "hhd800.com@041126_001-1PON-h265.mkv");
        assertEquals("041126_001-1PON",                             p.code());
        assertEquals("/fresh/(041126_001-1PON)",                    p.targetFolder());
        assertEquals("/fresh/(041126_001-1PON)/h265",               p.targetSubfolder());
        assertEquals("/fresh/(041126_001-1PON)/h265/041126_001-1PON-h265.mkv",
                     p.targetVideoPath());
    }

    @Test
    void suffixLabel_CARIB_keptLiteral_noEncoding() {
        var p = svc.planOne(Path.of("/fresh"), "hhd800.com@013126-001-CARIB.mp4");
        assertEquals("013126-001-CARIB",                            p.code());
        assertEquals("/fresh/(013126-001-CARIB)",                   p.targetFolder());
        assertEquals("video",                                       p.subfolderName());
        assertEquals("/fresh/(013126-001-CARIB)/video/013126-001-CARIB.mp4",
                     p.targetVideoPath());
    }

    @Test
    void fc2PpvSpace_normalizedToDash() {
        // "FC2PPV 2009419" (space separator) → replacelist converts to "FC2PPV-2009419" before parse
        var p = svc.planOne(Path.of("/fresh"), "FC2PPV 2009419-h265.mkv");
        assertEquals("FC2PPV-2009419",                              p.code());
        assertEquals("/fresh/(FC2PPV-2009419)/h265/FC2PPV-2009419-h265.mkv", p.targetVideoPath());
    }

    @Test
    void suffixLabel_caseIsPreservedLiterally() {
        // no uppercasing — whatever case comes in stays
        var p = svc.planOne(Path.of("/fresh"), "hhd800.com@050825_001-carib-h265.mkv");
        assertEquals("050825_001-carib",                            p.code());
        assertEquals("/fresh/(050825_001-carib)",                   p.targetFolder());
    }

    // ── run-level: plan / execute / collisions / skips ──────────────────────

    @Test
    void plan_filtersOutThumbsAndDirectoriesAndNonVideos() throws IOException {
        fs.mkdir("/fresh");
        fs.mkdir("/fresh/(ALREADY-PREPPED)");
        fs.file("/fresh/Thumbs.db");
        fs.file("/fresh/readme.txt");
        fs.file("/fresh/hhd800.com@JUR-717-h265.mkv");

        var r = svc.plan(fs, Path.of("/fresh"), 0, 0);
        assertTrue(r.dryRun());
        assertEquals(1, r.totalVideosAtRoot());
        assertEquals(1, r.planned().size());
        assertEquals("/fresh/(JUR-717)", r.planned().get(0).targetFolder());
    }

    @Test
    void plan_skipsWhenTargetFolderExists() throws IOException {
        fs.mkdir("/fresh");
        fs.mkdir("/fresh/(JUR-717)");                  // collision
        fs.file("/fresh/hhd800.com@JUR-717-h265.mkv");

        var r = svc.plan(fs, Path.of("/fresh"), 0, 0);
        assertEquals(0, r.planned().size());
        assertEquals(1, r.skipped().size());
        assertTrue(r.skipped().get(0).reason().contains("already exists"));
    }

    @Test
    void plan_skipsUnparseableWithReason() throws IOException {
        fs.mkdir("/fresh");
        fs.file("/fresh/garbage-no-code.mkv");

        var r = svc.plan(fs, Path.of("/fresh"), 0, 0);
        assertEquals(0, r.planned().size());
        assertEquals(1, r.skipped().size());
        assertTrue(r.skipped().get(0).reason().contains("unparseable"));
    }

    @Test
    void execute_movesFileAndCreatesSubfolder() throws IOException {
        fs.mkdir("/fresh");
        fs.file("/fresh/hhd800.com@JUR-717-h265.mkv");

        var r = svc.execute(fs, Path.of("/fresh"), 0, 0);
        assertFalse(r.dryRun());
        assertEquals(1, r.moved().size());
        assertTrue(r.failed().isEmpty());

        assertFalse(fs.exists(Path.of("/fresh/hhd800.com@JUR-717-h265.mkv")));
        assertTrue(fs.exists(Path.of("/fresh/(JUR-717)")));
        assertTrue(fs.exists(Path.of("/fresh/(JUR-717)/h265")));
        assertTrue(fs.exists(Path.of("/fresh/(JUR-717)/h265/JUR-717-h265.mkv")));
    }

    @Test
    void pagination_limitAndOffset() throws IOException {
        fs.mkdir("/fresh");
        fs.file("/fresh/hhd800.com@AAA-001-h265.mkv");
        fs.file("/fresh/hhd800.com@BBB-002-h265.mkv");
        fs.file("/fresh/hhd800.com@CCC-003-h265.mkv");

        var r1 = svc.plan(fs, Path.of("/fresh"), 2, 0);
        assertEquals(3, r1.totalVideosAtRoot());
        assertEquals(2, r1.planned().size());

        var r2 = svc.plan(fs, Path.of("/fresh"), 2, 2);
        assertEquals(1, r2.planned().size());
        assertTrue(r2.planned().get(0).targetFolder().contains("CCC-003"));
    }

    @Test
    void rejectsMissingPartitionRoot() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.plan(fs, Path.of("/does-not-exist"), 0, 0));
    }

    // ── in-memory VolumeFileSystem ─────────────────────────────────────────

    static final class FakeFS implements VolumeFileSystem {
        private final Map<Path, Boolean> nodes = new HashMap<>();

        void mkdir(String p) {
            Path path = Path.of(p);
            Path cur = path;
            while (cur != null) { nodes.put(cur, true); cur = cur.getParent(); }
        }
        void file(String p) {
            Path path = Path.of(p);
            nodes.put(path, false);
            Path parent = path.getParent();
            while (parent != null) { nodes.put(parent, true); parent = parent.getParent(); }
        }

        @Override public List<Path> listDirectory(Path path) {
            List<Path> out = new ArrayList<>();
            String parent = path.toString();
            for (Path p : nodes.keySet()) {
                Path pp = p.getParent();
                if (pp != null && pp.toString().equals(parent)) out.add(p);
            }
            return out;
        }
        @Override public List<Path> walk(Path root)             { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)              { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path)         { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path p)  { return null; }
        @Override public InputStream openFile(Path p) throws IOException { throw new IOException("n/a"); }
        @Override public long size(Path p) throws IOException { throw new IOException("n/a"); }

        @Override public void move(Path source, Path destination) {
            Boolean kind = nodes.remove(source);
            if (kind != null) nodes.put(destination, kind);
        }
        @Override public void rename(Path p, String newName)    { move(p, p.resolveSibling(newName)); }
        @Override public void createDirectories(Path path) {
            Path cur = path;
            while (cur != null && !cur.equals(Path.of("/"))) {
                nodes.put(cur, true);
                cur = cur.getParent();
            }
        }
        @Override public void writeFile(Path path, byte[] body) { nodes.put(path, false); }
        @Override public com.organizer3.filesystem.FileTimestamps getTimestamps(Path p) {
            return new com.organizer3.filesystem.FileTimestamps(null, null, null);
        }
        @Override public void setTimestamps(Path p, java.time.Instant c, java.time.Instant m) {}
    }
}
