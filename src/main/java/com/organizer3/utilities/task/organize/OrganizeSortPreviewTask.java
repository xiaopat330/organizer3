package com.organizer3.utilities.task.organize;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import org.jdbi.v3.core.Jdbi;

import java.util.Set;
import java.util.function.Supplier;

/** Dry-run preview of the Sort phase. */
public final class OrganizeSortPreviewTask extends OrganizeBaseTask {

    public static final String ID = "organize.sort.preview";

    public OrganizeSortPreviewTask(OrganizeVolumeService service, Jdbi jdbi,
                                    OrganizerConfig config, Supplier<CommandInvoker> invokerFactory) {
        super(spec(ID, "Preview sort", "Dry-run: show where sort would file each title."),
                Set.of(OrganizeVolumeService.Phase.SORT), true,
                service, jdbi, config, invokerFactory);
    }
}
