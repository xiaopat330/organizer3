package com.organizer3.filesystem;

import com.organizer3.shell.SessionContext;

import java.io.PrintWriter;

/**
 * Factory for obtaining the correct {@link VolumeFileSystem} for the current session.
 *
 * <p>Commands that perform filesystem operations accept a {@code FileSystemProvider} rather
 * than a concrete implementation. This keeps the dry-run/armed decision out of command logic
 * and makes commands trivially testable — tests inject a provider that returns a mock or a
 * {@link DryRunFileSystem} backed by a {@link java.io.StringWriter}.
 *
 * <p>The standard wiring in the composition root:
 * <pre>{@code
 * LocalFileSystem liveFs = new LocalFileSystem();
 * FileSystemProvider fsProvider = (ctx, out) ->
 *     ctx.isDryRun() ? new DryRunFileSystem(out) : liveFs;
 * }</pre>
 */
@FunctionalInterface
public interface FileSystemProvider {
    VolumeFileSystem get(SessionContext ctx, PrintWriter out);
}
