package org.tvrenamer.controller.subtitle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.tvrenamer.controller.util.ProcessOps;
import org.tvrenamer.controller.util.ProcessRunner;

/**
 * Template for the per-format subtitle mergers: owns the shared merge
 * skeleton (temp path → pre-flight cleanup → size/timeout → run tool →
 * failure logging → integrity gate → atomic swap) that was previously
 * duplicated, with drift, across {@link Mp4SubtitleMerger} and
 * {@link MkvSubtitleMerger} (Round-4 #24).
 *
 * <p>Subclasses supply the format specifics: tool detection, the command
 * line, and the progress-line parser.  Process spawning goes through the
 * constructor-injected {@link ProcessOps} pair so tests inject fakes.
 */
abstract class AbstractSubtitleMerger implements SubtitleMerger {

    private static final Logger logger =
        Logger.getLogger(AbstractSubtitleMerger.class.getName());

    /** Maximum number of trailing characters of process output to log on failure. */
    private static final int FAILURE_OUTPUT_TAIL = 1000;

    private final ProcessOps.Run runOp;
    private final ProcessOps.Streaming streamingOp;

    protected AbstractSubtitleMerger(ProcessOps.Run runOp, ProcessOps.Streaming streamingOp) {
        if (runOp == null || streamingOp == null) {
            throw new IllegalArgumentException("ProcessOps must not be null");
        }
        this.runOp = runOp;
        this.streamingOp = streamingOp;
    }

    /** The non-streaming process op, for subclass use (e.g. identify calls). */
    protected final ProcessOps.Run runOp() {
        return runOp;
    }

    /** @return whether the external tool was detected (probing lazily). */
    protected abstract boolean toolDetected();

    /**
     * Build the full merge command line.
     *
     * @param mediaFile the source media file
     * @param temp      the sibling temp file to write merged output to
     * @param subtitles the subtitle entries to mux in
     * @param streaming whether the caller wants progress (subclasses may add
     *                  tool flags like mkvmerge's {@code --gui-mode})
     */
    protected abstract List<String> buildMergeCommand(
        Path mediaFile, Path temp, List<SubtitleEntry> subtitles, boolean streaming);

    /**
     * Parse a progress percentage from one line of tool output.
     *
     * @return the clamped percentage [0, 100], or -1 for non-progress lines
     */
    protected abstract int parseProgress(String line);

    @Override
    public final MergeOutcome merge(
            Path mediaFile,
            List<SubtitleEntry> subtitles,
            IntConsumer onProgress) {
        if (subtitles == null || subtitles.isEmpty()) {
            // Vacuously successful: nothing to do.
            return MergeOutcome.SUCCESS;
        }
        if (!toolDetected()) {
            return MergeOutcome.SKIPPED_NO_TOOL;
        }
        if (mediaFile == null) {
            return MergeOutcome.FAILED;
        }

        Path temp = SubtitleSwap.computeTempPath(mediaFile);

        // Pre-flight: remove any leftover from a prior crashed run so the
        // merge doesn't fight an existing file.  Best-effort.
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ioe) {
            logger.log(Level.FINE, "Could not remove stale temp before merge: " + temp, ioe);
        }

        long sourceBytes;
        try {
            sourceBytes = Files.size(mediaFile);
        } catch (IOException ioe) {
            logger.log(Level.WARNING, "Cannot size media file " + mediaFile, ioe);
            return MergeOutcome.FAILED;
        }
        int timeoutSeconds = SubtitleSwap.computeTimeoutSeconds(sourceBytes);

        boolean streaming = onProgress != null;
        List<String> cmd = buildMergeCommand(mediaFile, temp, subtitles, streaming);

        // When no progress consumer is requested we use the non-streaming
        // path — preserves pre-progress-feature behaviour and matches what
        // non-streaming test fakes inject.
        Consumer<String> lineSink = !streaming
            ? null
            : line -> {
                int pct = parseProgress(line);
                if (pct >= 0) {
                    onProgress.accept(pct);
                }
            };

        ProcessRunner.Result result;
        try {
            result = (lineSink == null)
                ? runOp.run(cmd, timeoutSeconds)
                : streamingOp.run(cmd, timeoutSeconds, lineSink);
        } catch (RuntimeException re) {
            logger.log(Level.WARNING,
                getToolName() + " threw while merging " + mediaFile, re);
            deleteQuietly(temp);
            return MergeOutcome.FAILED;
        }

        if (result == null || !result.success()) {
            int exit = (result == null) ? -1 : result.exitCode();
            String tail = (result == null) ? "" : tailOf(result.output());
            logger.warning(getToolName() + " failed (exit " + exit + ") merging " + mediaFile
                + (tail.isEmpty() ? "" : "\nOutput tail: " + tail));
            deleteQuietly(temp);
            return MergeOutcome.FAILED;
        }

        boolean intact;
        try {
            intact = SubtitleSwap.integrityGate(temp, mediaFile);
        } catch (IOException ioe) {
            logger.log(Level.WARNING, "Integrity gate failed for " + mediaFile, ioe);
            deleteQuietly(temp);
            return MergeOutcome.FAILED;
        }
        if (!intact) {
            logger.warning("Integrity gate rejected merged temp for " + mediaFile
                + "; deleting temp " + temp);
            deleteQuietly(temp);
            return MergeOutcome.FAILED;
        }

        if (!SubtitleSwap.swap(temp, mediaFile)) {
            // SubtitleSwap preserves temp on retry exhaustion so the user can recover.
            logger.warning("Swap failed merging subtitles into " + mediaFile
                + "; merged temp preserved at " + temp + " for manual recovery");
            return MergeOutcome.FAILED;
        }

        int trackCount = subtitles.size();
        logger.info("Merged " + trackCount + " subtitle track"
            + (trackCount == 1 ? "" : "s") + " into " + mediaFile);
        return MergeOutcome.SUCCESS;
    }

    private static String tailOf(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.length() <= FAILURE_OUTPUT_TAIL) {
            return s;
        }
        return s.substring(s.length() - FAILURE_OUTPUT_TAIL);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
