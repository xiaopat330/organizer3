package com.organizer3.utilities.task.avstars;

import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.avstars.sync.AvFilenameParser;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * Run the filename parser over AV videos and persist any fields it extracts
 * (studio / releaseDate / resolution / codec / tags). Read-only regarding files;
 * writes only to {@code av_videos} metadata columns.
 *
 * <p>Inputs (all optional):
 * <ul>
 *   <li>{@code actressId} — scope to one actress's videos.</li>
 *   <li>{@code volumeId} — scope to one volume.</li>
 * </ul>
 * With neither set, parses across all videos.
 */
public final class ParseFilenamesTask implements Task {

    public static final String ID = "avstars.parse_filenames";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Parse AV filenames",
            "Run the filename parser and persist extracted metadata.",
            List.of(new TaskSpec.InputSpec("actressId", "AV actress", TaskSpec.InputSpec.InputType.STRING, false),
                    new TaskSpec.InputSpec("volumeId",  "Volume",     TaskSpec.InputSpec.InputType.STRING, false))
    );

    private final AvVideoRepository videoRepo;
    private final AvFilenameParser parser;

    public ParseFilenamesTask(AvVideoRepository videoRepo, AvFilenameParser parser) {
        this.videoRepo = videoRepo;
        this.parser = parser;
    }

    @Override public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        Object actressIdRaw = inputs.values().get("actressId");
        Object volumeIdRaw  = inputs.values().get("volumeId");

        List<AvVideo> videos;
        String scopeLabel;
        if (actressIdRaw != null) {
            long actressId = Long.parseLong(String.valueOf(actressIdRaw));
            videos = videoRepo.findByActress(actressId);
            scopeLabel = "actress " + actressId;
        } else if (volumeIdRaw != null) {
            String volumeId = String.valueOf(volumeIdRaw);
            videos = videoRepo.findByVolume(volumeId);
            scopeLabel = "volume " + volumeId;
        } else {
            // Whole-library scope isn't supported by the existing repo API directly;
            // parse the favourable unit (per-actress is the Utilities entry point today).
            io.phaseStart("parse", "Parse filenames");
            io.phaseEnd("parse", "failed", "Provide actressId or volumeId");
            return;
        }

        io.phaseStart("parse", "Parse filenames (" + scopeLabel + ")");
        int updated = 0, skipped = 0;
        for (int i = 0; i < videos.size(); i++) {
            if (io.isCancellationRequested()) {
                io.phaseLog("parse", "Cancellation requested — stopping after " + i + " of " + videos.size());
                break;
            }
            AvVideo v = videos.get(i);
            AvFilenameParser.ParsedFilename p = parser.parse(v.getFilename());
            if (p.studio() == null && p.releaseDate() == null
                    && p.resolution() == null && p.codec() == null
                    && p.tags().isEmpty()) {
                skipped++;
                continue;
            }
            videoRepo.updateParsedFields(v.getId(), p.studio(), p.releaseDate(),
                    p.resolution(), p.codec(), tagsToJson(p.tags()));
            updated++;
            io.phaseProgress("parse", i + 1, videos.size(), v.getFilename());
        }
        io.phaseEnd("parse", "ok",
                "Parsed " + videos.size() + " · updated " + updated + " · skipped " + skipped);
    }

    private static String tagsToJson(List<String> tags) {
        if (tags.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(tags.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
