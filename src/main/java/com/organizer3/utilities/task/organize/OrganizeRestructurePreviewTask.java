package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.Set;
import java.util.function.Supplier;

/** Dry-run preview of the Restructure phase. */
public final class OrganizeRestructurePreviewTask extends OrganizeBaseTask {

    public static final String ID = "organize.restructure.preview";

    public OrganizeRestructurePreviewTask(OrganizeVolumeService service, Jdbi jdbi,
                                           OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Preview restructure", "Dry-run: show what restructure would move."),
                Set.of(OrganizeVolumeService.Phase.RESTRUCTURE), true,
                service, jdbi, config, invokerFactory);
    }
}
