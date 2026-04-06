package com.organizer3.model;

/**
 * A JAV label entry from the labels reference table.
 *
 * <p>{@code code} is the label identifier as it appears in title codes (e.g., "ABP", "MIDE").
 * {@code labelName} is the human-readable label name (e.g., "Moody's Diva").
 * {@code company} is the production company that owns this label (e.g., "Moodyz").
 */
public record Label(String code, String labelName, String company) {}
