package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.Spinner;
import com.organizer3.smb.SmbConnectionException;
import com.organizer3.smb.SmbConnector;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.IndexLoader;
import com.organizer3.sync.VolumeIndex;

/**
 * Connects to an SMB volume and activates it as the current session context.
 *
 * <p>Usage: {@code mount <volume-id>}
 *
 * <p>Mount is idempotent — calling it on the already-connected volume simply reactivates it
 * as the session context without reconnecting.
 *
 * <p>When switching volumes, the previous connection is closed before opening the new one.
 * An animated spinner provides feedback during the connection phases (host resolution,
 * authentication, share connect), each of which may block for several seconds.
 */
public class MountCommand implements Command {

    private final SmbConnector smbConnector;
    private final IndexLoader indexLoader;

    public MountCommand(SmbConnector smbConnector, IndexLoader indexLoader) {
        this.smbConnector = smbConnector;
        this.indexLoader = indexLoader;
    }

    @Override
    public String name() {
        return "mount";
    }

    @Override
    public String description() {
        return "Connect to a volume and activate it as the current context. Usage: mount <id>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: mount <volume-id>");
            return;
        }

        OrganizerConfig config = AppConfig.get().volumes();
        String volumeId = args[1];
        VolumeConfig volume = config.findById(volumeId).orElse(null);
        if (volume == null) {
            io.println("Unknown volume: " + volumeId);
            io.println("Known volumes: " + config.volumes().stream()
                    .map(VolumeConfig::id)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)"));
            return;
        }

        // Already connected to this volume — just reactivate
        if (volumeId.equals(ctx.getMountedVolumeId()) && ctx.isConnected()) {
            io.println("Volume '" + volumeId + "' already connected.");
            return;
        }

        // Close existing connection before switching volumes
        VolumeConnection existing = ctx.getActiveConnection();
        if (existing != null) {
            existing.close();
            ctx.setActiveConnection(null);
        }

        ServerConfig server = config.findServerById(volume.server()).orElseThrow(() ->
                new IllegalStateException("No server config found for id: " + volume.server()));

        VolumeConnection connection;
        try (Spinner spinner = io.startSpinner("Connecting to " + volume.smbPath())) {
            connection = smbConnector.connect(volume, server, spinner::setStatus);
        } catch (SmbConnectionException e) {
            io.println("Connection failed: " + e.getMessage());
            return;
        }

        ctx.setActiveConnection(connection);
        ctx.setMountedVolume(volume);
        loadIndex(volumeId, ctx, io);
        io.println("Connected. Volume '" + volumeId + "' is now active.");
    }

    private void loadIndex(String volumeId, SessionContext ctx, CommandIO io) {
        VolumeIndex index = indexLoader.load(volumeId);
        ctx.setIndex(index);
        if (index.titleCount() == 0) {
            io.println("No index found for volume '" + volumeId + "' — run sync-all to build it.");
        } else {
            io.println("Loaded index: " + index.titleCount() + " title(s), "
                    + index.actressCount() + " actress(es).");
        }
    }
}
