package org.tvrenamer.controller.subtitle;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.tvrenamer.controller.subtitle.SubtitleMerger.MergeOutcome;
import org.tvrenamer.controller.util.StringUtils;
import org.tvrenamer.model.FileEpisode;
import org.tvrenamer.model.UserPreferences;

/**
 * Coordinates subtitle merging for a renamed media file.
 *
 * <p>Mirrors the shape of {@link org.tvrenamer.controller.metadata.MetadataTaggingController}:
 * one orchestrator that picks the right per-format strategy based on the container
 * extension, surfaces a small, well-defined {@link Result} set to its caller, and
 * exposes a tool-detection summary for the Preferences UI.
 *
 * <p>The controller never deletes sibling subtitle files. Per the spec, deletion
 * is handled by {@code FileMover} only after the entire move pipeline has
 * succeeded — otherwise a successful merge followed by a failed move would lose
 * the user's only copy of the subtitle file.
 */
public final class SubtitleMergeController {

    /** Outcome of a single {@link #mergeIfEnabled(Path, FileEpisode)} call. */
    public enum Result {
        /** Merge succeeded for at least one subtitle. */
        SUCCESS,
        /** {@link UserPreferences#isMergeSubtitles()} returned false; nothing attempted. */
        DISABLED,
        /** No paired subtitle siblings found, or none in a format the merger supports. */
        NO_SUBTITLES_FOUND,
        /** No tool is available for this container family. */
        NO_TOOL,
        /** Container already contains all the languages we'd otherwise merge. */
        ALREADY_HAS_LANGUAGE,
        /** No merger handles this container extension (e.g. {@code .avi}). */
        UNSUPPORTED,
        /** Tool was found and invoked but the merge itself failed. */
        FAILED;

        /** @return true if the operation succeeded or was intentionally skipped. */
        public boolean isOk() {
            return this != FAILED;
        }
    }

    private static final Logger logger = Logger.getLogger(SubtitleMergeController.class.getName());

    /** Once-per-session log gate for an MP4 family tool that's missing. */
    private static final AtomicBoolean MP4_NO_TOOL_LOGGED = new AtomicBoolean(false);

    /** Once-per-session log gate for an MKV family tool that's missing. */
    private static final AtomicBoolean MKV_NO_TOOL_LOGGED = new AtomicBoolean(false);

    /** Stale temp files older than this are removed by the pre-merge cleanup pass. */
    private static final Duration STALE_TEMP_AGE = Duration.ofHours(1);

    /** Sibling temp files have the form {@code <base>.merging.<ext>} (case-insensitive). */
    private static final String MERGING_INFIX = ".merging";

    private final List<SubtitleMerger> mergers;
    private final UserPreferences userPrefs;

    /** Production constructor — wires up MP4 and MKV mergers. */
    public SubtitleMergeController() {
        this(List.of(new Mp4SubtitleMerger(), new MkvSubtitleMerger()),
             UserPreferences.getInstance());
    }

    /** Test-friendly constructor — supplies fake mergers, default preferences. */
    SubtitleMergeController(List<SubtitleMerger> mergers) {
        this(mergers, UserPreferences.getInstance());
    }

    /** Test-friendly constructor — supplies fake mergers AND a fake preferences instance. */
    SubtitleMergeController(List<SubtitleMerger> mergers, UserPreferences prefsForTest) {
        this.mergers = List.copyOf(mergers);
        this.userPrefs = prefsForTest;
    }

    /**
     * Run subtitle merging for the given media file if enabled in preferences.
     *
     * @param mediaFile the media file (the source of the rename, before any move)
     * @param episode   the episode metadata source — currently unused but
     *                  reserved for future per-show overrides
     * @return a {@link Result} describing what happened
     */
    public Result mergeIfEnabled(Path mediaFile, FileEpisode episode) {
        return mergeIfEnabled(mediaFile, episode, p -> { /* no-op */ });
    }

