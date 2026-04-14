package com.organizer3.avstars.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Jackson binding for one entry in {@code data/av_tags.yaml}.
 *
 * <p>Example YAML:
 * <pre>
 * - slug: big-tits
 *   displayName: Big Tits
 *   category: body
 *   aliases: [bigtits, big_tits, bignaturals]
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AvTagYamlEntry {
    public String slug;
    public String displayName;
    public String category;
    public List<String> aliases;
}
