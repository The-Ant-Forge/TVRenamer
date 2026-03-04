package org.tvrenamer.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;
import org.tvrenamer.controller.ShowInformationListener;
import org.tvrenamer.controller.TheTVDBProvider;

/**
 * ShowStore -- maps strings to Show objects.<p>
 *
 * Note that, just for a single file, we may have up to five versions of the show's "name".
 * Let's look at an example.  Say we have a file named "Cosmos, A Space Time Odyssey S01E02.mp4".
 * The first thing we do is try to extract the show name from the filename.  If we do it right,
 * we'll get "Cosmos, A Space Time Odyssey".  That string is stored as the "filenameShow" of
 * the FileEpisode.<p>
 *
 * Next, we'll want to query for that string.  But first we try to eliminate characters that
 * are either problematic, because they might serve as meta-characters in various contexts,
 * or simply irrelevant.  We consider show titles to essentially be case-insensitive, and
 * we don't think punctuation matters, at this point.  So we normalize the string.  Since
 * this is the text we're going to send to the provider to query for which actual show it
 * might match, I sometimes call this the "query string".  In this case, it would be
 * "cosmos a space time odyssey".<p>
 *
 * Then, from the provider, we get back the actual show name: "Cosmos: A Spacetime Odyssey".<p>
 *
 * But this is a bit of a problem, because Windows does not allow the colon character to
 * appear in filenames.  So we "sanitise" the title to "Cosmos - A Spacetime Odyssey".
 * That's four versions of the same show name.<p>
 *
 * The fifth?  We allow users to set a preference to use dots instead of spaces in the
 * filenames, which would turn this into "Cosmos-A.Spacetime.Odyssey".<p>
 *
 * (Note that I did say, "up to" five versions.  In the case of a show like "Futurama",
 * we'd likely only deal with two versions, upper-case and lower-case.  For "24", there
 * is probably just the one version.)<p>
 *
 * Once again, in table form:
 * <table summary="Versions of a show's name">
 *  <tr><td>(1) filename show</td><td>"Cosmos, A Space Time Odyssey"</td></tr>
 *  <tr><td>(2) query string</td>       <td>"cosmos a space time odyssey"</td></tr>
 *  <tr><td>(3) actual show name</td>   <td>"Cosmos: A Spacetime Odyssey"</td></tr>
 *  <tr><td>(4) sanitised name</td>     <td>"Cosmos - A Spacetime Odyssey"</td></tr>
 *  <tr><td>(5) output name</td>        <td>"Cosmos-A.Spacetime.Odyssey"</td></tr></table><p>
 *
 * Most of these transitions are simple string transformations, provided by
 * {@link org.tvrenamer.controller.util.StringUtils}:<ul>
 *  <li>(1) -&gt;(2) makeQueryString</li>
 *  <li>(3) -&gt;(4) sanitiseTitle</li>
 *  <li>(4) -&gt;(5) makeDotTitle</li></ul><p>
 *
 * This file is how we get from (2) -&gt;(3).  It maps query strings to Show objects, and the
 * Show objects obviously contain the actual show name.  So we have:<ul>
 *
 *  <li>(1) -&gt;(2)  makeQueryString</li>
 *  <li>(2) -&gt;(3a) ShowStore.mapStringToShow</li>
 *  <li>(3a) -&gt;(3) Show.getName</li>
 *  <li>(3) -&gt;(4)  sanitiseTitle</li>
 *  <li>(4) -&gt;(5)  makeDotTitle</li></ul><p>
 *
 * Note that makeQueryString should be idempotent.  If you already have a query string, and
 * you call makeQueryString on it, you should get back the identical string.<p>
 *
 * One other small note, the "actual show name" is not necessarily the true, actual actual
 * show name.  In fact, the strings we consider as "actual show name" are expected to be
 * unique (not sure if I can say "guaranteed", that's kind of out of our hands), whereas
 * actual show names are not.  There was never a show called "Archer (2009)"; the show that
 * refers to was just called "Archer".  But The TVDB adds the date because there had been
 * a previous show called "Archer".<p>
 *
 * This is true despite the fact that Shows also have a show ID, which is presumably even more
 * guaranteed to be unique.<p>
 *
 * Given the assumption about the uniqueness of the "actual show name", we hope to have:<ul>
 *  <li>(1) -&gt;(2)  many to one</li>
 *  <li>(2) -&gt;(3a) many to one</li>
 *  <li>(3a) -&gt;(3) one to one</li>
 *  <li>(3) -&gt;(4)  one to one</li>
 *  <li>(4) -&gt;(5)  one to one</li></ul><p>
 *
 * I still must say "hope to have", because this does all depend on the idea that a show
 * is never identified by punctuation or case.  That is, if we had DIFFERENT shows, one
 * called "Cosmos: A Spacetime Odyssey" and the other called "Cosmos - A Spacetime Odyssey",
 * or the other called "Cosmos: a spacetime odyssey", we would not be able to accurately
 * tell them apart.  But it's a safe assumption that won't happen.<p>
 *
 * On the other hand, we likely DO have issues involving the non-uniqueness of a title like
 * "Archer" or "The Bullpen".  The fact that The TVDB assigns unique names to these series
 * does not necessarily help us much in doing the (2) -&gt;(3a) mapping.<p>
 *
 * What we might want to do in the future is make it potentially a many-to-many relation,
 * and say that calling mapStringToShow() does not necessarily pin down the exact series the file
 * refers to.  We might be able to figure it out later, based on additional information.
 * For example, if we're looking at "The Bullpen, Season 8", we know it has to be the US
 * version, because the UK version didn't do that many seasons.  Or, if the actual episode
 * name is already embedded in the filename, we could try to match that up with the information
 * we get about episode listings.<p>
 *
 * Perhaps the best option would be to have something in the UI to notify the user of the
 * ambiguity, make our best guess, and let the user correct it, if they like. But we don't
 * have that functionality, now.<p>
 *
 * So, anyway, this class.  :)  Again, this is the (2) -&gt;(3a) step, mapping query strings
 * to Show objects.  The real work here is when we take a query string, pass it to the
 * provider to get back a list of options, choose the best option, and return it to the
 * listener via callback.  But we do, of course, also store the mapping in a hash map, so
 * if a second file comes in with the same query string, we don't go look it up again,
 * but simply return the same answer we gave the first time.
 *
 */
