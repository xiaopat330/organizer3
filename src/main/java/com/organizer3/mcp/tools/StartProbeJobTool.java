package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.media.ProbeJobRunner;
import com.organizer3.shell.SessionContext;

/**
 * Kick off a background probe job on the currently-mounted volume and return
 * immediately with the new job's state. If a probe job is already running,
 * returns that job's state with {@code alreadyRunning: true} — the runner
 * enforces single-job semantics (matches the single-mount constraint).
 *
 * <p>Use {@code probe_job_status} to poll progress; {@code cancel_probe_job} to stop.
 */
public class StartProbeJobTool implements Tool {

    private final SessionContext session;
    private final ProbeJobRunner runner;

    public StartProbeJobTool(SessionContext session, ProbeJobRunner runner) {
        this.session = session;
        this.runner = runner;
    }

    @Override public String name()        { return "start_probe_job"; }
    @Override public String description() {
        return "Start a background probe job on the mounted volume. Returns a jobId you can poll "
             + "with probe_job_status. At most one probe job runs at a time.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("maxVideos", "integer",
                        "Optional cap on videos attempted this run. <= 0 means no cap. Default 0.", 0)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException(
                    "No volume is currently mounted. Mount one first (see mount_volume).");
        }
        int maxVideos = Schemas.optInt(args, "maxVideos", 0);
        return runner.start(volumeId, maxVideos);
    }
}
