package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dedicated unit tests for the Show model class.
 * Covers construction, episode management, season indexing, and ordering.
 */
public class ShowTest {

    /** Helper to build an EpisodeInfo with both air and DVD placement. */
    private static EpisodeInfo makeInfo(String id, String season, String episode,
                                        String title, String dvdSeason,
                                        String dvdEpisode) {
        return new EpisodeInfo.Builder()
            .episodeId(id)
            .seasonNumber(season)
            .episodeNumber(episode)
            .episodeName(title)
            .firstAired("2025-01-15")
            .dvdSeason(dvdSeason)
            .dvdEpisodeNumber(dvdEpisode)
            .build();
    }

    /** Helper to build an EpisodeInfo with air placement only (no DVD). */
    private static EpisodeInfo makeAirOnly(String id, String season, String episode,
                                           String title) {
        return new EpisodeInfo.Builder()
            .episodeId(id)
            .seasonNumber(season)
            .episodeNumber(episode)
            .episodeName(title)
            .firstAired("2025-03-01")
            .build();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("public constructor sets name and generates negative ID")
        void publicConstructorSetsFields() {
            Show show = new Show("ext-99", "Westmark Academy");
            assertEquals("Westmark Academy", show.getName());
            assertEquals("ext-99", show.getIdString());
        }

        @Test
        @DisplayName("getDirName returns sanitised title")
        void dirNameIsSanitised() {
            Show show = new Show("d1", "The Quiet Ones: Season's End");
            String dirName = show.getDirName();
            // Should not contain colons or apostrophes (filesystem-unsafe chars)
            assertFalse(dirName.contains(":"), "dirName should not contain colon");
        }

        @Test
        @DisplayName("new Show starts with no episodes")
        void startsEmpty() {
            Show show = new Show("d2", "Solar Drift");
            assertTrue(show.noEpisodes());
        }

        @Test
        @DisplayName("toString includes name and id")
        void toStringFormat() {
            Show show = new Show("d3", "Ember Ridge");
            String s = show.toString();
            assertTrue(s.contains("Ember Ridge"));
            assertTrue(s.contains("d3"));
        }
    }

    @Nested
    @DisplayName("Episode management")
    class EpisodeManagement {

        @Test
        @DisplayName("addOneEpisode adds episode and noEpisodes returns false")
        void addOneEpisode() {
            Show show = new Show("e1", "Neon Harbor");
            EpisodeInfo info = makeInfo("ep100", "1", "1", "Pilot", "1", "1");
            assertTrue(show.addOneEpisode(info));
            assertFalse(show.noEpisodes());
        }

        @Test
        @DisplayName("addOneEpisode rejects null info")
        void addNullEpisodeReturnsFalse() {
            Show show = new Show("e2", "Neon Harbor");
            assertFalse(show.addOneEpisode(null));
        }

        @Test
        @DisplayName("addOneEpisode rejects duplicate episode ID")
        void addDuplicateEpisodeId() {
            Show show = new Show("e3", "Neon Harbor");
            EpisodeInfo info1 = makeInfo("ep200", "1", "1", "Pilot", "1", "1");
            EpisodeInfo info2 = makeInfo("ep200", "1", "2", "Second", "1", "2");
            assertTrue(show.addOneEpisode(info1));
            assertFalse(show.addOneEpisode(info2), "Duplicate episode ID should be rejected");
        }

        @Test
        @DisplayName("addEpisodeInfos indexes episodes by season")
        void addEpisodeInfosBulk() {
            Show show = new Show("e4", "Cloudbreak");
            show.setPreferDvd(false);
            EpisodeInfo[] infos = {
                makeInfo("ep301", "1", "1", "Dawn", "1", "1"),
                makeInfo("ep302", "1", "2", "Dusk", "1", "2"),
                makeInfo("ep303", "2", "1", "Revival", "2", "1"),
            };
            show.addEpisodeInfos(infos);

            assertFalse(show.noEpisodes());
            Episode ep = show.getEpisode(new EpisodePlacement(1, 2));
            assertNotNull(ep);
            assertEquals("Dusk", ep.getTitle());
        }
    }

    @Nested
    @DisplayName("Episode lookup")
    class EpisodeLookup {

        @Test
        @DisplayName("getEpisode returns null for non-existent season")
        void missingSeasonReturnsNull() {
            Show show = new Show("l1", "Frost Line");
            show.setPreferDvd(false);
            show.addEpisodeInfos(new EpisodeInfo[]{
                makeInfo("ep400", "1", "1", "Pilot", "1", "1")
            });
            assertNull(show.getEpisode(new EpisodePlacement(99, 1)));
        }

        @Test
        @DisplayName("getEpisode returns null for non-existent episode in valid season")
        void missingEpisodeReturnsNull() {
            Show show = new Show("l2", "Frost Line");
            show.setPreferDvd(false);
            show.addEpisodeInfos(new EpisodeInfo[]{
                makeInfo("ep401", "1", "1", "Pilot", "1", "1")
            });
            assertNull(show.getEpisode(new EpisodePlacement(1, 99)));
        }

