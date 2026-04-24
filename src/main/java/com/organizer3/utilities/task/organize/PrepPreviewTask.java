package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.FreshPrepService;
import com.organizer3.utilities.task.CommandInvoker;

import java.util.function.Supplier;

/** Dry-run preview of the Prep phase for queue-type volumes. */
public final class PrepPreviewTask extends PrepBaseTask {

    public static final String ID = "prep.preview";

    public PrepPreviewTask(FreshPrepService service, OrganizerConfig config,
                           Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Preview prep", "Dry-run: show what prep would move for raw queue files."),
                true, service, config, invokerFactory);
    }
}
