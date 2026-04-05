package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.model.Volume;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;

import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lists all configured volumes with their connection status and last-sync timestamp.
 *
 * <p>Config is the source of truth for which volumes exist; the DB supplies
 * last-sync timestamps for volumes that have been synced at least once.
 * Only the currently active volume in session can report as "connected" — there
 * is no OS-level mount state to query for other volumes.
 *
 * <p>Usage: {@code volumes}
 */
public class VolumesCommand implements Command {

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final VolumeRepository volumeRepository;

    public VolumesCommand(VolumeRepository volumeRepository) {
        this.volumeRepository = volumeRepository;
    }

    @Override
    public String name() {
        return "volumes";
    }

    @Override
    public String description() {
        return "List all configured volumes with connection and sync status";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, PrintWriter out) {
        var configVolumes = AppConfig.get().volumes().volumes();

        Map<String, Volume> dbRecords = volumeRepository.findAll().stream()
                .collect(Collectors.toMap(Volume::getId, Function.identity()));

        String activeId = ctx.getMountedVolumeId();
        boolean activeConnected = ctx.isConnected();

        out.printf("%-6s  %-14s  %-10s  %s%n", "ID", "STRUCTURE", "CONNECTED", "LAST SYNC");
        out.println("-".repeat(60));

        for (VolumeConfig vc : configVolumes) {
            Optional<Volume> dbVol = Optional.ofNullable(dbRecords.get(vc.id()));

            String syncedAt = dbVol
                    .map(Volume::getLastSyncedAt)
                    .map(dt -> dt.format(DISPLAY_FMT))
                    .orElse("never");

            boolean connected = vc.id().equals(activeId) && activeConnected;
            String connStatus = connected ? "yes" : "-";
            String active = connected ? " *" : "";

            out.printf("%-6s  %-14s  %-10s  %s%s%n",
                    vc.id(),
                    vc.structureType(),
                    connStatus,
                    syncedAt,
                    active);
        }
    }
}
