package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.sync.SyncOperation;

import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

/**
 * Executes a sync operation against the currently mounted volume.
 *
 * <p>One {@code SyncCommand} instance is registered per term (e.g., {@code sync queue},
 * {@code sync all}) at startup, derived from the {@code syncConfig} section of
 * {@code organizer-config.yaml}. Each instance is bound to a specific
 * {@link SyncOperation} and the set of structure types for which it is valid.
 *
 * <p>Sync always reads the real filesystem regardless of dry-run mode — it is a
 * read-only operation from the filesystem's perspective (all writes go to the DB).
 */
@Slf4j
@RequiredArgsConstructor
public class SyncCommand implements Command {

    private final String term;
    private final Set<String> validStructureTypes;
    private final SyncOperation operation;

    @Override
    public String name() {
        return term;
    }

    @Override
    public String description() {
        return "Sync the current volume's database index from the filesystem. "
                + "Valid for: " + validStructureTypes;
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        VolumeConfig volume = ctx.getMountedVolume();
        if (volume == null) {
            io.println("No volume mounted. Use: mount <id>");
            return;
        }

        if (!validStructureTypes.contains(volume.structureType())) {
            io.println("'" + term + "' is not available for volume '" + volume.id()
                    + "' (structure type: " + volume.structureType() + ")");
            return;
        }

        VolumeStructureDef structure = AppConfig.get().volumes()
                .findStructureById(volume.structureType())
                .orElseThrow(() -> new IllegalStateException(
                        "No structure definition for type: " + volume.structureType()));

        if (!ctx.isConnected()) {
            io.println("Volume not connected. Use: mount <id>");
            return;
        }

        try {
            operation.execute(volume, structure, ctx.getActiveConnection().fileSystem(), ctx, io);
        } catch (IOException e) {
            io.println("Sync failed: " + e.getMessage());
            log.error("Sync error on volume {}", volume.id(), e);
        }
    }
}
