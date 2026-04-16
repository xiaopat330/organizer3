package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;

/**
 * Read-only status of the current SMB mount. Safe to expose regardless of
 * {@code allowNetworkOps} — surfaces state, doesn't change it.
 */
public class MountStatusTool implements Tool {

    private final SessionContext session;

    public MountStatusTool(SessionContext session) { this.session = session; }

    @Override public String name()        { return "mount_status"; }
    @Override public String description() { return "Return the currently-mounted volume id and connection health."; }
    @Override public JsonNode inputSchema() { return Schemas.empty(); }

    @Override
    public Object call(JsonNode args) {
        return new Status(
                session.getMountedVolumeId(),
                session.isConnected(),
                session.getIndex() != null ? session.getIndex().titleCount() : 0
        );
    }

    public record Status(String volumeId, boolean connected, int indexedTitleCount) {}
}
