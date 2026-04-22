package com.organizer3.utilities.task.avstars;

import com.organizer3.avstars.cleanup.AvArtifactCleaner;
import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;
import java.util.Optional;

/**
 * Permanently delete an AV actress and all associated videos, tags, screenshots, and
 * on-disk artifacts. Wraps the same cleanup-before-cascade pattern as the CLI command.
 *
 * <p>Inputs: {@code actressId} (Long).
 */
public final class DeleteAvActressTask implements Task {

    public static final String ID = "avstars.delete";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Delete AV actress",
            "Permanently delete an AV actress + her videos, screenshots, and headshot.",
            List.of(new TaskSpec.InputSpec("actressId", "AV actress", TaskSpec.InputSpec.InputType.STRING, true))
    );

    private final AvActressRepository actressRepo;
    private final AvVideoRepository videoRepo;
    private final AvArtifactCleaner artifactCleaner;

    public DeleteAvActressTask(AvActressRepository actressRepo, AvVideoRepository videoRepo,
                                AvArtifactCleaner artifactCleaner) {
        this.actressRepo = actressRepo;
        this.videoRepo = videoRepo;
        this.artifactCleaner = artifactCleaner;
    }

    @Override public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        long actressId = Long.parseLong(String.valueOf(inputs.values().get("actressId")));
        io.phaseStart("delete", "Delete AV actress");
        try {
            Optional<AvActress> opt = actressRepo.findById(actressId);
            if (opt.isEmpty()) {
                io.phaseEnd("delete", "failed", "Unknown av actress id: " + actressId);
                return;
            }
            AvActress a = opt.get();

            List<Long> videoIds = videoRepo.findIdsByActress(a.getId());
            int screenshotsCleared = artifactCleaner.deleteScreenshotsFor(videoIds);
            boolean headshotDeleted = artifactCleaner.deleteHeadshot(a.getHeadshotPath());
            actressRepo.delete(a.getId());

            io.phaseEnd("delete", "ok",
                    "Deleted " + a.getStageName()
                            + " · " + videoIds.size() + " video(s)"
                            + " · " + screenshotsCleared + " screenshot dir(s)"
                            + (headshotDeleted ? " · headshot removed" : ""));
        } catch (RuntimeException e) {
            io.phaseLog("delete", "Delete failed: " + e.getMessage());
            io.phaseEnd("delete", "failed", e.getMessage());
        }
    }
}
