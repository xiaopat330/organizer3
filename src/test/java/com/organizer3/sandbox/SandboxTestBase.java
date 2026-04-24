package com.organizer3.sandbox;

import com.organizer3.filesystem.LocalFileSystem;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Base class for sandbox tests — tests that exercise service logic against a real local filesystem
 * (via {@link LocalFileSystem} and {@code @TempDir}) rather than in-memory fakes.
 *
 * <p>Tagged {@code sandbox} — run with {@code ./gradlew sandboxTest}, excluded from the default
 * {@code test} task.
 */
public abstract class SandboxTestBase {

    @TempDir
    protected Path runDir;

    protected final LocalFileSystem fs = new LocalFileSystem();

    /** Absolute path to the per-test trash root: {@code runDir/_trash/}. */
    protected Path trashRoot() {
        return runDir.resolve("_trash");
    }
}
