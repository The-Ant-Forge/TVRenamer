package org.tvrenamer.controller.subtitle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared helper for the temp-then-swap discipline used by the per-format subtitle mergers.
 *
 * <p>Both {@code Mp4SubtitleMerger} and {@code MkvSubtitleMerger} produce their muxed
 * output to a sibling temp file (e.g. {@code <src>.merging.<ext>}) before replacing
 * the source.  This class concentrates the pre-swap integrity gate, the swap itself
 * (with retry against transient Windows AV/indexer locks), and the timeout calculation
 * used to bound the merger process runtime, so both backends behave identically.
 *
 * <p>This is a pure utility class: no instance state, no constructor.
 *
 * <p>See the "File-handling discipline (temp + swap)" section of the Subtitle Merge spec
 * for the rationale behind the integrity gate, the 80% size floor, and the 100/300/1000 ms
 * retry backoff.
 */
public final class SubtitleSwap {

    private static final Logger logger = Logger.getLogger(SubtitleSwap.class.getName());

    /** Minimum acceptable size of the temp file as a fraction of the source. */
    private static final double MIN_SIZE_RATIO = 0.8d;

    /** Backoff delays (ms) between swap attempts; length determines max attempts. */
    static final long[] SWAP_BACKOFF_MILLIS = { 100L, 300L, 1000L };

    /** Base process timeout in seconds, before per-MB scaling. */
    private static final int TIMEOUT_BASE_SECONDS = 30;

    /** Scaling factor: one extra second per byte-step. */
    private static final long TIMEOUT_BYTES_PER_SECOND = 1_000_000L;

    /** Maximum process timeout in seconds (10 minutes). */
    private static final int TIMEOUT_MAX_SECONDS = 600;

