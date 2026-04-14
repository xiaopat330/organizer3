package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * Shows profile and video list for a single AV actress.
 *
 * <p>The name argument is matched case-insensitively as a prefix or substring of
 * folder_name / stage_name. If multiple actresses match, they are listed for disambiguation.
 *
 * <p>Usage: {@code av actress <name>}
 */
@RequiredArgsConstructor
public class AvActressCommand implements Command {

    private final AvActressRepository actressRepo;
    private final AvVideoRepository videoRepo;

    @Override
    public String name() {
        return "av actress";
    }

    @Override
    public String description() {
        return "Show profile and video list for one AV actress: av actress <name>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: av actress <name>");
            return;
        }

        // args[0] is "av actress", args[1..] is the name
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).toLowerCase();

        List<AvActress> all = actressRepo.findAllByVideoCountDesc();
        List<AvActress> matches = all.stream()
                .filter(a -> a.getStageName().toLowerCase().contains(query)
                        || a.getFolderName().toLowerCase().contains(query))
                .toList();

        if (matches.isEmpty()) {
            io.println("No AV actress matching '" + query + "' found.");
            return;
        }
        if (matches.size() > 1) {
            io.println("Multiple matches — be more specific:");
            matches.forEach(a -> io.println("  " + a.getStageName()
                    + "  (" + a.getVideoCount() + " videos)"));
            return;
        }

        AvActress a = matches.get(0);
        printProfile(a, io);

        List<AvVideo> videos = videoRepo.findByActress(a.getId());
        printVideos(videos, io);
    }

    private static void printProfile(AvActress a, CommandIO io) {
        io.println("");
        io.println("  " + a.getStageName());
        if (!a.getStageName().equals(a.getFolderName())) {
            io.println("  Folder: " + a.getFolderName());
        }
        io.println("  Volume: " + a.getVolumeId()
                + "  |  Videos: " + a.getVideoCount()
                + "  |  Size: " + AvActressesCommand.formatSize(a.getTotalSizeBytes()));

        if (a.getGrade() != null || a.isFavorite() || a.isBookmark()) {
            StringBuilder flags = new StringBuilder("  ");
            if (a.isFavorite()) flags.append("♥ Favorite  ");
            if (a.isBookmark()) flags.append("⊕ Bookmarked  ");
            if (a.getGrade() != null) flags.append("Grade: ").append(a.getGrade()).append("  ");
            io.println(flags.toString().stripTrailing());
        }

        if (a.getIafdId() != null) {
            io.println("  IAFD: " + a.getIafdId());
        }
        if (a.getNationality() != null) io.println("  Nationality: " + a.getNationality());
        if (a.getDateOfBirth() != null)  io.println("  Born: " + a.getDateOfBirth());
        if (a.getActiveFrom() != null) {
            String range = a.getActiveTo() != null
                    ? a.getActiveFrom() + " – " + a.getActiveTo()
                    : a.getActiveFrom() + " – present";
            io.println("  Active: " + range);
        }
        if (a.getMeasurements() != null) io.println("  Measurements: " + a.getMeasurements());
        if (a.getNotes() != null) io.println("  Notes: " + a.getNotes());
        io.println("");
    }

    private static void printVideos(List<AvVideo> videos, CommandIO io) {
        if (videos.isEmpty()) {
            io.println("  (no videos indexed)");
            return;
        }

        io.println("  Videos (" + videos.size() + "):");
        io.println("  " + "-".repeat(66));

        String currentBucket = "__NONE__";
        for (AvVideo v : videos) {
            String bucket = v.getBucket() != null ? v.getBucket() : "";
            if (!bucket.equals(currentBucket)) {
                if (!bucket.isEmpty()) io.println("  [" + bucket + "]");
                currentBucket = bucket;
            }
            String size = v.getSizeBytes() != null
                    ? AvActressesCommand.formatSize(v.getSizeBytes()) : "-";
            io.println(String.format("    %-52s  %8s", truncate(v.getFilename(), 52), size));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
