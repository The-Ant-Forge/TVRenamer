package org.tvrenamer.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.PropertyChangeListener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tvrenamer.model.UserPreferences;

/**
 * Tests covering XML round-trip persistence for the subtitle-merge preference
 * fields added in Phase 4 of the Subtitle Merge feature:
 *
 *   - mergeSubtitles (boolean, default false)
 *   - defaultSubtitleLanguage (String, default "eng")
 *   - deleteSubtitlesAfterMerge (boolean, default false)
 *
 * Existing UserPreferences behaviour is exercised indirectly by the round-trip
 * tests; these tests deliberately do not modify singleton state.
 */
public class UserPreferencesPersistenceTest {

    /**
     * Helper: serialize an in-memory UserPreferences to a temp file and
     * read it back, returning the freshly-loaded instance.
     */
    private static UserPreferences roundTrip(UserPreferences prefs, Path tmpDir) {
        Path file = tmpDir.resolve("prefs.xml");
        UserPreferencesPersistence.persist(prefs, file);
        return UserPreferencesPersistence.retrieve(file);
    }

    /**
     * Helper: write a minimal preferences XML (only the fields we care to
     * test) and read it back via the persistence reader.
     */
    private static UserPreferences readFromXml(Path tmpDir, String body) throws Exception {
        Path file = tmpDir.resolve("prefs.xml");
        String xml = "<preferences>\n" + body + "</preferences>";
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return UserPreferencesPersistence.retrieve(file);
    }

    @Test
    @DisplayName("Round-trip: all three new fields with non-default values")
    void roundTripNonDefaults(@TempDir Path tmpDir) {
        UserPreferences prefs = UserPreferences.fromParsedXml(null, null, null, null);
        prefs.setMergeSubtitles(true);
        prefs.setDefaultSubtitleLanguage("fre");
        prefs.setDeleteSubtitlesAfterMerge(true);

        UserPreferences loaded = roundTrip(prefs, tmpDir);

        assertTrue(loaded.isMergeSubtitles(), "mergeSubtitles should round-trip true");
        assertEquals("fre", loaded.getDefaultSubtitleLanguage(),
                "defaultSubtitleLanguage should round-trip");
        assertTrue(loaded.isDeleteSubtitlesAfterMerge(),
                "deleteSubtitlesAfterMerge should round-trip true");
    }

    @Test
    @DisplayName("Defaults applied when all three fields are absent from XML")
    void defaultsWhenFieldsAbsent(@TempDir Path tmpDir) throws Exception {
        UserPreferences loaded = readFromXml(tmpDir, "");

        assertFalse(loaded.isMergeSubtitles(),
                "mergeSubtitles default is false");
        assertEquals("eng", loaded.getDefaultSubtitleLanguage(),
                "defaultSubtitleLanguage default is \"eng\"");
        assertFalse(loaded.isDeleteSubtitlesAfterMerge(),
                "deleteSubtitlesAfterMerge default is false");
    }

    @Test
    @DisplayName("Forward-compat: unknown long language code falls back to eng")
    void forwardCompatInvalidLanguageLength(@TempDir Path tmpDir) throws Exception {
        UserPreferences loaded = readFromXml(
            tmpDir,
            "  <defaultSubtitleLanguage>klingon</defaultSubtitleLanguage>\n"
        );

        assertEquals("eng", loaded.getDefaultSubtitleLanguage());
    }

