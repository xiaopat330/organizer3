package com.organizer3.javdb.draft;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for applying PATCH edits to a draft (cast-slot resolution updates).
 *
 * <p>Each patch atomically:
 * <ol>
 *   <li>Validates all resolution shapes (see {@link #validate}).
 *   <li>Upserts {@code draft_actresses} for each affected slot.
 *   <li>Updates {@code draft_title_actresses} for the affected slots (via
 *       {@link DraftTitleActressesRepository#replaceForDraft} — full replacement).
 *   <li>Bumps {@code draft_titles.updated_at} so the optimistic-lock token is fresh.
 * </ol>
 *
 * <p>Optimistic locking: the caller supplies {@code expectedUpdatedAt} which must
 * match the current {@code draft_titles.updated_at}; a mismatch throws
 * {@link OptimisticLockException}.
 *
 * <p>Sentinel validation: the supplied {@code sentinel:<id>} resolutions are
 * checked against the {@code actresses} table ({@code is_sentinel=1}).
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §12.1 (PATCH route), §5.2 (resolution kinds).
 */
@Slf4j
@RequiredArgsConstructor
public class DraftPatchService {

    private final Jdbi jdbi;
    private final DraftTitleRepository draftTitleRepo;
    private final DraftActressRepository draftActressRepo;
    private final DraftTitleActressesRepository draftTitleActressesRepo;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Applies cast resolution and/or new-actress edits to the given draft.
     *
     * <p>Resolution kinds accepted per slot:
     * <ul>
     *   <li>{@code pick} — requires {@code linkToExistingId} non-null.
     *   <li>{@code create_new} — requires {@code englishLastName} non-blank.
     *   <li>{@code skip} — no extra fields; only valid in multi-actress (≥2 stage_names) mode.
     *   <li>{@code sentinel:<id>} — the actress at {@code id} must have {@code is_sentinel=1}.
     *   <li>{@code unresolved} — valid (user explicitly resetting a slot).
     * </ul>
     *
     * @param titleId           canonical title id; must have an active draft.
     * @param expectedUpdatedAt optimistic-lock token from the most recent GET response.
     * @param castResolutions   slots to update; may be empty.
     * @param newActresses      actress rows to upsert (name edits); may be empty.
     * @return the new {@code updated_at} token after the patch.
     * @throws DraftNotFoundException     if no active draft exists for {@code titleId}.
     * @throws OptimisticLockException    if {@code expectedUpdatedAt} doesn't match.
     * @throws PatchValidationException   if any resolution shape is invalid.
     */
    public String patch(long titleId,
                        String expectedUpdatedAt,
                        List<CastResolutionEdit> castResolutions,
                        List<NewActressEdit> newActresses) {

        // 1. Load the draft.
        DraftTitle draft = draftTitleRepo.findByTitleId(titleId)
                .orElseThrow(() -> new DraftNotFoundException(titleId));

        // 2. Optimistic-lock check.
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(draft.getUpdatedAt())) {
            throw new OptimisticLockException(
                    "draft_titles.updated_at mismatch: expected=" + expectedUpdatedAt
                    + " actual=" + draft.getUpdatedAt());
        }

        // 3. Validate all resolution shapes.
        List<String> validationErrors = validate(castResolutions, newActresses);
        if (!validationErrors.isEmpty()) {
            throw new PatchValidationException(validationErrors);
        }

        String nowIso = Instant.now().toString();

        // 4. Execute the patch atomically.
        jdbi.useTransaction(h -> {
            // 4a. Upsert draft_actresses for each slot and new-actress edit.
            for (CastResolutionEdit edit : castResolutions) {
                applyResolutionUpsert(edit, nowIso);
            }
            for (NewActressEdit edit : newActresses) {
                // Upsert actress name data only (no resolution change).
                DraftActress existing = draftActressRepo.findBySlug(edit.javdbSlug())
                        .orElse(null);
                DraftActress updated = DraftActress.builder()
                        .javdbSlug(edit.javdbSlug())
                        .stageName(existing != null ? existing.getStageName() : null)
                        .englishFirstName(edit.englishFirstName())
                        .englishLastName(edit.englishLastName())
                        .linkToExistingId(existing != null ? existing.getLinkToExistingId() : null)
                        .createdAt(existing != null ? existing.getCreatedAt() : nowIso)
                        .updatedAt(nowIso)
                        .lastValidationError(null)
                        .build();
                draftActressRepo.upsertBySlug(updated);
            }

            // 4b. Rebuild full draft_title_actresses list for this draft.
            //     We do a full replacement using the merged view: start from existing,
            //     apply edits on top.
            long draftId = draft.getId();
            List<DraftTitleActress> existing = draftTitleActressesRepo.findByDraftTitleId(draftId);

            // Build a map: javdbSlug → new resolution (from the edit).
            java.util.Map<String, String> overrides = new java.util.LinkedHashMap<>();
            for (CastResolutionEdit edit : castResolutions) {
                overrides.put(edit.javdbSlug(), edit.resolution());
            }

            // Apply overrides to existing list.
            List<DraftTitleActress> updated = new ArrayList<>();
            for (DraftTitleActress row : existing) {
                String newRes = overrides.getOrDefault(row.getJavdbSlug(), row.getResolution());
                updated.add(new DraftTitleActress(draftId, row.getJavdbSlug(), newRes));
            }
            // Add any slug in overrides that wasn't in the existing list (new slots).
            java.util.Set<String> existingSlugs = new java.util.HashSet<>();
            for (DraftTitleActress row : existing) existingSlugs.add(row.getJavdbSlug());
            for (CastResolutionEdit edit : castResolutions) {
                if (!existingSlugs.contains(edit.javdbSlug())) {
                    updated.add(new DraftTitleActress(draftId, edit.javdbSlug(), edit.resolution()));
                }
            }

            draftTitleActressesRepo.replaceForDraft(draftId, updated);

            // 4c. Bump draft_titles.updated_at (optimistic-lock token refresh).
            // We do this inside the transaction by issuing the UPDATE directly.
            h.createUpdate("""
                    UPDATE draft_titles
                    SET updated_at = :now
                    WHERE id = :id
                      AND updated_at = :expected
                    """)
                    .bind("now",      nowIso)
                    .bind("id",       draftId)
                    .bind("expected", draft.getUpdatedAt())
                    .execute();
        });

        log.info("DraftPatchService: patched draft for titleId={} with {} resolutions, {} newActresses",
                titleId, castResolutions.size(), newActresses.size());
        return nowIso;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Validates all resolution shapes in the patch request.
     * Returns a list of error codes; empty means valid.
     */
    List<String> validate(List<CastResolutionEdit> castResolutions,
                          List<NewActressEdit> newActresses) {
        List<String> errors = new ArrayList<>();

        for (CastResolutionEdit edit : castResolutions) {
            String res = edit.resolution();
            if (res == null) {
                errors.add("RESOLUTION_NULL");
                continue;
            }
            switch (res) {
                case "pick" -> {
                    if (edit.linkToExistingId() == null) {
                        errors.add("PICK_MISSING_LINK_ID");
                    }
                }
                case "create_new" -> {
                    if (edit.englishLastName() == null || edit.englishLastName().isBlank()) {
                        errors.add("CREATE_NEW_MISSING_LAST_NAME");
                    }
                }
                case "skip", "unresolved" -> { /* always valid */ }
                default -> {
                    if (res.startsWith("sentinel:")) {
                        String idStr = res.substring("sentinel:".length());
                        try {
                            long sentinelId = Long.parseLong(idStr);
                            if (!isSentinel(sentinelId)) {
                                errors.add("SENTINEL_NOT_FLAGGED");
                            }
                        } catch (NumberFormatException e) {
                            errors.add("SENTINEL_INVALID_ID");
                        }
                    } else {
                        errors.add("UNKNOWN_RESOLUTION_KIND");
                    }
                }
            }
        }

        for (NewActressEdit edit : newActresses) {
            if (edit.englishLastName() == null || edit.englishLastName().isBlank()) {
                errors.add("CREATE_NEW_MISSING_LAST_NAME");
            }
        }

        return errors.stream().distinct().toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyResolutionUpsert(CastResolutionEdit edit, String nowIso) {
        DraftActress existing = draftActressRepo.findBySlug(edit.javdbSlug()).orElse(null);

        DraftActress.DraftActressBuilder builder = DraftActress.builder()
                .javdbSlug(edit.javdbSlug())
                .stageName(existing != null ? existing.getStageName() : null)
                .createdAt(existing != null ? existing.getCreatedAt() : nowIso)
                .updatedAt(nowIso)
                .lastValidationError(null);

        String res = edit.resolution();
        if ("pick".equals(res)) {
            builder.linkToExistingId(edit.linkToExistingId())
                   .englishFirstName(existing != null ? existing.getEnglishFirstName() : null)
                   .englishLastName(existing != null ? existing.getEnglishLastName() : null);
        } else if ("create_new".equals(res)) {
            builder.linkToExistingId(null)
                   .englishLastName(edit.englishLastName())
                   .englishFirstName(edit.englishFirstName());
        } else {
            // skip, unresolved, sentinel: preserve existing name data
            builder.linkToExistingId(existing != null ? existing.getLinkToExistingId() : null)
                   .englishFirstName(existing != null ? existing.getEnglishFirstName() : null)
                   .englishLastName(existing != null ? existing.getEnglishLastName() : null);
        }

        draftActressRepo.upsertBySlug(builder.build());
    }

    private boolean isSentinel(long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT is_sentinel FROM actresses WHERE id = :id")
                        .bind("id", actressId)
                        .mapTo(Integer.class)
                        .findOne()
                        .map(v -> v == 1)
                        .orElse(false));
    }

    // ── Request records ───────────────────────────────────────────────────────

    /**
     * One cast-slot resolution edit (from the PATCH body {@code castResolutions} array).
     *
     * @param javdbSlug       the actress javdb slug identifying the slot.
     * @param resolution      new resolution string.
     * @param linkToExistingId  required when {@code resolution='pick'}.
     * @param englishLastName   required when {@code resolution='create_new'}.
     * @param englishFirstName  optional; used for {@code create_new}.
     */
    public record CastResolutionEdit(
            String javdbSlug,
            String resolution,
            Long   linkToExistingId,
            String englishLastName,
            String englishFirstName
    ) {}

    /**
     * Name-only edit for a draft actress (from the PATCH body {@code newActresses} array).
     *
     * @param javdbSlug        slug of the actress to update.
     * @param englishLastName  required (non-blank).
     * @param englishFirstName optional; nullable for mononyms.
     */
    public record NewActressEdit(
            String javdbSlug,
            String englishLastName,
            String englishFirstName
    ) {}

    /**
     * Thrown when the PATCH body contains invalid resolution shapes.
     * Carries a list of error codes (e.g. {@code PICK_MISSING_LINK_ID}).
     */
    public static class PatchValidationException extends RuntimeException {
        private final List<String> errors;

        public PatchValidationException(List<String> errors) {
            super("patch validation failed: " + errors);
            this.errors = errors;
        }

        public List<String> getErrors() { return errors; }
    }
}
