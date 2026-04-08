package com.organizer3.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Jackson-mapped model for an actress YAML file under {@code resources/actresses/}.
 *
 * <p>Field names mirror the YAML keys exactly. Unknown keys are ignored so that
 * fields added to the YAML (e.g. {@code meta}) do not cause parse failures.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActressYaml(
        @JsonProperty("profile") Profile profile,
        @JsonProperty("portfolio") List<PortfolioEntry> portfolio
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(
            @JsonProperty("name") Name name,
            @JsonProperty("date_of_birth") String dateOfBirth,
            @JsonProperty("birthplace") String birthplace,
            @JsonProperty("blood_type") String bloodType,
            @JsonProperty("height_cm") Integer heightCm,
            @JsonProperty("measurements") Measurements measurements,
            @JsonProperty("cup") String cup,
            @JsonProperty("active_from") String activeFrom,
            @JsonProperty("active_to") String activeTo,
            @JsonProperty("biography") String biography,
            @JsonProperty("legacy") String legacy
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Name(
            @JsonProperty("family_name") String familyName,
            @JsonProperty("given_name") String givenName,
            @JsonProperty("stage_name") String stageName,
            @JsonProperty("alternate_names") List<AlternateName> alternateNames
    ) {
        /** Returns the canonical romanized name: "Given Family" order. */
        public String toCanonicalName() {
            if (givenName == null && familyName == null) return null;
            if (givenName == null) return familyName;
            if (familyName == null) return givenName;
            return givenName + " " + familyName;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AlternateName(
            @JsonProperty("name") String name,
            @JsonProperty("note") String note
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Measurements(
            @JsonProperty("bust") Integer bust,
            @JsonProperty("waist") Integer waist,
            @JsonProperty("hip") Integer hip
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PortfolioEntry(
            @JsonProperty("code") String code,
            @JsonProperty("title") TitleNames title,
            @JsonProperty("label") String label,
            @JsonProperty("date") String date,
            @JsonProperty("notes") String notes,
            @JsonProperty("grade") String grade,
            @JsonProperty("tags") List<String> tags
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TitleNames(
            @JsonProperty("original") String original,
            @JsonProperty("english") String english
    ) {}
}
