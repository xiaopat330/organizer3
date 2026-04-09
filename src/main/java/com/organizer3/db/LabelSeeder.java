package com.organizer3.db;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

/**
 * Seeds the {@code labels} table from the bundled {@code labels.yaml} classpath resource.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #seedIfEmpty()} — inserts all rows only when the table is empty. Called on startup.
 *   <li>{@link #reimport()} — clears all rows and re-inserts. Call when the YAML has been updated.
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class LabelSeeder {

    private static final String RESOURCE = "/labels.yaml";

    private final Jdbi jdbi;

    /** Seeds the labels table if it is currently empty. Reimports if company_description is unpopulated. */
    public void seedIfEmpty() {
        // Ensure company_description column exists (may be missing on DBs stamped before this field was added)
        boolean hasCompanyDesc = jdbi.withHandle(h ->
                h.createQuery("PRAGMA table_info(labels)")
                        .mapToMap()
                        .list()
                        .stream()
                        .anyMatch(row -> "company_description".equals(row.get("name")))
        );
        if (!hasCompanyDesc) {
            log.info("Labels table missing company_description column — adding and reimporting");
            jdbi.useHandle(h -> h.execute("ALTER TABLE labels ADD COLUMN company_description TEXT"));
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
        } else {
            long missing = jdbi.withHandle(h ->
                    h.createQuery("SELECT COUNT(*) FROM labels WHERE company_description IS NULL OR company_description = ''")
                            .mapTo(Long.class)
                            .one()
            );
            if (missing > 0) {
                log.info("Labels missing company_description ({} rows) — reimporting", missing);
                reimport();
            } else {
                log.debug("Labels table already has {} rows, skipping seed", count);
            }
        }
    }

    /**
     * Clears all existing label data and re-imports from {@code labels.yaml}.
     * Use this when the YAML has been corrected or extended.
     */
    public void reimport() {
        log.info("Reimporting labels from {}", RESOURCE);
        jdbi.useTransaction(h -> {
            h.execute("DELETE FROM labels");
            insertAll(h);
        });
        log.info("Labels reimport complete");
    }

    private void insertAll(Handle h) {
        List<CompanyEntry> companies = loadYaml();
        int total = 0;
        for (CompanyEntry company : companies) {
            for (LabelEntry label : company.labels()) {
                h.createUpdate("""
                                INSERT OR IGNORE INTO labels (code, label_name, company, description, company_description)
                                VALUES (:code, :labelName, :company, :description, :companyDescription)
                                """)
                        .bind("code", label.code())
                        .bind("labelName", label.label())
                        .bind("company", company.company())
                        .bind("description", label.description() != null ? label.description() : "")
                        .bind("companyDescription", company.description() != null ? company.description() : "")
                        .execute();
                total++;
            }
        }
        log.info("Inserted {} label rows", total);
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

    public record CompanyEntry(
            @JsonProperty("company") String company,
            @JsonProperty("description") String description,
            @JsonProperty("labels") List<LabelEntry> labels
    ) {}

    public record LabelEntry(
            @JsonProperty("code") String code,
            @JsonProperty("label") String label,
            @JsonProperty("description") String description
    ) {}
}
