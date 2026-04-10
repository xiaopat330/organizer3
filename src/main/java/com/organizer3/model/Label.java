package com.organizer3.model;

import java.util.List;

/**
 * A JAV label entry from the labels reference table.
 *
 * <p>{@code code} is the label identifier as it appears in title codes (e.g., "ABP", "MIDE").
 * {@code labelName} is the human-readable label name (e.g., "Moody's Diva").
 * {@code company} is the production company that owns this label (e.g., "Moodyz").
 * {@code description} is an optional short description of the label's focus or history.
 * {@code tags} are the content classification tags inferred from the label's profile (e.g., "creampie", "amateur").
 */
public record Label(
        String code,
        String labelName,
        String company,
        String description,
        String companyDescription,
        String companySpecialty,
        String companyFounded,
        String companyStatus,
        String companyParent,
        List<String> tags
) {
    public Label(String code, String labelName, String company, String description, String companyDescription) {
        this(code, labelName, company, description, companyDescription, null, null, null, null, List.of());
    }
}
