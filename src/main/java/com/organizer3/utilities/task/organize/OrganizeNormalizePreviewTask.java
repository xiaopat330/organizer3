package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.Set;
import java.util.function.Supplier;

/** Dry-run preview of the Normalize phase. */
public final class OrganizeNormalizePreviewTask extends OrganizeBaseTask {

    public static final String ID = "organize.normalize.preview";

    public OrganizeNormalizePreviewTask(OrganizeVolumeService service, Jdbi jdbi,
                                        OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Preview normalize", "Dry-run: show what normalize would rename."),
                Set.of(OrganizeVolumeService.Phase.NORMALIZE), true,
                service, jdbi, config, invokerFactory);
    }
}
