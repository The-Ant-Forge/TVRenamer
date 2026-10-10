package org.tvrenamer.controller;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tvrenamer.controller.tvdb.TvdbV4Transport;
import org.tvrenamer.controller.tvdb.TvdbV4Transport.TvdbHttpResponse;
import org.tvrenamer.controller.tvmaze.TvMazeClient;
import org.tvrenamer.model.Series;
import org.tvrenamer.model.ShowName;
import org.tvrenamer.model.ShowOption;

public class TvMazeProviderTest {

    private static TvMazeClient clientReturning(java.util.function.Function<String,String> byUrl) {
        TvdbV4Transport t = new TvdbV4Transport() {
            public TvdbHttpResponse post(String u, String b, Map<String,String> h) {
                return new TvdbHttpResponse(405, "");
            }
            public TvdbHttpResponse get(String u, Map<String,String> h) {
                return new TvdbHttpResponse(200, byUrl.apply(u));
            }
        };
        return new TvMazeClient(t, 0);
    }

    @Test
    public void getShowOptionsPopulatesShowName() throws Exception {
        TvMazeClient c = clientReturning(u ->
            "[{\"score\":1.0,\"show\":{\"id\":38052,\"name\":\"Solar Drift\",\"premiered\":\"2019-01-01\"}}]");
        TvMazeProvider p = new TvMazeProvider(c);
        ShowName sn = ShowName.mapShowName("solar drift");
        p.getShowOptions(sn);
        List<ShowOption> opts = sn.getShowOptions();
        assertEquals(1, opts.size());
        assertEquals("38052", opts.get(0).getIdString());
    }

    @Test
    public void getSeriesListingPopulatesEpisodes() throws Exception {
        TvMazeClient c = clientReturning(u ->
            "[{\"id\":1,\"season\":1,\"number\":1,\"name\":\"Pilot\",\"airdate\":\"2019-01-01\"}]");
        TvMazeProvider p = new TvMazeProvider(c);
        Series s = Series.createSeries(990101, "Solar Drift");
        p.getSeriesListing(s);
        assertFalse(s.noEpisodes(), "episodes should be populated");
    }

    @Test
    public void pinnedIdMissingFromSearchIsFetchedById() throws Exception {
        // Same contract as the v4 provider: a pinned id the search missed is
        // fetched directly from /shows/{id}.
        TvMazeClient c = clientReturning(u ->
            u.contains("/shows/990401")
                ? "{\"id\":990401,\"name\":\"The Quiet Ones\",\"premiered\":\"2023-05-01\"}"
                : "[]");
        TvMazeProvider p = new TvMazeProvider(c);
        ShowName sn = ShowName.mapShowName("the quiet ones tvmaze pinned");
        p.getShowOptions(sn, "990401");
        List<ShowOption> opts = sn.getShowOptions();
        assertEquals(1, opts.size());
        assertEquals("990401", opts.get(0).getIdString());
        assertEquals("The Quiet Ones", opts.get(0).getName());
    }
}
