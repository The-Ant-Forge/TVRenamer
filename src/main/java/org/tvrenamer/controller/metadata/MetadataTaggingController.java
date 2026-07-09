package org.tvrenamer.controller.metadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.tvrenamer.controller.util.StringUtils;
import org.tvrenamer.model.FileEpisode;
import org.tvrenamer.model.UserPreferences;

/**
 * Coordinates metadata tagging for video files.
 * Selects appropriate tagger based on file extension.
 */
public class MetadataTaggingController {

    private static final Logger logger = Logger.getLogger(MetadataTaggingController.class.getName());
    private static final UserPreferences userPrefs = UserPreferences.getInstance();

    /** Result of a tagging attempt. */
    public enum TaggingResult {
        /** Tagging succeeded. */
        SUCCESS,
        /** Tagging is disabled in preferences. */
        DISABLED,
        /** No tagger supports this file format. */
        UNSUPPORTED,
        /** The format is supported but the external tool is not installed. */
        NO_TOOL,
        /** Tagging was attempted but failed. */
        FAILED;

        /** @return true if tagging succeeded or was intentionally skipped. */
        public boolean isOk() {
            return this != FAILED;
        }
    }

    /** Once-per-session gate for the missing-tool notice (mirrors the subtitle controller). */
    private static final java.util.concurrent.atomic.AtomicBoolean noToolLogged =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private final List<VideoMetadataTagger> taggers;

    /**
     * Creates a new MetadataTaggingController with all available taggers.
     */
    public MetadataTaggingController() {
        taggers = new ArrayList<>();
        taggers.add(new Mp4MetadataTagger());
        taggers.add(new MkvMetadataTagger());  // Requires mkvpropedit; skips if not found
    }

    /**
     * Tag a video file with episode metadata if enabled and supported.
     *
     * @param videoFile the file to tag
     * @param episode the episode metadata source
     * @return a {@link TaggingResult} indicating what happened
     */
    public TaggingResult tagIfEnabled(Path videoFile, FileEpisode episode) {
        if (!userPrefs.isTagVideoMetadata()) {
            logger.fine("Metadata tagging is disabled");
            return TaggingResult.DISABLED;
        }

        String filename = videoFile.getFileName().toString();
        String extension = StringUtils.getExtension(filename);

        for (VideoMetadataTagger tagger : taggers) {
            if (tagger.supportsExtension(extension)) {
                // Distinguish "tool missing" from success: taggers themselves
                // return true for a missing-tool skip ("not an error"), which
                // the boolean mapping below would report as SUCCESS — files
                // silently went untagged with no diagnosable signal.
                if (!tagger.isToolAvailable()) {
                    if (noToolLogged.compareAndSet(false, true)) {
                        logger.info(tagger.getToolName()
                            + " not found; metadata tagging is skipped for "
                            + extension + " files this session");
                    }
                    return TaggingResult.NO_TOOL;
                }
                logger.log(Level.FINE, () -> "Tagging " + filename + " with " + tagger.getClass().getSimpleName());
                try {
                    return tagger.tagFile(videoFile, episode)
                        ? TaggingResult.SUCCESS
                        : TaggingResult.FAILED;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Exception during metadata tagging for: " + videoFile, e);
                    return TaggingResult.FAILED;
                }
            }
        }

        // No tagger for this format - not an error
        logger.log(Level.FINE, () -> "No metadata tagger available for extension: " + extension);
        return TaggingResult.UNSUPPORTED;
    }

    /**
     * Get a summary of available tagging tools, for display in UI.
     * Example: "MP4: AtomicParsley, MKV: mkvpropedit"
     *
     * @return human-readable tool summary
     */
    public String getToolSummary() {
        StringJoiner sj = new StringJoiner(", ");
        for (VideoMetadataTagger tagger : taggers) {
            if (tagger.isToolAvailable()) {
                sj.add(tagger.getToolName());
            }
        }
        return sj.length() == 0 ? "No tagging tools found" : sj.toString();
    }

    /**
     * Check if any tagging tool is available on this system.
     *
     * @return true if at least one tagger has its external tool installed
     */
    public boolean isAnyToolAvailable() {
        for (VideoMetadataTagger tagger : taggers) {
            if (tagger.isToolAvailable()) {
                return true;
            }
        }
        return false;
    }
}
