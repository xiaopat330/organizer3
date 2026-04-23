package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.FixTimestampsVolumeService;
import com.organizer3.utilities.task.CommandInvoker;

import java.util.function.Supplier;

/** Executes timestamp correction on curated titles in the selected volume. */
public final class FixTimestampsTask extends FixTimestampsBaseTask {

    public static final String ID = "organize.timestamps";

    public FixTimestampsTask(FixTimestampsVolumeService service,
                             OrganizerConfig config,
                             Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Fix timestamps",
                "Set each curated title folder's timestamp to its earliest child file date."),
                false, service, config, invokerFactory);
    }
}
