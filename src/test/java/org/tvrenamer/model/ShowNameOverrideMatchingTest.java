package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tvrenamer.controller.util.StringUtils;

/**
 * Tests how show name override keys are matched against the name parsed from a
 * filename.
 *
 * The "click to set Hint" link pre-fills the normalized query string (the text
 * the row shows), while the parser hands resolveShowName the raw name with its
 * underscores and capitals. Both spellings of a key must therefore match.
 */
public class ShowNameOverrideMatchingTest {

    private static final String RAW_NAME = "Solar Drift_ 2_14 and the Long Night";

    private final UserPreferences prefs = UserPreferences.getInstance();

    @AfterEach
    public void cleanUp() {
        prefs.setShowNameOverrides(new HashMap<>());
    }

    private void override(String from, String to) {
        Map<String, String> m = new HashMap<>();
        m.put(from, to);
        prefs.setShowNameOverrides(m);
    }

    @Test
    @DisplayName("A key written as the query string matches the raw parsed name")
    public void queryStringKeyMatchesRawName() {
        String query = StringUtils.makeQueryString(RAW_NAME);
        assertEquals("solar drift 2 14 and the long night", query);

        override(query, "Solar Drift (2031)");
        assertEquals("Solar Drift (2031)", prefs.resolveShowName(RAW_NAME));
    }

    @Test
    @DisplayName("A key written as the raw parsed name still matches")
    public void rawKeyStillMatches() {
        override(RAW_NAME, "Solar Drift (2031)");
        assertEquals("Solar Drift (2031)", prefs.resolveShowName(RAW_NAME));
    }

    @Test
    @DisplayName("Matching ignores case")
    public void matchingIgnoresCase() {
        override("WESTMARK ACADEMY", "Westmark Academy (2029)");
        assertEquals(
            "Westmark Academy (2029)",
            prefs.resolveShowName("westmark.academy")
        );
    }

    @Test
    @DisplayName("A different name is left alone")
    public void unrelatedNameUnchanged() {
        override("solar drift", "Solar Drift (2031)");
        assertEquals("The Quiet Ones", prefs.resolveShowName("The Quiet Ones"));
    }
}
