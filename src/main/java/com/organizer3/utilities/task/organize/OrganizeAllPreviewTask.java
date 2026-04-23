package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.function.Supplier;

/** Dry-run preview of all four organize phases. */
public final class OrganizeAllPreviewTask extends OrganizeBaseTask {

    public static final String ID = "organize.preview";

    public OrganizeAllPreviewTask(OrganizeVolumeService service, Jdbi jdbi,
                                   OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Preview organize all", "Dry-run: show the full organize plan for all phases."),
                OrganizeVolumeService.ALL, true,
                service, jdbi, config, invokerFactory);
    }
}
