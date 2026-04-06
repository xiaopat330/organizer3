package com.organizer3.model;

/**
 * Maps an alternate stage name to a canonical {@link com.organizer3.model.Actress}.
 * Maps directly to the {@code actress_aliases} DB table (composite PK: actress_id + alias_name).
 *
 * <p>Example: actressId=42 (Aya Sazanami) with aliasName="Haruka Suzumiya" means
 * any folder or file attributed to "Haruka Suzumiya" belongs to actress 42.
 */
public record ActressAlias(long actressId, String aliasName) {}
