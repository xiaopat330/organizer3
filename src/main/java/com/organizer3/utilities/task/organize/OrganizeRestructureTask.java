package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.Set;
import java.util.function.Supplier;

/** Execute the Restructure phase: move videos to video/h265/4K subfolders. */
public final class OrganizeRestructureTask extends OrganizeBaseTask {

    public static final String ID = "organize.restructure";

    public OrganizeRestructureTask(OrganizeVolumeService service, Jdbi jdbi,
                                    OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Restructure", "Move videos into video/h265/4K subfolders for queue titles."),
                Set.of(OrganizeVolumeService.Phase.RESTRUCTURE), false,
                service, jdbi, config, invokerFactory);
    }
}
