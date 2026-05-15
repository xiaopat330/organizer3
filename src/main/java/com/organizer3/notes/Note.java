package com.organizer3.notes;

/**
 * A user-curated short annotation attached to an actress or title.
 *
 * <p>Keyed by {@code (entityType, entityId)} — one note per entity.
 * {@code body} is NFC-normalized, trimmed, and capped at 280 chars server-side.
 * {@code createdAt} and {@code updatedAt} are epoch millis.
 */
public record Note(
        EntityType entityType,
        String entityId,
        String body,
        long createdAt,
        long updatedAt
) {}
