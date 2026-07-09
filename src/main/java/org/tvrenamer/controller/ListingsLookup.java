package org.tvrenamer.controller;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.tvrenamer.model.Series;
import org.tvrenamer.model.TVRenamerIOException;

/**
 * A utility class to help with looking up series listings from the provider.
 * This class does not manage the listeners, or deal with the episodes.
 * It is just for managing threads and communication with the provider.
 */
public class ListingsLookup {

    private static final Logger logger = Logger.getLogger(
        ListingsLookup.class.getName()
    );

    /**
     * A small pool of low-priority threads to execute the listings lookups.
     * Bounded (matching ShowStore's pool size) so adding a large folder
     * doesn't spawn one simultaneous provider fetch per unique series —
     * an unbounded cached pool hammered the provider (rate-limit exposure)
     * with arbitrary thread counts.
     */
    private static final ExecutorService THREAD_POOL =
        Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            return t;
        });

    /**
     * Spawn a thread to ask the provider to look up the listings for the given Series.
     *
     * This is public so it can be called from the Series class.  No one else should
     * call it.  Other classes which are interested in series listings should call
     * addListener() on the Series itself.
     *
     * @param series
     *           the series to download listings for
     */
    public static void downloadListings(final Series series) {
        if (series == null) {
            logger.warning("downloadListings called with null Series");
            return;
        }
        if (!series.beginDownload()) {
            logger.warning(
                "should not call downloadListings; Series is already download[ing/ed]."
            );
            return;
        }
        Callable<Boolean> listingsFetcher = () -> {
            try {
                TheTVDBProvider.getSeriesListing(series);
                return true;
            } catch (TVRenamerIOException e) {
                series.listingsFailed(e);
                return false;
            } catch (Exception e) {
                // Because this is running in a separate thread, an uncaught
                // exception does not get caught by the main thread, and
                // prevents this thread from dying.  Try to make sure that the
                // thread dies, one way or another.
                logger.log(
                    Level.WARNING,
                    "generic exception doing getListings for " + series,
                    e
                );
                series.listingsFailed(e);
                return false;
            }
        };
        try {
            Future<Boolean> future = THREAD_POOL.submit(listingsFetcher);
            logger.log(Level.FINE, () -> "successfully submitted task " + future);
        } catch (RejectedExecutionException e) {
            String name;
            try {
                name = series.getName();
            } catch (Exception ignored) {
                name = "<unknown>";
            }
            logger.log(
                Level.WARNING,
                "unable to submit listings download task (" +
                    name +
                    ") for execution",
                e
            );
        }
    }

    /**
     * Kill any threads that might be running, so the program can shut down.
     *
     */
    public static void cleanUp() {
        THREAD_POOL.shutdownNow();
    }

    /**
     * This is a utility class; prevent it from being instantiated.
     *
     */
    private ListingsLookup() {}
}
