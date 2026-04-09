package com.organizer3.model;

import java.util.List;

/**
 * A named grouping of label companies under a common parent studio/distributor.
 * Loaded from {@code studios.yaml} and served to the UI for the Studio browser.
 */
public record StudioGroup(String name, String slug, List<String> companies) {}
