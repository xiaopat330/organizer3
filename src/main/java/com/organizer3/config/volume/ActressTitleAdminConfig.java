package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Config for the per-actress Admin tab (title management surface).
 *
 * <p>See {@code spec/PROPOSAL_ACTRESS_TITLE_ADMIN.md}.
 */
public record ActressTitleAdminConfig(
        @JsonProperty("pageSize") Integer pageSize
) {

    public static final ActressTitleAdminConfig DEFAULTS = new ActressTitleAdminConfig(5);

    /** Page size for the Admin tab title list. Defaults to 5. */
    public int pageSizeOrDefault() {
        return pageSize != null ? pageSize : 5;
    }
}
