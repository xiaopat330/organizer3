package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Library classification thresholds — the title-count boundaries that decide which
 * tier folder an actress lives under. Bound from the {@code library:} block in
 * {@code organizer-config.yaml}.
 *
 * <p>Tiers (in increasing order): {@code pool → library → minor → popular → superstar → goddess}.
 * Given a threshold set {@code (s, m, p, ss, g)}, an actress with {@code n} titles is:
 * <ul>
 *   <li>{@code n &lt; s}  → {@code pool}     (no tier folder; titles stay in volume queue)</li>
 *   <li>{@code n &lt; m}  → {@code library}  ({@code [s, m)})</li>
 *   <li>{@code n &lt; p}  → {@code minor}    ({@code [m, p)})</li>
 *   <li>{@code n &lt; ss} → {@code popular}  ({@code [p, ss)})</li>
 *   <li>{@code n &lt; g}  → {@code superstar} ({@code [ss, g)})</li>
 *   <li>{@code n &gt;= g} → {@code goddess}  ({@code &gt;= g})</li>
 * </ul>
 *
 * <p>Defaults: {@code s=3, m=5, p=20, ss=50, g=100} — matches the legacy Organizer v2
 * configuration. Thresholds are only used in the upward direction; see
 * {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §6.1.
 *
 * <p>Example:
 * <pre>
 * library:
 *   star:      3
 *   minor:     5
 *   popular:   20
 *   superstar: 50
 *   goddess:   100
 * </pre>
 */
public record LibraryConfig(
        @JsonProperty("star")      Integer star,
        @JsonProperty("minor")     Integer minor,
        @JsonProperty("popular")   Integer popular,
        @JsonProperty("superstar") Integer superstar,
        @JsonProperty("goddess")   Integer goddess
) {

    public static final LibraryConfig DEFAULTS = new LibraryConfig(3, 5, 20, 50, 100);

    public int effectiveStar()      { return star      != null ? star      : DEFAULTS.star; }
    public int effectiveMinor()     { return minor     != null ? minor     : DEFAULTS.minor; }
    public int effectivePopular()   { return popular   != null ? popular   : DEFAULTS.popular; }
    public int effectiveSuperstar() { return superstar != null ? superstar : DEFAULTS.superstar; }
    public int effectiveGoddess()   { return goddess   != null ? goddess   : DEFAULTS.goddess; }

    /** Tier name for an actress with {@code titleCount} titles. Returns {@code "pool"} when below star threshold. */
    public String tierFor(int titleCount) {
        int s  = effectiveStar();
        int m  = effectiveMinor();
        int p  = effectivePopular();
        int ss = effectiveSuperstar();
        int g  = effectiveGoddess();
        if (titleCount < s)  return "pool";
        if (titleCount < m)  return "library";
        if (titleCount < p)  return "minor";
        if (titleCount < ss) return "popular";
        if (titleCount < g)  return "superstar";
        return "goddess";
    }

    /** Convenience wrapper returning effective values for all thresholds. */
    public LibraryConfig withDefaultsApplied() {
        return new LibraryConfig(effectiveStar(), effectiveMinor(), effectivePopular(),
                effectiveSuperstar(), effectiveGoddess());
    }
}