public class ShowStore {

    private static final Logger logger = Logger.getLogger(
        ShowStore.class.getName()
    );

    private static final ExecutorService threadPool =
        Executors.newFixedThreadPool(4);

    private static final UserPreferences prefs = UserPreferences.getInstance();

    /**
     * Listener invoked when pending show disambiguations are enqueued/changed.
     *
     * This allows the UI to react immediately (e.g., open the batch disambiguation dialog)
     * without timer-based polling.
     */
    public interface PendingDisambiguationsListener {
        void pendingDisambiguationsChanged();
    }

    private static final CopyOnWriteArrayList<
        PendingDisambiguationsListener
    > pendingDisambiguationsListeners = new CopyOnWriteArrayList<>();

    public static void addPendingDisambiguationsListener(
        final PendingDisambiguationsListener listener
    ) {
        if (listener != null) {
            pendingDisambiguationsListeners.addIfAbsent(listener);
        }
    }

    public static void removePendingDisambiguationsListener(
        final PendingDisambiguationsListener listener
    ) {
        if (listener != null) {
            pendingDisambiguationsListeners.remove(listener);
        }
    }

    private static void notifyPendingDisambiguationsChanged() {
        for (PendingDisambiguationsListener l : pendingDisambiguationsListeners) {
            try {
                l.pendingDisambiguationsChanged();
            } catch (RuntimeException e) {
                logger.warning(
                    "pendingDisambiguationsChanged listener threw: " + e
                );
            }
        }
    }

    /**
     * Pending disambiguations collected during lookup so the UI can resolve them
     * in a single batch dialog after adding files.
     *
     * Keyed by normalized provider query string.
     */
    private static final Map<
        String,
        PendingDisambiguation
    > pendingDisambiguations = new LinkedHashMap<>();

    /**
     * Represents one ambiguous show lookup that requires user input.
     *
     * - queryString: provider query string (normalized; used as key and for persistence)
     * - extractedShowName: user-facing extracted name (display in batch dialog)
     * - exampleFileName: FileEpisode.getFileName() (display in batch dialog)
     * - options: provider candidates (can be truncated to first 5 in UI)
     * - scoredOptions: options with similarity scores, sorted best-first (for UI ranking)
     */
    public static final class PendingDisambiguation {

