package org.tvrenamer.controller;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.tvrenamer.controller.tvdb.TvdbV4Client;
import org.tvrenamer.controller.tvdb.TvdbV4Transport;
import org.tvrenamer.controller.tvdb.TvdbV4Transport.TvdbHttpResponse;
import org.tvrenamer.model.Series;
import org.tvrenamer.model.ShowName;
import org.tvrenamer.model.ShowOption;
import org.tvrenamer.model.TVRenamerIOException;
import org.tvrenamer.model.TitleLanguage;
import org.tvrenamer.model.UserPreferences;

public class TheTVDBv4ProviderTest {

    /** One episode in aired ("default") order; hasNext=false. */
    private static final String AIRED_EPISODES_BODY =
        "{\"data\":{\"episodes\":[{\"id\":\"501\",\"seasonNumber\":\"1\","
        + "\"number\":\"1\",\"name\":\"Pilot\",\"aired\":\"2019-01-01\"}]},\"links\":{}}";

    private static TvdbV4Client clientReturning(String searchBody) {
        TvdbV4Transport t = new TvdbV4Transport() {
            public TvdbHttpResponse post(String u, String b, java.util.Map<String,String> h) {
                return new TvdbHttpResponse(200, "{\"data\":{\"token\":\"tok\"}}");
            }
            public TvdbHttpResponse get(String u, java.util.Map<String,String> h) {
                return new TvdbHttpResponse(200, searchBody);
            }
        };
        return new TvdbV4Client(t, () -> "key");
    }

    /** Client whose GET responses are decided per-URL by the supplied responder. */
    private static TvdbV4Client clientForListing(Function<String, TvdbHttpResponse> getResponder) {
        TvdbV4Transport t = new TvdbV4Transport() {
            public TvdbHttpResponse post(String u, String b, java.util.Map<String,String> h) {
                return new TvdbHttpResponse(200, "{\"data\":{\"token\":\"tok\"}}");
            }
            public TvdbHttpResponse get(String u, java.util.Map<String,String> h) {
                return getResponder.apply(u);
            }
        };
        return new TvdbV4Client(t, () -> "key");
    }

    /** Client whose GET responses are decided per-URL substring (episodes vs. translations);
     *  always answers with HTTP 200. Built atop {@link #clientForListing} so both helpers
     *  share one fake-transport implementation. */
    private static TvdbV4Client scriptedClient(Function<String, String> byUrl) {
        return clientForListing(u -> new TvdbHttpResponse(200, byUrl.apply(u)));
    }

