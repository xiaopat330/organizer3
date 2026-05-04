package com.organizer3.translation;

/**
 * Row from the {@code translation_queue} table.
 *
 * <p>Status values: {@code pending}, {@code in_flight}, {@code done}, {@code failed}.
 *
 * <p>The {@code callbackKind} + {@code callbackId} pair identifies the catalog field to update
 * once translation completes. Either may be null for fire-and-forget requests.
 */
public record TranslationQueueRow(
        long id,
        String sourceText,
        long strategyId,
        String submittedAt,
        String startedAt,
        String completedAt,
        String status,
        String callbackKind,
        Long callbackId,
        int attemptCount,
        String lastError
) {
    public static final String STATUS_PENDING   = "pending";
    public static final String STATUS_IN_FLIGHT = "in_flight";
    public static final String STATUS_DONE      = "done";
    public static final String STATUS_FAILED    = "failed";
}
