package org.tvrenamer.controller.subtitle;

import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Mux soft subtitle tracks into a media container.
 *
 * <p>One implementation per container family (MP4, MKV).  Implementations
 * delegate process spawning to {@link org.tvrenamer.controller.util.ProcessRunner}
 * and the temp-file swap to {@link SubtitleSwap}.
 */
public interface SubtitleMerger {

    /** @return true if this merger handles the given container extension (with leading dot). */
    boolean supportsContainerExtension(String containerExtension);

    /** @return true if the given subtitle extension can be muxed into this container. */
    boolean supportsSubtitleExtension(String subtitleExtension);

    /** @return true if the external tool this merger relies on is available on PATH. */
    boolean isToolAvailable();

    /** @return human-readable name of the external tool, for status messages. */
    String getToolName();

    /**
     * Inspect the media container and report whether it already contains a
     * subtitle track in the given language (3-letter B-form code).
     *
     * <p>Used by the controller to enforce idempotency — re-running the
     * rename pipeline must not duplicate existing language tracks.
     */
    boolean alreadyHasLanguageTrack(Path mediaFile, String langCode3);

    /**
     * Mux the supplied subtitle entries into the media file in place.
     *
     * <p>Implementations write to a sibling temp file, run an integrity gate,
     * then atomically swap.  On failure the original media file is left
     * untouched.
     */
    default MergeOutcome merge(Path mediaFile, List<SubtitleEntry> subtitles) {
        // null progress consumer is the signal for "no progress wanted" — the
        // merger then takes the non-streaming code path, matching the
        // pre-progress-feature behaviour exactly.  Test fakes only need to
        // implement the non-streaming process indirection.
        return merge(mediaFile, subtitles, null);
    }

    /**
     * Mux the supplied subtitle entries into the media file in place,
     * streaming progress updates (0–100) to the given consumer as the
     * underlying tool emits parseable progress lines.  Implementations
     * fall back to silent (no progress) if the tool doesn't expose
     * progress in its current configuration.
     *
     * <p>The consumer is invoked on the merger's worker thread (the same
     * thread that called {@code merge}); UI implementations must marshal
     * to the SWT thread inside the consumer.  Updates are not guaranteed
     * monotonic — clamp to {@code [0, 100]} on consume.
     */
    MergeOutcome merge(
            Path mediaFile,
            List<SubtitleEntry> subtitles,
            IntConsumer onProgress);

    /** Outcome of a merge attempt. */
    enum MergeOutcome {
        SUCCESS,
        FAILED,
        SKIPPED_NO_TOOL,
        SKIPPED_ALREADY_PRESENT
    }
}