        public final String queryString;
        public final String extractedShowName;
        public final String exampleFileName;
        public final List<ShowOption> options;
        public final List<ShowSelectionEvaluator.ScoredOption> scoredOptions;

        public PendingDisambiguation(
            final String queryString,
            final String extractedShowName,
            final String exampleFileName,
            final List<ShowOption> options,
            final List<ShowSelectionEvaluator.ScoredOption> scoredOptions
        ) {
            this.queryString = queryString;
            this.extractedShowName = extractedShowName;
            this.exampleFileName = exampleFileName;
            this.options = options;
            this.scoredOptions = scoredOptions;
        }
    }

    /**
     * Get a snapshot of currently pending disambiguations.
     * The UI can use this to show a single batch dialog.
     *
     * @return map of queryString -> pending disambiguation (snapshot)
     */
    public static synchronized Map<
        String,
        PendingDisambiguation
    > getPendingDisambiguations() {
        Map<String, PendingDisambiguation> snapshot = new LinkedHashMap<>(
            pendingDisambiguations
        );
        logger.info(
            "ShowStore.getPendingDisambiguations: returning " +
                snapshot.size() +
                " pending item(s)"
        );
        return snapshot;
    }

    /**
     * Clear all currently pending disambiguations.
     * Intended to be called after the UI resolves them (or cancels).
     */
    public static synchronized void clearPendingDisambiguations() {
        int before = pendingDisambiguations.size();
        pendingDisambiguations.clear();
        logger.info(
            "ShowStore.clearPendingDisambiguations: cleared " +
                before +
                " pending item(s)"
        );
        if (before > 0) {
            notifyPendingDisambiguationsChanged();
        }
    }

    /**
     * Remove only the specified pending disambiguations (e.g., those the user has resolved),
     * leaving any unresolved pending items queued.
     *
     * @param queryStrings the provider query strings to remove from the pending queue
     */
    public static synchronized void removePendingDisambiguations(
        final java.util.Set<String> queryStrings
    ) {
        if (queryStrings == null || queryStrings.isEmpty()) {
            return;
        }

        int removed = 0;
        for (String q : queryStrings) {
            if (q == null || q.isBlank()) {
                continue;
            }
            if (pendingDisambiguations.remove(q) != null) {
                removed++;
            }
        }

        if (removed > 0) {
            logger.info(
                "ShowStore.removePendingDisambiguations: removed " +
                    removed +
                    " pending item(s)"
            );
            notifyPendingDisambiguationsChanged();
        }
    }

    /**
     * Persist and apply a user-selected disambiguation for a query string.
     *
     * @param queryString normalized provider query string
     * @param chosenId provider series id (e.g., TVDB seriesid)
     */
    public static synchronized void applyShowDisambiguationSelection(
        final String queryString,
        final String chosenId
    ) {
        if (queryString == null || queryString.isBlank()) {
            logger.warning(
                "ShowStore.applyShowDisambiguationSelection: blank queryString"
            );
            return;
        }
        if (chosenId == null || chosenId.isBlank()) {
            logger.warning(
                "ShowStore.applyShowDisambiguationSelection: blank chosenId for queryString=" +
                    queryString
            );
            return;
        }
        Map<String, String> map = new LinkedHashMap<>(
            prefs.getShowDisambiguationOverrides()
        );
        map.put(queryString, chosenId);
        prefs.setShowDisambiguationOverrides(map);
        UserPreferences.store(prefs);
        logger.info(
            "ShowStore.applyShowDisambiguationSelection: stored mapping queryString='" +
                queryString +
                "' -> id='" +
                chosenId +
                "'"
        );
    }

