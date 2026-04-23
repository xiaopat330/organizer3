package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.function.Supplier;

/** Execute all four organize phases: normalize, restructure, sort, classify. */
public final class OrganizeAllTask extends OrganizeBaseTask {

    public static final String ID = "organize.queue";

    public OrganizeAllTask(OrganizeVolumeService service, Jdbi jdbi,
                            OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Organize all", "Run all organize phases: normalize, restructure, sort, classify."),
                OrganizeVolumeService.ALL, false,
                service, jdbi, config, invokerFactory);
    }
}
