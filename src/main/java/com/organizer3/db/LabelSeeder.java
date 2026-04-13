package com.organizer3.db;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the {@code labels} and {@code label_tags} tables from the bundled {@code labels.yaml}
 * classpath resource.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #seedIfEmpty()} — inserts all rows only when the table is empty. Called on startup.
 *   <li>{@link #reimport()} — clears all rows and re-inserts. Call when the YAML has been updated.
 * </ul>
 */
@Slf4j
public class LabelSeeder {

    private static final String RESOURCE = "/labels.yaml";

    private final Jdbi jdbi;
    private final TitleEffectiveTagsService titleEffectiveTagsService;

    public LabelSeeder(Jdbi jdbi, TitleEffectiveTagsService titleEffectiveTagsService) {
        this.jdbi = jdbi;
        this.titleEffectiveTagsService = titleEffectiveTagsService;
    }

    /**
     * Seeds the labels table if it is currently empty. Reimports if required columns are missing
     * or label_tags is unpopulated.
     */
    public void seedIfEmpty() {
        // Safety net: ensure company_description column exists (pre-v4 DBs)
        boolean hasCompanyDesc = columnExists("labels", "company_description");
        if (!hasCompanyDesc) {
            log.info("Labels table missing company_description column — adding and reimporting");
            jdbi.useHandle(h -> h.execute("ALTER TABLE labels ADD COLUMN company_description TEXT"));
            reimport();
            return;
        }

        // Safety net: ensure v5 profile columns exist (pre-v5 DBs where SchemaUpgrader may not
        // have run yet because this runs before the upgrader in some test setups)
        boolean hasSpecialty = columnExists("labels", "company_specialty");
        if (!hasSpecialty) {
            log.info("Labels table missing v5 profile columns — adding and reimporting");
            jdbi.useHandle(h -> {
                h.execute("ALTER TABLE labels ADD COLUMN company_specialty TEXT");
                h.execute("ALTER TABLE labels ADD COLUMN company_founded INTEGER");
                h.execute("ALTER TABLE labels ADD COLUMN company_status TEXT");
                h.execute("ALTER TABLE labels ADD COLUMN company_parent TEXT");
            });
            reimport();
            return;
        }

        long count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM labels")
                        .mapTo(Long.class)
                        .one()
        );
        if (count == 0) {
            log.info("Labels table is empty — seeding from {}", RESOURCE);
            jdbi.useTransaction(this::insertAll);
            return;
        }

        // If label_tags is empty while labels is populated, the tags data needs to be loaded
        long tagCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM label_tags")
                        .mapTo(Long.class)
                        .one()
        );
        if (tagCount == 0) {
            log.info("label_tags table is empty — reimporting to populate tags");
            reimport();
            return;
        }

        log.debug("Labels table already has {} rows with tags, skipping seed", count);
    }

    /**
     * Clears all existing label and label_tags data and re-imports from {@code labels.yaml}.
     * Use this when the YAML has been corrected or extended.
     */
    public void reimport() {
        log.info("Reimporting labels from {}", RESOURCE);
        jdbi.useTransaction(h -> {
            h.execute("DELETE FROM label_tags");
            h.execute("DELETE FROM labels");
            insertAll(h);
        });
        log.info("Labels reimport complete");
        titleEffectiveTagsService.recomputeAll();
    }

    private void insertAll(Handle h) {
        List<CompanyEntry> companies = loadYaml();
        int labelTotal = 0;
        int tagTotal = 0;
        for (CompanyEntry company : companies) {
            CompanyProfile profile = company.profile();
            String specialty = profile != null ? profile.specialty() : null;
            String founded   = profile != null ? profile.founded()  : null;
            String status    = profile != null ? profile.status()   : null;
            String parent    = profile != null ? profile.parent()   : null;

            for (LabelEntry label : company.labels()) {
                h.createUpdate("""
                                INSERT OR IGNORE INTO labels
                                    (code, label_name, company, description, company_description,
                                     company_specialty, company_founded, company_status, company_parent)
                                VALUES (:code, :labelName, :company, :description, :companyDescription,
                                        :companySpecialty, :companyFounded, :companyStatus, :companyParent)
                                """)
                        .bind("code",               label.code())
                        .bind("labelName",           label.label())
                        .bind("company",             company.company())
                        .bind("description",         label.description() != null ? label.description() : "")
                        .bind("companyDescription",  company.description() != null ? company.description() : "")
                        .bind("companySpecialty",    specialty)
                        .bind("companyFounded",      founded)
                        .bind("companyStatus",       status)
                        .bind("companyParent",       parent)
                        .execute();
                labelTotal++;

                for (String tag : label.tags()) {
                    h.createUpdate("""
                                    INSERT OR IGNORE INTO label_tags (label_code, tag)
                                    VALUES (:labelCode, :tag)
                                    """)
                            .bind("labelCode", label.code())
                            .bind("tag",       tag)
                            .execute();
                    tagTotal++;
                }
            }
        }
        log.info("Inserted {} label rows and {} label_tag rows", labelTotal, tagTotal);
    }

    private boolean columnExists(String table, String column) {
        return jdbi.withHandle(h ->
                h.createQuery("PRAGMA table_info(" + table + ")")
                        .mapToMap()
                        .list()
                        .stream()
                        .anyMatch(row -> column.equals(row.get("name")))
        );
    }

    private List<CompanyEntry> loadYaml() {
        try (InputStream is = LabelSeeder.class.getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Cannot find classpath resource: " + RESOURCE);
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            return mapper.readerForListOf(CompanyEntry.class).readValue(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + RESOURCE, e);
        }
    }

    // --- YAML model ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompanyEntry(
            @JsonProperty("company")     String company,
            @JsonProperty("description") String description,
            @JsonProperty("profile")     CompanyProfile profile,
            @JsonProperty("labels")      List<LabelEntry> labels
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompanyProfile(
            @JsonProperty("specialty") String specialty,
            @JsonProperty("founded")   String founded,
            @JsonProperty("status")    String status,
            @JsonProperty("parent")    String parent
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelEntry(
            @JsonProperty("code")        String code,
            @JsonProperty("label")       String label,
            @JsonProperty("description") String description,
            @JsonProperty("tags")        List<String> tags
    ) {
        public List<String> tags() {
            return tags != null ? tags : List.of();
        }
    }
}
