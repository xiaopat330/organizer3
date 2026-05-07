package com.organizer3.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Serializes the detail lists of a {@link ReconcileReport} to JSON for storage in
 * {@code reconcile_reports.detail_json}. Only populated in verbose mode.
 */
@Slf4j
public final class ReconcileDetailSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReconcileDetailSerializer() {}

    /**
     * Serialize the four detail lists of a report to a JSON string.
     * Returns {@code null} if all detail lists are empty (non-verbose run).
     */
    public static String toJson(ReconcileReport report) {
        if (report.duplicateLiveDetails().isEmpty()
                && report.pendingGraceDetails().isEmpty()
                && report.pastGraceDetails().isEmpty()
                && report.mismatchDetails().isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(new Details(
                    report.duplicateLiveDetails(),
                    report.pendingGraceDetails(),
                    report.pastGraceDetails(),
                    report.mismatchDetails()
            ));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize reconcile report details: {}", e.getMessage());
            return null;
        }
    }

    public record Details(
            java.util.List<com.organizer3.repository.TitleLocationRepository.DuplicateLiveLocation> duplicateLive,
            java.util.List<com.organizer3.repository.TitleLocationRepository.PendingGraceRow> pendingGrace,
            java.util.List<com.organizer3.repository.TitleLocationRepository.PendingGraceRow> pastGrace,
            java.util.List<com.organizer3.repository.TitleRepository.ActressMismatch> mismatches
    ) {}
}
