package org.tvrenamer.controller.subtitle;

import java.nio.file.Path;

/**
 * Receives progress events while {@link SubtitleMergeController} processes
 * the post-batch subtitle merge.
 *
 * <p>Consumed by the UI to surface merge progress in the row statuses and
 * the bottom status label.  All callbacks are fired from a non-UI worker
 * thread; implementations must marshal back to the UI thread themselves.
 */
public interface SubtitleMergeProgressListener {

    /**
     * Called once before the post-batch merge begins, with the total number
     * of candidate media files that will be visited.  Implementations
     * typically use this to push a "Merging subtitles..." status message.
     */
    void subtitleMergeStarted(int totalCandidates);

    /**
     * Called immediately before {@link SubtitleMergeController#mergeIfEnabled}
     * is invoked for each candidate file.  Files that turn out to have no
     * paired subtitles still trigger this callback — the UI may use that to
     * indicate transient activity, or ignore it.
     */
    void subtitleMergeFileStarted(Path mediaFile);

    /**
     * Per-file progress (0–100) while the merger tool is running.  Called
     * potentially many times between {@link #subtitleMergeFileStarted} and
     * {@link #subtitleMergeFileFinished} as parseable progress lines arrive
     * from the tool's output.  The percentage is already clamped.
     *
     * <p>Default no-op for listeners that don't render per-file progress.
     */
    default void subtitleMergeFileProgress(Path mediaFile, int percent) {
        // default no-op
    }

    /**
     * Called after each merge attempt with the controller's outcome.
     */
    void subtitleMergeFileFinished(Path mediaFile, SubtitleMergeController.Result result);

    /**
     * Called once after every candidate has been processed.  The counts
     * partition the visited files by outcome so the UI can summarise.
     */
    void subtitleMergeFinished(int merged, int skipped, int failed);
}
