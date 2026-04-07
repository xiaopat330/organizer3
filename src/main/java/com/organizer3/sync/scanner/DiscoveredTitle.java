package com.organizer3.sync.scanner;

import com.organizer3.model.Actress;

import java.nio.file.Path;

/**
 * A title discovered during a filesystem scan, before it is persisted to the database.
 *
 * @param path         full path to the title folder on the volume
 * @param partitionId  logical partition id for the DB (e.g., "queue", "stars", "stars/popular")
 * @param actressName  actress name if known (null for unstructured partitions with no inferrable name)
 * @param actressTier  tier to assign if creating a new actress record (null when actress is unknown)
 */
public record DiscoveredTitle(
        Path path,
        String partitionId,
        String actressName,
        Actress.Tier actressTier
) {}
