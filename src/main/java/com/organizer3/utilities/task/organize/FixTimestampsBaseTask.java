package com.organizer3.utilities.task.organize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.FixTimestampsVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Supplier;

/**
 * Shared mount/run/unmount pattern for the Fix Timestamps task pair.
 *
 * <p>Walks every title in the curated (non-queue) partitions of the selected volume
 * via {@link FixTimestampsVolumeService} and corrects folder timestamps to match
 * the earliest child file timestamp.
 */
@Slf4j
abstract class FixTimestampsBaseTask implements Task {

    static final String PHASE_ID = "timestamps";

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final TaskSpec spec;
    private final boolean dryRun;
    private final FixTimestampsVolumeService service;
    private final OrganizerConfig config;
    private final Supplier<CommandInvoker> invokerFactory;

    FixTimestampsBaseTask(
            TaskSpec spec,
            boolean dryRun,
            FixTimestampsVolumeService service,
            OrganizerConfig config,
            Supplier<CommandInvoker> invokerFactory) {
        this.spec = spec;
        this.dryRun = dryRun;
        this.service = service;
        this.config = config;
        this.invokerFactory = invokerFactory;
    }

    @Override
    public TaskSpec spec() { return spec; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) throws Exception {
        String volumeId = inputs.getString("volumeId");
        CommandInvoker invoker = invokerFactory.get();

        boolean mounted = OrganizeBaseTask.runPhase(io, "mount", "Mount volume", () ->
                invoker.invoke("mount", "mount", new String[]{"mount", volumeId}, io)
                        && invoker.session().isConnected());

        if (mounted && !io.isCancellationRequested()) {
            runTimestamps(io, invoker, volumeId);
        } else if (!mounted) {
            io.phaseLog("mount", "Skipping timestamps phase because mount failed.");
        }

        OrganizeBaseTask.runPhase(io, "unmount", "Unmount volume", () ->
                invoker.invoke("unmount", "unmount", new String[]{"unmount"}, io));
    }

    private void runTimestamps(TaskIO io, CommandInvoker invoker, String volumeId) {
        io.phaseStart(PHASE_ID, dryRun ? "Preview plan" : "Executing");
        try {
            if (config.findById(volumeId).isEmpty()) {
                io.phaseLog(PHASE_ID, "Volume not in config: " + volumeId);
                io.phaseEnd(PHASE_ID, "failed", "Volume not in config");
                return;
            }

            VolumeFileSystem fs = invoker.session().getActiveConnection().fileSystem();
            FixTimestampsVolumeService.Result result = service.fix(fs, volumeId, dryRun);

            int total = result.summary().checked();
            int done  = result.summary().changed() + result.summary().skipped() + result.summary().failed();
            io.phaseProgress(PHASE_ID, done, total, "");

            String resultJson = JSON.writeValueAsString(result);
            io.phaseEnd(PHASE_ID, "ok", resultJson);

        } catch (Exception e) {
            log.error("Fix timestamps task failed for volume {}", volumeId, e);
            io.phaseLog(PHASE_ID, "Fix timestamps failed: " + e.getMessage());
            io.phaseEnd(PHASE_ID, "failed", e.getMessage());
        }
    }

    static TaskSpec spec(String id, String title, String description) {
        return new TaskSpec(id, title, description,
                List.of(new TaskSpec.InputSpec(
                        "volumeId", "Volume", TaskSpec.InputSpec.InputType.VOLUME_ID, true)));
    }
}
