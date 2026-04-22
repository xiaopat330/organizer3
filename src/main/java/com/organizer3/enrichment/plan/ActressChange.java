package com.organizer3.enrichment.plan;

import java.util.List;

/**
 * Planned change for the actress row itself: either create a new record, or update an existing
 * one with a set of field deltas. Profile and extended-profile fields are collapsed into a single
 * {@code fields} list — the UI doesn't need to distinguish.
 */
public sealed interface ActressChange {

    String canonicalName();

    /** Kind is exposed so Jackson emits a discriminator for the UI to key on. */
    String kind();

    record Create(String canonicalName, List<FieldChange> fields) implements ActressChange {
        public Create { fields = List.copyOf(fields); }
        @Override public String kind() { return "create"; }
    }

    record Update(long actressId, String canonicalName, List<FieldChange> fields) implements ActressChange {
        public Update { fields = List.copyOf(fields); }
        @Override public String kind() { return "update"; }
    }
}
