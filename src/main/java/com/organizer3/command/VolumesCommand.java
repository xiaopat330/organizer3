package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.model.Volume;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
public class VolumesCommand implements Command {

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a");

    private static final String BOLD    = "\033[1m";
    private static final String GREEN   = "\033[92m";
    private static final String YELLOW  = "\033[93m";
    private static final String CYAN    = "\033[96m";
    private static final String DIM     = "\033[2m";
    private static final String RESET   = "\033[0m";

    private final VolumeRepository volumeRepository;

    @Override
    public String name() {
        return "volumes";
    }

    @Override
    public String description() {
        return "List all configured volumes with connection and sync status";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        var configVolumes = AppConfig.get().volumes().volumes();

        Map<String, Volume> dbRecords = volumeRepository.findAll().stream()
                .collect(Collectors.toMap(Volume::getId, Function.identity()));

        String activeId = ctx.getMountedVolumeId();
        boolean activeConnected = ctx.isConnected();

        // Group volumes by server
        Map<String, List<VolumeConfig>> byServer = new LinkedHashMap<>();
        for (VolumeConfig vc : configVolumes) {
            byServer.computeIfAbsent(vc.server(), k -> new java.util.ArrayList<>()).add(vc);
        }

        boolean first = true;
        for (Map.Entry<String, List<VolumeConfig>> entry : byServer.entrySet()) {
            if (!first) io.println();
            first = false;

            io.printlnAnsi(BOLD + CYAN + "  " + entry.getKey().toUpperCase() + RESET);
            io.printlnAnsi(DIM + "  " + "─".repeat(62) + RESET);
            io.printlnAnsi(DIM + String.format("  %-8s  %-28s  %-14s  %s", "ID", "PATH", "STRUCTURE", "LAST SYNCED") + RESET);
            io.printlnAnsi(DIM + "  " + "─".repeat(62) + RESET);

            for (VolumeConfig vc : entry.getValue()) {
                Optional<Volume> dbVol = Optional.ofNullable(dbRecords.get(vc.id()));

                String syncedAt = dbVol
                        .map(Volume::getLastSyncedAt)
                        .map(dt -> dt.format(DISPLAY_FMT))
                        .orElse("never synced");

                boolean connected = vc.id().equals(activeId) && activeConnected;

                // Extract just the share name from //server/share
                String path = vc.smbPath().replaceFirst("^//[^/]+/", "/");

                if (connected) {
                    io.printlnAnsi(String.format(
                            GREEN + "  %-8s" + RESET + "  %-28s  %-14s  %s" + YELLOW + "  ← connected" + RESET,
                            vc.id(), path, vc.structureType(), syncedAt));
                } else {
                    io.printlnAnsi(String.format(
                            "  %-8s  %-28s  %-14s  " + DIM + "%s" + RESET,
                            vc.id(), path, vc.structureType(), syncedAt));
                }
            }
        }
        io.println();
    }
}
