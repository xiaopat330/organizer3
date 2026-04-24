package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.Set;
import java.util.function.Supplier;

/** Dry-run preview of the Classify phase. */
public final class OrganizeClassifyPreviewTask extends OrganizeBaseTask {

    public static final String ID = "organize.classify.preview";

    public OrganizeClassifyPreviewTask(OrganizeVolumeService service, Jdbi jdbi,
                                        OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Preview classify", "Dry-run: show which actresses would be promoted."),
                Set.of(OrganizeVolumeService.Phase.CLASSIFY), true,
                service, jdbi, config, invokerFactory);
    }
}
