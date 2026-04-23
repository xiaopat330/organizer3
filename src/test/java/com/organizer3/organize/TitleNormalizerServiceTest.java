package com.organizer3.organize;

import com.organizer3.config.volume.MediaConfig;
import com.organizer3.config.volume.NormalizeConfig;
import com.organizer3.filesystem.LocalFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TitleNormalizerServiceTest {

    @TempDir Path tempDir;

    private LocalFileSystem fs;
    private TitleNormalizerService svc;

    @BeforeEach
    void setUp() {
        fs = new LocalFileSystem();
        svc = new TitleNormalizerService(MediaConfig.DEFAULTS);
    }

    // ── cover rename ────────────────────────────────────────────────────────

    @Test
    void singleCover_renamesToCanonical() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123pl.jpg"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(Files.exists(t.resolve("MIDE-123.jpg")));
        assertFalse(Files.exists(t.resolve("mide123pl.jpg")));
        assertEquals(1, r.applied().size());
        assertEquals("cover-rename", r.applied().get(0).op());
    }

    @Test
    void coverAlreadyCanonical_noOp() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.applied().isEmpty());
        assertTrue(r.skipped().stream().anyMatch(s -> s.reason().contains("already canonical")));
    }

    @Test
    void multipleCovers_skipped() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123pl.jpg"));
        Files.createFile(t.resolve("mide123pl (2).jpg"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.applied().isEmpty());
        assertTrue(r.skipped().stream().anyMatch(s ->
                "cover".equals(s.kind()) && s.reason().contains("multiple covers")));
    }

    @Test
    void noCover_reported() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.skipped().stream().anyMatch(s ->
                "cover".equals(s.kind()) && s.reason().contains("no cover at base")));
    }

    // ── video rename ────────────────────────────────────────────────────────

    @Test
    void singleVideoAtBase_renamesToCanonical() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123pl.jpg"));
        Files.createFile(t.resolve("random-upload-name.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(Files.exists(t.resolve("MIDE-123.mp4")));
        assertFalse(Files.exists(t.resolve("random-upload-name.mp4")));
        assertEquals(2, r.applied().size(), "cover + video both renamed");
    }

    @Test
    void videoWithDashSuffix_preservesFreeform() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("ONED-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("ONED-123-h265.mkv"));

        svc.apply(fs, t, "ONED-00123", false);

        assertTrue(Files.exists(videoDir.resolve("ONED-00123-h265.mkv")));
        assertFalse(Files.exists(videoDir.resolve("ONED-123-h265.mkv")));
    }

    @Test
    void videoWithUnderscoreSuffix_preservesFreeform() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("ONED-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("ONED-123_4K.mkv"));

        svc.apply(fs, t, "ONED-00123", false);

        assertTrue(Files.exists(videoDir.resolve("ONED-00123_4K.mkv")));
    }

    @Test
    void videoAlreadyCanonicalWithSuffix_noOp() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("MIDE-123-h265.mkv"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.applied().isEmpty());
        assertTrue(r.skipped().stream().anyMatch(s ->
                "video".equals(s.kind()) && s.reason().contains("already canonical")));
    }

    // ── freeformSuffix helper ───────────────────────────────────────────────

    @Test
    void freeformSuffix_dashH265() {
        assertEquals("-h265", TitleNormalizerService.freeformSuffix("ONED-999-h265.mkv"));
    }

    @Test
    void freeformSuffix_underscore4K() {
        assertEquals("_4K", TitleNormalizerService.freeformSuffix("ONED-999_4K.mkv"));
    }

    @Test
    void freeformSuffix_noSuffix() {
        assertEquals("", TitleNormalizerService.freeformSuffix("MIDE-123.mp4"));
    }

    @Test
    void freeformSuffix_randomName() {
        assertEquals("", TitleNormalizerService.freeformSuffix("random-upload-name.mp4"));
    }

    @Test
    void singleVideoInSubfolder_renamesInPlace() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("random-upload-name.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(Files.exists(videoDir.resolve("MIDE-123.mp4")));
        assertEquals(1, r.applied().size());
        assertEquals("video-rename", r.applied().get(0).op());
    }

    @Test
    void multipleVideos_skipped() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("disc1.mp4"));
        Files.createFile(videoDir.resolve("disc2.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.skipped().stream().anyMatch(s ->
                "video".equals(s.kind()) && s.reason().contains("multi-file title")));
    }

    @Test
    void videoAlreadyCanonical_noOp() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("MIDE-123.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.applied().isEmpty());
        assertTrue(r.skipped().stream().anyMatch(s ->
                "video".equals(s.kind()) && s.reason().contains("already canonical")));
    }

    @Test
    void videoAtBaseAndInSubfolder_countedTogetherAsMulti() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("foo.mp4"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("bar.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.skipped().stream().anyMatch(s -> s.reason().contains("multi-file")));
    }

    @Test
    void coverAlreadyCanonical_caseInsensitive_noOp() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide-123.jpg"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.applied().isEmpty(), "case-only rename must not be attempted");
        assertTrue(r.skipped().stream().anyMatch(s ->
                "cover".equals(s.kind()) && s.reason().contains("already canonical")));
    }

    @Test
    void videoAlreadyCanonical_caseInsensitive_noOp() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("mide-123.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.applied().isEmpty(), "case-only rename must not be attempted");
        assertTrue(r.skipped().stream().anyMatch(s ->
                "video".equals(s.kind()) && s.reason().contains("already canonical")));
    }

    // ── dry-run ─────────────────────────────────────────────────────────────

    @Test
    void dryRun_producesPlanWithoutRenaming() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123pl.jpg"));

        var r = svc.apply(fs, t, "MIDE-123", true);

        assertTrue(r.dryRun());
        assertEquals(1, r.planned().size());
        assertTrue(r.applied().isEmpty());
        assertTrue(Files.exists(t.resolve("mide123pl.jpg")), "dry-run must leave file untouched");
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    void rejectsMissingFolder() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.apply(fs, tempDir.resolve("nope"), "X-1", true));
    }

    @Test
    void rejectsBlankCode() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.apply(fs, t, " ", true));
    }

    @Test
    void preservesExtensionCase() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123.JPEG"));

        svc.apply(fs, t, "MIDE-123", false);

        assertTrue(Files.exists(t.resolve("MIDE-123.JPEG")));
    }

    // ── numeric-prefix label (e.g. 300MIUM) ────────────────────────────────

    @Test
    void freeformSuffix_numericPrefixLabel_dashH265() {
        assertEquals("-h265", TitleNormalizerService.freeformSuffix("300MIUM-1355-h265.mkv"));
    }

    @Test
    void freeformSuffix_numericPrefixLabel_underscore4K() {
        assertEquals("_4K", TitleNormalizerService.freeformSuffix("300MIUM-1355_4K.mkv"));
    }

    @Test
    void freeformSuffix_numericPrefixLabel_noSuffix() {
        assertEquals("", TitleNormalizerService.freeformSuffix("300MIUM-1355.mkv"));
    }

    @Test
    void videoNumericPrefixLabel_preservesFreeform() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIUM-1355.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("h265"));
        Files.createFile(videoDir.resolve("300MIUM-1355-h265.mkv"));

        // titleCode as stored in DB (TitleCodeParser strips the 300 prefix)
        svc.apply(fs, t, "MIUM-1355", false);

        assertTrue(Files.exists(videoDir.resolve("MIUM-1355-h265.mkv")));
        assertFalse(Files.exists(videoDir.resolve("300MIUM-1355-h265.mkv")));
    }

    @Test
    void videoWithSeparatorVariantInCode_noDoubleSuffix() throws IOException {
        // titleCode has _4K (underscore), file uses -4k (dash) — must rename cleanly, no -4k appended
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("START-488_4K.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("start-488-4k.mp4"));

        var r = svc.apply(fs, t, "START-488_4K", false);

        assertTrue(Files.exists(videoDir.resolve("START-488_4K.mp4")),
                "file must become START-488_4K.mp4, not START-488_4K-4k.mp4");
        assertFalse(Files.exists(videoDir.resolve("start-488-4k.mp4")));
        assertEquals(1, r.applied().size(), "video renamed");
        assertEquals("video-rename", r.applied().get(0).op());
    }

    @Test
    void videoWithSuffixInCode_noDoubleSuffix() throws IOException {
        // titleCode contains _4K (put there by FreshPrepService); file has _4K-h265.
        // Bug: _4K was applied twice → MIKR-055_4K_4K-h265.mkv. Fix: prefix-strip titleCode first.
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIKR-055_4K.jpg"));
        Path h265Dir = Files.createDirectory(t.resolve("h265"));
        Files.createFile(h265Dir.resolve("MIKR-055_4K-h265.mkv"));

        var r = svc.apply(fs, t, "MIKR-055_4K", false);

        assertTrue(Files.exists(h265Dir.resolve("MIKR-055_4K-h265.mkv")),
                "file must stay MIKR-055_4K-h265.mkv, not become MIKR-055_4K_4K-h265.mkv");
        assertTrue(r.applied().isEmpty(), "already canonical — no rename needed");
    }

    // ── removelist prefix stripping ─────────────────────────────────────────

    @Test
    void removelist_stripsPrefixBeforeFreeformExtraction() throws IOException {
        var norm = new NormalizeConfig(List.of("hhd800.com@"), List.of());
        var svcWithNorm = new TitleNormalizerService(MediaConfig.DEFAULTS, norm);

        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("PRED-848.jpg"));
        Path h265Dir = Files.createDirectory(t.resolve("h265"));
        Files.createFile(h265Dir.resolve("hhd800.com@PRED-848-h265.mkv"));

        svcWithNorm.apply(fs, t, "PRED-848", false);

        assertTrue(Files.exists(h265Dir.resolve("PRED-848-h265.mkv")),
                "prefix stripped and -h265 preserved");
        assertFalse(Files.exists(h265Dir.resolve("hhd800.com@PRED-848-h265.mkv")));
    }

    @Test
    void removelist_alreadyCanonicalAfterStrip() throws IOException {
        var norm = new NormalizeConfig(List.of("hhd800.com@"), List.of());
        var svcWithNorm = new TitleNormalizerService(MediaConfig.DEFAULTS, norm);

        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("PRED-848.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        // File already has canonical name — no rename needed
        Files.createFile(videoDir.resolve("PRED-848.mp4"));

        var r = svcWithNorm.apply(fs, t, "PRED-848", false);

        assertTrue(r.applied().isEmpty());
        assertTrue(r.skipped().stream().anyMatch(s -> s.reason().contains("already canonical")));
    }
}
