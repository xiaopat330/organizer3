package com.organizer3.avstars.model;

import lombok.Builder;
import lombok.Value;

/**
 * A tag definition in the AV Stars tag taxonomy. Slugs are stable identifiers
 * (e.g. {@code "big-tits"}); display names are human-readable. Aliases map raw
 * tokens found in filenames to this canonical slug during {@code av tags apply}.
 */
@Value
@Builder
public class AvTagDefinition {
    String slug;
    String displayName;
    String category;
    /** JSON array of alias strings, e.g. {@code ["bigtits","big_tits"]}. Null if none. */
    String aliasesJson;
}
