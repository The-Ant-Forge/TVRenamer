package org.tvrenamer.controller.subtitle;

import java.nio.file.Path;
import java.util.List;

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
    MergeOutcome merge(Path mediaFile, List<SubtitleEntry> subtitles);

    /** Outcome of a merge attempt. */
    enum MergeOutcome {
        SUCCESS,
        FAILED,
        SKIPPED_NO_TOOL,
        SKIPPED_ALREADY_PRESENT
    }
}
