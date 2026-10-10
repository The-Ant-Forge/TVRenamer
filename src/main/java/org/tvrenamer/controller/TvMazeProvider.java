package org.tvrenamer.controller;

import org.tvrenamer.controller.tvmaze.TvMazeClient;
import org.tvrenamer.controller.tvmaze.TvMazeParser;
import org.tvrenamer.controller.tvmaze.TvMazeParser.TvMazeResult;
import org.tvrenamer.model.EpisodeInfo;
import org.tvrenamer.model.Series;
import org.tvrenamer.model.ShowName;
import org.tvrenamer.model.TVRenamerIOException;

import java.util.List;

/** EpisodeDataProvider backed by the keyless TVMaze API. */
public class TvMazeProvider implements EpisodeDataProvider {

    private final TvMazeClient client;

    public TvMazeProvider() {
        this(new TvMazeClient());
    }

    // Test seam.
    public TvMazeProvider(TvMazeClient client) {
        this.client = client;
    }

    @Override
    public void getShowOptions(ShowName showName) throws TVRenamerIOException {
        showName.clearShowOptions();
        String json = client.searchShowsJson(showName.getQueryString());
        for (TvMazeResult r : TvMazeParser.parseSearchShows(json)) {
            showName.addShowOption(r.id(), r.name(), r.year(), r.aliases());
        }
    }

    @Override
    public boolean addShowOptionById(ShowName showName, int seriesId)
        throws TVRenamerIOException
    {
        TvMazeResult r = TvMazeParser.parseShow(client.showJson(seriesId));
        if (r == null) {
            return false;
        }
        showName.addShowOption(r.id(), r.name(), r.year(), r.aliases());
        return true;
    }

    @Override
    public void getSeriesListing(Series series) throws TVRenamerIOException {
        List<EpisodeInfo> episodes =
            TvMazeParser.parseEpisodes(client.episodesJson(series.getId()));
        // TVMaze has a single episode ordering (no DVD variant).
        series.setPreferDvd(false);
        series.addEpisodeInfos(episodes.toArray(new EpisodeInfo[0]));
        series.listingsSucceeded();
    }
}
