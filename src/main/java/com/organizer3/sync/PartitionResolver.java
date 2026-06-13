package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeStructureDef;

import java.nio.file.Path;

/**
 * Config-aware partition ID resolver for MCP tools.
 *
 * <p>Returns the {@code partition_id} that {@link com.organizer3.sync.scanner.ConventionalScanner}
 * or {@link com.organizer3.sync.scanner.ExhibitionScanner} would assign to a title folder at
 * {@code volumeRelativePath} on a volume with the given {@link VolumeStructureDef}.
 *
 * <p>Resolution order (mirrors the scanner):
 * <ol>
 *   <li>Unstructured partitions — if the first path segment equals {@code p.path()}, return
 *       {@code p.id()}.</li>
 *   <li>Structured partition (stars) — if non-null, the first path segment matches
 *       {@code stars.path()}, and the second segment matches a tier's {@code path()}, return
 *       {@code "stars/" + sub.id()}.  If no tier matches (flat layout / empty tiers list),
 *       return the structured-partition path itself (e.g. {@code "stars"}).</li>
 *   <li>Fallback — return the first path segment as a last resort (preserves prior behaviour
 *       for unknown / unmapped layouts).</li>
 *   <li>Empty path — return {@code "unknown"}.</li>
 * </ol>
 *
 * <p><b>Flat vs tiered stars:</b>
 * <ul>
 *   <li>Conventional volumes use tiered stars: {@code /stars/popular/Actress/Title} →
 *       {@code "stars/popular"}.</li>
 *   <li>Exhibition volumes (e.g. qnap) use flat stars: actress folders sit directly under
 *       {@code /stars/}. The structured partition has an empty tiers list, so no tier sub-match
 *       is possible and the method returns {@code "stars"} for any {@code /stars/...} path.</li>
 * </ul>
 */
public final class PartitionResolver {

    private PartitionResolver() {}

    /**
     * Resolves the partition ID for a given volume-relative path.
     *
     * @param structure          the volume's structure definition; may be {@code null} (→ fallback)
     * @param volumeRelativePath the path of the title folder, volume-relative; leading {@code /}
     *                           is stripped automatically (Java's {@link Path} normalises it away)
     * @return the logical partition ID that the scanner would assign
     */
    public static String resolvePartitionId(VolumeStructureDef structure, Path volumeRelativePath) {
        if (volumeRelativePath == null || volumeRelativePath.getNameCount() == 0) {
            return "unknown";
        }

        String firstSegment = volumeRelativePath.getName(0).toString();

        if (structure != null) {
            // 1. Unstructured partitions — check first segment against each partition's path
            if (structure.unstructuredPartitions() != null) {
                for (PartitionDef p : structure.unstructuredPartitions()) {
                    if (firstSegment.equals(p.path())) {
                        return p.id();
                    }
                }
            }

            // 2. Structured partition (stars tree)
            StructuredPartitionDef stars = structure.structuredPartition();
            if (stars != null && firstSegment.equals(stars.path())) {
                // Try to match a tier sub-partition from the second segment
                if (volumeRelativePath.getNameCount() >= 2) {
                    String secondSegment = volumeRelativePath.getName(1).toString();
                    if (stars.partitions() != null) {
                        for (PartitionDef sub : stars.partitions()) {
                            if (secondSegment.equals(sub.path())) {
                                return "stars/" + sub.id();
                            }
                        }
                    }
                }
                // No tier matched (flat stars, empty tiers, or 1-segment path under stars)
                return stars.path(); // e.g. "stars"
            }
        }

        // 3. Fallback: return first segment
        return firstSegment;
    }
}
