package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FileEpisode#hasResolvedShow()}, which selects the rows to refresh
 * when a setting that shapes episode listings (ordering, title language) changes.
 *
 * Any row already matched to a show depends on that show's listing, including a
 * row whose listing is still downloading (it would otherwise finish into the old
 * ordering) and one whose episode number found no match (a different ordering
 * may well match it). Rows that never found a show are unaffected.
 */
public class FileEpisodeResolvedShowTest {

    // Unique ids so tests do not collide in the shared, static Series.KNOWN_SERIES
    // cache (every test class runs in the same JVM). Ids already taken elsewhere:
    // 770001+ FileEpisodeRematchTest, 770101-770103 and 990201-990203
    // TheTVDBv4ProviderTest, 880001+ ShowOptionDisplayNameTest, 990101
    // TvMazeProviderTest. This class uses 660001+.
    private static final AtomicInteger SERIES_ID = new AtomicInteger(660001);

    private static FileEpisode episodeAt(String show, String season, String episode) {
        FileEpisode ep = new FileEpisode(show + ".S01E01.mp4");
        ep.setParsed();
        ep.setExtractedFilenameShow(show);
        ep.setFilenameShow(show);
        ep.setEpisodePlacement(season, episode);
        return ep;
    }

    private static Series seriesWithFirstEpisode(String name) {
        Series series = Series.createSeries(SERIES_ID.getAndIncrement(), name);
        series.addEpisodeInfos(new EpisodeInfo[] {
            new EpisodeInfo.Builder()
                .episodeId(String.valueOf(SERIES_ID.getAndIncrement()))
                .seasonNumber("1")
                .episodeNumber("1")
                .episodeName("Opening Night")
                .firstAired("2026-01-05")
                .build()
        });
        return series;
    }

    @Test
    @DisplayName("A row not yet looked up has no resolved show")
    public void notStartedIsNotResolved() {
        assertFalse(episodeAt("westmark academy", "1", "1").hasResolvedShow());
    }

    @Test
    @DisplayName("A row whose show was not found has no resolved show")
    public void unfoundIsNotResolved() {
        FileEpisode ep = episodeAt("westmark academy", "1", "1");
        ep.setEpisodeShow(null);
        assertFalse(ep.hasResolvedShow());
    }

    @Test
    @DisplayName("A matched row whose listing is still downloading is resolved")
    public void listingPendingIsResolved() {
        FileEpisode ep = episodeAt("solar drift", "1", "1");
        ep.setEpisodeShow(Series.createSeries(SERIES_ID.getAndIncrement(), "Solar Drift"));
        assertTrue(ep.hasResolvedShow());
    }

    @Test
    @DisplayName("A matched row whose show has no listings is resolved")
    public void noListingsIsResolved() {
        FileEpisode ep = episodeAt("solar drift", "1", "1");
        ep.setEpisodeShow(Series.createSeries(SERIES_ID.getAndIncrement(), "Solar Drift"));
        ep.listingsComplete();
        assertTrue(ep.hasResolvedShow());
    }

    @Test
    @DisplayName("A matched row whose episode number found no match is resolved")
    public void noMatchIsResolved() {
        FileEpisode ep = episodeAt("the quiet ones", "2", "5");
        ep.setEpisodeShow(seriesWithFirstEpisode("The Quiet Ones"));
        ep.listingsComplete();
        assertTrue(ep.hasResolvedShow());
    }

    @Test
    @DisplayName("A fully matched row is resolved")
    public void gotListingsIsResolved() {
        FileEpisode ep = episodeAt("the quiet ones", "1", "1");
        ep.setEpisodeShow(seriesWithFirstEpisode("The Quiet Ones"));
        ep.listingsComplete();
        assertTrue(ep.hasResolvedShow());
    }
}
