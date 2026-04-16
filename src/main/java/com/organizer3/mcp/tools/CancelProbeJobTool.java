package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.media.ProbeJobRunner;

/**
 * Request cancellation of a probe job. The runner checks the flag after each video,
 * so cancellation takes effect on the next iteration — there's no mid-probe abort.
 */
public class CancelProbeJobTool implements Tool {

    private final ProbeJobRunner runner;

    public CancelProbeJobTool(ProbeJobRunner runner) { this.runner = runner; }

    @Override public String name()        { return "cancel_probe_job"; }
    @Override public String description() { return "Signal a probe job to stop after its current video. Returns whether the jobId was found."; }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("jobId", "string", "Job id to cancel.")
                .require("jobId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String id = Schemas.requireString(args, "jobId");
        boolean found = runner.cancel(id);
        return new Result(found, id);
    }

    public record Result(boolean cancelled, String jobId) {}
}
