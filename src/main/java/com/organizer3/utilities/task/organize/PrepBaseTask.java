package com.organizer3.utilities.task.organize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.FreshPrepService;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Shared mount/service/unmount pattern for Prep pipeline tasks.
 *
 * <p>Iterates all unstructured partitions of a queue-type volume, calls
 * {@link FreshPrepService} on each, aggregates results, and emits a {@code prep}
 * phase with a JSON summary. Only meaningful for volumes whose structure type has
 * no structured partition (i.e. structureType = "queue").
 */
@Slf4j
abstract class PrepBaseTask implements Task {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final TaskSpec spec;
    private final boolean dryRun;
    private final FreshPrepService service;
    private final OrganizerConfig config;
    private final Supplier<CommandInvoker> invokerFactory;

    PrepBaseTask(TaskSpec spec, boolean dryRun, FreshPrepService service,
                 OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
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
            runPrep(io, invoker, volumeId);
        } else if (!mounted) {
            io.phaseLog("mount", "Skipping prep phase because mount failed.");
        }

        OrganizeBaseTask.runPhase(io, "unmount", "Unmount volume", () ->
                invoker.invoke("unmount", "unmount", new String[]{"unmount"}, io));
    }

    private void runPrep(TaskIO io, CommandInvoker invoker, String volumeId) {
        io.phaseStart("prep", dryRun ? "Preview plan" : "Executing");
        try {
            VolumeConfig volumeConfig = config.findById(volumeId).orElse(null);
            if (volumeConfig == null) {
                io.phaseLog("prep", "Volume not in config: " + volumeId);
                io.phaseEnd("prep", "failed", "Volume not in config");
                return;
            }
            VolumeStructureDef def = config.findStructureById(volumeConfig.structureType()).orElse(null);
            if (def == null) {
                io.phaseLog("prep", "Unknown structure type: " + volumeConfig.structureType());
                io.phaseEnd("prep", "failed", "Unknown structure type: " + volumeConfig.structureType());
                return;
            }
            List<PartitionDef> partitions = def.unstructuredPartitions();
            if (partitions == null || partitions.isEmpty()) {
                io.phaseLog("prep", "No unstructured partitions defined for structure: " + def.id());
                io.phaseEnd("prep", "ok", buildResultJson(volumeId, List.of(), dryRun));
                return;
            }

            VolumeFileSystem fs = invoker.session().getActiveConnection().fileSystem();

            List<Map<String, Object>> partitionResults = new ArrayList<>();
            int totalVideos = 0, totalPlanned = 0, totalSkipped = 0, totalMoved = 0, totalFailed = 0;

            for (PartitionDef part : partitions) {
                Path partitionRoot = Path.of("/", part.path());
                FreshPrepService.Result r = dryRun
                        ? service.plan(fs, partitionRoot, 0, 0)
                        : service.execute(fs, partitionRoot, 0, 0);

                Map<String, Object> pr = new LinkedHashMap<>();
                pr.put("partitionId", part.id());
                pr.put("partitionRoot", r.partitionRoot());
                pr.put("totalVideosAtRoot", r.totalVideosAtRoot());
                pr.put("planned", r.planned());
                pr.put("skipped", r.skipped());
                pr.put("moved", r.moved());
                pr.put("failed", r.failed());
                partitionResults.add(pr);

                totalVideos  += r.totalVideosAtRoot();
                totalPlanned += r.planned().size();
                totalSkipped += r.skipped().size();
                totalMoved   += r.moved().size();
                totalFailed  += r.failed().size();
            }

            io.phaseProgress("prep", totalPlanned, totalVideos, "");

            String resultJson = buildResultJson(volumeId, partitionResults, dryRun,
                    totalVideos, totalPlanned, totalSkipped, totalMoved, totalFailed);
            io.phaseEnd("prep", "ok", resultJson);

        } catch (Exception e) {
            log.error("Prep task failed for volume {}", volumeId, e);
            io.phaseLog("prep", "Prep failed: " + e.getMessage());
            io.phaseEnd("prep", "failed", e.getMessage());
        }
    }

    private static String buildResultJson(String volumeId, List<Map<String, Object>> partitions, boolean dryRun) {
        return buildResultJson(volumeId, partitions, dryRun, 0, 0, 0, 0, 0);
    }

    private static String buildResultJson(String volumeId, List<Map<String, Object>> partitions, boolean dryRun,
                                          int totalVideos, int totalPlanned, int totalSkipped,
                                          int totalMoved, int totalFailed) {
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalVideos", totalVideos);
            summary.put("planned",     totalPlanned);
            summary.put("skipped",     totalSkipped);
            summary.put("moved",       totalMoved);
            summary.put("failed",      totalFailed);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dryRun",     dryRun);
            result.put("volumeId",   volumeId);
            result.put("partitions", partitions);
            result.put("summary",    summary);
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }

    static TaskSpec spec(String id, String title, String description) {
        return new TaskSpec(id, title, description,
                List.of(new TaskSpec.InputSpec(
                        "volumeId", "Volume", TaskSpec.InputSpec.InputType.VOLUME_ID, true)));
    }
}
