package org.tvrenamer.controller.subtitle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.tvrenamer.controller.util.ExternalToolDetector;
import org.tvrenamer.controller.util.ProcessRunner;
import org.tvrenamer.controller.util.StringUtils;

/**
 * Mux soft subtitle tracks into MP4/M4V containers using GPAC's
 * {@code MP4Box} CLI.
 *
 * <p>Detection mirrors {@code Mp4MetadataTagger}: cache once per JVM via a
 * volatile field with a double-checked-locking guard.  The actual process
 * spawn is delegated to {@link ProcessRunner} via the constructor-injected
 * {@link ProcessOps.Run} / {@link ProcessOps.Streaming} indirection so unit
 * tests can substitute fakes without spawning real binaries.
 *
 * <p>Only {@code .srt} and {@code .vtt} subtitle inputs are accepted —
 * MP4Box rejects ASS/SSA in the MP4 box format, and the controller is
 * expected to filter them out (with a warning) before reaching this class.
 */
public final class Mp4SubtitleMerger implements SubtitleMerger {

    private static final Logger logger = Logger.getLogger(Mp4SubtitleMerger.class.getName());

    private static final Set<String> CONTAINER_EXTENSIONS = Set.of(".mp4", ".m4v");
    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(".srt", ".vtt");

    /** Suffix for the sibling temp file we write merged output to before swap. */
    private static final String MERGING_SUFFIX = ".merging";

    /** Tool name surfaced to the user in status messages. */
    private static final String TOOL_NAME = "MP4Box";

    /** Maximum number of trailing characters of process output to log on failure. */
    private static final int FAILURE_OUTPUT_TAIL = 1000;

    // ---- Tool detection cache (mirrors Mp4MetadataTagger) ----

    private static volatile String toolPath = null;
    private static volatile Boolean detected = null;
    private static final Object DETECTION_LOCK = new Object();

    // ---- Process indirection (constructor-injected) ----

    /** Per-instance process runner for tests to inject fakes via the package-private constructor. */
    private final ProcessOps.Run runOp;

    /** Per-instance streaming process runner — used during the merge for progress parsing. */
    private final ProcessOps.Streaming streamingOp;

    /** Public no-arg constructor for production: routes through {@link ProcessRunner}. */
    public Mp4SubtitleMerger() {
        this(ProcessOps.REAL, ProcessOps.REAL_STREAMING);
    }

    /** Test constructor: package-private, accepts injected process operations. */
    Mp4SubtitleMerger(ProcessOps.Run runOp, ProcessOps.Streaming streamingOp) {
        if (runOp == null || streamingOp == null) {
            throw new IllegalArgumentException("ProcessOps must not be null");
        }
        this.runOp = runOp;
        this.streamingOp = streamingOp;
    }

    /** Reset the cached tool detection result; tests use this to force re-detection. */
    static void resetDetectionForTesting() {
        synchronized (DETECTION_LOCK) {
            toolPath = null;
            detected = null;
        }
    }

    /** Force a particular detection state; tests use this to bypass the real PATH probe. */
    static void setToolPathForTesting(String path) {
        synchronized (DETECTION_LOCK) {
            if (path == null) {
                toolPath = "";
                detected = Boolean.FALSE;
            } else {
                toolPath = path;
                detected = Boolean.TRUE;
            }
        }
    }

    // ---- SubtitleMerger API ----

