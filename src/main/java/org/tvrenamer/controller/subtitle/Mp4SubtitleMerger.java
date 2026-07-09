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

import org.tvrenamer.controller.util.ProcessOps;
import org.tvrenamer.controller.util.ExternalToolDetector;
import org.tvrenamer.controller.util.ProcessRunner;
import org.tvrenamer.controller.util.StringUtils;

/**
 * Mux soft subtitle tracks into MP4/M4V containers using GPAC's
 * {@code MP4Box} CLI.
 *
 * <p>Detection is cached once per JVM via the shared
 * {@link org.tvrenamer.controller.util.DetectedTool}.  The actual process
 * spawn is delegated to {@link ProcessRunner} via the constructor-injected
 * {@link ProcessOps.Run} / {@link ProcessOps.Streaming} indirection so unit
 * tests can substitute fakes without spawning real binaries.
 *
 * <p>Only {@code .srt} and {@code .vtt} subtitle inputs are accepted —
 * MP4Box rejects ASS/SSA in the MP4 box format, and the controller is
 * expected to filter them out (with a warning) before reaching this class.
 */
public final class Mp4SubtitleMerger extends AbstractSubtitleMerger {

    private static final Logger logger = Logger.getLogger(Mp4SubtitleMerger.class.getName());

    private static final Set<String> CONTAINER_EXTENSIONS = Set.of(".mp4", ".m4v");
    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(".srt", ".vtt");

    /** Tool name surfaced to the user in status messages. */
    private static final String TOOL_NAME = "MP4Box";

    // ---- Tool detection (shared DetectedTool cache) ----

    private static final org.tvrenamer.controller.util.DetectedTool TOOL =
        new org.tvrenamer.controller.util.DetectedTool(TOOL_NAME, () ->
            ExternalToolDetector.detect(
                new String[] { "MP4Box" },
                new String[] {
                    "C:\\Program Files\\GPAC\\MP4Box.exe",
                    "C:\\Program Files (x86)\\GPAC\\MP4Box.exe"
                },
                new String[] {
                    "/usr/local/bin/MP4Box",
                    "/opt/homebrew/bin/MP4Box"
                }
            ));

    /** Public no-arg constructor for production: routes through {@link ProcessRunner}. */
    public Mp4SubtitleMerger() {
        this(ProcessOps.REAL, ProcessOps.REAL_STREAMING);
    }

    /** Test constructor: package-private, accepts injected process operations. */
    Mp4SubtitleMerger(ProcessOps.Run runOp, ProcessOps.Streaming streamingOp) {
        super(runOp, streamingOp);
    }

    // ---- AbstractSubtitleMerger hooks ----

    @Override
    protected boolean toolDetected() {
        return ensureDetected();
    }

    @Override
    protected List<String> buildMergeCommand(
            Path mediaFile, Path temp, List<SubtitleEntry> subtitles, boolean streaming) {
        // MP4Box needs no extra flags for progress output.
        return buildCommand(mediaFile, temp, subtitles);
    }

    @Override
    protected int parseProgress(String line) {
        return parseProgressPercent(line);
    }

    /** Reset the cached tool detection result; tests use this to force re-detection. */
    static void resetDetectionForTesting() {
        TOOL.resetForTesting();
    }

    /** Force a particular detection state (null = "not found"); tests only. */
    static void setToolPathForTesting(String path) {
        TOOL.setForTesting(path);
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

        List<String> cmd = List.of(TOOL.path(), "-info", mediaFile.toString());
        ProcessRunner.Result result;
        try {
            result = runOp().run(cmd, SubtitleSwap.computeTimeoutSeconds(0L));
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

    // merge() lives in AbstractSubtitleMerger (shared skeleton).

    // ---- internals ----

    /**
     * Build the {@code MP4Box -add ... -out <temp> <src>} command line for the
     * given subtitle entries.  Visible for testing.
     */
    static List<String> buildCommand(Path mediaFile, Path temp, List<SubtitleEntry> subtitles) {
        List<String> cmd = new ArrayList<>(4 + 2 * subtitles.size());
        cmd.add(TOOL.path().isEmpty() ? TOOL_NAME : TOOL.path());
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

    /** @return true if MP4Box was located (shared DetectedTool cache). */
    private static boolean ensureDetected() {
        return TOOL.isAvailable();
    }
}
