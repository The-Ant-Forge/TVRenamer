package org.tvrenamer.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit-based progress accounting for an entire rename action.
 *
 * <p>The total work is a sum of discrete operations across all files in the
 * batch — typically one tick per file move, one per metadata-tagging op,
 * and one per subtitle merge.  Callers register the predicted total at
 * construction; each completed op calls {@link #tick}; the UI listens for
 * changes via {@link #setProgressListener} and updates the progress bar
 * proportionally.
 *
 * <p>Initial predictions are optimistic — for the merge phase we don't know
 * the actual candidate count until after all moves complete.  When the
 * actual count is known (or any other op turns out not to fire),
 * {@link #retract} reduces the total so the bar can still reach 100% even
 * when fewer ops than predicted actually run.
 *
 * <p>Thread-safe: ticks and retractions can be called from any thread.
 * The progress listener is invoked synchronously after each change; the UI
 * implementation marshals back to the SWT thread.
 */
public final class WorkPlan {

    private final AtomicInteger totalUnits;
    private final AtomicInteger completedUnits = new AtomicInteger(0);
    private volatile Runnable progressListener;

    public WorkPlan(int initialTotalUnits) {
        this.totalUnits = new AtomicInteger(Math.max(0, initialTotalUnits));
    }

    /**
     * Register a callback invoked after every {@link #tick} or
     * {@link #retract}.  The callback runs on the producer thread; UI
     * implementations should marshal to their own thread inside it.
     */
    public void setProgressListener(Runnable listener) {
        this.progressListener = listener;
    }

    /** Increment completed-units by one and notify. */
    public void tick() {
        completedUnits.incrementAndGet();
        notifyListener();
    }

    /**
     * Reduce the predicted total by {@code n} units.  Used when an op that
     * was predicted at start turns out not to run (e.g. the actual subtitle
     * merge candidate count is lower than the optimistic estimate).
     */
    public void retract(int n) {
        if (n <= 0) {
            return;
        }
        // Floor at zero — never let total go negative.
        totalUnits.updateAndGet(prev -> Math.max(0, prev - n));
        notifyListener();
    }

    /** @return number of units completed so far. */
    public int getCompleted() {
        return completedUnits.get();
    }

    /** @return current predicted total (may shrink via {@link #retract}). */
    public int getTotal() {
        return totalUnits.get();
    }

    /**
     * Force the bar to 100% — used at end-of-batch to absorb any rounding
     * mismatch between predicted and actual ops without leaving the bar
     * sitting at 99%.
     */
    public void completeAll() {
        completedUnits.set(totalUnits.get());
        notifyListener();
    }

    private void notifyListener() {
        Runnable r = progressListener;
        if (r != null) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // listener errors must not propagate into the producer thread
            }
        }
    }
}
