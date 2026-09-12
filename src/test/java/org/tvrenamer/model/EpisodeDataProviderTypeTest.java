package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests which providers honour the episode-ordering and title-language
 * preferences. Both the Preferences dialog (enabling those controls) and the
 * results table (refreshing loaded listings when either changes) key off this,
 * so it is the single source of truth for "does this setting do anything here".
 */
public class EpisodeDataProviderTypeTest {

    @Test
    @DisplayName("TheTVDB v4 honours the ordering and title-language preferences")
    public void tvdbV4SupportsOrderingAndLanguage() {
        assertTrue(EpisodeDataProviderType.TVDB_V4.supportsOrderingAndLanguage());
    }

    @Test
    @DisplayName("TVMaze ignores the ordering and title-language preferences")
    public void tvMazeDoesNotSupportOrderingAndLanguage() {
        assertFalse(EpisodeDataProviderType.TVMAZE.supportsOrderingAndLanguage());
    }
}
