package com.organizer3.model;

import java.util.Set;

public record TitleSortSpec(String sortBy, String sortDir) {

    private static final Set<String> VALID_SORT_BY  = Set.of("release_date", "code", "grade");
    private static final Set<String> VALID_SORT_DIR = Set.of("asc", "desc");

    public static final TitleSortSpec DEFAULT = new TitleSortSpec("release_date", "desc");

    public static TitleSortSpec of(String sortBy, String sortDir) {
        String by  = sortBy  != null && VALID_SORT_BY.contains(sortBy)  ? sortBy  : "release_date";
        String dir = sortDir != null && VALID_SORT_DIR.contains(sortDir) ? sortDir : "desc";
        return new TitleSortSpec(by, dir);
    }
}
