package com.organizer3.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

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
@Getter
@AllArgsConstructor
@ToString(exclude = "parent")
public final class Partition implements Comparable<Partition> {

    private final String id;
    private final Path path;
    private final String strategy;
    private final Volume parent;

    @Override
    public int compareTo(Partition o) {
        return path.toString().compareTo(o.getPath().toString());
    }
}
