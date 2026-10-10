package org.tvrenamer.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.tvrenamer.controller.tvdb.JdkHttpTransport;
import org.tvrenamer.controller.tvdb.TvdbV4Client;
import org.tvrenamer.controller.tvdb.V4Parser;
import org.tvrenamer.controller.tvdb.V4Parser.V4EpisodesPage;
import org.tvrenamer.controller.tvdb.V4Parser.V4SeriesResult;
import org.tvrenamer.model.EpisodeInfo;
import org.tvrenamer.model.Series;
import org.tvrenamer.model.ShowName;
import org.tvrenamer.model.TVRenamerIOException;
import org.tvrenamer.model.UserPreferences;

/** v4 provider: uses the v4 client + parser to populate the shared models. */
public class TheTVDBv4Provider implements EpisodeDataProvider {

    private static final Logger logger = Logger.getLogger(TheTVDBv4Provider.class.getName());

    private static final int MAX_PAGES = 20; // safety cap: 20 * 500 episodes

    private final TvdbV4Client client;

    public TheTVDBv4Provider() {
        this(new TvdbV4Client(new JdkHttpTransport(),
            () -> UserPreferences.getInstance().getTvdbV4ApiKey()));
    }

    // Test seam.
    public TheTVDBv4Provider(TvdbV4Client client) {
        this.client = client;
    }

    @Override
    public void getShowOptions(ShowName showName) throws TVRenamerIOException {
        showName.clearShowOptions();
        String json = client.searchSeriesJson(showName.getQueryString());
        for (V4SeriesResult r : V4Parser.parseSearchSeries(json)) {
            showName.addShowOption(r.tvdbId(), r.name(), r.year(), r.aliases());
        }
    }

    @Override
    public boolean addShowOptionById(ShowName showName, int seriesId)
        throws TVRenamerIOException
    {
        V4SeriesResult r = V4Parser.parseSeries(client.seriesJson(seriesId));
        if (r == null) {
            return false;
        }
        showName.addShowOption(r.tvdbId(), r.name(), r.year(), r.aliases());
        return true;
    }

    @Override
    public void getSeriesListing(Series series) throws TVRenamerIOException {
        final int id = series.getId();
        final String lang = UserPreferences.getInstance().getTitleLanguage().code();

        // Series name: reset first (determinism across cache re-use), then apply the
        // translated name for the chosen language if one is available.
        series.setDisplayNameOverride(null);
        try {
            String translated = V4Parser.parseTranslationName(
                client.seriesTranslationJson(id, lang));
            if (translated != null && !translated.isBlank()) {
                series.setDisplayNameOverride(translated);
            }
        } catch (TVRenamerIOException e) {
            // No translation available for this language: keep the original name.
            logger.fine("v4 series translation unavailable for " + id + "/" + lang
                + ": " + e.getMessage());
        }

        // Episodes: prefer DVD ordering when requested, else aired; language applied
        // to whichever season-type is used.
        boolean preferDvd = UserPreferences.getInstance().isPreferDvdOrderIfPresent();
        List<EpisodeInfo> episodes;
        if (preferDvd) {
            // A series without DVD ordering may be signalled either as 200 + an
            // empty list OR as a non-200 error status (which fetchAll surfaces as
            // a TVRenamerIOException). Treat both the same: fall back to aired
            // order. Only a failure of the aired ("default") fetch is fatal.
            List<EpisodeInfo> dvd;
            try {
                dvd = fetchAll(id, "dvd", lang);
            } catch (TVRenamerIOException e) {
                dvd = Collections.emptyList();
            }
            episodes = dvd.isEmpty() ? fetchAll(id, "default", lang) : dvd;
        } else {
            episodes = fetchAll(id, "default", lang);
        }

        // v4 ordering is baked into the chosen season-type; no per-episode DVD fallback.
        series.setPreferDvd(false);
        series.addEpisodeInfos(episodes.toArray(new EpisodeInfo[0]));
        series.listingsSucceeded();
    }

    private List<EpisodeInfo> fetchAll(int id, String seasonType, String lang)
        throws TVRenamerIOException {
        try {
            return fetchPages(id, seasonType, lang);
        } catch (TVRenamerIOException e) {
            if (lang != null) {
                // Language-qualified request failed: retry the same season-type
                // without a language segment (default-language titles).
                return fetchPages(id, seasonType, null);
            }
            throw e;
        }
    }

    private List<EpisodeInfo> fetchPages(int id, String seasonType, String lang)
        throws TVRenamerIOException {
        List<EpisodeInfo> all = new ArrayList<>();
        int page = 0;
        boolean more = true;
        while (more && page < MAX_PAGES) {
            V4EpisodesPage p = V4Parser.parseEpisodes(
                client.episodesJson(id, seasonType, lang, page));
            all.addAll(p.episodes());
            more = p.hasNext();
            page++;
        }
        return all;
    }
}
