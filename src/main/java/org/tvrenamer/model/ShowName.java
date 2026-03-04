package org.tvrenamer.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import org.tvrenamer.controller.ShowInformationListener;
import org.tvrenamer.controller.util.StringUtils;

/**
 * The ShowName class is an object that represents a string that is believed to represent
 * the name of a show.  Ultimately it will include a reference to the Show object.<p>
 *
 * Some examples may be helpful.  Let's say we have the following files:<ul>
 *   <li>"The Bullpen S01E02 Work Experience.mp4"</li>
 *   <li>"The Bullpen S05E07.mp4"</li>
 *   <li>"the.bullpen.s06e20.mkv"</li>
 *   <li>"the.bullpen.us.s08e11.avi"</li></ul><p>
 *
 * These would produce "filenameShow" values of "The Bullpen", "The Bullpen", "the.bullpen",
 * and "the.bullpen.us", respectively.  The first two are identical, and therefore will map
 * to the same ShowName object.<p>
 *
 * From the filenameShow, we create a query string, which normalizes the case and punctuation.
 * For the examples given, the query strings would be "the bullpen", "the bullpen", "the bullpen",
 * and "the bullpen us"; that is, the first *three* have the same value.  So even though there's
 * a separate ShowName object for the third file, it maps to the same QueryString.<p>
 *
 * The QueryString will be sent to the provider, which will potentially give us options for
 * shows it knows about, that match the query string.  We map each query string to a Show.
 * Potentially, multiple query strings can map to the same show.  For example, the strings
 * "the bullpen" and "bullpen" might map to the same show.<p>
 *
 * This example was chosen because there could be two distinct shows with the same name.
 * There are different ways to distinguish them, such as "The Bullpen (US)", "The Bullpen (UK)",
 * "The Bullpen (2005)", etc.  But the filenames may not have these differentiators.<p>
 *
 * Currently, we pick one Show for a given ShowName, even though in this example, the two
 * files actually do refer to separate shows.  We (as humans) know the first one is the
 * original version, because of the episode title; we know the second one is the reboot,
 * because it is Season 5, and the original didn't run that long.  Currently, this program
 * is not able to make those inferences, but it would be good to add in the future.
 */
public class ShowName {

    private static final Logger logger = Logger.getLogger(
        ShowName.class.getName()
    );

    /**
     * Inner class to hold a query string.  The query string is what we send to the provider
     * to try to resolve a show name.  We may re-use a single query string for multiple
     * show names.
     */
    private static class QueryString {

        final String queryString;
        private ShowOption matchedShow = null;
        private final List<ShowInformationListener> listeners =
            new java.util.LinkedList<>();

        private static final Map<String, QueryString> QUERY_STRINGS =
            new ConcurrentHashMap<>();

        private QueryString(String queryString) {
            this.queryString = queryString;
        }

        /**
         * Set the mapping between this QueryString and a Show.  Checks to see if this has already
         * been mapped to a show, but if it has, we still accept the new mapping; we just warn
         * about it.
         *
         * @param showOption the ShowOption to map this QueryString to
         */
        synchronized void setShowOption(ShowOption showOption) {
            if (matchedShow == null) {
                matchedShow = showOption;
                return;
            }
            if (matchedShow == showOption) {
                // same object; not just equals() but ==
                logger.info("re-setting show in QueryString " + queryString);
                return;
            }
            logger.warning("changing show in QueryString " + queryString);
            matchedShow = showOption;
        }

        // see ShowName.addListener for documentation
        private void addListener(ShowInformationListener listener) {
            synchronized (listeners) {
                listeners.add(listener);
            }
        }

        // see ShowName.hasListeners for documentation
        private boolean hasListeners() {
            synchronized (listeners) {
                return !listeners.isEmpty();
            }
        }

        // see ShowName.nameResolved for documentation
        private void nameResolved(Show show) {
            synchronized (listeners) {
                for (ShowInformationListener informationListener : listeners) {
                    informationListener.downloadSucceeded(show);
                }
                // Clear listeners once notified so future lookups can re-trigger downloads if needed.
                // This avoids "stuck" sessions where a previously evaluated query never re-queues.
                listeners.clear();
            }
        }

        // see ShowName.nameNotFound for documentation
        private void nameNotFound(FailedShow failedShow) {
            synchronized (listeners) {
                for (ShowInformationListener informationListener : listeners) {
                    informationListener.downloadFailed(failedShow);
                }
                // Clear listeners once notified so future lookups can re-trigger downloads if needed.
                listeners.clear();
            }
        }

        // see ShowName.apiDiscontinued for documentation
        private void apiDiscontinued() {
            synchronized (listeners) {
                listeners.forEach(
                    ShowInformationListener::apiHasBeenDeprecated
                );
                // Clear listeners once notified so future lookups can re-trigger downloads if needed.
                listeners.clear();
            }
        }

