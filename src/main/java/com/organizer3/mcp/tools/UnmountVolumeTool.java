package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;

/**
 * Disconnect the session's active SMB mount. Idempotent — returns state information
 * whether or not a volume was mounted.
 *
 * <p>Gated on {@code mcp.allowNetworkOps} — hidden from {@code tools/list} when off.
 */
public class UnmountVolumeTool implements Tool {

    private final SessionContext session;

    public UnmountVolumeTool(SessionContext session) { this.session = session; }

    @Override public String name()        { return "unmount_volume"; }
    @Override public String description() { return "Disconnect the active SMB volume. Gated on mcp.allowNetworkOps."; }
    @Override public JsonNode inputSchema() { return Schemas.empty(); }

    @Override
    public Object call(JsonNode args) {
        String priorId = session.getMountedVolumeId();
        if (priorId == null && !session.isConnected()) {
            return new Result(false, null, "no_mount_active");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn != null) {
            conn.close();
            session.setActiveConnection(null);
        }
        session.setMountedVolume(null);
        session.setIndex(null);
        return new Result(true, priorId, "unmounted");
    }

    public record Result(boolean unmounted, String priorVolumeId, String state) {}
}
