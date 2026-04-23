package com.organizer3.organize;

import com.organizer3.filesystem.VolumeFileSystem;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies {@link TitleTimestampService} to every title in the curated (non-queue)
 * partitions of a volume. Used by the "Fix timestamps" organize action.
 *
 * <p>The curated area is defined as all {@code title_locations} rows for the volume
 * whose {@code partition_id} is not {@code "queue"}. This covers {@code stars/},
 * actress folders (exhibition), and collection partitions.
 */
@Slf4j
public class FixTimestampsVolumeService {

    private final Jdbi jdbi;
    private final TitleTimestampService timestampService;

    public FixTimestampsVolumeService(Jdbi jdbi, TitleTimestampService timestampService) {
        this.jdbi = jdbi;
        this.timestampService = timestampService;
    }

    public Result fix(VolumeFileSystem fs, String volumeId, boolean dryRun) {
        List<TitleRow> rows = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tl.path, t.code
                        FROM title_locations tl
                        JOIN titles t ON t.id = tl.title_id
                        WHERE tl.volume_id = :volumeId AND tl.partition_id != 'queue'
                        ORDER BY tl.path
                        """)
                        .bind("volumeId", volumeId)
                        .map((rs, ctx) -> new TitleRow(rs.getString("path"), rs.getString("code")))
                        .list());

        List<TitleResult> results = new ArrayList<>();
        int changed = 0, skipped = 0, failed = 0;

        for (TitleRow row : rows) {
            Path folder = Path.of(row.path());
            try {
                TitleTimestampService.Result r = timestampService.apply(fs, folder, dryRun);
                String current = r.plan().folderCurrent() != null && r.plan().folderCurrent().created() != null
                        ? r.plan().folderCurrent().created().toString() : null;
                String target = r.plan().earliestChildTime() != null
                        ? r.plan().earliestChildTime().toString() : null;

                results.add(new TitleResult(row.code(), row.path(), current, target,
                        r.plan().needsChange(), r.applied(), r.error()));

                if (r.error() != null)        failed++;
                else if (!r.plan().needsChange()) skipped++;
                else                           changed++;
            } catch (Exception e) {
                log.warn("FixTimestamps skipped {} — {}", row.path(), e.getMessage());
                results.add(new TitleResult(row.code(), row.path(), null, null, false, false, describe(e)));
                failed++;
            }
        }

        return new Result(dryRun, volumeId, results, new Summary(rows.size(), changed, skipped, failed));
    }

    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (depth > 0) sb.append(" | caused by ");
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    // ── result shapes ──────────────────────────────────────────────────────

    record TitleRow(String path, String code) {}

    public record TitleResult(
            String titleCode,
            String path,
            String currentTimestamp,
            String targetTimestamp,
            boolean needsChange,
            boolean applied,
            String error
    ) {}

    public record Summary(int checked, int changed, int skipped, int failed) {}

    public record Result(
            boolean dryRun,
            String volumeId,
            List<TitleResult> titles,
            Summary summary
    ) {}
}
