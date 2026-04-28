package com.organizer3.javdb.enrichment;

/**
 * A row from {@code javdb_enrichment_queue}.
 */
public record EnrichmentJob(
        long id,
        String jobType,      // 'fetch_title' | 'fetch_actress_profile'
        long targetId,       // title_id or actress_id depending on jobType
        long actressId,      // owning actress; 0 when null (title-driven jobs have no owning actress)
        String source,       // 'actress' | 'recent' | 'pool' | 'collection'
        String status,       // 'pending' | 'in_flight' | 'done' | 'failed' | 'cancelled'
        int attempts,
        String nextAttemptAt,
        String lastError,
        String createdAt,
        String updatedAt
) {
    public static final String FETCH_TITLE = "fetch_title";
    public static final String FETCH_ACTRESS_PROFILE = "fetch_actress_profile";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_FLIGHT = "in_flight";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String SOURCE_ACTRESS    = "actress";
    public static final String SOURCE_RECENT     = "recent";
    public static final String SOURCE_POOL       = "pool";
    public static final String SOURCE_COLLECTION = "collection";

    /** True when this job was enqueued for a specific owning actress (actress-driven flow). */
    public boolean isActressDriven() {
        return SOURCE_ACTRESS.equals(source);
    }
}
