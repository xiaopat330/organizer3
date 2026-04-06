package com.organizer3.sync;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.io.IOException;

/**
 * A single sync operation — walks a defined scope of the volume's filesystem and
 * reconciles the results into the database and in-memory index.
 *
 * <p>Implementations are constructed once per command term at startup from config.
 * The {@code fs} parameter is always a live {@link com.organizer3.filesystem.LocalFileSystem}
 * since sync reads the real filesystem regardless of dry-run mode.
 */
public interface SyncOperation {

    void execute(VolumeConfig volume, VolumeStructureDef structure,
                 VolumeFileSystem fs, SessionContext ctx, CommandIO io) throws IOException;
}
