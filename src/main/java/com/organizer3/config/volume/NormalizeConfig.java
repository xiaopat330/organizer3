package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Filename normalization rules — the substrings and patterns that get stripped from
 * or rewritten in title / video / cover filenames during the normalize phase
 * (see {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.1). Bound from the
 * {@code normalize:} block in {@code organizer-config.yaml}.
 *
 * <p>Two lists:
 * <ul>
 *   <li>{@link #removelist()} — substrings to delete (e.g. {@code "hhd800.com@"},
 *       {@code "-1080P"}, watermark tokens).</li>
 *   <li>{@link #replacelist()} — one-for-one rewrites (e.g. {@code FC2-PPV → FC2PPV},
 *       {@code -Uncen → _U}).</li>
 * </ul>
 *
 * <p>Both lists are optional; null/absent is equivalent to an empty list and means
 * "don't normalize for this rule."
 *
 * <p>Initial content is carried forward verbatim from {@code legacy/operation-config.yaml}.
 */
public record NormalizeConfig(
        @JsonProperty("removelist")  List<String>  removelist,
        @JsonProperty("replacelist") List<Replace> replacelist
) {

    public static final NormalizeConfig EMPTY = new NormalizeConfig(List.of(), List.of());

    public List<String>  effectiveRemovelist()  { return removelist  != null ? removelist  : List.of(); }
    public List<Replace> effectiveReplacelist() { return replacelist != null ? replacelist : List.of(); }

    /** One replace rule — a literal {@code from} → {@code to} substitution. */
    public record Replace(
            @JsonProperty("from") String from,
            @JsonProperty("to")   String to
    ) {}
}