        @Test
        @DisplayName("getEpisodes returns empty list for missing season")
        void getEpisodesEmptyForMissingSeason() {
            Show show = new Show("l3", "Frost Line");
            show.setPreferDvd(false);
            show.addEpisodeInfos(new EpisodeInfo[]{
                makeInfo("ep402", "1", "1", "Pilot", "1", "1")
            });
            List<Episode> result = show.getEpisodes(new EpisodePlacement(99, 1));
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("DVD vs air ordering")
    class Ordering {

        @Test
        @DisplayName("air-only episodes are findable when preferDvd is false")
        void airOnlyWithPreferAir() {
            Show show = new Show("o1", "Bright Signal");
            show.setPreferDvd(false);
            show.addEpisodeInfos(new EpisodeInfo[]{
                makeAirOnly("ep500", "1", "3", "No DVD Info")
            });
            Episode ep = show.getEpisode(new EpisodePlacement(1, 3));
            assertNotNull(ep, "Air-only episode should be found with preferDvd=false");
            assertEquals("No DVD Info", ep.getTitle());
        }

        @Test
        @DisplayName("air-only episodes are found via fallback when preferDvd is true")
        void airOnlyWithPreferDvdFallback() {
            Show show = new Show("o2", "Bright Signal");
            // preferDvd defaults to true; the fallback pass indexes by air ordering
            show.addEpisodeInfos(new EpisodeInfo[]{
                makeAirOnly("ep501", "2", "5", "Fallback Test")
            });
            Episode ep = show.getEpisode(new EpisodePlacement(2, 5));
            assertNotNull(ep, "Air-only episode should be found via fallback pass");
            assertEquals("Fallback Test", ep.getTitle());
        }

        @Test
        @DisplayName("DVD placement differs from air placement")
        void dvdDiffersFromAir() {
            Show show = new Show("o3", "Bright Signal");
            // Air: S1E3, DVD: S1E5
            show.addEpisodeInfos(new EpisodeInfo[]{
                makeInfo("ep502", "1", "3", "Reordered", "1", "5")
            });

            // With preferDvd (default), S1E5 should find it
            Episode byDvd = show.getEpisode(new EpisodePlacement(1, 5));
            assertNotNull(byDvd, "Should find by DVD placement");
            assertEquals("Reordered", byDvd.getTitle());

            // Air placement S1E3 should also find it via fallback
            Episode byAir = show.getEpisode(new EpisodePlacement(1, 3));
            assertNotNull(byAir, "Should also find by air placement (fallback)");
            assertEquals("Reordered", byAir.getTitle());
        }

        @Test
        @DisplayName("setPreferDvd switches ordering after re-index")
        void switchOrdering() {
            Show show = new Show("o4", "Bright Signal");
            // Air: S1E1, DVD: S1E10
            EpisodeInfo info = makeInfo("ep503", "1", "1", "Switched", "1", "10");
            show.addOneEpisode(info);

            // Index with DVD preference
            show.setPreferDvd(true);
            show.indexEpisodesBySeason();
            Episode byDvd = show.getEpisode(new EpisodePlacement(1, 10));
            assertNotNull(byDvd);

            // Switch to air and re-index
            show.setPreferDvd(false);
            show.indexEpisodesBySeason();
            Episode byAir = show.getEpisode(new EpisodePlacement(1, 1));
            assertNotNull(byAir);
            assertEquals("Switched", byAir.getTitle());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("episode with null season number is still added to show")
        void nullSeasonNumber() {
            Show show = new Show("x1", "Void Runner");
            // Season number is null — Episode will have null airPlacement
            EpisodeInfo info = new EpisodeInfo.Builder()
                .episodeId("ep600")
                .episodeNumber("1")
                .episodeName("No Season")
                .firstAired("2025-06-01")
                .build();
            assertTrue(show.addOneEpisode(info), "Episode should be added to episode map");
            assertFalse(show.noEpisodes());
            // But it won't be indexed by season
            show.setPreferDvd(false);
            show.indexEpisodesBySeason();
            // No season to look up, so getEpisode should return null
            assertNull(show.getEpisode(new EpisodePlacement(1, 1)));
        }

        @Test
        @DisplayName("episode with null episode number is added but not indexed")
        void nullEpisodeNumber() {
            Show show = new Show("x2", "Void Runner");
            EpisodeInfo info = new EpisodeInfo.Builder()
                .episodeId("ep601")
                .seasonNumber("1")
                .episodeName("No Episode Num")
                .firstAired("2025-06-01")
                .build();
            assertTrue(show.addOneEpisode(info));
            show.setPreferDvd(false);
            show.indexEpisodesBySeason();
            // Episode has season but no episode number → null placement → not indexed
            assertTrue(show.getEpisodes(new EpisodePlacement(1, 0)).isEmpty());
        }

        @Test
        @DisplayName("season 0 is valid (specials)")
        void seasonZeroForSpecials() {
            Show show = new Show("x3", "Void Runner");
            show.setPreferDvd(false);
            show.addEpisodeInfos(new EpisodeInfo[]{
                makeInfo("ep602", "0", "1", "Behind the Scenes", "0", "1")
            });
            Episode special = show.getEpisode(new EpisodePlacement(0, 1));
            assertNotNull(special, "Season 0 (specials) should work");
            assertEquals("Behind the Scenes", special.getTitle());
        }
    }
}