    /**
     * Submits the task to download the information about the ShowName.
     *
     * Makes sure that the task is successfully submitted, and provides the
     * ShowName with an alternate path if anything goes wrong with the task.
     *
     * @param showName
     *    an object containing the part of the filename that is presumed to name
     *    the show, as well as the version of that string we can give the provider
     * @param showFetcher
     *    the task that will download the information
     */
    private static void submitDownloadTask(
        final ShowName showName,
        final Callable<Boolean> showFetcher
    ) {
        Future<Boolean> result = null;
        FailedShow failure = null;

        if (showFetcher == null) {
            logger.warning(
                "unable to submit download task (" +
                    showName +
                    "): showFetcher is null"
            );
            failure = showName.getFailedShow(
                new TVRenamerIOException("showFetcher is null")
            );
        } else if (threadPool == null) {
            logger.warning(
                "unable to submit download task (" +
                    showName +
                    "): threadPool is null"
            );
            failure = showName.getFailedShow(
                new TVRenamerIOException("threadPool is null")
            );
        } else {
            try {
                result = threadPool.submit(showFetcher);
            } catch (RejectedExecutionException e) {
                logger.warning(
                    "unable to submit download task (" +
                        showName +
                        ") for execution"
                );
                failure = showName.getFailedShow(
                    new TVRenamerIOException(e.getMessage())
                );
            }
        }

        if ((result == null) && (failure == null)) {
            logger.warning("not downloading " + showName);
            failure = showName.getFailedShow(null);
        }
        if (failure != null) {
            showName.nameNotFound(failure);
        }
    }

    /**
     * <p>
     * Download the show details if required, otherwise notify listener.
     * </p>
     * <ul>
     * <li>if we have already downloaded the show (the ShowName returns a matched show)
     *     then just notify the listener</li>
     * <li>if we don't have the show, but are in the process of downloading the show
     *     (the show already has listeners) then add the listener to the registration</li>
     * <li>if we don't have the show and aren't downloading, then add the listener and
     *     kick off the download</li>
     * </ul>
     *
     * @param filenameShow
     *            the name of the show as it appears in the filename
     * @param listener
     *            the listener to notify or register
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public static void mapStringToShow(
        String filenameShow,
        ShowInformationListener listener
    ) {
        if (listener == null) {
            logger.warning("cannot look up show without a listener");
            return;
        }
        ShowName showName = ShowName.mapShowName(filenameShow);
        ShowOption showOption = showName.getMatchedShow();

        if (showOption == null) {
            boolean needsDownload;
            // Since "show" is null, we know we haven't downloaded the options for
            // this filenameShow yet; that is, we know we haven't FINISHED doing so.
            // But we might have started.  If the showName already has one or more
            // listeners, that means the download is already underway.
            synchronized (showName) {
                needsDownload = showName.needsQuery();
                // We add this listener whether or not the download has been started.
                showName.addShowInformationListener(listener);
            }
            // Now we start a download only if we need to.
            if (needsDownload) {
                downloadShow(showName);
            }
            // If we've already downloaded the show, we don't need to involve the
            // ShowName at all.  We invoke the listener's callback immediately and
            // directly.  If, in the future, we expand ShowInformationListener so
            // that there is more information to be sent later, we'd want to edit
            // the following clauses to add the listener.
        } else if (showOption.isFailedShow()) {
            listener.downloadFailed(showOption.asFailedShow());
        } else {
            listener.downloadSucceeded(showOption.getShowInstance());
        }
    }

    /**
     * Download information about shows that match the given ShowName, and
     * choose the best option, if one exists.
     *
     * This method is private, because only this class can decide when it is
     * necessary to go to the provider to get information.  We might already
     * have the information.  Callers must go through the public interfaces
     * which check our internal data structures before initiating an call to
     * the provider.
     *
     * Does not return the value.  Spawns a thread to notify all interested
     * listeners after it has an answer.
     *
     * @param showName
     *    an object containing the part of the filename that is presumed to name
     *    the show, as well as the version of that string we can give the provider
     *
     * Returns nothing; but via callback, sends the series from the list which best
     * matches the series information.
     */
    private static void downloadShow(final ShowName showName) {
        Callable<Boolean> showFetcher = () -> {
            ShowOption showOption;
            try {
                TheTVDBProvider.getShowOptions(showName);

                // If the user previously disambiguated this query string, honor it.
                String queryString = showName.getQueryString();
                String preferredId = prefs.resolveDisambiguatedSeriesId(
                    queryString
                );

                // Unified decision: use shared evaluator to decide whether we can auto-resolve
                // or must prompt. This keeps runtime behavior aligned with Preferences validation.
                List<ShowOption> options = showName.getShowOptions();
                ShowSelectionEvaluator.Decision decision =
                    ShowSelectionEvaluator.evaluate(
                        showName.getExampleFilename(),
                        options,
                        preferredId
                    );

                if (decision.isResolved()) {
                    showOption = decision.getChosen();
                } else if (decision.isAmbiguous()) {
                    logger.info(
                        "ShowStore: queuing disambiguation for queryString='" +
                            queryString +
                            "' (options=" +
                            options.size() +
                            ")"
                    );
                    queuePendingDisambiguation(showName, options, decision.getScoredOptions());
                    showOption = showName.getNonCachingFailedShow(
                        new TVRenamerIOException("show selection required")
                    );
                } else {
                    // NOT_FOUND: preserve existing failure behavior.
                    showOption = showName.selectShowOption();
                }
            } catch (DiscontinuedApiException e) {
                showName.apiDiscontinued();
                return false;
            } catch (TVRenamerIOException e) {
                showOption = showName.getFailedShow(e);
            }

            logger.fine(
                "Show options for '" + showOption.getName() + "' downloaded"
            );
            if (showOption.isFailedShow()) {
                showName.nameNotFound(showOption.asFailedShow());
            } else {
                showName.nameResolved(showOption.getShowInstance());
            }
            return true;
        };
        submitDownloadTask(showName, showFetcher);
    }

