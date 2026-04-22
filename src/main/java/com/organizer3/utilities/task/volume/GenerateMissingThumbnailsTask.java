package com.organizer3.utilities.task.volume;

import com.organizer3.media.ThumbnailService;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import com.organizer3.utilities.volume.MissingThumbnailsService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Generate any missing thumbnails for videos on a volume. Non-destructive — only writes new files
 * where nothing existed, never overwrites or deletes. So no visualize-then-confirm step is needed;
 * the button starts the task directly.
 *
 * <p>Flow:
 * <ol>
 *   <li><b>mount</b> — required because thumbnail generation streams the video via the app's
 *       own HTTP server, which reads through the SMB connection.</li>
 *   <li><b>scan</b> — walks all videos on the volume, checks on-disk thumbnail caches, emits the
 *       missing list. Cheap if few missing, expensive otherwise (one stat per video). Progress
 *       shows "N scanned / Total".</li>
 *   <li><b>generate</b> — calls {@link ThumbnailService#generateBlocking} per missing video.
 *       Single-threaded; each video takes seconds to minutes depending on file size. Progress
 *       shows "M generated / missingCount".</li>
 *   <li><b>unmount</b> — always runs.</li>
 * </ol>
 */
public final class GenerateMissingThumbnailsTask implements Task {

    public static final String ID = "volume.generate_missing_thumbnails";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Generate missing thumbnails",
            "Scan the volume for videos lacking thumbnail caches and generate them.",
            List.of(new TaskSpec.InputSpec(
                    "volumeId", "Volume", TaskSpec.InputSpec.InputType.VOLUME_ID, true))
    );

    private final Supplier<CommandInvoker> invokerFactory;
    private final MissingThumbnailsService missingService;
    private final ThumbnailService thumbnailService;

    public GenerateMissingThumbnailsTask(Supplier<CommandInvoker> invokerFactory,
                                         MissingThumbnailsService missingService,
                                         ThumbnailService thumbnailService) {
        this.invokerFactory = invokerFactory;
        this.missingService = missingService;
        this.thumbnailService = thumbnailService;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        String volumeId = inputs.getString("volumeId");
        CommandInvoker invoker = invokerFactory.get();

        // Phase 1 — mount
        io.phaseStart("mount", "Mount volume");
        boolean mounted = invoker.invoke("mount", "mount", new String[]{"mount", volumeId}, io)
                && invoker.session().isConnected();
        io.phaseEnd("mount", mounted ? "ok" : "failed", "");
        if (!mounted) {
            runUnmount(invoker, io);
            return;
        }

        // Phase 2 — scan for videos whose thumbnails are missing/incomplete
        io.phaseStart("scan", "Scan for missing thumbnails");
        List<MissingThumbnailsService.Missing> missing;
        try {
            missing = missingService.findMissing(volumeId);
            io.phaseEnd("scan", "ok", missing.size() + " video(s) need thumbnails");
        } catch (RuntimeException e) {
            io.phaseLog("scan", "Scan failed: " + e.getMessage());
            io.phaseEnd("scan", "failed", e.getMessage());
            runUnmount(invoker, io);
            return;
        }

        // Phase 3 — generate (only if anything is missing)
        if (missing.isEmpty()) {
            io.phaseStart("generate", "Generate missing thumbnails");
            io.phaseEnd("generate", "ok", "Nothing to do — all thumbnails present");
        } else {
            io.phaseStart("generate", "Generate missing thumbnails");
            int total = missing.size();
            int done = 0;
            int failed = 0;
            for (MissingThumbnailsService.Missing m : missing) {
                try {
                    thumbnailService.generateBlocking(m.titleCode(), m.video());
                } catch (Exception e) {
                    failed++;
                    io.phaseLog("generate",
                            "Failed " + m.titleCode() + " / " + m.video().getFilename() + ": " + e.getMessage());
                }
                done++;
                io.phaseProgress("generate", done, total,
                        m.titleCode() + " / " + m.video().getFilename());
            }
            String summary = (total - failed) + " of " + total + " generated"
                    + (failed > 0 ? " (" + failed + " failed)" : "");
            io.phaseEnd("generate", failed == 0 ? "ok" : (failed < total ? "ok" : "failed"), summary);
        }

        runUnmount(invoker, io);
    }

    private static void runUnmount(CommandInvoker invoker, TaskIO io) {
        io.phaseStart("unmount", "Unmount volume");
        boolean ok = invoker.invoke("unmount", "unmount", new String[]{"unmount"}, io);
        io.phaseEnd("unmount", ok ? "ok" : "failed", "");
    }
}