    @Override
    public boolean supportsContainerExtension(String containerExtension) {
        if (containerExtension == null) {
            return false;
        }
        return CONTAINER_EXTENSIONS.contains(containerExtension.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean supportsSubtitleExtension(String subtitleExtension) {
        if (subtitleExtension == null) {
            return false;
        }
        return SUBTITLE_EXTENSIONS.contains(subtitleExtension.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean isToolAvailable() {
        return ensureDetected();
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public boolean alreadyHasLanguageTrack(Path mediaFile, String langCode3) {
        if (mediaFile == null || langCode3 == null || langCode3.isBlank()) {
            return false;
        }
        if (!ensureDetected()) {
            return false;
        }

        List<String> cmd = List.of(toolPath, "-info", mediaFile.toString());
        ProcessRunner.Result result;
        try {
            result = runOp.run(cmd, SubtitleSwap.computeTimeoutSeconds(0L));
        } catch (RuntimeException re) {
            logger.log(Level.FINE, "MP4Box -info threw for " + mediaFile, re);
            return false;
        }

        if (result == null || !result.success()) {
            // Conservative: any failure -> let the merge proceed.
            return false;
        }

        return infoHasSubtitleLanguage(result.output(), langCode3);
    }

    /**
     * Scan {@code MP4Box -info} output for a subtitle track in the given language.
     *
     * <p>MP4Box output places per-track fields on consecutive lines under a
     * {@code # Track N Info ...} header.  A track that's a subtitle stream is
     * marked by {@code Media Type: text:<codec>} (e.g. {@code text:tx3g} or
     * {@code text:wvtt}) or by older wording like {@code Subtitle in language}.
     * The language appears on its own line as {@code Media Language: <name> (<code>)}
     * <em>or</em> embedded in the {@code Media Type} line for older versions.
     *
     * <p>We split the output into per-track blocks at each {@code # Track }
     * boundary and check, for each block, whether it looks like a subtitle
     * stream <em>and</em> contains the requested language code anywhere in the
     * block.  This handles both modern and legacy MP4Box wording without
     * having to enumerate every variant.
     */
    static boolean infoHasSubtitleLanguage(String output, String langCode3) {
        if (output == null || output.isEmpty() || langCode3 == null) {
            return false;
        }
        String wantedLang = langCode3.toLowerCase(Locale.ROOT);

        // Split into per-track blocks.  The first chunk is the file/movie
        // prelude (no "# Track " header) which we skip.
        String[] blocks = output.split("(?m)^# Track\\b");
        for (int i = 1; i < blocks.length; i++) {
            String lower = blocks[i].toLowerCase(Locale.ROOT);
            boolean isSubtitleTrack =
                lower.contains("media type: text:")
                || lower.contains("media type:text:")
                || lower.contains("subtitle in language")
                || lower.contains("subtitle stream")
                || lower.contains("unknown text stream");
            if (!isSubtitleTrack) {
                continue;
            }
            // Look for the language code as a whole word anywhere in the block,
            // since Media Language is on a separate line from the type indicator.
            // Use a word-boundary check so "eng" doesn't match inside "english"
            // — except MP4Box does emit "English (eng)" so we must accept either
            // the parenthesised form or a standalone token.
            if (lower.matches("(?s).*\\b" + Pattern.quote(wantedLang) + "\\b.*")
                || lower.contains("(" + wantedLang + ")")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public MergeOutcome merge(
            Path mediaFile,
            List<SubtitleEntry> subtitles,
            java.util.function.IntConsumer onProgress) {
        if (subtitles == null || subtitles.isEmpty()) {
            // Vacuously successful: nothing to do.
            return MergeOutcome.SUCCESS;
        }
        if (!ensureDetected()) {
            return MergeOutcome.SKIPPED_NO_TOOL;
        }
        if (mediaFile == null) {
            return MergeOutcome.FAILED;
        }

        Path temp = computeTempPath(mediaFile);

        // Pre-flight: try to remove any leftover from a prior crashed run so the merge
        // doesn't fight an existing file.  This is best-effort; failure is logged and
        // we let MP4Box error out naturally.
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ioe) {
            logger.log(Level.FINE, "Could not remove stale temp before merge: " + temp, ioe);
        }

        List<String> cmd = buildCommand(mediaFile, temp, subtitles);

        long sourceBytes;
        try {
            sourceBytes = Files.size(mediaFile);
        } catch (IOException ioe) {
            logger.log(Level.WARNING, "Cannot size media file " + mediaFile, ioe);
            return MergeOutcome.FAILED;
        }
        int timeoutSeconds = SubtitleSwap.computeTimeoutSeconds(sourceBytes);

        // Parse MP4Box's "ISO File Writing: |......| (NN/100)" progress lines
        // and forward the percentage to the caller.  When onProgress is null
        // we skip the streaming path entirely — preserves the
        // pre-progress-feature behaviour and matches what existing test fakes
        // inject (non-streaming ops).
        java.util.function.Consumer<String> lineSink = (onProgress == null)
            ? null
            : line -> {
                int pct = parseProgressPercent(line);
                if (pct >= 0) {
                    onProgress.accept(pct);
                }
            };

        ProcessRunner.Result result;
        try {
            if (lineSink != null) {
                result = streamingOp.run(cmd, timeoutSeconds, lineSink);
            } else {
                result = runOp.run(cmd, timeoutSeconds);
            }
        } catch (RuntimeException re) {
            logger.log(Level.WARNING, "MP4Box threw while merging " + mediaFile, re);
            deleteQuietly(temp);
            return MergeOutcome.FAILED;
        }

        if (result == null || !result.success()) {
            int exit = (result == null) ? -1 : result.exitCode();
            String tail = (result == null) ? "" : tailOf(result.output(), FAILURE_OUTPUT_TAIL);
            logger.warning(TOOL_NAME + " failed (exit " + exit + ") merging " + mediaFile
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
            logger.warning("Swap failed merging subtitles into " + mediaFile);
            return MergeOutcome.FAILED;
        }

        final int trackCount = subtitles.size();
        logger.info("Merged " + trackCount + " subtitle track(s) into " + mediaFile);
        return MergeOutcome.SUCCESS;
    }

    // ---- internals ----

    /**
     * Build the {@code MP4Box -add ... -out <temp> <src>} command line for the
     * given subtitle entries.  Visible for testing.
     */
    static List<String> buildCommand(Path mediaFile, Path temp, List<SubtitleEntry> subtitles) {
        List<String> cmd = new ArrayList<>(4 + 2 * subtitles.size());
        cmd.add(toolPath == null || toolPath.isEmpty() ? TOOL_NAME : toolPath);
        for (SubtitleEntry entry : subtitles) {
            String lang = entry.langCode3() == null ? "" : entry.langCode3();
            String safeName = sanitiseTrackName(entry.trackName());
            cmd.add("-add");
            cmd.add(entry.file().toString() + ":lang=" + lang + ":name=" + safeName);
        }
        cmd.add("-out");
        cmd.add(temp.toString());
        cmd.add(mediaFile.toString());
        return cmd;
    }

    /**
     * Parse a percentage from an MP4Box progress line of the form
     * {@code "ISO File Writing: |=====   | (42/100)"}.  Package-private so
     * tests can pin the parser against real tool output — format drift here
     * is exactly the "output-parsing resilience" risk the review flagged.
     *
     * @param line one line of MP4Box output
     * @return the clamped percentage [0, 100], or -1 if the line carries no
     *    parseable progress
     */
    static int parseProgressPercent(String line) {
        if (line == null) {
            return -1;
        }
        int idx = line.indexOf('(');
        int slash = (idx < 0) ? -1 : line.indexOf('/', idx);
        int closeParen = (slash < 0) ? -1 : line.indexOf(')', slash);
        if (slash > idx && closeParen > slash) {
            String numStr = line.substring(idx + 1, slash).trim();
            String denStr = line.substring(slash + 1, closeParen).trim();
            try {
                int num = Integer.parseInt(numStr);
                int den = Integer.parseInt(denStr);
                if (den > 0) {
                    int pct = (int) Math.round(num * 100.0 / den);
                    return Math.max(0, Math.min(100, pct));
                }
            } catch (NumberFormatException ignored) {
                // Not a progress line.
            }
        }
        return -1;
    }

    /**
     * Sanitise a track name for use inside MP4Box's {@code :}-separated
     * modifier syntax.  {@code :} is the field separator and {@code =} is the
     * key/value separator, so embedding either raw confuses the parser.
     * Replace {@code :} with {@code _} and {@code =} with {@code -}; this is
     * a cheap safety net since most track names are plain ASCII like
     * "English (Forced, SDH)".
     */
    static String sanitiseTrackName(String trackName) {
        if (trackName == null || trackName.isEmpty()) {
            return "";
        }
        return trackName.replace(':', '_').replace('=', '-');
    }

    /** Compute the sibling {@code <name>.merging.<ext>} path used during the swap. */
    static Path computeTempPath(Path mediaFile) {
        Path parent = mediaFile.getParent();
        String name = mediaFile.getFileName().toString();
        String ext = StringUtils.getExtension(name); // includes the leading dot
        String base = StringUtils.getBaseName(name);
        String tempName = base + MERGING_SUFFIX + ext;
        return (parent == null) ? Path.of(tempName) : parent.resolve(tempName);
    }

    private static String tailOf(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(s.length() - max);
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

    /**
     * Mirror {@code Mp4MetadataTagger.ensureDetected()}: lazily detect MP4Box
     * once per JVM and cache the path (or empty string for "not found") under
     * a static lock.
     *
     * @return true if MP4Box was located, false otherwise.
     */
    private static synchronized boolean ensureDetected() {
        if (detected != null) {
            return detected;
        }
        synchronized (DETECTION_LOCK) {
            if (detected != null) {
                return detected;
            }
            String path = ExternalToolDetector.detect(
                new String[] { "MP4Box" },
                new String[] {
                    "C:\\Program Files\\GPAC\\MP4Box.exe",
                    "C:\\Program Files (x86)\\GPAC\\MP4Box.exe"
                },
                new String[] {
                    "/usr/local/bin/MP4Box",
                    "/opt/homebrew/bin/MP4Box"
                }
            );
            if (path != null && !path.isEmpty()) {
                toolPath = path;
                detected = Boolean.TRUE;
                logger.info("Found MP4Box: " + toolPath);
            } else {
                toolPath = "";
                detected = Boolean.FALSE;
                logger.info("MP4Box not found - MP4 subtitle merging will be disabled");
            }
            return detected;
        }
    }
}
