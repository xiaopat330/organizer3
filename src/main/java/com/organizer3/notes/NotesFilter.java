package com.organizer3.notes;

/**
 * Tri-state filter for notes on browse queries.
 *
 * <p>Used by actress-browse and title-browse to restrict results to those
 * with or without a note. {@code null} means "Any" — no predicate is applied.
 *
 * <p>Exists predicates:
 * <ul>
 *   <li>{@link #HAS_NOTE} — {@code AND EXISTS (SELECT 1 FROM notes WHERE ...)}</li>
 *   <li>{@link #NO_NOTE}  — {@code AND NOT EXISTS (SELECT 1 FROM notes WHERE ...)}</li>
 *   <li>null             — clause omitted</li>
 * </ul>
 */
public enum NotesFilter {
    HAS_NOTE,
    NO_NOTE
}
