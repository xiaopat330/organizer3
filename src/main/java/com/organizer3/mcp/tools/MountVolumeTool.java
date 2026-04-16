package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.SmbConnectionException;
import com.organizer3.smb.SmbConnector;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.IndexLoader;
import com.organizer3.sync.VolumeIndex;

/**
 * Connect to an SMB volume and activate it as the session's mounted volume.
 * Mirrors the logic of {@link com.organizer3.command.MountCommand} — reuses the same
 * connector, respects the single-active-mount invariant, and drops any prior
 * connection before switching.
 *
 * <p>Gated on {@code mcp.allowNetworkOps} — hidden from {@code tools/list} when
 * that flag is off. The SessionContext is shared with the interactive shell, so
 * agent mounts and user mounts see the same state. Race with concurrent shell
 * activity is possible but narrow (shell commands are synchronous).
 */
public class MountVolumeTool implements Tool {

    private final SessionContext session;
    private final SmbConnector smbConnector;
    private final IndexLoader indexLoader;

    public MountVolumeTool(SessionContext session, SmbConnector smbConnector, IndexLoader indexLoader) {
        this.session = session;
        this.smbConnector = smbConnector;
        this.indexLoader = indexLoader;
    }

    @Override public String name()        { return "mount_volume"; }
    @Override public String description() {
        return "Connect to a volume by id and make it the session's active mount. "
             + "Drops any prior connection first. Gated on mcp.allowNetworkOps.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string", "Volume id to mount (see list_volumes for known ids).")
                .require("volumeId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = Schemas.requireString(args, "volumeId");
        OrganizerConfig config = AppConfig.get().volumes();
        VolumeConfig volume = config.findById(volumeId).orElseThrow(() ->
                new IllegalArgumentException("Unknown volume: " + volumeId));

        if (volumeId.equals(session.getMountedVolumeId()) && session.isConnected()) {
            return new Result(true, volumeId, "already_mounted",
                    session.getIndex() != null ? session.getIndex().titleCount() : 0);
        }

        // Drop any prior connection
        VolumeConnection prior = session.getActiveConnection();
        if (prior != null) {
            prior.close();
            session.setActiveConnection(null);
        }

        ServerConfig server = config.findServerById(volume.server()).orElseThrow(() ->
                new IllegalStateException("No server config for id: " + volume.server()));

        VolumeConnection conn;
        try {
            conn = smbConnector.connect(volume, server, phase -> { /* status ignored */ });
        } catch (SmbConnectionException e) {
            throw new IllegalStateException("SMB connection failed: " + e.getMessage(), e);
        }

        session.setActiveConnection(conn);
        session.setMountedVolume(volume);
        VolumeIndex index = indexLoader.load(volumeId);
        session.setIndex(index);
        return new Result(true, volumeId, "mounted", index.titleCount());
    }

    public record Result(boolean mounted, String volumeId, String state, int indexedTitleCount) {}
}
