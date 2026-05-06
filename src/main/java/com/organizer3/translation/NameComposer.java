package com.organizer3.translation;

/**
 * Composes an English stage name from first + last name parts.
 *
 * <p>Rule: {@code "(first + ' ' + last).trim()"} when {@code first} is present;
 * {@code last} alone when {@code first} is blank or null (mononym convention).
 * Callers must validate that {@code last} is non-blank before invoking.
 */
public final class NameComposer {

    private NameComposer() {}

    public static String compose(String first, String last) {
        if (first != null && !first.isBlank()) {
            return (first.trim() + " " + last.trim()).trim();
        }
        return last != null ? last.trim() : "";
    }
}
