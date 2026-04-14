package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Lists all AV actresses sorted by video count (descending).
 *
 * <p>Usage: {@code av actresses}
 */
@RequiredArgsConstructor
public class AvActressesCommand implements Command {

    private final AvActressRepository actressRepo;

    @Override
    public String name() {
        return "av actresses";
    }

    @Override
    public String description() {
        return "List AV actresses sorted by video count";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        List<AvActress> actresses = actressRepo.findAllByVideoCountDesc();
        if (actresses.isEmpty()) {
            io.println("No AV actresses found. Run 'av sync' first.");
            return;
        }

        io.println(String.format("%-40s  %6s  %8s  %s",
                "Name", "Videos", "Size", "Flags"));
        io.println("-".repeat(70));

        for (AvActress a : actresses) {
            String flags = buildFlags(a);
            String size = formatSize(a.getTotalSizeBytes());
            io.println(String.format("%-40s  %6d  %8s  %s",
                    truncate(a.getStageName(), 40),
                    a.getVideoCount(),
                    size,
                    flags));
        }
        io.println("\n" + actresses.size() + " actress(es)");
    }

    private static String buildFlags(AvActress a) {
        StringBuilder sb = new StringBuilder();
        if (a.isFavorite()) sb.append("♥ ");
        if (a.getGrade() != null) sb.append("[").append(a.getGrade()).append("] ");
        if (a.getIafdId() != null) sb.append("IAFD ");
        return sb.toString().trim();
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) return "-";
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
