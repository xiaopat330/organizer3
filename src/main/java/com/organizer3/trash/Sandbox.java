package com.organizer3.trash;

import com.organizer3.filesystem.VolumeFileSystem;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Sandbox primitive — a volume-aware scratch area owned by the app. See
 * {@code spec/PROPOSAL_SANDBOX.md}.
 *
 * <p>Volatile by convention: anything under {@link #root()} may be overwritten or removed
 * by the app at any time. Useful for integration tests that need to touch real SMB without
 * risking user data, and for future staging/buffering needs.
 *
 * <p>This class is intentionally a thin convenience wrapper — it does not enforce volatility,
 * namespace isolation, or cleanup. Callers are responsible for their own sub-paths.
 */
public class Sandbox {

    private final VolumeFileSystem fs;
    private final String sandboxFolder;  // e.g. "_sandbox"

    public Sandbox(VolumeFileSystem fs, String sandboxFolder) {
        if (fs == null) throw new IllegalArgumentException("fs is required");
        if (sandboxFolder == null || sandboxFolder.isBlank()) {
            throw new IllegalArgumentException("sandboxFolder is required");
        }
        this.fs = fs;
        this.sandboxFolder = sandboxFolder;
    }

    /** Absolute sandbox root on the current volume, e.g. {@code /_sandbox}. */
    public Path root() {
        return Path.of("/", sandboxFolder);
    }

    /** Resolve {@code subPath} inside the sandbox root. */
    public Path resolve(String subPath) {
        if (subPath == null || subPath.isBlank()) return root();
        return root().resolve(subPath);
    }

    /** Idempotently create the sandbox root on the volume. Safe to call on every session. */
    public void ensureExists() throws IOException {
        fs.createDirectories(root());
    }
}
