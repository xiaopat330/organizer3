package com.organizer3.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canary test: verifies that the sandbox infrastructure is wired end-to-end.
 * Creates a 1-byte file on the NAS and asserts it appears in a directory listing.
 * Kept long-term to catch regressions in the harness itself.
 */
class ConnectivitySandboxTest extends SandboxTestBase {

    @Test
    void canWriteAndListFileOnNas() throws Exception {
        Path file = methodRunDir.resolve("canary.txt");
        fs.writeFile(file, new byte[]{1});

        List<Path> entries = fs.listDirectory(methodRunDir);
        assertTrue(
                entries.stream().anyMatch(p -> p.getFileName().toString().equals("canary.txt")),
                "Expected canary.txt in directory listing of " + methodRunDir);
    }
}