    /** Run {@code body} with the DVD-order preference forced on, then restore it. */
    private static void withPreferDvd(ThrowingRunnable body) throws Exception {
        UserPreferences prefs = UserPreferences.getInstance();
        boolean original = prefs.isPreferDvdOrderIfPresent();
        prefs.setPreferDvdOrderIfPresent(true);
        try {
            body.run();
        } finally {
            prefs.setPreferDvdOrderIfPresent(original);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    public void getShowOptionsPopulatesShowName() throws Exception {
        String body = "{\"data\":[{\"tvdb_id\":\"1001\",\"name\":\"Solar Drift\","
                    + "\"year\":\"2019\",\"aliases\":[\"SD\"]}]}";
        TheTVDBv4Provider provider = new TheTVDBv4Provider(clientReturning(body));
        ShowName sn = ShowName.mapShowName("solar drift");
        provider.getShowOptions(sn);
        List<ShowOption> opts = sn.getShowOptions();
        assertEquals(1, opts.size());
        assertEquals("1001", opts.get(0).getIdString());
    }

    @Test
    public void dvdErrorStatusFallsBackToAiredOrder() throws Exception {
        // Regression: v4 signals "no DVD ordering" with a non-200 error status
        // (not 200+empty). The dvd fetch throws; the provider must fall back to
        // aired ("default") order instead of failing the whole listing.
        TheTVDBv4Provider provider = new TheTVDBv4Provider(clientForListing(url -> {
            if (url.contains("/episodes/dvd")) {
                return new TvdbHttpResponse(404, "");
            }
            return new TvdbHttpResponse(200, AIRED_EPISODES_BODY);
        }));
        Series series = Series.createSeries(990201, "Fallback On Error");
        withPreferDvd(() -> provider.getSeriesListing(series));
        assertFalse(series.noEpisodes(),
            "aired-order episodes should be populated after the dvd fetch errored");
    }

    @Test
    public void dvdEmptyListFallsBackToAiredOrder() throws Exception {
        // The other "no DVD ordering" signal: 200 + an empty episode list.
        TheTVDBv4Provider provider = new TheTVDBv4Provider(clientForListing(url -> {
            if (url.contains("/episodes/dvd")) {
                return new TvdbHttpResponse(200, "{\"data\":{\"episodes\":[]},\"links\":{}}");
            }
            return new TvdbHttpResponse(200, AIRED_EPISODES_BODY);
        }));
        Series series = Series.createSeries(990202, "Fallback On Empty");
        withPreferDvd(() -> provider.getSeriesListing(series));
        assertFalse(series.noEpisodes(),
            "aired-order episodes should be populated after an empty dvd listing");
    }

    @Test
    public void defaultFetchFailurePropagates() throws Exception {
        // If the aired ("default") fetch itself fails there is nothing left to
        // fall back to, so the error must propagate rather than be swallowed.
        TheTVDBv4Provider provider = new TheTVDBv4Provider(
            clientForListing(url -> new TvdbHttpResponse(500, "")));
        Series series = Series.createSeries(990203, "Hard Failure");
        withPreferDvd(() ->
            assertThrows(TVRenamerIOException.class, () -> provider.getSeriesListing(series)));
    }

    @Test
    public void setsTranslatedSeriesNameOverride() throws Exception {
        UserPreferences.getInstance().setTitleLanguage(TitleLanguage.ENGLISH);
        try {
            TvdbV4Client c = scriptedClient(u -> {
                if (u.contains("/translations/")) {
                    return "{\"data\":{\"name\":\"Sun City\",\"language\":\"eng\"}}";
                }
                return "{\"data\":{\"episodes\":[]}}";
            });
            TheTVDBv4Provider provider = new TheTVDBv4Provider(c);
            Series s = Series.createSeries(770101, "Ciudad del Sol");
            provider.getSeriesListing(s);
            assertEquals("Sun City", s.getName());
        } finally {
            UserPreferences.getInstance().setTitleLanguage(TitleLanguage.ENGLISH);
        }
    }

    @Test
    public void clearsOverrideWhenTranslationMissing() throws Exception {
        TvdbV4Client c = scriptedClient(u ->
            u.contains("/translations/")
                ? "{\"data\":{\"language\":\"eng\"}}"     // no name
                : "{\"data\":{\"episodes\":[]}}");
        TheTVDBv4Provider provider = new TheTVDBv4Provider(c);
        Series s = Series.createSeries(770102, "Ciudad del Sol");
        s.setDisplayNameOverride("stale");
        provider.getSeriesListing(s);
        assertEquals("Ciudad del Sol", s.getName(), "override must reset when no translation");
    }

    @Test
    public void translationCallFailureLeavesOverrideCleared() throws Exception {
        // Regression: unlike clearsOverrideWhenTranslationMissing (200 OK, no "name" key),
        // this makes the translations call itself fail (non-200), which TvdbV4Client surfaces
        // as a TVRenamerIOException. The reset-then-set order in getSeriesListing must still
        // leave a pre-seeded stale override cleared, and episode fetching must be unaffected.
        TvdbV4Client c = clientForListing(u ->
            u.contains("/translations/")
                ? new TvdbHttpResponse(404, "")
                : new TvdbHttpResponse(200, AIRED_EPISODES_BODY));
        TheTVDBv4Provider provider = new TheTVDBv4Provider(c);
        Series s = Series.createSeries(770103, "Ciudad del Sol");
        s.setDisplayNameOverride("stale");
        provider.getSeriesListing(s);
        assertEquals("Ciudad del Sol", s.getName(),
            "override must reset when the translation call fails");
        assertFalse(s.noEpisodes(), "episodes should still populate despite translation failure");
    }

    /** Search body with no results: the v4 search can miss a series that exists. */
    private static final String EMPTY_SEARCH_BODY = "{\"data\":[],\"links\":{}}";

    /** v4 /series/{id} body: numeric id, string year, aliases as objects. */
    private static final String SERIES_990301_BODY =
        "{\"data\":{\"id\":990301,\"name\":\"The Quiet Ones\",\"year\":\"2023\","
        + "\"aliases\":[{\"language\":\"eng\",\"name\":\"Quiet Ones\"}]}}";

    @Test
    public void pinnedIdMissingFromSearchIsFetchedById() throws Exception {
        // Regression: the v4 search sometimes returns nothing for a full title
        // even though the series exists. A pinned id must still be offered as a
        // candidate, fetched directly from /series/{id}.
        TheTVDBv4Provider provider = new TheTVDBv4Provider(clientForListing(u ->
            u.contains("/series/990301")
                ? new TvdbHttpResponse(200, SERIES_990301_BODY)
                : new TvdbHttpResponse(200, EMPTY_SEARCH_BODY)));
        ShowName sn = ShowName.mapShowName("the quiet ones pinned");
        provider.getShowOptions(sn, "990301");
        List<ShowOption> opts = sn.getShowOptions();
        assertEquals(1, opts.size());
        assertEquals("990301", opts.get(0).getIdString());
        assertEquals("The Quiet Ones", opts.get(0).getName());
    }

    @Test
    public void pinnedIdAlreadyInSearchResultsIsNotFetchedAgain() throws Exception {
        java.util.concurrent.atomic.AtomicInteger seriesCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        String search = "{\"data\":[{\"tvdb_id\":\"990302\",\"name\":\"Solar Drift\"}]}";
        TheTVDBv4Provider provider = new TheTVDBv4Provider(clientForListing(u -> {
            if (u.contains("/series/")) {
                seriesCalls.incrementAndGet();
            }
            return new TvdbHttpResponse(200, search);
        }));
        ShowName sn = ShowName.mapShowName("solar drift pinned");
        provider.getShowOptions(sn, "990302");
        assertEquals(1, sn.getShowOptions().size());
        assertEquals(0, seriesCalls.get(), "no by-id lookup when search already has the pin");
    }

    @Test
    public void unknownPinnedIdLeavesSearchResultsUnchanged() throws Exception {
        // A pinned id the provider doesn't know (404) is not an error for the
        // search as a whole: the caller sees the plain search results.
        TheTVDBv4Provider provider = new TheTVDBv4Provider(clientForListing(u ->
            u.contains("/series/")
                ? new TvdbHttpResponse(404, "")
                : new TvdbHttpResponse(200, EMPTY_SEARCH_BODY)));
        ShowName sn = ShowName.mapShowName("westmark academy pinned");
        provider.getShowOptions(sn, "990303");
        assertTrue(sn.getShowOptions().isEmpty());
    }

    @Test
    public void nonNumericPinnedIdIsNotSentToProvider() throws Exception {
        // Pinned ids come from a user-editable prefs file; only digits may be
        // spliced into the request path.
        java.util.concurrent.atomic.AtomicInteger seriesCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        TheTVDBv4Provider provider = new TheTVDBv4Provider(clientForListing(u -> {
            if (u.contains("/series/")) {
                seriesCalls.incrementAndGet();
            }
            return new TvdbHttpResponse(200, EMPTY_SEARCH_BODY);
        }));
        ShowName sn = ShowName.mapShowName("ciudad del sol pinned");
        provider.getShowOptions(sn, "1/episodes/default");
        assertTrue(sn.getShowOptions().isEmpty());
        assertEquals(0, seriesCalls.get());
    }

    /** Episodes in the requested language: episode 1 has no translated title. */
    private static final String ENG_EPISODES_MISSING_TITLE =
        "{\"data\":{\"episodes\":["
        + "{\"id\":\"601\",\"seasonNumber\":\"1\",\"number\":\"1\",\"name\":null},"
        + "{\"id\":\"602\",\"seasonNumber\":\"1\",\"number\":\"2\",\"name\":\"Second Light\"}"
        + "]},\"links\":{}}";

    /** The same episodes in the series' original language. */
    private static final String ORIGINAL_EPISODES =
        "{\"data\":{\"episodes\":["
        + "{\"id\":\"601\",\"seasonNumber\":\"1\",\"number\":\"1\",\"name\":\"Primeira Luz\"},"
        + "{\"id\":\"602\",\"seasonNumber\":\"1\",\"number\":\"2\",\"name\":\"Segunda Luz\"}"
        + "]},\"links\":{}}";

    @Test
    public void missingTranslatedTitlesFallBackToOriginalLanguage() throws Exception {
        // Regression: v4 returns 200 with "name": null for every episode that has
        // no translation in the requested language. Those nulls reached the
        // filename formatter and crashed the whole listing. Fill each missing
        // title from the original-language listing; keep titles that exist.
        UserPreferences.getInstance().setTitleLanguage(TitleLanguage.ENGLISH);
        TheTVDBv4Provider provider = new TheTVDBv4Provider(clientForListing(u -> {
            if (u.contains("/episodes/default/eng")) {
                return new TvdbHttpResponse(200, ENG_EPISODES_MISSING_TITLE);
            }
            if (u.contains("/episodes/default?")) {
                return new TvdbHttpResponse(200, ORIGINAL_EPISODES);
            }
            return new TvdbHttpResponse(404, "");
        }));
        Series s = Series.createSeries(990501, "Solar Drift");
        provider.getSeriesListing(s);
        assertEquals("Primeira Luz",
            s.getEpisode(new org.tvrenamer.model.EpisodePlacement(1, 1)).getTitle());
        assertEquals("Second Light",
            s.getEpisode(new org.tvrenamer.model.EpisodePlacement(1, 2)).getTitle());
    }

    @Test
    public void titleFallbackFailureKeepsTheListing() throws Exception {
        // The original-language fetch is only a nicety: if it fails, the
        // listing still succeeds with the titles it has.
        UserPreferences.getInstance().setTitleLanguage(TitleLanguage.ENGLISH);
        TheTVDBv4Provider provider = new TheTVDBv4Provider(clientForListing(u ->
            u.contains("/episodes/default/eng")
                ? new TvdbHttpResponse(200, ENG_EPISODES_MISSING_TITLE)
                : new TvdbHttpResponse(500, "")));
        Series s = Series.createSeries(990502, "Solar Drift");
        provider.getSeriesListing(s);
        assertFalse(s.noEpisodes());
        assertEquals("Second Light",
            s.getEpisode(new org.tvrenamer.model.EpisodePlacement(1, 2)).getTitle());
    }
}
