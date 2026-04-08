package com.organizer3.sync.scanner;

import com.organizer3.model.Actress;

import java.nio.file.Path;
import java.util.List;

/**
 * A title discovered during a filesystem scan, before it is persisted to the database.
 *
 * @param path          full path to the title folder on the volume
 * @param partitionId   logical partition id for the DB (e.g., "queue", "stars", "stars/popular")
 * @param actressNames  all actress names associated with this title. Single-element for
 *                      conventional/exhibition/queue titles (the actress folder name or inferred name);
 *                      multi-element for collections titles (comma-parsed from folder name);
 *                      empty when the actress is unknown or "Various".
 * @param actressTier   tier to assign if creating a new actress record
 */
public record DiscoveredTitle(
        Path path,
        String partitionId,
        List<String> actressNames,
        Actress.Tier actressTier
) {}
