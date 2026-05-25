package org.tvrenamer.controller;

import static org.tvrenamer.model.util.Constants.*;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.tvrenamer.controller.subtitle.SubtitleEntry;
import org.tvrenamer.controller.subtitle.SubtitleMergeController;
import org.tvrenamer.controller.subtitle.SubtitleMergeProgressListener;
import org.tvrenamer.controller.subtitle.SubtitleMerger;
import org.tvrenamer.controller.subtitle.SubtitlePairing;
import org.tvrenamer.controller.util.FileUtilities;
import org.tvrenamer.controller.util.StringUtils;
import org.tvrenamer.model.ProgressUpdater;
import org.tvrenamer.model.UserPreferences;

public class MoveRunner implements Runnable {

    private static final Logger logger = Logger.getLogger(
        MoveRunner.class.getName()
    );

    private static final int DEFAULT_TIMEOUT = 120;

    // Using a single-thread executor is intentional: moves are mostly IO-bound and we prefer correctness
    // and predictable ordering over throughput.
    private static final ExecutorService EXECUTOR =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, FILE_MOVE_THREAD_LABEL);
            t.setDaemon(true);
            return t;
        });

    private final Thread progressThread = new Thread(
        this,
        FILE_MOVE_THREAD_LABEL
    );
    private final Queue<Future<Boolean>> futures = new LinkedList<>();
    private final List<FileMover> movers = new LinkedList<>();
    private final int numMoves;
    private final int timeout;
    private ProgressUpdater updater = null;
    private SubtitleMergeProgressListener subtitleListener = null;
    private org.tvrenamer.model.WorkPlan workPlan = null;
    /** Number of merge units predicted at start; reconciled when the real count is known. */
    private int predictedMergeUnits = 0;
    /**
     * Destination paths of media files whose subtitle merge was completed
     * source-side.  Used by the post-batch step to skip files that have
     * already been merged, avoiding double-counted WorkPlan ticks and
     * redundant idempotency-check process spawns.
     */
    private final Set<Path> sourceMergedDestinations =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile boolean shutdownRequested = false;

    // Aggregated duplicate files found after all moves complete.
    private final List<Path> aggregatedDuplicates = new LinkedList<>();

    /**
     * Does the activity of the thread, which is to dequeue a move task, and block
     * until it returns, then update the progress bar and repeat the whole thing,
     * until the queue is empty.
     */
    @Override
    public void run() {
        try {
            while (!shutdownRequested) {
                int remaining = futures.size();
                if (updater != null) {
                    updater.setProgress(numMoves, remaining);
                }

                if (remaining == 0) {
                    // Run subtitle merging now that every move (media + subtitle
                    // siblings alike) has been canonicalised.  Pairing scans the
                    // destination directories so a renamed .srt aligns with its
                    // renamed media by base name.
                    runPostBatchSubtitleMerge();
                    // Aggregate duplicates found by all movers before finishing.
                    aggregateDuplicates();
                    // Force the unified progress bar to 100% to absorb any
                    // mismatch between predicted and actual op counts.
                    if (workPlan != null) {
                        workPlan.completeAll();
                    }
                    if (updater != null) {
                        updater.finish();
                    }
                    return;
                }

                final Future<Boolean> future = futures.remove();
                try {
                    Boolean success = future.get(timeout, TimeUnit.SECONDS);
                    logger.finer("move task returned: " + success);
                } catch (InterruptedException ie) {
                    // Preserve interrupt status and stop processing further tasks.
                    Thread.currentThread().interrupt();
                    shutdownRequested = true;
                    future.cancel(true);
                    logger.warning(
                        "move runner interrupted; cancelling remaining tasks"
                    );
                } catch (TimeoutException te) {
                    future.cancel(true);
                    logger.warning(
                        "move task timed out after " +
                            timeout +
                            " seconds; cancelled"
                    );
                } catch (CancellationException ce) {
                    logger.fine("move task cancelled");
                } catch (ExecutionException ee) {
                    // Log the underlying cause for troubleshooting.
                    Throwable cause =
                        ee.getCause() != null ? ee.getCause() : ee;
                    logger.log(
                        Level.WARNING,
                        "exception executing move task",
                        cause
                    );
                }
            }
        } finally {
            if (shutdownRequested) {
                // Best-effort cancellation of anything still queued.
                while (!futures.isEmpty()) {
                    Future<Boolean> f = futures.remove();
                    f.cancel(true);
                }
                if (updater != null) {
                    updater.finish();
                }
            }
        }
    }

    /**
     * Runs the thread for this FileMover, to move all the files.
     *
     * This actually could be done right in the constructor, as that is, in fact, the only
     * way it's only currently used.  But it's nice to let it be more explicit.
     *
     */
    public void runThread() {
        if (!progressThread.isAlive()) {
            progressThread.start();
        }
    }

    /**
     * If the given key is not present in the map, returns a new, empty list.
     * Otherwise, returns the value mapped to the key.
     *
     * @param table
     *    a mapping from a String to a list of FileMovers.  There is no assumption
     *    about the meaning of the String key; it could be anything
     * @param key
     *    the key to look up in the table
     * @return a List of (zero or more) StringMovers that is associated with the key,
     *    in the table.  If no such list existed at the time this method was invoked,
     *    it will be created and the association be made before the value is returned.
     */
    private static List<FileMover> getListValue(
        Map<String, List<FileMover>> table,
        String key
    ) {
        return table.computeIfAbsent(key, ignored -> new LinkedList<>());
    }

    /**
     * Adds an index to files that would otherwise conflict with other files.
     *
     * There are a lot of ways to approach the indexing, as discussed in the
     * doc of resolveConflicts, below; but as a first pass, we:
     * - consider only the filename for a conflict
     * - leave existing files as they are
     * - add indexes to conflicting files in the files we're moving
     *
     * Since, at this point, we are only finding EXACT matches (the filename
     * must be identical), <code>existing</code> will contain at most one
     * element.  It's written this way because in the future, we will be able
     * to find other potentially conflicting files.
     *
     * @param moves the files which we want to move to the destination
     * @param existing the files which are already at the destination, and
     *        which the user has not specifically asked to move
     *
     * Returns nothing; modifies the entries of "moves" in-place
     */
    private static void addIndices(List<FileMover> moves, Set<Path> existing) {
        int index = existing.size();
        moves.sort((m1, m2) -> (int) (m2.getFileSize() - m1.getFileSize()));
        for (FileMover move : moves) {
            index++;
            if (index > 1) {
                move.destIndex = index;
            }
        }
    }

    /**
     * Finds existing conflicts; that is, files that are already in the
     * destination that have an episode which conflicts with one (or
     * more) that we want to move into the destination.
     *
     * It should be noted that we don't expect these conflicts to be
     * common.  Nevertheless, they can happen, and we are prepared to
     * deal with them.
     *
     * @param destDirName
     *    the specific directory into which we'll be moving files
     * @param desiredFilename
     *     the filename to which we'd move the files; this means, the part
     *     of their filepath without the directory
     * So, for example, for "/Users/me/TV/Lost.S06E05.Lighthouse.avi",
     * the filename would be "Lost.S06E05.Lighthouse.avi".
     * @param moves
     *     a list of moves, all of which must have a destination directory
     *     equivalent to destDirName, and all of which must have a source
     *     desiredFilename equal to the given desiredFilename; very often will be a list
     *     with just a single element
     * @return a set of paths that have conflicts; may be empty, and
     *         in fact almost always would be.
     */
    private static Set<Path> existingConflicts(
        String destDirName,
        String desiredFilename,
        List<FileMover> moves
    ) {
        // Since, at this point, we are only finding EXACT matches (the
        // filename must be identical), at most one element will be added
        // to <code>hits</code>.  It's written this way because in the
        // future, we will be able to find other potentially conflicting files.
        Set<Path> hits = new HashSet<>();
        Path destDir = Paths.get(destDirName);
        if (Files.exists(destDir) && Files.isDirectory(destDir)) {
            Path conflict = destDir.resolve(desiredFilename);
            if (Files.exists(conflict)) {
                hits.add(conflict);
            }
        }
        return hits;
    }

    /**
     * Resolves conflicts between episode names
     *
     * There are many different ways of renaming.  Some questions we might
     * deal with in the future:
     * - can we rename the files that are already in the destination?
     * - assuming two files refer to the same episode, is it still a conflict if:
     *   - they are different resolution?
     *   - they are different file formats (e.g., avi, mp4)?
     * - what do we do with identical files?
     *   - could treat as any other "conflict", or move into a special folder
     *   - but if we verify they are byte-for-byte duplicates, really no point
     *   - when we log all moves, for undo-ability, need to keep track of
     *     multiple file names that mapped to the same result
     * - do we prioritize by file type?  file size?  resolution?
     *     source (dvdrip, etc.)?
     * - can we integrate with a library that gives us information about the
     *   content (actual video quality, length, etc.)?
     *
     * @param listOfMoves
     *   a list of FileMover tasks to be done
     * @param destDir
     *   the name of the destination directory
     */
    private static void resolveConflicts(
        List<FileMover> listOfMoves,
        String destDir
    ) {
        // Group by a "base name" so that:
        // - exact filename matches (including extension) still conflict, AND
        // - files that differ only by extension (e.g., .mp4 vs .mkv) are also treated as conflicts.
        //
        // This is a pragmatic step toward "episode identity" conflict detection without requiring
        // metadata inspection. It reduces the chance of silently producing two near-identical
        // destination names with different extensions.
        Map<String, List<FileMover>> desiredBaseNames = new HashMap<>();
        for (FileMover move : listOfMoves) {
            final String desired = move.getDesiredDestName();
            final String base = baseName(desired);
            getListValue(desiredBaseNames, base).add(move);
        }

        for (Map.Entry<
            String,
            List<FileMover>
        > e : desiredBaseNames.entrySet()) {
            String base = e.getKey();
            List<FileMover> moves = e.getValue();

            // Check for any existing files in the destination directory whose base name matches.
            Set<Path> existing = existingConflictsByBaseName(destDir, base);

            // Also check for episode identity matches (fuzzy matching).
            // This catches files like "S01E02" vs "1x02" that have different filenames
            // but represent the same episode.
            if (!moves.isEmpty()) {
                String sampleFilename = moves.get(0).getDesiredDestName();
                int[] seasonEp = FilenameParser.extractSeasonEpisode(sampleFilename);
                if (seasonEp != null) {
                    Set<Path> episodeMatches = existingConflictsByEpisodeIdentity(destDir, seasonEp);
                    // Merge into existing set (avoid duplicates)
                    existing.addAll(episodeMatches);
                }
            }

            int nFiles = existing.size() + moves.size();
            if (nFiles > 1) {
                addIndices(moves, existing);
            }
        }
    }

    private static String baseName(final String filename) {
        if (filename == null) {
            return "";
        }
        return StringUtils.getBaseName(filename);
    }

    /**
     * Find existing files in destination that match the same episode identity
     * (season/episode numbers), regardless of filename format differences.
     *
     * @param destDirName the destination directory
     * @param seasonEp the [season, episode] array to match against
     * @return set of conflicting paths
     */
    private static Set<Path> existingConflictsByEpisodeIdentity(
        final String destDirName,
        final int[] seasonEp
    ) {
        Set<Path> hits = new HashSet<>();
        if (seasonEp == null || seasonEp.length < 2) {
            return hits;
        }

        Path destDir = Paths.get(destDirName);
        if (!Files.exists(destDir) || !Files.isDirectory(destDir)) {
            return hits;
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(destDir)) {
            for (Path p : files) {
                if (p == null || Files.isDirectory(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                int[] existingSeasonEp = FilenameParser.extractSeasonEpisode(name);
                if (existingSeasonEp != null
                    && existingSeasonEp[0] == seasonEp[0]
                    && existingSeasonEp[1] == seasonEp[1]) {
                    hits.add(p);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }

        return hits;
    }

    private static Set<Path> existingConflictsByBaseName(
        final String destDirName,
        final String desiredBaseName
    ) {
        Set<Path> hits = new HashSet<>();
        if (desiredBaseName == null || desiredBaseName.isBlank()) {
            return hits;
        }

        Path destDir = Paths.get(destDirName);
        if (!Files.exists(destDir) || !Files.isDirectory(destDir)) {
            return hits;
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(destDir)) {
            for (Path p : files) {
                if (p == null) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (desiredBaseName.equals(baseName(name))) {
                    hits.add(p);
                }
            }
        } catch (IOException ignored) {
            // best-effort: if we can't scan, fall back to no detected existing conflicts
        }

        return hits;
    }

    /**
     * Turns a flat list of file moves into a hash map keyed on destination directory;
     *
     * @param episodes
     *   a list of FileMovers -- the move tasks to be done
     * @return a mapping from directory names to a list of the FileMovers that will move
     *   files into the directory with that name
     */
    private static Map<String, List<FileMover>> mapByDestDir(
        final List<FileMover> episodes
    ) {
        final Map<String, List<FileMover>> toMove = new HashMap<>();

        for (final FileMover pendingMove : episodes) {
            Path moveToDir = pendingMove.getMoveToDirectory();
            List<FileMover> existingDirMoves = getListValue(
                toMove,
                moveToDir.toString()
            );
            existingDirMoves.add(pendingMove);
        }

        return toMove;
    }

    /**
     * Creates a MoveRunner to move all the episodes in the list, and update the progress
     * bar, using the specified timeout.
     *
     * @param episodes a list of FileMovers to execute
     * @param updater a ProgressUpdater to be informed of our progress
     * @param timeout the number of seconds to allow each FileMover to run, before killing it
     *
     */
    @SuppressWarnings("SameParameterValue")
    private MoveRunner(
        final List<FileMover> episodes,
        final ProgressUpdater updater,
        final int timeout
    ) {
        this.updater = updater;
        this.timeout = timeout;

        // progressThread is already named/daemonized in the field initializer

        // Group by destination directory for conflict resolution.
        final Map<String, List<FileMover>> mappings = mapByDestDir(episodes);
        // Skip conflict resolution if user wants to always overwrite.
        // When overwrite is enabled, FileMover will handle replacing existing files.
        if (!UserPreferences.getInstance().isAlwaysOverwriteDestination()) {
            for (Map.Entry<String, List<FileMover>> e : mappings.entrySet()) {
                resolveConflicts(e.getValue(), e.getKey());
            }
        }

        // Pre-verify each unique destination directory once to avoid redundant probe files.
        // This is an optimization: instead of each FileMover creating a temp probe file,
        // we verify each directory once here and tell FileMover instances to skip the check.
        final Set<Path> verifiedDirectories = new HashSet<>();
        for (String dirName : mappings.keySet()) {
            Path destDir = Paths.get(dirName);
            if (FileUtilities.ensureWritableDirectory(destDir)) {
                verifiedDirectories.add(destDir);
            }
        }

        // Source-side subtitle merge runs FIRST on the same single-thread executor.
        // FIFO ordering guarantees the merge completes before any per-file move
        // starts, so when subtitle FileMovers' .call() runs they observe any
        // consumedByMerge flags set by the merge phase.
        for (FileMover move : episodes) {
            movers.add(move);
        }
        Future<Boolean> mergeFuture = EXECUTOR.submit(this::runSourceSideMerge);
        futures.add(mergeFuture);

        // Submit moves in original list order (table display order, top to bottom).
        // Conflict resolution has already modified the FileMover objects in-place.
        for (FileMover move : episodes) {
            // Mark as pre-verified if we already checked this directory.
            Path moveDestDir = move.getMoveToDirectory();
            if (verifiedDirectories.contains(moveDestDir)) {
                move.setDirectoryPreVerified(true);
            }
            futures.add(EXECUTOR.submit(move));
        }
        numMoves = episodes.size();
        logger.log(Level.FINE, () -> "have " + numMoves + " files to move");
    }

    /**
     * Creates a MoveRunner to move all the episodes in the list, using the default timeout.
     *
     * @param episodes a list of FileMovers to execute
     *
     */
    public MoveRunner(final List<FileMover> episodes) {
        this(episodes, null, DEFAULT_TIMEOUT);
    }

    /**
     * Set the progress updater for this MoveRunner.
     *
     * @param updater a ProgressUpdater to be informed of our progress
     *
     */
    public void setUpdater(final ProgressUpdater updater) {
        this.updater = updater;
    }

    /**
     * Sets the listener notified about post-batch subtitle merge progress.
     * Implementations marshal back to the UI thread to update row icons and
     * the bottom status label.  Optional — {@code null} disables progress
     * reporting (the merge still runs).
     *
     * @param listener the listener to notify, or null to disable
     */
    public void setSubtitleListener(final SubtitleMergeProgressListener listener) {
        this.subtitleListener = listener;
    }

    /**
     * Provide a {@link org.tvrenamer.model.WorkPlan} for unified progress
     * accounting across the whole rename action.  Each FileMover receives
     * the plan; ticks happen on move success, tag completion, and merge
     * completion.  The {@code predictedMergeUnits} parameter is the
     * caller's optimistic estimate of merge candidates (typically the
     * count of media files in the batch); MoveRunner reconciles it with
     * the actual count once the post-batch merge phase begins.
     *
     * @param plan the WorkPlan to drive (may be null to disable)
     * @param predictedMergeUnits caller's upfront estimate of merge ops
     */
    public void setWorkPlan(final org.tvrenamer.model.WorkPlan plan,
                            final int predictedMergeUnits) {
        this.workPlan = plan;
        this.predictedMergeUnits = Math.max(0, predictedMergeUnits);
        if (plan != null) {
            for (FileMover m : movers) {
                m.setWorkPlan(plan);
            }
        }
    }

    /**
     * Shut down all the threads.
     *
     * This is intended for usage just in case the program wants to shut down while the
     * moves are still running.
     *
     */
    public static void shutDown() {
        EXECUTOR.shutdownNow();
    }

    /**
     * Request that this MoveRunner stop processing further queued moves.
     * This does not guarantee that a currently running move will stop immediately,
     * but it will stop consuming further tasks and will attempt to cancel queued ones.
     */
    public void requestShutdown() {
        shutdownRequested = true;
        progressThread.interrupt();
    }

    // Static caches: detection lookups for the source-side merge are cheap but
    // we want to avoid per-batch tool resolution.  The merger instances are
    // immutable and thread-safe.
    private static final java.util.List<SubtitleMerger> SHARED_MERGERS =
        java.util.List.of(
            new org.tvrenamer.controller.subtitle.Mp4SubtitleMerger(),
            new org.tvrenamer.controller.subtitle.MkvSubtitleMerger());

    /** Subtitle file extensions we recognise (lowercase, leading dot). */
    private static final Set<String> MR_SUBTITLE_EXTENSIONS =
        Set.of(".srt", ".ass", ".ssa", ".vtt");

    /**
     * Source-side subtitle merge.  Runs as the first task on the executor,
     * before any per-file move.  Pairs FileMovers in the batch by
     * {@code (sourceDir, canonicalBaseName)} — files that will land in the
     * same destination directory with matching base names — and merges
     * paired subtitles into media at the source location, leveraging fast
     * local I/O.  When {@code deleteSubtitlesAfterMerge} is enabled and the
     * merge succeeds, the source .srt is deleted and its FileMover marked
     * consumed so the subsequent move loop skips it.
     *
     * <p>Failures here are logged but never propagate; the post-batch merge
     * step still runs and acts as a fallback for any pair whose source-side
     * merge couldn't be satisfied (e.g. subtitle in a different source
     * directory than its media).
     */
    private Boolean runSourceSideMerge() {
        UserPreferences prefs = UserPreferences.getInstance();
        if (!prefs.isMergeSubtitles()) {
            return Boolean.TRUE;
        }
        logger.fine("runSourceSideMerge: starting; movers=" + movers.size());

        // Group FileMovers by destination dir + canonical base name.
        // Files that will share a base after rename land in the same group.
        Map<String, List<FileMover>> groups = new HashMap<>();
        for (FileMover m : movers) {
            Path destDir = m.getMoveToDirectory();
            if (destDir == null) {
                continue;
            }
            String base = stripExt(m.getDesiredDestName());
            if (base.isEmpty()) {
                continue;
            }
            String key = destDir + "::" + base;
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(m);
        }

        for (List<FileMover> group : groups.values()) {
            if (group.size() < 2) {
                continue; // need at least media + one subtitle
            }

            FileMover mediaMover = null;
            List<FileMover> subMovers = new java.util.ArrayList<>();
            for (FileMover m : group) {
                String ext = extOf(m.getCurrentPath());
                if (ext.isEmpty()) {
                    continue;
                }
                if (MR_SUBTITLE_EXTENSIONS.contains(ext)) {
                    subMovers.add(m);
                } else if (mediaMover == null) {
                    // First non-subtitle file in the group is treated as the media.
                    // Multiple medias in one group is unusual; we merge into the first.
                    mediaMover = m;
                }
            }
            if (mediaMover == null || subMovers.isEmpty()) {
                continue;
            }

            String mediaExt = extOf(mediaMover.getCurrentPath());
            SubtitleMerger merger = null;
            for (SubtitleMerger candidate : SHARED_MERGERS) {
                if (candidate.supportsContainerExtension(mediaExt)) {
                    merger = candidate;
                    break;
                }
            }
            if (merger == null || !merger.isToolAvailable()) {
                continue; // post-batch fallback will handle
            }

            // Build SubtitleEntry list using default language.  Source-side
            // merge intentionally does not parse filename language tags from
            // the source name — those are typically scrambled at this point.
            // Filename-tag detection still happens in the post-batch path.
            String defaultLang = prefs.getDefaultSubtitleLanguage();
            String trackName =
                org.tvrenamer.controller.subtitle.SubtitleLanguages
                    .findByCode3(defaultLang)
                    .map(org.tvrenamer.controller.subtitle.SubtitleLanguages.Language::displayName)
                    .orElse("English");
            List<org.tvrenamer.controller.subtitle.SubtitleEntry> entries =
                new java.util.ArrayList<>();
            for (FileMover sub : subMovers) {
                String subExt = extOf(sub.getCurrentPath());
                if (!merger.supportsSubtitleExtension(subExt)) {
                    continue;
                }
                entries.add(new org.tvrenamer.controller.subtitle.SubtitleEntry(
                    sub.getCurrentPath(),
                    defaultLang,
                    trackName,
                    java.util.EnumSet.noneOf(
                        org.tvrenamer.controller.subtitle.Descriptor.class)));
            }
            if (entries.isEmpty()) {
                continue;
            }

            // Idempotency: skip if the media already has the language track.
            // Important — also add the destination path to the skip-set so the
            // post-batch step doesn't re-run its own identification check
            // (which is slow over network destinations and renders nothing
            // useful on the row).
            if (merger.alreadyHasLanguageTrack(mediaMover.getCurrentPath(), defaultLang)) {
                logger.fine("Source-side merge skipped — language already present: "
                    + mediaMover.getCurrentPath());
                Path mediaDestDir = mediaMover.getMoveToDirectory();
                if (mediaDestDir != null) {
                    Path normalised = mediaDestDir.resolve(mediaMover.getDesiredDestName())
                        .toAbsolutePath().normalize();
                    sourceMergedDestinations.add(normalised);
                    logger.fine("Source-side already-present, will skip in post-batch: "
                        + normalised);
                }
                continue;
            }

            // Notify listener: merge starting on the source path.
            if (subtitleListener != null) {
                try {
                    subtitleListener.subtitleMergeFileStarted(mediaMover.getCurrentPath());
                } catch (RuntimeException re) {
                    logger.log(Level.FINE,
                        "subtitle listener subtitleMergeFileStarted threw", re);
                }
            }

            logger.fine("[SOURCE-SIDE] Invoking " + merger.getClass().getSimpleName()
                + ".merge on " + mediaMover.getCurrentPath()
                + " with " + entries.size() + " subtitle(s)");
            // Per-file progress consumer that forwards each percentage tick
            // to the subtitle listener for UI rendering on the matching row.
            final Path mediaSrcPath = mediaMover.getCurrentPath();
            java.util.function.IntConsumer progressConsumer = pct -> {
                SubtitleMergeProgressListener listener = subtitleListener;
                if (listener != null) {
                    try {
                        listener.subtitleMergeFileProgress(mediaSrcPath, pct);
                    } catch (RuntimeException re) {
                        logger.log(Level.FINE,
                            "subtitle listener subtitleMergeFileProgress threw", re);
                    }
                }
            };
            SubtitleMerger.MergeOutcome outcome;
            try {
                outcome = merger.merge(mediaSrcPath, entries, progressConsumer);
            } catch (RuntimeException re) {
                logger.log(Level.WARNING,
                    "Exception during source-side merge for "
                        + mediaSrcPath, re);
                outcome = SubtitleMerger.MergeOutcome.FAILED;
            }
            logger.fine("[SOURCE-SIDE] Outcome=" + outcome
                + " for " + mediaMover.getCurrentPath());

            if (workPlan != null) {
                workPlan.tick();
            }

            // Track the expected destination so the post-batch step can skip
            // this file (avoids double-tick + redundant tool invocations).
            // Normalise the path so equals comparison is robust against
            // representation drift (case, separators, ./ segments).
            if (outcome == SubtitleMerger.MergeOutcome.SUCCESS) {
                Path mediaDestDir = mediaMover.getMoveToDirectory();
                if (mediaDestDir != null) {
                    Path normalised = mediaDestDir.resolve(mediaMover.getDesiredDestName())
                        .toAbsolutePath().normalize();
                    sourceMergedDestinations.add(normalised);
                    logger.fine("Source-side merged, will skip in post-batch: " + normalised);
                }
            }

            // Map merger outcome to controller Result for the listener.
            SubtitleMergeController.Result reported;
            switch (outcome) {
                case SUCCESS -> reported = SubtitleMergeController.Result.SUCCESS;
                case FAILED -> reported = SubtitleMergeController.Result.FAILED;
                case SKIPPED_NO_TOOL -> reported = SubtitleMergeController.Result.NO_TOOL;
                default -> reported = SubtitleMergeController.Result.ALREADY_HAS_LANGUAGE;
            }
            if (subtitleListener != null) {
                final SubtitleMergeController.Result r = reported;
                final Path mediaPath = mediaMover.getCurrentPath();
                try {
                    subtitleListener.subtitleMergeFileFinished(mediaPath, r);
                } catch (RuntimeException re) {
                    logger.log(Level.FINE,
                        "subtitle listener subtitleMergeFileFinished threw", re);
                }
            }

            // On success with delete-after-merge, drop the source .srt files
            // and mark their FileMovers as consumed so the move loop skips.
            if (outcome == SubtitleMerger.MergeOutcome.SUCCESS
                    && prefs.isDeleteSubtitlesAfterMerge()) {
                for (FileMover sub : subMovers) {
                    Path subPath = sub.getCurrentPath();
                    try {
                        Files.deleteIfExists(subPath);
                        sub.setConsumedByMerge(true);
                        logger.fine("Deleted merged subtitle (source-side): " + subPath);
                    } catch (IOException ioe) {
                        logger.log(Level.WARNING,
                            "Could not delete merged subtitle: " + subPath, ioe);
                    }
                }
            }
        }

        return Boolean.TRUE;
    }

    /** Extension (with leading dot, lowercased) of the given path's filename. */
    private static String extOf(Path p) {
        if (p == null) {
            return "";
        }
        String name = p.getFileName() != null ? p.getFileName().toString() : "";
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    /** Strip the final extension off a filename. */
    private static String stripExt(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    /**
     * Collects duplicate files found by all FileMover instances after moves complete.
     * Called internally before finish() to aggregate results for the UI.
     *
     * Important: filters out any files that were successfully moved in this batch,
     * since those should not be offered for deletion.
     */
    private void aggregateDuplicates() {
        aggregatedDuplicates.clear();

        // First, collect all destination paths of successfully moved files.
        // These must be excluded from the duplicate list.
        Set<Path> successfullyMovedFiles = new HashSet<>();
        for (FileMover mover : movers) {
            Path dest = mover.getActualDestinationIfSuccess();
            if (dest != null) {
                successfullyMovedFiles.add(dest);
            }
        }

        // Now collect duplicates, excluding any that match successfully moved files.
        for (FileMover mover : movers) {
            List<Path> dups = mover.getFoundDuplicates();
            if (dups != null && !dups.isEmpty()) {
                for (Path dup : dups) {
                    if (!successfullyMovedFiles.contains(dup)) {
                        aggregatedDuplicates.add(dup);
                    }
                }
            }
        }

        if (!aggregatedDuplicates.isEmpty()) {
            logger.info("Aggregated " + aggregatedDuplicates.size() +
                " duplicate video file(s) for review");
        }
    }

    /**
     * Post-batch subtitle merging.
     *
     * <p>Runs once after every {@link FileMover} in the batch has finished.
     * This is deliberately AFTER the moves rather than per-file beforehand —
     * TVRenamer renames any file whose name parses as an episode (including
     * subtitle files), so by the time the batch finishes, both media and
     * subtitle files share a canonical base name in their destination
     * directory.  Pairing then becomes a trivial same-directory base-name
     * match handled by {@link SubtitlePairing#findFor}.
     *
     * <p>Each successfully moved file contributes its destination directory
     * to a deduplicated set; for every directory we walk regular files and
     * ask {@link SubtitleMergeController#mergeIfEnabled} to handle each one.
     * The controller short-circuits unsupported extensions (the .srt files
     * themselves, anything that isn't a media container) and silently skips
     * media files with no paired subtitles, so this is cheap to run
     * unconditionally.
     *
     * <p>If the user enabled "delete subtitle files after successful merge",
     * deletion happens here, after the merge has been confirmed successful
     * and the merge target is the destination file (the original sibling
     * subtitle has already moved into place by this point).
     */
    private void runPostBatchSubtitleMerge() {
        UserPreferences prefs = UserPreferences.getInstance();
        if (!prefs.isMergeSubtitles()) {
            return;
        }

        // Build the candidate list strictly from the files this batch moved.
        //
        // Two cases produce a candidate, both bounded to the batch:
        //   (A) a mover that moved a media-container file -- the destination
        //       path IS the candidate.  This is the common path.
        //   (B) a mover that moved a subtitle file (.srt/.ass/.ssa/.vtt) --
        //       look for a media sibling at the same destination directory
        //       whose base name + "." prefixes the subtitle's name.  This
        //       supports the workflow "user renames a sibling .srt into a
        //       folder that already contains the matching media, and expects
        //       it to auto-mux."  We inverse SubtitlePairing's matching rule
        //       and limit the directory scan to a same-base prefix check, so
        //       unrelated files in the destination folder are never touched.
        //
        // Historic note: this method used to enumerate every regular file in
        // every destination directory the batch touched, which silently
        // processed media files that were already at the destination before
        // the batch ran -- a real user-trust bug.  The strictly-batch scope
        // below is the fix.
        List<Path> candidates = new LinkedList<>();
        Set<Path> seenCandidates = new HashSet<>();

        for (FileMover mover : movers) {
            Path dest = mover.getActualDestinationIfSuccess();
            if (dest == null) {
                continue;
            }
            // consumedByMerge movers short-circuit without actually moving;
            // their .getActualDestinationIfSuccess() returns the (now-deleted)
            // source path.  Filter those out.
            if (!Files.isRegularFile(dest)) {
                continue;
            }

            String ext = extOf(dest);

            // Case A: media-container mover -- it is itself the candidate.
            boolean isContainer = false;
            for (SubtitleMerger m : SHARED_MERGERS) {
                if (m.supportsContainerExtension(ext)) {
                    isContainer = true;
                    break;
                }
            }
            if (isContainer) {
                Path normalised = dest.toAbsolutePath().normalize();
                if (seenCandidates.add(normalised)) {
                    candidates.add(dest);
                }
                continue;
            }

            // Case B (niche): subtitle mover -- look for a same-base media
            // sibling already at the destination directory.  The directory
            // scan is bounded by the prefix check, so the only files we
            // can touch are those whose canonical base name is a prefix of
            // a subtitle file the user explicitly put in the batch.
            if (!SubtitlePairing.SUPPORTED_EXTENSIONS.contains(ext)) {
                continue;
            }
            Path destDir = dest.getParent();
            if (destDir == null) {
                continue;
            }
            String subNameLower = dest.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(destDir)) {
                for (Path entry : stream) {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    String entryExt = extOf(entry);
                    boolean entryIsContainer = false;
                    for (SubtitleMerger m : SHARED_MERGERS) {
                        if (m.supportsContainerExtension(entryExt)) {
                            entryIsContainer = true;
                            break;
                        }
                    }
                    if (!entryIsContainer) {
                        continue;
                    }
                    String entryName = entry.getFileName().toString();
                    String entryBaseLower = stripExt(entryName)
                        .toLowerCase(java.util.Locale.ROOT);
                    if (entryBaseLower.isEmpty()) {
                        continue;
                    }
                    if (subNameLower.startsWith(entryBaseLower + ".")) {
                        Path normalised = entry.toAbsolutePath().normalize();
                        if (seenCandidates.add(normalised)) {
                            candidates.add(entry);
                        }
                    }
                }
            } catch (IOException ioe) {
                logger.log(Level.WARNING,
                    "Could not scan destination directory while pairing subtitle: "
                        + destDir, ioe);
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        // Reconcile the WorkPlan: if the actual candidate count is less than
        // what the caller predicted (e.g. fewer media files in the destination
        // than estimated), retract the over-counted units so the bar can still
        // reach 100%.  If actual > predicted, the missing units were never
        // counted in the total, so the bar will simply tick past expected
        // and we let WorkPlan.completeAll() at the end clamp.
        if (workPlan != null && predictedMergeUnits > candidates.size()) {
            workPlan.retract(predictedMergeUnits - candidates.size());
        }

        if (subtitleListener != null) {
            try {
                subtitleListener.subtitleMergeStarted(candidates.size());
            } catch (RuntimeException re) {
                logger.log(Level.FINE, "subtitle listener subtitleMergeStarted threw", re);
            }
        }

        int merged = 0;
        int skipped = 0;
        int failed = 0;

        SubtitleMergeController controller = new SubtitleMergeController();
        for (Path entry : candidates) {
            // Skip files that were already merged source-side: ticking again
            // here would over-count the WorkPlan and the idempotency check
            // would just spawn the tool to confirm what we already know.
            Path normalisedEntry = entry.toAbsolutePath().normalize();
            if (sourceMergedDestinations.contains(normalisedEntry)) {
                logger.fine("Post-batch skipping source-merged: " + normalisedEntry);
                continue;
            }
            logger.fine("Post-batch will examine: " + normalisedEntry);
            if (subtitleListener != null) {
                try {
                    subtitleListener.subtitleMergeFileStarted(entry);
                } catch (RuntimeException re) {
                    logger.log(Level.FINE, "subtitle listener subtitleMergeFileStarted threw", re);
                }
            }

            logger.fine("[POST-BATCH] Invoking controller.mergeIfEnabled on " + entry);
            // Forward each progress tick to the listener so the row's
            // percentage label updates while the merger tool runs.
            final Path entryPath = entry;
            java.util.function.IntConsumer postProgressConsumer = pct -> {
                SubtitleMergeProgressListener listener = subtitleListener;
                if (listener != null) {
                    try {
                        listener.subtitleMergeFileProgress(entryPath, pct);
                    } catch (RuntimeException re) {
                        logger.log(Level.FINE,
                            "subtitle listener subtitleMergeFileProgress threw", re);
                    }
                }
            };
            SubtitleMergeController.Result result;
            try {
                result = controller.mergeIfEnabled(entry, null, postProgressConsumer);
            } catch (RuntimeException re) {
                logger.log(Level.WARNING,
                    "Exception during subtitle merge for: " + entry, re);
                result = SubtitleMergeController.Result.FAILED;
            }
            logger.fine("[POST-BATCH] Result=" + result + " for " + entry);

            if (result == SubtitleMergeController.Result.SUCCESS) {
                merged++;
                if (prefs.isDeleteSubtitlesAfterMerge()) {
                    deleteSubtitleSiblings(entry, prefs);
                }
            } else if (result == SubtitleMergeController.Result.FAILED) {
                failed++;
            } else {
                skipped++;
            }

            // Tick the unified progress bar regardless of outcome — every
            // candidate counted toward the predicted total.
            if (workPlan != null) {
                workPlan.tick();
            }

            if (subtitleListener != null) {
                try {
                    subtitleListener.subtitleMergeFileFinished(entry, result);
                } catch (RuntimeException re) {
                    logger.log(Level.FINE, "subtitle listener subtitleMergeFileFinished threw", re);
                }
            }
        }

        if (subtitleListener != null) {
            try {
                subtitleListener.subtitleMergeFinished(merged, skipped, failed);
            } catch (RuntimeException re) {
                logger.log(Level.FINE, "subtitle listener subtitleMergeFinished threw", re);
            }
        }
    }

    /**
     * Delete sibling subtitle files of the given (now-merged) media file.
     *
     * <p>Pairing logic is the same as the merger uses, so we delete exactly
     * the files that contributed tracks to the merged container.  Failures
     * are logged but do not propagate.
     */
    private static void deleteSubtitleSiblings(Path mergedMedia, UserPreferences prefs) {
        List<SubtitleEntry> siblings;
        try {
            siblings = SubtitlePairing.findFor(mergedMedia, prefs.getDefaultSubtitleLanguage());
        } catch (RuntimeException re) {
            logger.log(Level.WARNING,
                "Could not enumerate subtitle siblings for " + mergedMedia, re);
            return;
        }
        for (SubtitleEntry entry : siblings) {
            try {
                if (Files.deleteIfExists(entry.file())) {
                    logger.fine("Deleted merged subtitle: " + entry.file());
                }
            } catch (IOException ioe) {
                logger.log(Level.WARNING,
                    "Could not delete merged subtitle: " + entry.file(), ioe);
            }
        }
    }

    /**
     * Returns the list of duplicate video files found after all moves completed.
     * This should be called after finish() to retrieve files for user confirmation.
     *
     * @return unmodifiable list of duplicate file paths (may be empty)
     */
    public List<Path> getFoundDuplicates() {
        return java.util.Collections.unmodifiableList(aggregatedDuplicates);
    }
}
