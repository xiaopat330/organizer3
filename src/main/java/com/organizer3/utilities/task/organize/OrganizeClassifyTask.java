package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.Set;
import java.util.function.Supplier;

/** Execute the Classify phase: promote actresses who have crossed tier thresholds. */
public final class OrganizeClassifyTask extends OrganizeBaseTask {

    public static final String ID = "organize.classify";

    public OrganizeClassifyTask(OrganizeVolumeService service, Jdbi jdbi,
                                 OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Classify", "Promote actresses who have crossed their tier threshold."),
                Set.of(OrganizeVolumeService.Phase.CLASSIFY), false,
                service, jdbi, config, invokerFactory);
    }
}