    /**
     * Strategy for performing the underlying file move.  Defaults to a real-filesystem
     * {@link Files#move(Path, Path, java.nio.file.CopyOption...)} call; tests replace this
     * with a fake to simulate transient failures without mocking the static
     * {@code Files} API directly.
     *
     * <p>Package-private so tests in the same package can swap it out and restore it
     * via {@link #setMoveOperation(MoveOperation)}.
     */
    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target) throws IOException;
    }

    /** The default real-filesystem move (REPLACE_EXISTING). */
    static final MoveOperation REAL_MOVE =
        (src, dst) -> Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);

    /**
     * The currently active move operation.  Mutable for test injection; production code
     * never writes to this.
     */
    private static volatile MoveOperation moveOperation = REAL_MOVE;

    private SubtitleSwap() {
        // Utility class — no instances.
    }

    /**
     * Replace the current {@link MoveOperation}; intended for tests only.
     *
     * @param op the new operation; must not be {@code null}.
     */
    static void setMoveOperation(MoveOperation op) {
        if (op == null) {
            throw new IllegalArgumentException("MoveOperation must not be null");
        }
        moveOperation = op;
    }

    /**
     * Restore the default real-filesystem move; tests should call this in
     * {@code @AfterEach} to keep the static field clean.
     */
    static void resetMoveOperation() {
        moveOperation = REAL_MOVE;
    }

    /** Suffix for the sibling temp file merged output is written to before the swap. */
    private static final String MERGING_SUFFIX = ".merging";

    /**
     * Compute the sibling {@code <base>.merging.<ext>} temp path used during
     * the swap.  This is THE canonical scheme for both mergers — previously
     * MP4 used {@code <base>.merging.<ext>} while MKV used
     * {@code <full-name>.merging.<ext>}, forcing the controller's stale-temp
     * cleanup to enumerate both shapes (Round-4 #27).
     *
     * @param mediaFile the media file being merged into
     * @return the sibling temp path
     */
    public static Path computeTempPath(Path mediaFile) {
        Path parent = mediaFile.getParent();
        String name = mediaFile.getFileName().toString();
        String ext = org.tvrenamer.controller.util.StringUtils.getExtension(name);
        String base = org.tvrenamer.controller.util.StringUtils.getBaseName(name);
        String tempName = base + MERGING_SUFFIX + ext;
        return (parent == null) ? Path.of(tempName) : parent.resolve(tempName);
    }

    /**
     * Cheap pre-swap sanity check: the temp file exists and is at least 80% of the
     * source size.  Subtitle muxing should never shrink the container dramatically;
     * a substantial size drop signals truncation or a malformed write.
     *
     * <p>This deliberately does not parse the container — that's the responsibility of
     * the per-format merger (e.g. {@code mkvmerge --identify} or {@code MP4Box -info}).
     *
     * @param tmp the temp file produced by the merger.
     * @param src the original source file the merger read from.
     * @return true if the temp file looks intact enough to swap in over the source.
     * @throws IOException if {@code src} cannot be sized (e.g. it has been removed).
     */
    public static boolean integrityGate(Path tmp, Path src) throws IOException {
        if (!Files.exists(tmp)) {
            return false;
        }
        long srcSize = Files.size(src);
        long tmpSize = Files.size(tmp);
        // Use multiplication on the source side to keep arithmetic in long-space and
        // avoid any double-precision rounding surprises around the 80% boundary.
        // tmp >= 0.8 * src  <=>  10 * tmp >= 8 * src.
        return tmpSize * 10L >= srcSize * 8L;
    }

    /**
     * Atomically replace {@code src} with {@code tmp} using
     * {@link StandardCopyOption#REPLACE_EXISTING}.  Retries up to 3 times with
     * 100/300/1000 ms backoff to handle transient Windows AV/indexer locks.
     *
     * <p>On retry exhaustion, this method returns {@code false} and <strong>does not
     * delete</strong> {@code tmp}, so the caller can log the preserved temp path and
     * a user can recover the merged file manually.
     *
     * <p>If the calling thread is interrupted during a backoff sleep, the interrupt
     * flag is restored and the method returns {@code false}.
     *
     * @param tmp the merger's output temp file.
     * @param src the source file to be replaced.
     * @return true if the swap eventually succeeded; false on retry exhaustion or interruption.
     */
    public static boolean swap(Path tmp, Path src) {
        final int attempts = SWAP_BACKOFF_MILLIS.length;
        for (int i = 0; i < attempts; i++) {
            try {
                moveOperation.move(tmp, src);
                return true;
            } catch (IOException ioe) {
                logger.log(Level.FINE,
                    "Subtitle swap attempt " + (i + 1) + " of " + attempts
                        + " failed for " + src + " (temp " + tmp + ")",
                    ioe);
                // Don't sleep after the final attempt — we're about to give up.
                if (i == attempts - 1) {
                    break;
                }
                try {
                    Thread.sleep(SWAP_BACKOFF_MILLIS[i]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.fine("Subtitle swap interrupted during backoff for " + src);
                    return false;
                }
            }
        }
        logger.warning("Subtitle swap failed after " + attempts
            + " attempts; preserving temp file for manual recovery: " + tmp);
        return false;
    }

    /**
     * Compute a process timeout (seconds) scaled to the source file size.
     *
     * <p>The formula is {@code 30 + (sourceBytes / 1_000_000)}, capped at 600 seconds.
     * For zero or negative input the base timeout of 30 seconds is returned, so callers
     * don't need a separate guard for unknown sizes.
     *
     * @param sourceBytes the size of the source media file in bytes.
     * @return the recommended timeout in seconds, in the range [30, 600].
     */
    public static int computeTimeoutSeconds(long sourceBytes) {
        if (sourceBytes <= 0L) {
            return TIMEOUT_BASE_SECONDS;
        }
        long scaled = sourceBytes / TIMEOUT_BYTES_PER_SECOND;
        // Cap before the add to avoid any chance of overflow when sourceBytes is huge.
        if (scaled >= TIMEOUT_MAX_SECONDS) {
            return TIMEOUT_MAX_SECONDS;
        }
        long total = TIMEOUT_BASE_SECONDS + scaled;
        if (total > TIMEOUT_MAX_SECONDS) {
            return TIMEOUT_MAX_SECONDS;
        }
        return (int) total;
    }
}
