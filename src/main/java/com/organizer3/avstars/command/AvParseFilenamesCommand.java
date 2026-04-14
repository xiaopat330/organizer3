package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.avstars.sync.AvFilenameParser;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Runs the filename parser over all {@code av_videos} rows and persists the
 * extracted metadata (studio, release date, resolution, codec, tags).
 *
 * <p>Usage: {@code av parse [volume-id]}
 *
 * <p>Without a volume argument all videos are parsed. With a volume-id only
 * that volume's videos are re-parsed. Existing parsed values are overwritten.
 */
@RequiredArgsConstructor
public class AvParseFilenamesCommand implements Command {

    private final AvVideoRepository videoRepo;
    private final AvFilenameParser parser;

    @Override
    public String name() {
        return "av parse";
    }

    @Override
    public String description() {
        return "Parse filenames and persist metadata for AV videos (uses mounted volume, or: av parse <volume-id>)";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        String volumeId = args.length >= 2 ? args[1] : null;

        if (volumeId == null) {
            var mounted = ctx.getMountedVolume();
            if (mounted == null) {
                io.println("No volume mounted. Use: mount <id>  or  av parse <volume-id>");
                return;
            }
            volumeId = mounted.id();
        }

        List<AvVideo> videos = videoRepo.findByVolume(volumeId);
        if (videos.isEmpty()) {
            io.println("No videos found for volume '" + volumeId + "'.");
            return;
        }

        int updated = 0;
        int skipped = 0;

        for (AvVideo video : videos) {
            AvFilenameParser.ParsedFilename parsed = parser.parse(video.getFilename());

            String tagsJson = tagsToJson(parsed.tags());

            // Only update if at least one field was extracted
            if (parsed.studio() == null && parsed.releaseDate() == null
                    && parsed.resolution() == null && parsed.codec() == null
                    && parsed.tags().isEmpty()) {
                skipped++;
                continue;
            }

            videoRepo.updateParsedFields(
                    video.getId(),
                    parsed.studio(),
                    parsed.releaseDate(),
                    parsed.resolution(),
                    parsed.codec(),
                    tagsJson);
            updated++;
        }

        io.println("Parsed " + videos.size() + " video(s): " + updated + " updated, " + skipped + " no matches.");
    }

    /** Converts a list of tags to a compact JSON array string, or null if empty. */
    private static String tagsToJson(List<String> tags) {
        if (tags.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"');
            // Minimal JSON escaping for tag strings (no control chars expected in tags)
            sb.append(tags.get(i).replace("\\", "\\\\").replace("\"", "\\\""));
            sb.append('"');
        }
        sb.append(']');
        return sb.toString();
    }
}
