package com.organizer3.javdb.enrichment;

import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Service backing {@code POST /api/triage/cast-anomaly/:queueId/add-alias}.
 *
 * <p>Validates that:
 * <ul>
 *   <li>The queue row exists and is open ({@code resolved_at IS NULL}).</li>
 *   <li>The row has {@code reason = 'cast_anomaly'}.</li>
 *   <li>The supplied {@code actressId} is actually linked to the title.</li>
 *   <li>The {@code aliasName} is non-blank.</li>
 * </ul>
 * Then upserts the alias (additive, idempotent) and fires the recovery sweep.
 */
@Slf4j
@RequiredArgsConstructor
public class CastAnomalyTriageService {

    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final ActressRepository actressRepo;
    private final TitleActressRepository titleActressRepo;
    private final EnrichmentRunner enrichmentRunner;

    /**
     * Adds {@code aliasName} as an alias for actress {@code actressId}, then fires
     * {@link EnrichmentRunner#recoverCastAnomaliesAfterMatcherFix()} to discharge this
     * row and any sibling rows that can now be resolved.
     *
     * @return result containing whether the alias was inserted and how many rows were recovered
     * @throws IllegalArgumentException if validation fails (4xx)
     */
    public AddAliasResult addAlias(long queueRowId, long actressId, String aliasName) {
        // 1. Validate aliasName
        if (aliasName == null || aliasName.isBlank()) {
            throw new IllegalArgumentException("aliasName must not be blank");
        }
        String trimmed = aliasName.strip();

        // 2. Look up the queue row — must exist and be open
        Optional<EnrichmentReviewQueueRepository.OpenRow> maybeRow =
                reviewQueueRepo.findOpenById(queueRowId);
        if (maybeRow.isEmpty()) {
            throw new NotFoundException("queue row not found or already resolved: " + queueRowId);
        }
        EnrichmentReviewQueueRepository.OpenRow row = maybeRow.get();

        // 3. Row must be cast_anomaly
        if (!"cast_anomaly".equals(row.reason())) {
            throw new IllegalArgumentException(
                    "queue row " + queueRowId + " has reason '" + row.reason() + "', expected 'cast_anomaly'");
        }

        // 4. actressId must be linked to the title
        List<Long> linkedIds = titleActressRepo.findActressIdsByTitle(row.titleId());
        if (!linkedIds.contains(actressId)) {
            throw new IllegalArgumentException(
                    "actress " + actressId + " is not linked to title " + row.titleId());
        }

        // 5. Verify actress exists
        Optional<Actress> maybeActress = actressRepo.findById(actressId);
        if (maybeActress.isEmpty()) {
            throw new IllegalArgumentException("actress not found: " + actressId);
        }
        Actress actress = maybeActress.get();

        // 6. Upsert alias (INSERT OR IGNORE — idempotent)
        actressRepo.saveAlias(new com.organizer3.model.ActressAlias(actressId, trimmed));
        log.info("cast_anomaly triage: added alias '{}' for actress {} ({}) via queue row {}",
                trimmed, actressId, actress.getCanonicalName(), queueRowId);

        // 7. Fire recovery sweep — discharges this row + siblings now resolvable
        int rowsRecovered = enrichmentRunner.recoverCastAnomaliesAfterMatcherFix();

        return new AddAliasResult(true, rowsRecovered);
    }

    /** Result of a successful add-alias operation. */
    public record AddAliasResult(boolean aliasInserted, int rowsRecovered) {}

    /** Signals a 404-class error (row not found). */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
}
