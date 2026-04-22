package com.organizer3.enrichment.plan;

/**
 * A single field-level delta in a YAML-to-DB plan. Values are serialized as their
 * {@code toString()} form in the UI; record types (dates, numbers, lists) round-trip through
 * Jackson's default serialization so the browser can render them without type awareness.
 *
 * <p>Only emitted for fields where {@code oldValue} and {@code newValue} differ. Equal values
 * are dropped by the planner, so the plan is a pure diff.
 */
public record FieldChange(String field, Object oldValue, Object newValue) {
    /** Convenience: returns true if this represents a "create" (no prior value). */
    public boolean isFresh() { return oldValue == null; }
}
