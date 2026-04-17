package com.organizer3.organize;

import com.organizer3.config.volume.LibraryConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 of the organize pipeline: move a title folder from the volume's queue
 * partition into {@code /stars/{tier}/{actressName}/{titleFolder}}, routing to
 * {@code /attention/} when filing isn't possible. See
 * {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.3 + §4.
 *
 * <p>Decision flow:
 * <ol>
 *   <li>No filing actress → route to attention ({@code reason=actressless-title}).</li>
 *   <li>Multiple credited actresses → skip (belongs on {@code collections} volume).</li>
 *   <li>Actress's canonical first letter not covered by the mounted volume's
 *       {@code letters} config → route to attention
 *       ({@code reason=actress-letter-mismatch}).</li>
 *   <li>Actress's title count below {@code star} threshold → skip (stays in queue).</li>
 *   <li>Target path already exists (different title folder with same name) →
 *       route to attention ({@code reason=collision}).</li>
 *   <li>Otherwise → move folder + update DB + apply timestamp correction.</li>
 * </ol>
 *
 * <p>DB + FS are updated inside a single SQLite transaction; if the FS move throws,
 * the transaction rolls back. Timestamp correction runs after the move and is
 * best-effort (its failure doesn't revert the sort).
 */
@Slf4j
public class TitleSorterService {

    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final TitleActressRepository titleActressRepo;
    private final TitleLocationRepository titleLocationRepo;
    private final LibraryConfig libraryConfig;
    private final TitleTimestampService timestampService;

    public TitleSorterService(
            TitleRepository titleRepo,
            ActressRepository actressRepo,
            TitleActressRepository titleActressRepo,
            TitleLocationRepository titleLocationRepo,
            LibraryConfig libraryConfig,
            TitleTimestampService timestampService) {
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.titleActressRepo = titleActressRepo;
        this.titleLocationRepo = titleLocationRepo;
        this.libraryConfig = libraryConfig != null ? libraryConfig : LibraryConfig.DEFAULTS;
        this.timestampService = timestampService;
    }

    public Result sort(
            VolumeFileSystem fs,
            VolumeConfig volumeConfig,
            AttentionRouter attentionRouter,
            Jdbi jdbi,
            String titleCode,
            boolean dryRun) {

        Title title = titleRepo.findByCode(titleCode).orElseThrow(
                () -> new IllegalArgumentException("No title with code " + titleCode));

        // Locate the title on this volume
        String volumeId = volumeConfig.id();
        TitleLocation current = titleLocationRepo.findByTitle(title.getId()).stream()
                .filter(l -> volumeId.equals(l.getVolumeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Title '" + titleCode + "' has no location on volume '" + volumeId + "'"));

        Path currentFolder = current.getPath();

        List<Long> creditedActressIds = titleActressRepo.findActressIdsByTitle(title.getId());
        boolean multiActress = creditedActressIds.size() > 1;
        Long filingActressId = title.getActressId();
        Actress filingActress = filingActressId == null ? null
                : actressRepo.findById(filingActressId).orElse(null);

        // 1. Actressless
        if (filingActress == null) {
            return routeAttention(fs, attentionRouter, currentFolder,
                    "actressless-title",
                    Map.of("title-code", titleCode),
                    "Title has no filing actress in the DB (titles.actress_id is null). "
                            + "Attribute it manually via attribute_title, then re-run sort.",
                    title, current, dryRun);
        }

        // 2. Multi-actress
        if (multiActress) {
            return new Result(dryRun, Outcome.SKIPPED, currentFolder.toString(), null,
                    "multi-actress (" + creditedActressIds.size() + ") — belongs on collections volume", null, null);
        }

        String name = filingActress.getCanonicalName();

        // 3. Letter mismatch
        if (!volumeConfig.coversName(name)) {
            String expected = volumeConfig.letters() == null ? "any" : String.join(",", volumeConfig.letters());
            return routeAttention(fs, attentionRouter, currentFolder,
                    "actress-letter-mismatch",
                    Map.of("expected-letters", expected, "actual-actress", name),
                    "Title's filing actress '" + name + "' does not match the letter prefixes "
                            + "[" + expected + "] covered by volume '" + volumeId + "'. "
                            + "Move this title folder manually to the correct volume.",
                    title, current, dryRun);
        }

        // 4. Below threshold
        int titleCount = titleRepo.countByActress(filingActress.getId());
        String tier = libraryConfig.tierFor(titleCount);
        if ("pool".equals(tier)) {
            return new Result(dryRun, Outcome.SKIPPED, currentFolder.toString(), null,
                    "actress '" + name + "' has " + titleCount + " title(s) — below star threshold; stays in queue",
                    null, null);
        }

        // 5. Collision
        Path targetParent = Path.of("/", "stars", tier, name);
        Path target = targetParent.resolve(currentFolder.getFileName().toString());
        if (currentFolder.equals(target)) {
            return new Result(dryRun, Outcome.SKIPPED, currentFolder.toString(), target.toString(),
                    "already at canonical path", null, null);
        }
        if (fs.exists(target)) {
            return routeAttention(fs, attentionRouter, currentFolder,
                    "collision",
                    Map.of("target", target.toString(), "actress", name, "tier", tier),
                    "Sort target already exists on disk. Manual review needed — could be a "
                            + "legitimate duplicate (merge?) or a same-name conflict.",
                    title, current, dryRun);
        }

        if (dryRun) {
            return new Result(true, Outcome.WOULD_SORT, currentFolder.toString(), target.toString(),
                    "tier=" + tier + " actress='" + name + "'", null, null);
        }

        // 6. Apply — DB + FS in a transaction
        try {
            jdbi.useTransaction(h -> {
                fs.createDirectories(targetParent);
                fs.move(currentFolder, target);
                titleLocationRepo.updatePathAndPartition(current.getId(), target, tier);
            });
        } catch (Exception e) {
            return new Result(false, Outcome.FAILED, currentFolder.toString(), target.toString(),
                    "apply failed: " + describe(e), null, null);
        }

        // 7. Best-effort timestamp correction
        String timestampNote = null;
        try {
            var tr = timestampService.apply(fs, target, false);
            if (tr.applied()) timestampNote = "timestamps normalized to " + tr.plan().earliestChildTime();
            else if (tr.error() != null) timestampNote = "timestamp fix failed: " + tr.error();
        } catch (IOException e) {
            timestampNote = "timestamp fix threw: " + e.getMessage();
        }

        return new Result(false, Outcome.SORTED, currentFolder.toString(), target.toString(),
                "tier=" + tier + " actress='" + name + "'", null, timestampNote);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Result routeAttention(VolumeFileSystem fs,
                                   AttentionRouter router,
                                   Path currentFolder,
                                   String reasonCode,
                                   Map<String, String> headers,
                                   String body,
                                   Title title,
                                   TitleLocation current,
                                   boolean dryRun) {
        Path attentionTarget = Path.of("/", "attention").resolve(currentFolder.getFileName().toString());
        if (dryRun) {
            return new Result(true, Outcome.WOULD_ROUTE_TO_ATTENTION,
                    currentFolder.toString(), attentionTarget.toString(),
                    reasonCode, null, null);
        }
        try {
            AttentionRouter.Result r = router.route(currentFolder, reasonCode, headers, body);
            titleLocationRepo.updatePathAndPartition(current.getId(), Path.of(r.attentionPath()), "attention");
            return new Result(false, Outcome.ROUTED_TO_ATTENTION,
                    currentFolder.toString(), r.attentionPath(),
                    reasonCode, r.sidecarPath(), null);
        } catch (IOException e) {
            return new Result(false, Outcome.FAILED, currentFolder.toString(), attentionTarget.toString(),
                    "attention-route failed: " + describe(e), null, null);
        }
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

    public enum Outcome {
        SORTED,                    // moved to /stars/{tier}/{actress}/...
        WOULD_SORT,                // dry-run version of SORTED
        ROUTED_TO_ATTENTION,       // moved to /attention/ with REASON.txt
        WOULD_ROUTE_TO_ATTENTION,  // dry-run
        SKIPPED,                   // multi-actress / below threshold / already-canonical
        FAILED                     // apply threw
    }

    public record Result(
            boolean dryRun,
            Outcome outcome,
            String from,
            String to,
            String reason,
            String reasonSidecar,
            String timestampNote
    ) {}
}
