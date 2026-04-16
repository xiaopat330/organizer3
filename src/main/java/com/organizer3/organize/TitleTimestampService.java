package com.organizer3.organize;

import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Core logic for timestamp correction on a title folder: walk the folder's immediate
 * children, find the earliest creation (or modification) timestamp among them, and
 * set the title folder's own creation + lastWrite times to that earliest value.
 *
 * <p>Rationale: folder timestamps on the NAS often don't reflect when the title
 * actually joined the catalog. Contents — especially the cover image, which is
 * usually added by a human curator at catalog-add time — are a more reliable signal.
 * See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.5.
 */
public class TitleTimestampService {

    /** Small rounding tolerance for "folder timestamp already matches" check. */
    private static final long EQUALITY_TOLERANCE_MILLIS = 2_000L;

    /**
     * Inspect the title folder, gather child timestamps, compute the target, and
     * apply when {@code dryRun} is false. Always returns a full plan.
     */
    public Result apply(VolumeFileSystem fs, Path titleFolder, boolean dryRun) throws IOException {
        if (!fs.exists(titleFolder) || !fs.isDirectory(titleFolder)) {
            throw new IllegalArgumentException("Title folder does not exist or is not a directory: " + titleFolder);
        }

        FileTimestamps folderNow = fs.getTimestamps(titleFolder);

        List<ChildTimestamp> children = new ArrayList<>();
        for (Path child : fs.listDirectory(titleFolder)) {
            FileTimestamps ct = safeTimestamps(fs, child);
            children.add(new ChildTimestamp(
                    filename(child),
                    ct == null ? null : ct.created(),
                    ct == null ? null : ct.modified()));
        }

        Instant earliest = earliestChildTime(children);
        boolean needsChange = shouldChange(folderNow, earliest);

        Plan plan = new Plan(
                titleFolder.toString(),
                folderNow,
                earliest,
                children,
                needsChange);

        if (dryRun || !needsChange || earliest == null) {
            return new Result(dryRun, plan, false, null);
        }

        try {
            fs.setTimestamps(titleFolder, earliest, earliest);
            return new Result(false, plan, true, null);
        } catch (IOException e) {
            return new Result(false, plan, false, describe(e));
        }
    }

    /** Build a plan only — no side effects. Useful for audits that render and move on. */
    public Plan buildPlan(VolumeFileSystem fs, Path titleFolder) throws IOException {
        return apply(fs, titleFolder, true).plan();
    }

    // ── internals ──────────────────────────────────────────────────────────

    private static FileTimestamps safeTimestamps(VolumeFileSystem fs, Path p) {
        try {
            return fs.getTimestamps(p);
        } catch (IOException e) {
            return null;  // best-effort; we'll simply skip this child in the min
        }
    }

    /**
     * Returns the absolute earliest timestamp across all children, considering both
     * creation and modification times. On NAS-hosted shares, {@code created} often
     * reflects "when this file arrived on this NAS" (a batch copy date) while
     * {@code modified} preserves the original authoring time — so the *minimum across
     * both* is the most reliable signal of the title's true catalog-add era.
     */
    static Instant earliestChildTime(List<ChildTimestamp> children) {
        Instant best = null;
        for (ChildTimestamp c : children) {
            best = min(best, c.created());
            best = min(best, c.modified());
        }
        return best;
    }

    private static Instant min(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    static boolean shouldChange(FileTimestamps folder, Instant earliest) {
        if (earliest == null) return false;
        Instant folderCreated  = folder.created();
        Instant folderModified = folder.modified();
        // Change if either field is unset OR differs from earliest beyond tolerance.
        if (folderCreated == null)  return true;
        if (folderModified == null) return true;
        return Math.abs(folderCreated.toEpochMilli()  - earliest.toEpochMilli()) > EQUALITY_TOLERANCE_MILLIS
            || Math.abs(folderModified.toEpochMilli() - earliest.toEpochMilli()) > EQUALITY_TOLERANCE_MILLIS;
    }

    private static String filename(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
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

    public record ChildTimestamp(String filename, Instant created, Instant modified) {}

    public record Plan(
            String titleFolder,
            FileTimestamps folderCurrent,
            Instant earliestChildTime,
            List<ChildTimestamp> children,
            boolean needsChange
    ) {}

    public record Result(boolean dryRun, Plan plan, boolean applied, String error) {}
}
