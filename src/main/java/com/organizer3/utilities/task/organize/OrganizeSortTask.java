package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.Set;
import java.util.function.Supplier;

/** Execute the Sort phase: move title folders from queue into stars/{tier}/{actress}/. */
public final class OrganizeSortTask extends OrganizeBaseTask {

    public static final String ID = "organize.sort";

    public OrganizeSortTask(OrganizeVolumeService service, Jdbi jdbi,
                             OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Sort", "File queue titles into stars/{tier}/{actress}/ or attention/."),
                Set.of(OrganizeVolumeService.Phase.SORT), false,
                service, jdbi, config, invokerFactory);
    }
}