        /**
         * Get the mapping between this QueryString and a ShowOption, if any has been established.
         *
         * @return show the ShowOption to map this QueryString to
         */
        synchronized ShowOption getMatchedShow() {
            return matchedShow;
        }

        /**
         * Factory-style method to obtain a QueryString.  If an object has already been created
         * for the query string we need for the found name, re-use it.
         *
         * @param foundName
         *    the portion of the filename that is believed to represent the show's name
         * @return a QueryString object for looking up the foundName
         */
        static QueryString lookupQueryString(String foundName) {
            String queryString = StringUtils.makeQueryString(foundName);
            return QUERY_STRINGS.computeIfAbsent(
                queryString, QueryString::new
            );
        }
    }

    /**
     * A mapping from Strings to ShowName objects.  This is potentially a
     * many-to-one relationship.
     */
    private static final Map<String, ShowName> SHOW_NAMES =
        new ConcurrentHashMap<>();

    /**
     * Get the ShowName object for the given String.  If one was already created,
     * it is returned, and if not, one will be created, stored, and returned.
     *
     * Note, the functionality here is identical to {@link #lookupShowName}.  The only
     * implementation difference is the error message.  But callers should know which
     * one they want.
     *
     * @param filenameShow
     *            the name of the show as it appears in the filename
     * @return the ShowName object for that filenameShow
     */
    public static ShowName mapShowName(String filenameShow) {
        return SHOW_NAMES.computeIfAbsent(filenameShow, ShowName::new);
    }

    /**
     * Get the ShowName object for the given String, under the assumption that such
     * a mapping already exists.  If no mapping is found, one will be created, stored,
     * and returned, but an error message will also be generated.
     *
     * Note, the functionality here is identical to {@link #mapShowName}.  The only
     * implementation difference is the error message.  But callers should know which
     * one they want.
     *
     * @param filenameShow
     *            the name of the show as it appears in the filename
     * @return the ShowName object for that filenameShow
     */
    public static ShowName lookupShowName(String filenameShow) {
        ShowName existing = SHOW_NAMES.get(filenameShow);
        if (existing != null) {
            return existing;
        }
        logger.severe(
            "could not get show name for " +
                filenameShow +
                ", so created one instead"
        );
        return SHOW_NAMES.computeIfAbsent(filenameShow, ShowName::new);
    }

    /*
     * Instance variables
     */
    private final String foundName;
    private final QueryString queryString;

    private final List<ShowOption> showOptions;

    /*
     * QueryString methods -- these four methods are the public interface to the
     * functionality, but they are just pass-throughs to the real implementations
     * kept inside the QueryString inner class.
     */

    /**
     * Add a listener for this ShowName's query string.
     *
     * @param listener
     *            the listener registering interest
     */
    void addShowInformationListener(final ShowInformationListener listener) {
        synchronized (queryString) {
            queryString.addListener(listener);
        }
    }

    /**
     * Determine if this ShowName needs to be queried.
     *
     * If the answer is "yes", we add a listener and query immediately,
     * in a synchronized block.  Therefore, that becomes how we determine
     * the answer: if this ShowName already has a listener, that means
     * its download is already underway.
     *
     * @return false if this ShowName's query string already has a listener;
     *     true if not
     */
    boolean needsQuery() {
        synchronized (queryString) {
            return !queryString.hasListeners();
        }
    }

    /**
     * Notify registered interested parties that the provider has found a show,
     * and we've created a Show object to represent it.
     *
     * @param show
     *    the Show object representing the TV show we've mapped the string to.
     */
    public void nameResolved(Show show) {
        synchronized (queryString) {
            queryString.nameResolved(show);
        }
    }

    /**
     * Notify registered interested parties that the provider did not give us a
     * viable option, and provide a stand-in object.
     *
     * @param show
     *    the FailedShow object representing the string we searched for.
     */
    public void nameNotFound(FailedShow show) {
        synchronized (queryString) {
            queryString.nameNotFound(show);
        }
    }

    /**
     * Notify registered interested parties that the provider is unusable
     * due to a discontinued API.
     *
     */
    public void apiDiscontinued() {
        synchronized (queryString) {
            queryString.apiDiscontinued();
        }
    }

    /**
     * Create a ShowName object for the given "foundName" String.  The "foundName"
     * is expected to be the exact String that was extracted by the FilenameParser
     * from the filename, that is believed to represent the show name.
     *
     * @param foundName
     *            the name of the show as it appears in the filename
     */
    private ShowName(String foundName) {
        this.foundName = foundName;
        queryString = QueryString.lookupQueryString(foundName);

        showOptions = new CopyOnWriteArrayList<>();
    }

    /**
     * Return a snapshot of the show options returned by the provider for this ShowName.
     * This is intended for UI/lookup code that wants to prompt the user when multiple
     * candidates exist.
     *
     * @return a copy of the current show options list (may be empty)
     */
    public List<ShowOption> getShowOptions() {
        return List.copyOf(showOptions);
    }

