package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Test episode lookup with DVD fields set to "0" — reproduces the scenario
 * where all episodes have DVD_season=0, DVD_episodenumber=0 (common for new
 * shows on TVDB).
 */
public class EpisodeLookupTest {

    /**
     * Creates an EpisodeInfo matching what the TVDB API returns for shows
     * where DVD ordering hasn't been populated yet (DVD fields = "0").
     */
    private static EpisodeInfo makeEpisodeInfo(String id, String season, String episode,
                                                String title, String dvdSeason,
                                                String dvdEpisode) {
        return new EpisodeInfo.Builder()
            .episodeId(id)
            .seasonNumber(season)
            .episodeNumber(episode)
            .episodeName(title)
            .firstAired("2026-02-12")
            .dvdSeason(dvdSeason)
            .dvdEpisodeNumber(dvdEpisode)
            .build();
    }

    @Test
    void episodeLookupWithDvdFieldsZero_preferDvdFalse() {
        // Simulate the exact TVDB data for show 408694 (a new 2026 show)
        // All episodes have DVD_season=0, DVD_episodenumber=0
        Show show = new Show("408694", "Love Story");
        show.setPreferDvd(false);

        EpisodeInfo[] infos = {
            makeEpisodeInfo("8611439",  "1", "1", "Pilot",            "0", "0"),
            makeEpisodeInfo("11612030", "1", "2", "The Pools Party",  "0", "0"),
            makeEpisodeInfo("11612031", "1", "3", "America's Widow",  "0", "0"),
        };
        show.addEpisodeInfos(infos);

        // Verify all 3 episodes are found
        for (int ep = 1; ep <= 3; ep++) {
            EpisodePlacement placement = new EpisodePlacement(1, ep);
            Episode found = show.getEpisode(placement);
            assertNotNull(found, "Episode " + ep + " should be found");
        }

        assertEquals("Pilot",           show.getEpisode(new EpisodePlacement(1, 1)).getTitle());
        assertEquals("The Pools Party",  show.getEpisode(new EpisodePlacement(1, 2)).getTitle());
        assertEquals("America's Widow",  show.getEpisode(new EpisodePlacement(1, 3)).getTitle());
    }

    @Test
    void episodeLookupWithDvdFieldsZero_preferDvdTrue() {
        // Same data but with DVD ordering preferred
        Show show = new Show("408694", "Love Story");
        show.setPreferDvd(true);

        EpisodeInfo[] infos = {
            makeEpisodeInfo("8611439",  "1", "1", "Pilot",            "0", "0"),
            makeEpisodeInfo("11612030", "1", "2", "The Pools Party",  "0", "0"),
            makeEpisodeInfo("11612031", "1", "3", "America's Widow",  "0", "0"),
        };
        show.addEpisodeInfos(infos);

        // Even with preferDvd=true, season 1 episodes should still be found
        // via the air ordering fallback
        for (int ep = 1; ep <= 3; ep++) {
            EpisodePlacement placement = new EpisodePlacement(1, ep);
            Episode found = show.getEpisode(placement);
            assertNotNull(found, "Episode " + ep + " should be found (preferDvd=true)");
        }
    }

    @Test
    void episodeLookupWithEmptyDvdFields() {
        // DVD fields empty (null) — another common pattern
        Show show = new Show("408694", "Love Story");
        show.setPreferDvd(false);

        EpisodeInfo[] infos = {
            makeEpisodeInfo("8611439",  "1", "1", "Pilot",            null, null),
            makeEpisodeInfo("11612030", "1", "2", "The Pools Party",  null, null),
            makeEpisodeInfo("11612031", "1", "3", "America's Widow",  null, null),
        };
        show.addEpisodeInfos(infos);

        for (int ep = 1; ep <= 3; ep++) {
            EpisodePlacement placement = new EpisodePlacement(1, ep);
            Episode found = show.getEpisode(placement);
            assertNotNull(found, "Episode " + ep + " should be found (empty DVD)");
        }
    }

    @Test
    void episodeLookupWithEmptyDvdFields_emptyString() {
        // DVD fields as empty string "" — tests StringUtils.stringToInt("")
        Show show = new Show("408694", "Love Story");
        show.setPreferDvd(false);

        EpisodeInfo[] infos = {
            makeEpisodeInfo("8611439",  "1", "1", "Pilot",            "", ""),
            makeEpisodeInfo("11612030", "1", "2", "The Pools Party",  "", ""),
            makeEpisodeInfo("11612031", "1", "3", "America's Widow",  "", ""),
        };
        show.addEpisodeInfos(infos);

        for (int ep = 1; ep <= 3; ep++) {
            EpisodePlacement placement = new EpisodePlacement(1, ep);
            Episode found = show.getEpisode(placement);
            assertNotNull(found, "Episode " + ep + " should be found (empty string DVD)");
        }
    }

    @Test
    void getEpisodesPlural_withDvdFieldsZero() {
        // Test getEpisodes() (plural) which is used by FileEpisode.listingsComplete()
        Show show = new Show("408694", "Love Story");
        show.setPreferDvd(false);

        EpisodeInfo[] infos = {
            makeEpisodeInfo("8611439",  "1", "1", "Pilot",            "0", "0"),
            makeEpisodeInfo("11612030", "1", "2", "The Pools Party",  "0", "0"),
            makeEpisodeInfo("11612031", "1", "3", "America's Widow",  "0", "0"),
        };
        show.addEpisodeInfos(infos);

        for (int ep = 1; ep <= 3; ep++) {
            EpisodePlacement placement = new EpisodePlacement(1, ep);
            List<Episode> episodes = show.getEpisodes(placement);
            assertFalse(episodes.isEmpty(), "getEpisodes(" + ep + ") should not be empty");
            assertEquals(1, episodes.size(), "getEpisodes(" + ep + ") should have 1 entry");
        }
    }

    @Test
    void episodeLookup_withSpecials() {
        // Full scenario with Season 0 specials (like show 408694)
        Show show = new Show("408694", "Love Story");
        show.setPreferDvd(false);

        EpisodeInfo[] infos = {
            makeEpisodeInfo("8611439",  "1", "1", "Pilot",            "0", "0"),
            makeEpisodeInfo("11612030", "1", "2", "The Pools Party",  "0", "0"),
            makeEpisodeInfo("11612031", "1", "3", "America's Widow",  "0", "0"),
            // Season 0 specials
            makeEpisodeInfo("11614076", "0", "1", "Special 1",        "0", "0"),
            makeEpisodeInfo("11614077", "0", "2", "Special 2",        "0", "0"),
        };
        show.addEpisodeInfos(infos);

        // Season 1 episodes should all be found
        assertNotNull(show.getEpisode(new EpisodePlacement(1, 1)));
        assertNotNull(show.getEpisode(new EpisodePlacement(1, 2)));
        assertNotNull(show.getEpisode(new EpisodePlacement(1, 3)));

        // Season 0 specials should also be found
        assertNotNull(show.getEpisode(new EpisodePlacement(0, 1)));
        assertNotNull(show.getEpisode(new EpisodePlacement(0, 2)));
    }
}
