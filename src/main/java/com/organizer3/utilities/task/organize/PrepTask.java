package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.FreshPrepService;
import com.organizer3.utilities.task.CommandInvoker;

import java.util.function.Supplier;

/** Execute the Prep phase: move raw queue videos into (CODE)/<video|h265>/ folder skeletons. */
public final class PrepTask extends PrepBaseTask {

    public static final String ID = "prep";

    public PrepTask(FreshPrepService service, OrganizerConfig config,
                    Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Prep", "Move raw queue videos into title folder skeletons."),
                false, service, config, invokerFactory);
    }
}
