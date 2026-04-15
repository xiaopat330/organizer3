package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Returns all configured volumes plus their current mount status from the active session.
 * Mount status is snapshot-at-call-time; a volume mounted later will appear as mounted
 * the next time the tool is called.
 */
public class ListVolumesTool implements Tool {

    private final SessionContext session;

    public ListVolumesTool(SessionContext session) {
        this.session = session;
    }

    @Override public String name()        { return "list_volumes"; }
    @Override public String description() { return "List all configured volumes with mount status, server, and share path."; }
    @Override public JsonNode inputSchema() { return Schemas.empty(); }

    @Override
    public Object call(JsonNode args) {
        var volumes = AppConfig.get().volumes().volumes();
        String activeId = session.getMountedVolumeId();
        List<VolumeInfo> out = new ArrayList<>();
        for (VolumeConfig v : volumes) {
            out.add(new VolumeInfo(
                    v.id(),
                    v.server(),
                    v.smbPath(),
                    v.structureType(),
                    v.id().equals(activeId)
            ));
        }
        return new Result(out.size(), activeId, out);
    }

    public record Result(int total, String activeVolumeId, List<VolumeInfo> volumes) {}

    public record VolumeInfo(
            String id,
            String server,
            String smbPath,
            String structureType,
            boolean active
    ) {}
}
