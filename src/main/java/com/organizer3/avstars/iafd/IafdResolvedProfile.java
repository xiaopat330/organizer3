package com.organizer3.avstars.iafd;

import lombok.Builder;
import lombok.Value;

/**
 * The IAFD-sourced fields extracted from a performer's profile page.
 *
 * <p>This is the output of {@link IafdProfileParser} — a flat bag of parsed values
 * that the resolve command applies to an {@code av_actresses} row.
 * Only fields where data was actually present on the page are non-null.
 */
@Value
@Builder
public class IafdResolvedProfile {

    // Identity
    String iafdId;
    String headshotUrl;       // raw URL from search result; headshot downloaded separately
    String akaNamesJson;      // JSON array [{name, source}] or "[]"

    // Personal
    String gender;
    String dateOfBirth;
    String dateOfDeath;
    String birthplace;
    String nationality;
    String ethnicity;

    // Physical
    String hairColor;
    String eyeColor;
    Integer heightCm;
    Integer weightKg;
    String measurements;
    String cup;
    String shoeSize;
    String tattoos;
    String piercings;

    // Career
    Integer activeFrom;
    Integer activeTo;
    Integer directorFrom;
    Integer directorTo;
    Integer iafdTitleCount;

    // External links
    String websiteUrl;
    String socialJson;        // {"twitter":"...", "instagram":"..."}
    String platformsJson;     // {"onlyfans":"..."}
    String externalRefsJson;  // {"egafd":"..."}

    // Editorial
    String iafdCommentsJson;  // JSON array of comment strings
    String awardsJson;        // {"AVN Awards":[{status,year,category,...}],...}
}