    /**
     * Same as {@link #mergeIfEnabled(Path, FileEpisode)} but also forwards
     * per-percentage progress ticks to the supplied consumer while the
     * underlying merge tool is running.  Used by callers that want to
     * surface live progress in the UI.
     */
    public Result mergeIfEnabled(
            Path mediaFile,
            FileEpisode episode,
            java.util.function.IntConsumer onProgress) {
        if (!userPrefs.isMergeSubtitles()) {
            return Result.DISABLED;
        }
        if (mediaFile == null) {
            return Result.UNSUPPORTED;
        }

        String filename = mediaFile.getFileName() == null
            ? ""
            : mediaFile.getFileName().toString();
        String containerExt = StringUtils.getExtension(filename);

        SubtitleMerger merger = pickMerger(containerExt);
        if (merger == null) {
            final String extForLog = containerExt;
            logger.log(Level.FINE, () -> "No subtitle merger supports extension " + extForLog);
            return Result.UNSUPPORTED;
        }

        if (!merger.isToolAvailable()) {
            AtomicBoolean gate = gateFor(merger);
            if (gate != null && gate.compareAndSet(false, true)) {
                logger.log(Level.INFO,
                    "{0} not found on PATH; subtitle merging is disabled for {1} files this session.",
                    new Object[] { merger.getToolName(), containerExt });
            }
            return Result.NO_TOOL;
        }

        // Best-effort pre-merge cleanup of orphaned <base>.merging.<ext> temp files
        // left behind by a previous crashed/aborted run.
        cleanStaleTempFiles(mediaFile, containerExt);

        List<SubtitleEntry> all;
        try {
            all = SubtitlePairing.findFor(mediaFile, userPrefs.getDefaultSubtitleLanguage());
        } catch (RuntimeException re) {
            logger.log(Level.WARNING, "Failed to locate sibling subtitles for " + mediaFile, re);
            return Result.FAILED;
        }
        if (all == null || all.isEmpty()) {
            return Result.NO_SUBTITLES_FOUND;
        }

        // Filter out subtitles whose extension this merger doesn't support
        // (e.g. ASS/SSA into an MP4 container). Each rejection logs a WARNING.
        List<SubtitleEntry> supported = new ArrayList<>(all.size());
        for (SubtitleEntry entry : all) {
            String subExt = StringUtils.getExtension(entry.file().getFileName().toString());
            if (merger.supportsSubtitleExtension(subExt)) {
                supported.add(entry);
            } else {
                logger.warning(entry.file().getFileName()
                    + " cannot be muxed into " + containerExt
                    + " (" + merger.getToolName() + " does not support this subtitle format);"
                    + " skipping");
            }
        }
        if (supported.isEmpty()) {
            return Result.NO_SUBTITLES_FOUND;
        }

        // Idempotency: ask the merger which languages are already present. We
        // query each unique language once even if multiple files share it.
        Set<String> uniqueLanguages = new LinkedHashSet<>();
        for (SubtitleEntry entry : supported) {
            String lang = entry.langCode3();
            if (lang != null && !lang.isEmpty()) {
                uniqueLanguages.add(lang.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> existingLanguages = new LinkedHashSet<>();
        for (String lang : uniqueLanguages) {
            try {
                if (merger.alreadyHasLanguageTrack(mediaFile, lang)) {
                    existingLanguages.add(lang);
                }
            } catch (RuntimeException re) {
                logger.log(Level.FINE,
                    "alreadyHasLanguageTrack threw for " + mediaFile + " / " + lang, re);
            }
        }

        List<SubtitleEntry> toMerge = new ArrayList<>(supported.size());
        for (SubtitleEntry entry : supported) {
            String lang = entry.langCode3() == null
                ? ""
                : entry.langCode3().toLowerCase(Locale.ROOT);
            if (!lang.isEmpty() && existingLanguages.contains(lang)) {
                logger.info("media " + mediaFile + " already has " + lang
                    + " subtitle track; skipping " + entry.file().getFileName());
            } else {
                toMerge.add(entry);
            }
        }
        if (toMerge.isEmpty()) {
            return Result.ALREADY_HAS_LANGUAGE;
        }

        MergeOutcome outcome;
        try {
            outcome = merger.merge(mediaFile, toMerge,
                onProgress == null ? p -> { /* no-op */ } : onProgress);
        } catch (RuntimeException re) {
            logger.log(Level.WARNING,
                "Unexpected exception from " + merger.getToolName()
                    + " merging subtitles into " + mediaFile, re);
            return Result.FAILED;
        }
        if (outcome == null) {
            return Result.FAILED;
        }
        return switch (outcome) {
            case SUCCESS -> {
                logger.info("Merged " + toMerge.size() + " subtitle track(s) into " + mediaFile);
                yield Result.SUCCESS;
            }
            // Tool became unavailable between our check and the merger's; treat as NO_TOOL.
            case SKIPPED_NO_TOOL -> Result.NO_TOOL;
            // Already logged by the merger; just propagate the failure.
            case FAILED -> Result.FAILED;
        };
    }

    /**
     * Build a status string mirroring the Preferences-dialog mock-up in the spec, e.g.
     * {@code "MP4Box: detected · mkvmerge: detected"}.  Each merger contributes one
     * entry separated by {@code " · "} (U+00B7).
     *
     * @return human-readable per-tool detection summary
     */
    public String getToolSummary() {
        StringJoiner sj = new StringJoiner(" · ");
        for (SubtitleMerger merger : mergers) {
            String state = merger.isToolAvailable() ? "detected" : "not found";
            sj.add(merger.getToolName() + ": " + state);
        }
        return sj.toString();
    }

    /**
     * @return true if at least one of the configured mergers reports its tool
     *         as available on this system.
     */
    public boolean isAnyToolAvailable() {
        for (SubtitleMerger merger : mergers) {
            if (merger.isToolAvailable()) {
                return true;
            }
        }
        return false;
    }

    // ---- internals ----

    private SubtitleMerger pickMerger(String containerExtension) {
        if (containerExtension == null || containerExtension.isEmpty()) {
            return null;
        }
        for (SubtitleMerger merger : mergers) {
            if (merger.supportsContainerExtension(containerExtension)) {
                return merger;
            }
        }
        return null;
    }

    /**
     * Pick the once-per-session log gate keyed off the merger's tool name.
     * Returns {@code null} for unrecognised tools so the warning still fires
     * every call (defensive — wouldn't happen with the built-in mergers).
     */
    private static AtomicBoolean gateFor(SubtitleMerger merger) {
        String name = merger.getToolName();
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("mp4")) {
            return MP4_NO_TOOL_LOGGED;
        }
        if (lower.contains("mkv")) {
            return MKV_NO_TOOL_LOGGED;
        }
        return null;
    }

    /**
     * Visible for tests: reset the once-per-session log gates so a subsequent
     * NO_TOOL invocation will log again.
     */
    static void resetSessionLogGatesForTesting() {
        MP4_NO_TOOL_LOGGED.set(false);
        MKV_NO_TOOL_LOGGED.set(false);
    }

    /**
     * Best-effort cleanup of orphaned {@code <base>.merging.<ext>} temp files in
     * the same directory as {@code mediaFile} that are older than
     * {@link #STALE_TEMP_AGE}.  Failures are logged at FINE/WARNING and never
     * abort the merge.
     */
    private static void cleanStaleTempFiles(Path mediaFile, String containerExt) {
        Path parent = mediaFile.getParent();
        if (parent == null || containerExt == null || containerExt.isEmpty()) {
            return;
        }
        String filename = mediaFile.getFileName() == null
            ? ""
            : mediaFile.getFileName().toString();
        String base = StringUtils.getBaseName(filename);
        if (base == null || base.isEmpty()) {
            return;
        }

        // Match either "<base>.merging.<ext>" (Mp4SubtitleMerger style) or
        // "<filename>.merging.<ext>" (current MkvSubtitleMerger style) in case
        // the latter ever leaves temps behind. Both share the .merging. infix.
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put((base + MERGING_INFIX + containerExt).toLowerCase(Locale.ROOT), "");
        candidates.put((filename + MERGING_INFIX + containerExt).toLowerCase(Locale.ROOT), "");

        Instant cutoff = Instant.now().minus(STALE_TEMP_AGE);

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                Path entryName = entry.getFileName();
                if (entryName == null) {
                    continue;
                }
                String nameLower = entryName.toString().toLowerCase(Locale.ROOT);
                if (!candidates.containsKey(nameLower)) {
                    continue;
                }
                FileTime mtime;
                try {
                    mtime = Files.getLastModifiedTime(entry);
                } catch (IOException ioe) {
                    logger.log(Level.FINE, "Cannot stat candidate stale temp " + entry, ioe);
                    continue;
                }
                if (mtime.toInstant().isAfter(cutoff)) {
                    // Younger than the cutoff — leave it alone, it might belong
                    // to a sibling rename in flight.
                    continue;
                }
                try {
                    Files.deleteIfExists(entry);
                    logger.log(Level.FINE, () -> "Deleted stale subtitle merge temp: " + entry);
                } catch (IOException ioe) {
                    logger.log(Level.WARNING, "Could not delete stale temp " + entry, ioe);
                }
            }
        } catch (IOException ioe) {
            logger.log(Level.FINE,
                "Could not scan " + parent + " for stale subtitle temp files", ioe);
        }
    }
}
