package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.tvrenamer.controller.util.StringUtils;

/**
 * Tests for {@link FileEpisode#rematchWouldChangeResult(UserPreferences)}, the
 * pure predicate that decides which table rows to re-match when the Matching
 * preferences (show-name overrides / disambiguation overrides) change.
 *
 * A row qualifies for re-match if it is currently unmatched, if a name override
 * now resolves its extracted name differently, or if a disambiguation override
 * now pins a different provider series than the one currently matched.
 */
public class FileEpisodeRematchTest {

    private final UserPreferences prefs = UserPreferences.getInstance();

    // Unique series ids so tests don't collide in the Series.KNOWN_SERIES cache.
    private static final AtomicInteger SERIES_ID = new AtomicInteger(770001);

    private static int uniqueSeriesId() {
        return SERIES_ID.getAndIncrement();
    }

    @AfterEach
    public void clearOverrides() {
        // Prevent cross-test pollution of the shared preferences singleton.
        prefs.setShowNameOverrides(new HashMap<>());
        prefs.setShowDisambiguationOverrides(new HashMap<>());
    }

    private static FileEpisode parsedEpisode(String extractedShow) {
        FileEpisode ep = new FileEpisode(extractedShow + ".S01E01.mp4");
        ep.setParsed();
        ep.setExtractedFilenameShow(extractedShow);
        ep.setFilenameShow(extractedShow);
        return ep;
    }

    @Test
    @DisplayName("Unmatched (unfound) row is always selected for re-match")
    public void unfoundRowIsSelected() {
        FileEpisode ep = parsedEpisode("westmark academy");
        ep.setEpisodeShow(null); // UNFOUND

        assertTrue(ep.rematchWouldChangeResult(prefs));
    }

    @Test
    @DisplayName("Matched row with no relevant override change is NOT selected")
    public void matchedRowUnaffectedNotSelected() {
        FileEpisode ep = parsedEpisode("westmark academy");
        ep.setEpisodeShow(Series.createSeries(uniqueSeriesId(), "Westmark Academy"));

        // An override for an unrelated show must not select this row.
        Map<String, String> nameOverrides = new HashMap<>();
        nameOverrides.put("solar drift", "the solar drift");
        prefs.setShowNameOverrides(nameOverrides);

        assertFalse(ep.rematchWouldChangeResult(prefs));
    }

    @Test
    @DisplayName("Name override that redirects this show is selected")
    public void overrideRedirectIsSelected() {
        FileEpisode ep = parsedEpisode("westmark");
        ep.setEpisodeShow(Series.createSeries(uniqueSeriesId(), "Westmark"));

        Map<String, String> nameOverrides = new HashMap<>();
        nameOverrides.put("westmark", "westmark academy");
        prefs.setShowNameOverrides(nameOverrides);

        assertTrue(ep.rematchWouldChangeResult(prefs));
    }

    @Test
    @DisplayName("Disambiguation that re-pins to a different series is selected")
    public void disambiguationRepinIsSelected() {
        int currentId = uniqueSeriesId();
        FileEpisode ep = parsedEpisode("the quiet ones");
        ep.setEpisodeShow(Series.createSeries(currentId, "The Quiet Ones"));

        Map<String, String> disambig = new HashMap<>();
        disambig.put(StringUtils.makeQueryString("the quiet ones"),
                     String.valueOf(uniqueSeriesId())); // a DIFFERENT id
        prefs.setShowDisambiguationOverrides(disambig);

        assertTrue(ep.rematchWouldChangeResult(prefs));
    }

    @Test
    @DisplayName("Disambiguation that pins the already-matched series is NOT selected")
    public void disambiguationSameSeriesNotSelected() {
        int currentId = uniqueSeriesId();
        FileEpisode ep = parsedEpisode("the quiet ones");
        ep.setEpisodeShow(Series.createSeries(currentId, "The Quiet Ones"));

        Map<String, String> disambig = new HashMap<>();
        disambig.put(StringUtils.makeQueryString("the quiet ones"),
                     String.valueOf(currentId)); // the SAME id already matched
        prefs.setShowDisambiguationOverrides(disambig);

        assertFalse(ep.rematchWouldChangeResult(prefs));
    }

    @Test
    @DisplayName("Unparsed row is never selected")
    public void unparsedRowNotSelected() {
        FileEpisode ep = new FileEpisode("gibberish-no-episode-here.mp4");
        // deliberately not calling setParsed()
        assertFalse(ep.rematchWouldChangeResult(prefs));
    }
}
