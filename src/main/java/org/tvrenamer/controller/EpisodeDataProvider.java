package org.tvrenamer.controller;

import java.util.logging.Logger;
import org.tvrenamer.model.DiscontinuedApiException;
import org.tvrenamer.model.Series;
import org.tvrenamer.model.ShowName;
import org.tvrenamer.model.ShowOption;
import org.tvrenamer.model.TVRenamerIOException;

/** Abstraction over a TheTVDB API version. Implementations populate the shared models. */
public interface EpisodeDataProvider {
    void getShowOptions(ShowName showName) throws TVRenamerIOException, DiscontinuedApiException;
    void getSeriesListing(Series series) throws TVRenamerIOException;

    /**
     * Look up a single series by its provider id and add it to the show's options.
     *
     * @param seriesId provider series id, already checked to be all digits
     * @return true if the provider knew the series and it was added
     */
    boolean addShowOptionById(ShowName showName, int seriesId) throws TVRenamerIOException;

    /**
     * Search for show options, then make sure a pinned series is among them.
     *
     * <p>A provider's name search can miss a series that exists (seen with the
     * TVDB v4 search for some full titles). A user who pinned the series id has
     * already told us which series they mean, so it is fetched directly by id
     * instead of relying on the search to return it.
     *
     * @param pinnedId pinned provider series id for this show, or null
     */
    default void getShowOptions(ShowName showName, String pinnedId)
        throws TVRenamerIOException, DiscontinuedApiException
    {
        getShowOptions(showName);
        if (pinnedId == null) {
            return;
        }
        String id = pinnedId.trim();
        // Pinned ids come from a user-editable prefs file; only a plain number
        // is spliced into a request path.
        if (!id.matches("\\d{1,9}")) {
            return;
        }
        for (ShowOption option : showName.getShowOptions()) {
            if (id.equals(option.getIdString())) {
                return;
            }
        }
        try {
            addShowOptionById(showName, Integer.parseInt(id));
        } catch (TVRenamerIOException e) {
            // Unknown id (or lookup failure): the plain search results stand.
            Logger.getLogger(EpisodeDataProvider.class.getName())
                .fine("Pinned series id " + id + " lookup failed: " + e.getMessage());
        }
    }
}
