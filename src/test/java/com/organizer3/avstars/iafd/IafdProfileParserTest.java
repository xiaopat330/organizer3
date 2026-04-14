package com.organizer3.avstars.iafd;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IafdProfileParserTest {

    private static final String IAFD_ID = "53696199-bf71-4219-b58a-bd1e2fae9f1e";

    private final IafdProfileParser parser = new IafdProfileParser();

    private String loadFixture(String name) throws IOException, URISyntaxException {
        Path path = Path.of(getClass().getClassLoader().getResource("iafd/" + name).toURI());
        return Files.readString(path);
    }

    private IafdResolvedProfile parsed() throws Exception {
        return parser.parse(IAFD_ID, loadFixture("profile_anissa_kate.html"));
    }

    // ── identity ───────────────────────────────────────────────────────────────

    @Test
    void iafdIdPassedThrough() throws Exception {
        assertEquals(IAFD_ID, parsed().getIafdId());
    }

    @Test
    void titleCountExtracted() throws Exception {
        assertEquals(352, parsed().getIafdTitleCount());
    }

    @Test
    void headshotUrlExtractedFromProfilePage() throws Exception {
        String url = parsed().getHeadshotUrl();
        assertNotNull(url);
        assertTrue(url.contains("anissakate_f_anissa_kate_otb.jpg"));
    }

    // ── personal ───────────────────────────────────────────────────────────────

    @Test
    void dateOfBirthExtracted() throws Exception {
        assertEquals("January 3, 1990", parsed().getDateOfBirth());
    }

    @Test
    void birthplaceExtracted() throws Exception {
        assertEquals("Carcassonne, France", parsed().getBirthplace());
    }

    @Test
    void genderExtracted() throws Exception {
        assertEquals("Female", parsed().getGender());
    }

    @Test
    void ethnicityExtracted() throws Exception {
        assertEquals("Caucasian", parsed().getEthnicity());
    }

    @Test
    void nationalityExtracted() throws Exception {
        assertEquals("French", parsed().getNationality());
    }

    // ── physical ───────────────────────────────────────────────────────────────

    @Test
    void hairColorExtractedFromHairColorsHeading() throws Exception {
        assertEquals("Black/Brown/Auburn", parsed().getHairColor());
    }

    @Test
    void eyeColorExtracted() throws Exception {
        assertEquals("Brown", parsed().getEyeColor());
    }

    @Test
    void heightCmExtracted() throws Exception {
        assertEquals(170, parsed().getHeightCm());
    }

    @Test
    void weightKgExtracted() throws Exception {
        assertEquals(54, parsed().getWeightKg());
    }

    @Test
    void measurementsExtracted() throws Exception {
        assertEquals("34B-25-35", parsed().getMeasurements());
    }

    @Test
    void cupExtractedFromMeasurements() throws Exception {
        assertEquals("B", parsed().getCup());
    }

    @Test
    void tattoosLiteralNoneStored() throws Exception {
        // "None" is literal page text; not null
        assertEquals("None", parsed().getTattoos());
    }

    @Test
    void piercingsExtracted() throws Exception {
        assertEquals("Ears", parsed().getPiercings());
    }

    // ── career ─────────────────────────────────────────────────────────────────

    @Test
    void activeFromExtracted() throws Exception {
        assertEquals(2010, parsed().getActiveFrom());
    }

    @Test
    void activeToExtracted() throws Exception {
        assertEquals(2024, parsed().getActiveTo());
    }

    // ── links ──────────────────────────────────────────────────────────────────

    @Test
    void websiteUrlExtracted() throws Exception {
        assertEquals("https://anissakate.com", parsed().getWebsiteUrl());
    }

    @Test
    void socialJsonContainsTwitter() throws Exception {
        String social = parsed().getSocialJson();
        assertNotNull(social);
        assertTrue(social.contains("\"twitter\""));
        assertTrue(social.contains("x.com/anissakate"));
    }

    @Test
    void socialJsonContainsInstagram() throws Exception {
        String social = parsed().getSocialJson();
        assertNotNull(social);
        assertTrue(social.contains("\"instagram\""));
    }

    @Test
    void platformsJsonContainsOnlyFans() throws Exception {
        String platforms = parsed().getPlatformsJson();
        assertNotNull(platforms);
        assertTrue(platforms.contains("\"onlyfans\""));
        assertTrue(platforms.contains("onlyfans.com/anissakate"));
    }

    @Test
    void externalRefsJsonContainsEgafd() throws Exception {
        String refs = parsed().getExternalRefsJson();
        assertNotNull(refs);
        assertTrue(refs.contains("egafd"));
        assertTrue(refs.contains("egafd.com"));
    }

    // ── AKA ────────────────────────────────────────────────────────────────────

    @Test
    void akaNamesJsonContainsNames() throws Exception {
        String akas = parsed().getAkaNamesJson();
        assertNotNull(akas);
        assertTrue(akas.contains("Alissa Kate"));
        assertTrue(akas.contains("Anissa Gate"));
        assertTrue(akas.contains("Annissa Kate"));
    }

    @Test
    void akaNamesJsonIsArray() throws Exception {
        String akas = parsed().getAkaNamesJson();
        assertTrue(akas.startsWith("["));
        assertTrue(akas.endsWith("]"));
    }

    // ── awards ─────────────────────────────────────────────────────────────────

    @Test
    void awardsJsonPresent() throws Exception {
        String awards = parsed().getAwardsJson();
        assertNotNull(awards);
        assertTrue(awards.contains("AVN Awards"));
    }

    @Test
    void awardsContainsWinner() throws Exception {
        assertTrue(parsed().getAwardsJson().contains("winner"));
    }

    @Test
    void awardsContainsNominee() throws Exception {
        assertTrue(parsed().getAwardsJson().contains("nominee"));
    }

    @Test
    void awardsContainYear() throws Exception {
        assertTrue(parsed().getAwardsJson().contains("2019"));
    }

    // ── comments ───────────────────────────────────────────────────────────────

    @Test
    void commentsJsonPresent() throws Exception {
        String comments = parsed().getIafdCommentsJson();
        assertNotNull(comments);
        assertTrue(comments.contains("directed"));
    }

    // ── edge cases ─────────────────────────────────────────────────────────────

    @Test
    void nullHtmlReturnsEmptyProfile() {
        IafdResolvedProfile p = parser.parse(IAFD_ID, null);
        assertEquals(IAFD_ID, p.getIafdId());
        assertNull(p.getGender());
        assertNull(p.getDateOfBirth());
    }

    @Test
    void blankHtmlReturnsEmptyProfile() {
        IafdResolvedProfile p = parser.parse(IAFD_ID, "  ");
        assertNull(p.getGender());
    }
}
