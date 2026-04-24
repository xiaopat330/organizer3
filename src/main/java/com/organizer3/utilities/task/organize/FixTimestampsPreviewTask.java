package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.FixTimestampsVolumeService;
import com.organizer3.utilities.task.CommandInvoker;

import java.util.function.Supplier;

/** Preview (dry-run) version of the Fix Timestamps task. */
public final class FixTimestampsPreviewTask extends FixTimestampsBaseTask {

    public static final String ID = "organize.timestamps.preview";

    public FixTimestampsPreviewTask(FixTimestampsVolumeService service,
                                    OrganizerConfig config,
                                    Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Fix timestamps (preview)",
                "Preview timestamp corrections for curated titles."),
                true, service, config, invokerFactory);
    }
}