    /**
     * Clear any previously collected provider show options for this ShowName.
     *
     * This is important when the same show/query is looked up repeatedly within a single
     * session (e.g., user adds/removes files multiple times). Without clearing, subsequent
     * provider searches would append new options onto the existing list, causing duplicate
     * candidates in the resolve dialog.
     */
    public void clearShowOptions() {
        showOptions.clear();
    }

    /**
     * Find out if this ShowName has received its options from the provider yet.
     *
     * @return true if this ShowName has show options; false otherwise
     */
    public boolean hasShowOptions() {
        return !showOptions.isEmpty();
    }

    /**
     * Add a possible Show option that could be mapped to this ShowName
     *
     * @param tvdbId
     *    the show's id in the TVDB database
     * @param seriesName
     *    the "official" show name
     */
    public void addShowOption(final String tvdbId, final String seriesName) {
        ShowOption option = ShowOption.getShowOption(tvdbId, seriesName);
        showOptions.add(option);
    }

    /**
     * Add a possible Show option that could be mapped to this ShowName, with
     * additional best-effort metadata returned by the provider search endpoint.
     *
     * @param tvdbId
     *    the show's id in the TVDB database
     * @param seriesName
     *    the "official" show name
     * @param firstAiredYear
     *    the year the show first aired (nullable)
     * @param aliasNames
     *    alias names returned by the provider (nullable; may be empty)
     */
    public void addShowOption(
        final String tvdbId,
        final String seriesName,
        final Integer firstAiredYear,
        final List<String> aliasNames
    ) {
        ShowOption option = ShowOption.getShowOption(
            tvdbId,
            seriesName,
            firstAiredYear,
            aliasNames
        );
        showOptions.add(option);
    }

    /**
     * Create a stand-in Show object in the case of failure from the provider.
     *
     * @param err any TVRenamerIOException that occurred trying to look up the show.
     *            May be null.
     * @return a Show representing this ShowName
     */
    public FailedShow getFailedShow(TVRenamerIOException err) {
        FailedShow standIn = new FailedShow(foundName, err);
        queryString.setShowOption(standIn);
        return standIn;
    }

    /**
     * Create a stand-in FailedShow for cases where the show lookup is intentionally
     * deferred pending user disambiguation, without caching that failure in the
     * QueryString mapping.
     *
     * This prevents "show selection required" from poisoning the in-memory cache for
     * this query string across the current session.
     *
     * @param err a TVRenamerIOException describing the deferred state (may be null)
     * @return a FailedShow instance (NOT cached into the QueryString mapping)
     */
    public FailedShow getNonCachingFailedShow(TVRenamerIOException err) {
        return new FailedShow(foundName, err);
    }

    /**
     * Given a list of two or more options for which series we're dealing with,
     * choose the best one and return it.
     *
     * @return the series from the list which best matches the series information
     */
    public ShowOption selectShowOption() {
        int nOptions = showOptions.size();
        if (nOptions == 0) {
            logger.info("did not find any options for " + foundName);
            return getFailedShow(null);
        }
        // logger.info("got " + nOptions + " options for " + foundName);
        ShowOption selected = null;
        for (ShowOption s : showOptions) {
            String actualName = s.getName();
            // Possibly instead of ignore case, we should make the foundName be
            // properly capitalized, and then we can do an exact comparison.
            if (foundName.equalsIgnoreCase(actualName)) {
                if (selected == null) {
                    selected = s;
                } else {
                    // Note: additional tie-breakers (language, year, aliases, etc.) are tracked in docs/todo.md
                    // under "Improve show selection heuristics when ambiguous".
                    logger.warning(
                        "multiple exact hits for " +
                            foundName +
                            "; choosing first one"
                    );
                }
            }
        }
        // Note: improved fuzzy matching / tie-breakers (e.g., Levenshtein distance) are tracked in docs/todo.md
        // under "Improve show selection heuristics when ambiguous".
        if (selected == null) {
            selected = showOptions.get(0);
        }

        queryString.setShowOption(selected);
        return selected;
    }

    /**
     * Get this ShowName's "example filename".<p>
     *
     * The "example filename" is an exact substring of the filename that caused
     * this ShowName to be created; specifically, it's the part of the filename
     * that we believe represents the show.
     *
     * @return the example filename
     */
    public String getExampleFilename() {
        return foundName;
    }

    /**
     * Get this ShowName's "query string"
     *
     * @return the query string
     *            the string we want to use as the value to query for a show
     *            that matches this ShowName
     */
    public String getQueryString() {
        return queryString.queryString;
    }

    /**
     * Get the mapping between this ShowName and a Show, if any has been established.
     *
     * @return a Show, if this ShowName is matched to one.  Null if not.
     */
    public synchronized ShowOption getMatchedShow() {
        return queryString.getMatchedShow();
    }

    /**
     * Standard object method to represent this ShowName as a string.
     *
     * @return string version of this
     */
    @Override
    public String toString() {
        return "ShowName [" + foundName + "]";
    }
}
