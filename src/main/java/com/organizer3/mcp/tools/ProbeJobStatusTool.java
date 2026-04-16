package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.media.ProbeJobRunner;

/**
 * Return the current state of a probe job. If {@code jobId} is omitted, returns
 * the active running job (or an empty result if none is running).
 */
public class ProbeJobStatusTool implements Tool {

    private final ProbeJobRunner runner;

    public ProbeJobStatusTool(ProbeJobRunner runner) { this.runner = runner; }

    @Override public String name()        { return "probe_job_status"; }
    @Override public String description() {
        return "Return progress of a probe job (specific id, or the currently-active one if omitted).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("jobId", "string", "Optional. If omitted, returns the currently-active job.")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String id = Schemas.optString(args, "jobId", null);
        if (id == null || id.isBlank()) {
            ProbeJobRunner.JobState active = runner.active();
            return new Result(active != null, active);
        }
        return new Result(true, runner.status(id));
    }

    public record Result(boolean present, ProbeJobRunner.JobState job) {}
}
