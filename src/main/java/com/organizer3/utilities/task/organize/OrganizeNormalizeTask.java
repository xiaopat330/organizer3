package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.Set;
import java.util.function.Supplier;

/** Execute the Normalize phase: rename covers and videos to CODE.ext. */
public final class OrganizeNormalizeTask extends OrganizeBaseTask {

    public static final String ID = "organize.normalize";

    public OrganizeNormalizeTask(OrganizeVolumeService service, Jdbi jdbi,
                                  OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Normalize", "Rename covers and videos to CODE.ext for queue titles."),
                Set.of(OrganizeVolumeService.Phase.NORMALIZE), false,
                service, jdbi, config, invokerFactory);
    }
}