    @Test
    @DisplayName("Forward-compat: numeric/garbage language codes fall back to eng")
    void forwardCompatGarbageLanguage(@TempDir Path tmpDir) throws Exception {
        UserPreferences withDigit = readFromXml(
            tmpDir,
            "  <defaultSubtitleLanguage>e1g</defaultSubtitleLanguage>\n"
        );
        assertEquals("eng", withDigit.getDefaultSubtitleLanguage(),
                "Digit in code should fail format check and fall back to eng");

        UserPreferences emptyLang = readFromXml(
            tmpDir,
            "  <defaultSubtitleLanguage></defaultSubtitleLanguage>\n"
        );
        assertEquals("eng", emptyLang.getDefaultSubtitleLanguage(),
                "Empty value should fall back to eng");

        UserPreferences whitespaceLang = readFromXml(
            tmpDir,
            "  <defaultSubtitleLanguage>   </defaultSubtitleLanguage>\n"
        );
        assertEquals("eng", whitespaceLang.getDefaultSubtitleLanguage(),
                "Whitespace-only value should fall back to eng");

        UserPreferences twoLetter = readFromXml(
            tmpDir,
            "  <defaultSubtitleLanguage>en</defaultSubtitleLanguage>\n"
        );
        assertEquals("eng", twoLetter.getDefaultSubtitleLanguage(),
                "Two-letter codes are not in B-form, fall back to eng");
    }

    @Test
    @DisplayName("Boolean parsing: true/false/garbage tolerance")
    void booleanParsingTolerance(@TempDir Path tmpDir) throws Exception {
        UserPreferences trueCase = readFromXml(
            tmpDir,
            "  <mergeSubtitles>true</mergeSubtitles>\n"
                + "  <deleteSubtitlesAfterMerge>true</deleteSubtitlesAfterMerge>\n"
        );
        assertTrue(trueCase.isMergeSubtitles());
        assertTrue(trueCase.isDeleteSubtitlesAfterMerge());

        UserPreferences falseCase = readFromXml(
            tmpDir,
            "  <mergeSubtitles>false</mergeSubtitles>\n"
                + "  <deleteSubtitlesAfterMerge>false</deleteSubtitlesAfterMerge>\n"
        );
        assertFalse(falseCase.isMergeSubtitles());
        assertFalse(falseCase.isDeleteSubtitlesAfterMerge());

        UserPreferences garbageCase = readFromXml(
            tmpDir,
            "  <mergeSubtitles>garbage</mergeSubtitles>\n"
                + "  <deleteSubtitlesAfterMerge>not-a-bool</deleteSubtitlesAfterMerge>\n"
        );
        assertFalse(garbageCase.isMergeSubtitles(),
                "Boolean.parseBoolean returns false for non-\"true\"");
        assertFalse(garbageCase.isDeleteSubtitlesAfterMerge(),
                "Boolean.parseBoolean returns false for non-\"true\"");
    }

    @Test
    @DisplayName("PropertyChange firing: only on actual value transitions")
    void propertyChangeOnlyOnTransition() {
        UserPreferences prefs = UserPreferences.fromParsedXml(null, null, null, null);

        // Start from a known state.
        prefs.setMergeSubtitles(true);

        AtomicInteger events = new AtomicInteger(0);
        PropertyChangeListener listener = evt -> events.incrementAndGet();
        prefs.addPropertyChangeListener(listener);

        try {
            // Setting to the same value -> no event.
            prefs.setMergeSubtitles(true);
            assertEquals(0, events.get(),
                    "No event when setter is called with the current value");

            // Transitioning -> one event.
            prefs.setMergeSubtitles(false);
            assertEquals(1, events.get(),
                    "Exactly one event on a true -> false transition");

            // Same again -> no event.
            prefs.setMergeSubtitles(false);
            assertEquals(1, events.get(),
                    "Still exactly one event after redundant set");
        } finally {
            prefs.removePropertyChangeListener(listener);
        }
    }

    @Test
    @DisplayName("Round-trip: defaults survive an empty round-trip")
    void roundTripDefaults(@TempDir Path tmpDir) {
        UserPreferences prefs = UserPreferences.fromParsedXml(null, null, null, null);

        UserPreferences loaded = roundTrip(prefs, tmpDir);

        assertFalse(loaded.isMergeSubtitles());
        assertEquals("eng", loaded.getDefaultSubtitleLanguage());
        assertFalse(loaded.isDeleteSubtitlesAfterMerge());
    }
}
