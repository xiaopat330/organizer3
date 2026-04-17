package com.organizer3.organize;

import com.organizer3.config.volume.MediaConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FreshAuditServiceTest {

    private FakeFS fs;
    private FreshAuditService svc;

    @BeforeEach
    void setUp() {
        fs  = new FakeFS();
        svc = new FreshAuditService(MediaConfig.DEFAULTS);
    }

    @Test
    void ready_actressPrefix_withCoverAndVideo() throws IOException {
        fs.mkdir("/fresh");
        fs.mkdir("/fresh/Yua Aida (ONED-1234)");
        fs.file("/fresh/Yua Aida (ONED-1234)/cover.jpg");
        fs.mkdir("/fresh/Yua Aida (ONED-1234)/h265");
        fs.file("/fresh/Yua Aida (ONED-1234)/h265/ONED-1234-h265.mkv");

        var r = svc.audit(fs, Path.of("/fresh"));
        assertEquals(1, r.total());
        assertEquals(1, r.counts().get(FreshAuditService.Bucket.READY));
        var e = r.entries().get(0);
        assertEquals("Yua Aida (ONED-1234)", e.folderName());
        assertEquals(FreshAuditService.Bucket.READY, e.bucket());
        assertTrue(e.hasCoverAtBase());
        assertTrue(e.hasVideoInside());
    }

    @Test
    void needsActress_bareCodeFolder() throws IOException {
        fs.mkdir("/fresh");
        fs.mkdir("/fresh/(ONED-1234)");
        fs.mkdir("/fresh/(ONED-1234)/h265");
        fs.file("/fresh/(ONED-1234)/h265/ONED-1234-h265.mkv");

        var r = svc.audit(fs, Path.of("/fresh"));
        assertEquals(FreshAuditService.Bucket.NEEDS_ACTRESS, r.entries().get(0).bucket());
    }

    @Test
    void needsCover_actressPrefixButNoCover() throws IOException {
        fs.mkdir("/fresh");
        fs.mkdir("/fresh/Yua Aida (ONED-1234)");
        fs.mkdir("/fresh/Yua Aida (ONED-1234)/h265");
        fs.file("/fresh/Yua Aida (ONED-1234)/h265/ONED-1234-h265.mkv");

        var r = svc.audit(fs, Path.of("/fresh"));
        assertEquals(FreshAuditService.Bucket.NEEDS_COVER, r.entries().get(0).bucket());
    }

    @Test
    void empty_skeletonShapedButNoVideo() throws IOException {
        fs.mkdir("/fresh");
        fs.mkdir("/fresh/(ONED-1234)");
        fs.mkdir("/fresh/(ONED-1234)/h265");  // empty subfolder

        var r = svc.audit(fs, Path.of("/fresh"));
        assertEquals(FreshAuditService.Bucket.EMPTY, r.entries().get(0).bucket());
    }

    @Test
    void other_folderDoesNotMatchAnySkeletonShape() throws IOException {
        fs.mkdir("/fresh");
        fs.mkdir("/fresh/_JAV_Ameri_Ichinose");  // actress workspace
        fs.file("/fresh/_JAV_Ameri_Ichinose/whatever.mkv");

        var r = svc.audit(fs, Path.of("/fresh"));
        assertEquals(FreshAuditService.Bucket.OTHER, r.entries().get(0).bucket());
    }

    @Test
    void videoAtFolderBase_alsoCountsAsHasVideo() throws IOException {
        // occasional layout: video directly at base, not in subfolder
        fs.mkdir("/fresh");
        fs.mkdir("/fresh/Yua Aida (ONED-1234)");
        fs.file("/fresh/Yua Aida (ONED-1234)/ONED-1234.mkv");
        fs.file("/fresh/Yua Aida (ONED-1234)/cover.jpg");

        var r = svc.audit(fs, Path.of("/fresh"));
        assertEquals(FreshAuditService.Bucket.READY, r.entries().get(0).bucket());
    }

    @Test
    void coverExtensions_jpegPngWebp_allDetected() throws IOException {
        for (String ext : List.of("jpg", "jpeg", "png", "webp")) {
            var localFs = new FakeFS();
            localFs.mkdir("/fresh");
            localFs.mkdir("/fresh/X Y (ABC-1)");
            localFs.file("/fresh/X Y (ABC-1)/cover." + ext);
            localFs.mkdir("/fresh/X Y (ABC-1)/h265");
            localFs.file("/fresh/X Y (ABC-1)/h265/ABC-1-h265.mkv");
            var r = svc.audit(localFs, Path.of("/fresh"));
            assertEquals(FreshAuditService.Bucket.READY, r.entries().get(0).bucket(),
                    "cover." + ext + " should count");
        }
    }

    @Test
    void mixedPartition_countsByBucket() throws IOException {
        fs.mkdir("/fresh");
        // READY
        fs.mkdir("/fresh/A (AAA-1)"); fs.file("/fresh/A (AAA-1)/cover.jpg");
        fs.mkdir("/fresh/A (AAA-1)/h265"); fs.file("/fresh/A (AAA-1)/h265/AAA-1-h265.mkv");
        // NEEDS_COVER
        fs.mkdir("/fresh/B (BBB-2)");
        fs.mkdir("/fresh/B (BBB-2)/h265"); fs.file("/fresh/B (BBB-2)/h265/BBB-2-h265.mkv");
        // NEEDS_ACTRESS
        fs.mkdir("/fresh/(CCC-3)");
        fs.mkdir("/fresh/(CCC-3)/h265"); fs.file("/fresh/(CCC-3)/h265/CCC-3-h265.mkv");
        // EMPTY
        fs.mkdir("/fresh/(DDD-4)");
        // OTHER
        fs.mkdir("/fresh/_scratch");
        fs.file("/fresh/_scratch/ignore.mkv");

        var r = svc.audit(fs, Path.of("/fresh"));
        assertEquals(5, r.total());
        assertEquals(1, r.counts().get(FreshAuditService.Bucket.READY));
        assertEquals(1, r.counts().get(FreshAuditService.Bucket.NEEDS_COVER));
        assertEquals(1, r.counts().get(FreshAuditService.Bucket.NEEDS_ACTRESS));
        assertEquals(1, r.counts().get(FreshAuditService.Bucket.EMPTY));
        assertEquals(1, r.counts().get(FreshAuditService.Bucket.OTHER));
    }

    @Test
    void ignoresFilesAtPartitionRoot() throws IOException {
        fs.mkdir("/fresh");
        fs.file("/fresh/hhd800.com@XYZ-001-h265.mkv");   // un-prepped raw — not our concern
        fs.file("/fresh/Thumbs.db");

        var r = svc.audit(fs, Path.of("/fresh"));
        assertEquals(0, r.total());
    }

    @Test
    void rejectsMissingPartitionRoot() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> svc.audit(fs, Path.of("/does-not-exist")));
    }

    @Test
    void lastModifiedIsCapturedFromFs() throws IOException {
        fs.mkdir("/fresh");
        fs.mkdir("/fresh/(AAA-1)");
        fs.mkdir("/fresh/(AAA-1)/h265");
        fs.file("/fresh/(AAA-1)/h265/AAA-1-h265.mkv");
        fs.setMtime("/fresh/(AAA-1)", LocalDate.of(2026, 3, 15));

        var r = svc.audit(fs, Path.of("/fresh"));
        assertEquals(LocalDate.of(2026, 3, 15), r.entries().get(0).lastModified());
    }

    // ── in-memory VolumeFileSystem ─────────────────────────────────────────

    static final class FakeFS implements VolumeFileSystem {
        private final Map<Path, Boolean>   nodes  = new HashMap<>();
        private final Map<Path, LocalDate> mtimes = new HashMap<>();

        void mkdir(String p) {
            Path path = Path.of(p);
            Path cur  = path;
            while (cur != null) { nodes.put(cur, true); cur = cur.getParent(); }
        }
        void file(String p) {
            Path path = Path.of(p);
            nodes.put(path, false);
            Path parent = path.getParent();
            while (parent != null) { nodes.put(parent, true); parent = parent.getParent(); }
        }
        void setMtime(String p, LocalDate d) { mtimes.put(Path.of(p), d); }

        @Override public List<Path> listDirectory(Path path) {
            List<Path> out = new ArrayList<>();
            String parent = path.toString();
            for (Path p : nodes.keySet()) {
                Path pp = p.getParent();
                if (pp != null && pp.toString().equals(parent)) out.add(p);
            }
            return out;
        }
        @Override public List<Path> walk(Path root)            { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)             { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path)        { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path p) { return mtimes.get(p); }
        @Override public InputStream openFile(Path p) throws IOException { throw new IOException("n/a"); }

        @Override public void move(Path source, Path destination) {
            Boolean kind = nodes.remove(source);
            if (kind != null) nodes.put(destination, kind);
        }
        @Override public void rename(Path p, String newName)   { move(p, p.resolveSibling(newName)); }
        @Override public void createDirectories(Path path) {
            Path cur = path;
            while (cur != null && !cur.equals(Path.of("/"))) { nodes.put(cur, true); cur = cur.getParent(); }
        }
        @Override public void writeFile(Path path, byte[] body) { nodes.put(path, false); }
        @Override public com.organizer3.filesystem.FileTimestamps getTimestamps(Path p) {
            return new com.organizer3.filesystem.FileTimestamps(null, null, null);
        }
        @Override public void setTimestamps(Path p, Instant c, Instant m) {}
    }
}
