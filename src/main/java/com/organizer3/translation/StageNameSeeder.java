package com.organizer3.translation;

import com.organizer3.enrichment.ActressYaml;
import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.translation.repository.StageNameLookupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Seeds the {@code stage_name_lookup} table from actress YAMLs at application startup.
 *
 * <p>For each actress YAML, the following entries are written:
 * <ul>
 *   <li>The {@code stage_name} field (if it contains Japanese characters) → canonical romanized name.</li>
 *   <li>Any {@code alternate_names} entries that contain Japanese characters → canonical name.</li>
 * </ul>
 *
 * <p>The table is atomically replaced via {@link StageNameLookupRepository#clearAndSeed} so that
 * removals/corrections in the YAML files are reflected on the next startup without manual cleanup.
 *
 * <p>NFKC normalisation is applied to all kanji forms before insertion, matching the lookup path.
 */
@Slf4j
@RequiredArgsConstructor
public class StageNameSeeder {

    /** Matches any hiragana, katakana, or CJK unified ideograph character. */
    private static final Pattern JP_CHAR = Pattern.compile("[\\u3041-\\u3096\\u30A1-\\u30FA\\u4E00-\\u9FFF]");

    private final ActressYamlLoader yamlLoader;
    private final StageNameLookupRepository lookupRepo;

    /**
     * Seed (or re-seed) the stage name lookup table from all actress YAMLs.
     * Atomically replaces the table contents; idempotent on repeated calls.
     */
    public void seed() {
        List<String> slugs;
        try {
            slugs = yamlLoader.listSlugs();
        } catch (IOException e) {
            log.error("StageNameSeeder: failed to list actress slugs — stage-name table not seeded", e);
            return;
        }
        List<StageNameLookupRow> rows = new ArrayList<>();

        for (String slug : slugs) {
            try {
                ActressYaml yaml = yamlLoader.peek(slug);
                if (yaml == null || yaml.profile() == null || yaml.profile().name() == null) {
                    continue;
                }
                ActressYaml.Name name = yaml.profile().name();
                String canonicalName = name.toCanonicalName();
                if (canonicalName == null) {
                    continue;
                }

                // The main stage_name field
                String stageName = name.stageName();
                if (stageName != null && containsJapanese(stageName)) {
                    String normalized = TranslationNormalization.normalize(stageName);
                    rows.add(new StageNameLookupRow(0, normalized, canonicalName, slug, "yaml_seed", ""));
                }

                // Alternate names that contain Japanese characters
                if (name.alternateNames() != null) {
                    for (ActressYaml.AlternateName alt : name.alternateNames()) {
                        if (alt.name() != null && containsJapanese(alt.name())) {
                            String normalized = TranslationNormalization.normalize(alt.name());
                            rows.add(new StageNameLookupRow(0, normalized, canonicalName, slug, "yaml_seed", ""));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("StageNameSeeder: skipping slug '{}' due to parse error: {}", slug, e.getMessage());
            }
        }

        lookupRepo.clearAndSeed(rows);
        log.info("StageNameSeeder: seeded {} entries from {} actress YAMLs", rows.size(), slugs.size());
    }

    /** Returns true if the string contains at least one Japanese character. */
    static boolean containsJapanese(String text) {
        return text != null && JP_CHAR.matcher(text).find();
    }
}
