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

        // Submit moves in original list order (table display order, top to bottom).
        // Conflict resolution has already modified the FileMover objects in-place.
        for (FileMover move : episodes) {
            // Mark as pre-verified if we already checked this directory.
            Path moveDestDir = move.getMoveToDirectory();
            if (verifiedDirectories.contains(moveDestDir)) {
                move.setDirectoryPreVerified(true);
            }
            movers.add(move);
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

        Set<Path> destDirs = new HashSet<>();
        for (FileMover mover : movers) {
            Path dest = mover.getActualDestinationIfSuccess();
            if (dest != null) {
                Path parent = dest.getParent();
                if (parent != null) {
                    destDirs.add(parent);
                }
            }
        }
        if (destDirs.isEmpty()) {
            return;
        }

        SubtitleMergeController controller = new SubtitleMergeController();
        for (Path dir : destDirs) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    SubtitleMergeController.Result result;
                    try {
                        result = controller.mergeIfEnabled(entry, null);
                    } catch (RuntimeException re) {
                        logger.log(Level.WARNING,
                            "Exception during subtitle merge for: " + entry, re);
                        continue;
                    }
                    if (result == SubtitleMergeController.Result.SUCCESS
                            && prefs.isDeleteSubtitlesAfterMerge()) {
                        deleteSubtitleSiblings(entry, prefs);
                    }
                }
            } catch (IOException ioe) {
                logger.log(Level.WARNING,
                    "Could not scan destination directory for subtitle merge: " + dir, ioe);
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
