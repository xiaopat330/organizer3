package com.organizer3.avstars.command;

import com.organizer3.avstars.sync.AvStarsSyncOperation;
import com.organizer3.command.Command;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * Syncs the currently mounted AV Stars volume.
 *
 * <p>Requires a volume with structureType {@code avstars} to be mounted. Delegates to
 * {@link AvStarsSyncOperation} for the actual treewalk and persistence.
 *
 * <p>Usage: {@code av sync}
 */
@Slf4j
@RequiredArgsConstructor
public class AvSyncCommand implements Command {

    private final AvStarsSyncOperation syncOperation;

    @Override
    public String name() {
        return "av sync";
    }

    @Override
    public String description() {
        return "Sync the mounted AV Stars volume (structureType: avstars)";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        VolumeConfig volume = ctx.getMountedVolume();
        if (volume == null) {
            io.println("No volume mounted. Use: mount <id>");
            return;
        }
        if (!"avstars".equals(volume.structureType())) {
            io.println("'av sync' requires an avstars volume. Currently mounted: "
                    + volume.id() + " (type: " + volume.structureType() + ")");
            return;
        }
        if (!ctx.isConnected()) {
            io.println("Volume not connected. Use: mount " + volume.id());
            return;
        }

        VolumeStructureDef structure = AppConfig.get().volumes()
                .findStructureById(volume.structureType())
                .orElseThrow(() -> new IllegalStateException(
                        "No structure definition for type: " + volume.structureType()));

        try {
            syncOperation.execute(volume, structure, ctx.getActiveConnection().fileSystem(), ctx, io);
        } catch (IOException e) {
            io.println("Sync failed: " + e.getMessage());
            log.error("AV sync error on volume {}", volume.id(), e);
        }
    }
}