    /**
     * Add a pending disambiguation entry for this show, keyed by query string.
     *
     * Note: This does not block or show UI. The UI is expected to call
     * {@link #getPendingDisambiguations()} and present a single batch dialog to resolve.
     *
     * @param showName the ShowName being disambiguated
     * @param options the provider candidates
     * @param scoredOptions options with similarity scores (sorted best-first), or null
     */
    private static synchronized void queuePendingDisambiguation(
        final ShowName showName,
        final List<ShowOption> options,
        final List<ShowSelectionEvaluator.ScoredOption> scoredOptions
    ) {
        if (showName == null) {
            logger.warning(
                "ShowStore.queuePendingDisambiguation: showName was null"
            );
            return;
        }
        String queryString = showName.getQueryString();
        if (queryString == null || queryString.isBlank()) {
            logger.warning(
                "ShowStore.queuePendingDisambiguation: blank queryString for extractedShowName='" +
                    showName.getExampleFilename() +
                    "'"
            );
            return;
        }

        // Only record the first occurrence; later occurrences don't add value for UI.
        if (pendingDisambiguations.containsKey(queryString)) {
            logger.info(
                "ShowStore.queuePendingDisambiguation: already queued for queryString='" +
                    queryString +
                    "'"
            );
            return;
        }

        // We do not have direct access to FileEpisode here; keep filename blank for now.
        // The UI layer can enrich this when calling mapStringToShow (by passing an example filename).
        pendingDisambiguations.put(
            queryString,
            new PendingDisambiguation(
                queryString,
                showName.getExampleFilename(),
                "",
                options,
                scoredOptions
            )
        );
        logger.info(
            "ShowStore.queuePendingDisambiguation: queued queryString='" +
                queryString +
                "', extractedShowName='" +
                showName.getExampleFilename() +
                "', options=" +
                ((options == null) ? 0 : options.size())
        );

        // Notify UI so it can open the batch resolve dialog without polling.
        notifyPendingDisambiguationsChanged();
    }

    // Note: one-at-a-time modal prompting has been replaced by a pending disambiguation queue
    // API to support resolving ambiguities in a single batch dialog.

    public static void cleanUp() {
        threadPool.shutdownNow();
    }

    /**
     * Create a show and add it to the store, unless a show is already registered
     * by the show name.<p>
     *
     * Added this distinct method to enable unit testing.  Unlike the "real" method
     * (<code>mapStringToShow</code>), this does not spawn a thread, connect to the internet,
     * or use listeners in any way.  This is just accessing the data store.
     *
     * @param  filenameShow
     *            the show name as it appears in the filename
     * @param  actualName
     *            the proper show name, as it appears in the provider DB
     * @return show
     *            the {@link Show}
     */
    static Show getOrAddShow(String filenameShow, String actualName) {
        ShowName showName = ShowName.mapShowName(filenameShow);
        ShowOption showOption = showName.getMatchedShow();
        if (showOption == null) {
            return new Show(filenameShow, actualName);
        }
        return showOption.getShowInstance();
    }
}
