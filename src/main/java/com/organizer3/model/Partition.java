package com.organizer3.model;

import lombok.Getter;

import java.nio.file.Path;

/**
 * A Partition is a logical folder grouping within a {@link Volume}. It corresponds to one of the
 * named directories directly under the volume root.
 *
 * <p>Partitions are either:
 * <ul>
 *   <li><b>Structured</b> — under {@code stars/}, organized by actress. Each subfolder is an actress
 *       folder containing her titles. The tiers ({@code library/}, {@code popular/}, {@code superstar/},
 *       etc.) are sub-partitions of {@code stars/}.</li>
 *   <li><b>Unstructured</b> — top-level folders like {@code queue/}, {@code archive/}, {@code recent/}
 *       that hold titles directly without an actress grouping level.</li>
 * </ul>
 *
 * <p>Finding content within a partition (titles, videos, orphans) is the responsibility of the
 * repository layer, not this class.
 */
public final class Partition implements Comparable<Partition> {

    @Getter
    private final String id;

    @Getter
    private final Path path;

    @Getter
    private final String strategy;

    @Getter
    private final Volume parent;

    public Partition(String id, Path path, String strategy, Volume parent) {
        this.id = id;
        this.path = path;
        this.strategy = strategy;
        this.parent = parent;
    }

    @Override
    public int compareTo(Partition o) {
        return path.toString().compareTo(o.getPath().toString());
    }

    @Override
    public String toString() {
        return "Partition{id='" + id + "', path=" + path + '}';
    }
}
