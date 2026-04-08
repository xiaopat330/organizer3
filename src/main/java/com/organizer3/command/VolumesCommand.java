package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.model.Volume;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // Injected so that selecting a volume in the interactive picker mounts it
    private final MountCommand mountCommand;

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a");

    private static final String BOLD   = "\033[1m";
    private static final String DIM    = "\033[2m";
    private static final String WHITE  = "\033[97m";
    private static final String GREEN  = "\033[92m";
    private static final String RED    = "\033[91m";
    private static final String CYAN   = "\033[96m";
    private static final String RESET  = "\033[0m";

    private final VolumeRepository volumeRepository;

    private record Row(
            String id,
            String path,
            String structure,
            String syncedAt,
            boolean connected,
            boolean neverSynced
    ) {}

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

        // Group volumes by server, preserving config order
        Map<String, List<VolumeConfig>> byServer = new LinkedHashMap<>();
        for (VolumeConfig vc : configVolumes) {
            byServer.computeIfAbsent(vc.server(), k -> new ArrayList<>()).add(vc);
        }

        // Build all rows first so we can compute column widths globally
        Map<String, List<Row>> rowsByServer = new LinkedHashMap<>();
        int wId = "ID".length(), wPath = "PATH".length(),
                wStructure = "STRUCTURE".length(), wSynced = "LAST SYNCED".length();

        for (Map.Entry<String, List<VolumeConfig>> entry : byServer.entrySet()) {
            List<Row> rows = new ArrayList<>();
            for (VolumeConfig vc : entry.getValue()) {
                String path     = vc.smbPath().replaceFirst("^//[^/]+/", "/");
                boolean neverSynced = !dbRecords.containsKey(vc.id())
                        || dbRecords.get(vc.id()).getLastSyncedAt() == null;
                String syncedAt = neverSynced ? "never synced"
                        : dbRecords.get(vc.id()).getLastSyncedAt().format(DISPLAY_FMT);
                boolean connected = vc.id().equals(activeId) && activeConnected;

                wId        = Math.max(wId,        vc.id().length());
                wPath      = Math.max(wPath,      path.length());
                wStructure = Math.max(wStructure, vc.structureType().length());
                wSynced    = Math.max(wSynced,    syncedAt.length());

                rows.add(new Row(vc.id(), path, vc.structureType(), syncedAt, connected, neverSynced));
            }
            rowsByServer.put(entry.getKey(), rows);
        }

        // Total separator width: indent(2) + col widths + 3 gaps of 2 spaces each
        int sepWidth = wId + wPath + wStructure + wSynced + 3 * 2;

        // Render each server section
        boolean first = true;
        for (Map.Entry<String, List<Row>> entry : rowsByServer.entrySet()) {
            if (!first) io.println();
            first = false;

            io.printlnAnsi(BOLD + CYAN + "  " + entry.getKey().toUpperCase() + RESET);
            io.printlnAnsi(DIM + "  " + "─".repeat(sepWidth) + RESET);
            io.printlnAnsi(DIM + "  "
                    + pad("ID",          wId)        + "  "
                    + pad("PATH",        wPath)       + "  "
                    + pad("STRUCTURE",   wStructure)  + "  "
                    + "LAST SYNCED"
                    + RESET);
            io.printlnAnsi(DIM + "  " + "─".repeat(sepWidth) + RESET);

            for (Row row : entry.getValue()) {
                String idDisplay     = colorId(row.id(), row.connected);
                String pathDisplay   = DIM + row.path + RESET;
                String structDisplay = DIM + row.structure + RESET;
                String syncDisplay   = row.neverSynced
                        ? BOLD + RED + row.syncedAt + RESET
                        : row.syncedAt;
                String suffix = row.connected
                        ? "  " + BOLD + GREEN + "● connected" + RESET
                        : "";

                io.printlnAnsi("  "
                        + idDisplay  + spaces(wId        - row.id().length())        + "  "
                        + pathDisplay + spaces(wPath      - row.path.length())        + "  "
                        + structDisplay + spaces(wStructure - row.structure.length()) + "  "
                        + syncDisplay
                        + suffix);
            }
        }
        io.println();

        // Build picker items with color coding matching the table
        List<String> pickLabels = new ArrayList<>();
        List<String> pickIds    = new ArrayList<>();
        for (Map.Entry<String, List<Row>> entry : rowsByServer.entrySet()) {
            for (Row row : entry.getValue()) {
                String syncDisplay = row.neverSynced()
                        ? BOLD + RED + row.syncedAt() + RESET
                        : row.syncedAt();
                pickLabels.add(colorId(row.id(), row.connected())
                        + spaces(wId - row.id().length()) + "  "
                        + DIM + row.path() + RESET
                        + spaces(wPath - row.path().length()) + "  "
                        + syncDisplay);
                pickIds.add(row.id());
            }
        }

        io.pick(pickLabels).ifPresent(label -> {
            String volumeId = pickIds.get(pickLabels.indexOf(label));
            mountCommand.execute(new String[]{"mount", volumeId}, ctx, io);
        });
    }

    private String colorId(String id, boolean connected) {
        if (connected) return BOLD + GREEN + id + RESET;
        return BOLD + WHITE + id + RESET;
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }

    private static String spaces(int n) {
        return n > 0 ? " ".repeat(n) : "";
    }
}
