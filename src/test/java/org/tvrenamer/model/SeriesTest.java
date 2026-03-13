package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dedicated unit tests for the Series model class.
 * Covers factory creation, ID uniqueness, download state machine, and episode integration.
 *
 * Note: Series uses a static KNOWN_SERIES cache that persists across tests.
 * Each test uses unique IDs to avoid cross-test interference.
 */
public class SeriesTest {

    // Use IDs in a high range to avoid collisions with other test classes
    private static int nextId = 900_000;

    private static synchronized int uniqueId() {
        return nextId++;
    }

    @Nested
    @DisplayName("Factory creation")
    class FactoryCreation {

        @Test
        @DisplayName("createSeries returns a Series with correct name and ID")
        void createSeriesBasic() {
            int id = uniqueId();
            Series s = Series.createSeries(id, "Starfall Chronicles");
            assertEquals(id, s.getId());
            assertEquals("Starfall Chronicles", s.getName());
            assertEquals(String.valueOf(id), s.getIdString());
        }

        @Test
        @DisplayName("createSeries throws on non-positive ID")
        void createSeriesNonPositiveId() {
            assertThrows(IllegalArgumentException.class,
                () -> Series.createSeries(0, "Zero ID Show"));
            assertThrows(IllegalArgumentException.class,
                () -> Series.createSeries(-1, "Negative ID Show"));
        }

        @Test
        @DisplayName("createSeries returns existing Series when same ID and name")
        void createSeriesDuplicateIdSameName() {
            int id = uniqueId();
            Series first = Series.createSeries(id, "Dune Walkers");
            Series second = Series.createSeries(id, "Dune Walkers");
            assertSame(first, second, "Should return the same cached instance");
        }

        @Test
        @DisplayName("createSeries throws when same ID but different name")
        void createSeriesDuplicateIdDifferentName() {
            int id = uniqueId();
            Series.createSeries(id, "Thunder Peak");
            assertThrows(IllegalArgumentException.class,
                () -> Series.createSeries(id, "Lightning Valley"));
        }

        @Test
        @DisplayName("getExistingSeries returns null for unknown ID")
        void getExistingSeriesUnknown() {
            assertNull(Series.getExistingSeries("999999999"));
        }

        @Test
        @DisplayName("getExistingSeries returns cached Series")
        void getExistingSeriesCached() {
            int id = uniqueId();
            Series created = Series.createSeries(id, "Iron Coast");
            Series found = Series.getExistingSeries(String.valueOf(id));
            assertSame(created, found);
        }
    }

    @Nested
    @DisplayName("Download state machine")
    class DownloadState {

        @Test
        @DisplayName("beginDownload returns true on first call")
        void beginDownloadFirst() {
            Series s = Series.createSeries(uniqueId(), "Echo Valley");
            assertTrue(s.beginDownload());
        }

        @Test
        @DisplayName("beginDownload returns false on subsequent calls")
        void beginDownloadSubsequent() {
            Series s = Series.createSeries(uniqueId(), "Echo Valley Sequel");
            assertTrue(s.beginDownload());
            assertFalse(s.beginDownload(), "Second call should return false");
            assertFalse(s.beginDownload(), "Third call should also return false");
        }
    }

    @Nested
    @DisplayName("Episode integration")
    class EpisodeIntegration {

        @Test
        @DisplayName("Series inherits Show episode management")
        void seriesCanHoldEpisodes() {
            Series s = Series.createSeries(uniqueId(), "Marble Arch");
            s.setPreferDvd(false);
            assertTrue(s.noEpisodes());

            EpisodeInfo info = new EpisodeInfo.Builder()
                .episodeId("ser-ep1")
                .seasonNumber("1")
                .episodeNumber("1")
                .episodeName("Foundation")
                .firstAired("2025-09-20")
                .dvdSeason("1")
                .dvdEpisodeNumber("1")
                .build();
            s.addEpisodeInfos(new EpisodeInfo[]{ info });

            assertFalse(s.noEpisodes());
            Episode ep = s.getEpisode(new EpisodePlacement(1, 1));
            assertNotNull(ep);
            assertEquals("Foundation", ep.getTitle());
        }
    }

    @Nested
    @DisplayName("Listings callbacks")
    class ListingsCallbacks {

        @Test
        @DisplayName("listingsSucceeded notifies registered listeners")
        void listingsSucceededNotifiesListeners() {
            Series s = Series.createSeries(uniqueId(), "Crystal Shore");
            boolean[] notified = { false };
            s.registrations.add(new org.tvrenamer.controller.ShowListingsListener() {
                @Override public void listingsDownloadComplete() { notified[0] = true; }
                @Override public void listingsDownloadFailed(Exception err) { fail("Should not fail"); }
            });
            s.listingsSucceeded();
            assertTrue(notified[0], "Listener should have been notified of success");
        }

        @Test
        @DisplayName("listingsFailed notifies registered listeners with exception")
        void listingsFailedNotifiesListeners() {
            Series s = Series.createSeries(uniqueId(), "Crimson Tide");
            Exception[] received = { null };
            s.registrations.add(new org.tvrenamer.controller.ShowListingsListener() {
                @Override public void listingsDownloadComplete() { fail("Should not succeed"); }
                @Override public void listingsDownloadFailed(Exception err) { received[0] = err; }
            });
            Exception testErr = new RuntimeException("test error");
            s.listingsFailed(testErr);
            assertSame(testErr, received[0]);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString includes name, id, and episode count")
        void toStringFormat() {
            int id = uniqueId();
            Series s = Series.createSeries(id, "Glass Tower");
            String str = s.toString();
            assertTrue(str.contains("Glass Tower"));
            assertTrue(str.contains(String.valueOf(id)));
            assertTrue(str.contains("0 episodes"));
        }
    }
}
