package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class EpisodeReplacementFormatterTest {

    @Test
    public void nullEpisodeTitleFormatsAsEmpty() {
        // Regression: a provider can return an episode with no title (e.g. no
        // translation in the chosen language). Formatting must not throw, and
        // must not write the word "null" into a filename.
        Show show = new Show("990503", "Solar Drift");
        Episode episode = new Episode(new EpisodeInfo.Builder()
            .episodeId("603")
            .seasonNumber("1")
            .episodeNumber("1")
            .episodeName(null)
            .firstAired("2023-01-01")
            .build());
        String result = EpisodeReplacementFormatter.format(
            "%S %s %e [%t][%T]", show, episode, new EpisodePlacement(1, 1), "", null, null);
        assertEquals("Solar Drift 1 1 [][]", result);
    }
}
