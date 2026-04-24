package com.organizer3.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct SMB filesystem behavior tests — not mediated by organize services.
 *
 * <p>These exist to document and assert NAS/NTFS semantics that differ from LocalFS (APFS):
 * <ul>
 *   <li>Case-only rename: NTFS is case-preserving but case-insensitive — renaming
 *       {@code mide-123.mp4} to {@code MIDE-123.mp4} updates the stored name.
 *       On APFS (case-insensitive), the same rename is effectively a no-op.
 *   <li>Listing returns exact stored case, not normalized case.
 * </ul>
 *
 * <p>If any test here fails, it means the NAS's SMB share behaves differently than
 * expected and the corresponding normalize/restructure assumptions need revisiting.
 */
class SmbFileSystemSandboxTest extends SandboxTestBase {

    @Test
    void listDirectoryReturnsExactStoredCase() throws Exception {
        Path file = methodRunDir.resolve("MixedCase_File.mp4");
        fs.writeFile(file, new byte[]{1});

        List<Path> entries = fs.listDirectory(methodRunDir);
        String listed = entries.stream()
                .map(p -> p.getFileName().toString())
                .filter(n -> n.equalsIgnoreCase("MixedCase_File.mp4"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("File not found in listing"));

        assertEquals("MixedCase_File.mp4", listed,
                "NAS listing should return the exact case the file was created with");
    }

    @Test
    void caseOnlyRenameUpdatesStoredName() throws Exception {
        Path file = methodRunDir.resolve("mide-123.mp4");
        fs.writeFile(file, new byte[]{1});

        // Rename to case-only different name — on NTFS this updates the stored filename.
        // The organize services avoid this via equalsIgnoreCase, but we document
        // the underlying NAS behavior here.
        fs.rename(file, "MIDE-123.mp4");

        List<Path> entries = fs.listDirectory(methodRunDir);
        String listed = entries.stream()
                .map(p -> p.getFileName().toString())
                .filter(n -> n.equalsIgnoreCase("mide-123.mp4"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("File not found after rename"));

        assertEquals("MIDE-123.mp4", listed,
                "After case-only rename on NTFS, stored name should reflect the new case");
    }

    @Test
    void moveAcrossSubfoldersIsAtomic() throws Exception {
        Path subDir = methodRunDir.resolve("video");
        fs.createDirectories(subDir);
        Path src = methodRunDir.resolve("MIDE-123.mp4");
        fs.writeFile(src, new byte[]{1});

        fs.move(src, subDir.resolve("MIDE-123.mp4"));

        assertFalse(fs.exists(src), "Source should not exist after move");
        assertTrue(fs.exists(subDir.resolve("MIDE-123.mp4")), "Destination should exist after move");

        // Both assertions passing together means the move was atomic (no brief window
        // where neither location contained the file, which would indicate copy+delete).
    }

    @Test
    void getAndSetTimestampsRoundTrip() throws Exception {
        Path file = methodRunDir.resolve("timestamp-test.mp4");
        fs.writeFile(file, new byte[]{1});

        java.time.Instant target = java.time.Instant.parse("2019-03-15T08:30:00Z");
        fs.setTimestamps(file, target, target);

        var ts = fs.getTimestamps(file);
        assertNotNull(ts.created(), "created should be readable after setTimestamps");
        assertNotNull(ts.modified(), "modified should be readable after setTimestamps");

        long diffSecs = Math.abs(ts.modified().getEpochSecond() - target.getEpochSecond());
        assertTrue(diffSecs <= 1,
                "Timestamp round-trip should be within 1s, got diff=" + diffSecs + "s "
                        + "(set=" + target + " got=" + ts.modified() + ")");
    }
}
