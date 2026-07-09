package org.tvrenamer.controller.subtitle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tvrenamer.controller.util.ProcessOps;
import org.tvrenamer.controller.util.ExternalToolDetector;
import org.tvrenamer.controller.util.ProcessRunner;


/**
 * Mux subtitle tracks into an MKV container using {@code mkvmerge} (MKVToolNix).
 *
 * <p>Writes the muxed result to a sibling temp file, runs the integrity gate from
 * {@link SubtitleSwap}, then atomically replaces the source on success.  If
 * {@code mkvmerge} is not on PATH (or in a known install location) all merge
 * calls return {@link MergeOutcome#SKIPPED_NO_TOOL}.
 *
 * <p>Idempotency: {@link #alreadyHasLanguageTrack(Path, String)} runs
 * {@code mkvmerge --identify --identification-format json} and walks the resulting
 * track list for an existing subtitle track in the requested language.  The
 * controller can then drop already-present languages from the merge call.
 *
 * <p>The mkvmerge JSON format is small and stable, and we only need two scalar
 * fields per track ({@code type} and {@code properties.language}).  Rather than
 * pull a JSON parser onto the classpath just for this, we scan the output with a
 * regex.  See {@link #PATTERN_SUBTITLE_LANG} for the exact shape.
 */
public final class MkvSubtitleMerger extends AbstractSubtitleMerger {

    private static final Logger logger = Logger.getLogger(MkvSubtitleMerger.class.getName());

    private static final Set<String> CONTAINER_EXTENSIONS = Set.of(".mkv");

    /** Subtitle formats mkvmerge ingests natively. */
    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(".srt", ".ass", ".ssa", ".vtt");

    /** Public no-arg constructor for production: routes through {@link ProcessRunner}. */
    public MkvSubtitleMerger() {
        this(ProcessOps.REAL, ProcessOps.REAL_STREAMING);
    }

    /** Test constructor: package-private, accepts injected process operations. */
    MkvSubtitleMerger(ProcessOps.Run runOp, ProcessOps.Streaming streamingOp) {
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
        // --gui-mode (added when streaming) makes mkvmerge emit the
        // machine-readable #GUI#progress NN% lines the parser consumes.
        return buildCommand(currentToolPath(), mediaFile, temp, subtitles, streaming);
    }

    @Override
    protected int parseProgress(String line) {
        return parseProgressPercent(line);
    }

    /**
     * Pattern for any {@code "language": "<code>"} field inside a track block.
     * The {@code [a-zA-Z]{2,3}} group accepts 2-letter (ietf) or 3-letter
     * (ISO 639-2) codes; mkvmerge emits both styles depending on the source.
     */
    private static final Pattern PATTERN_LANGUAGE_FIELD = Pattern.compile(
        "\"language(?:_ietf)?\"\\s*:\\s*\"([a-zA-Z]{2,3})\"");

    /** Pattern for the track-type field set to subtitles. */
    private static final Pattern PATTERN_TYPE_SUBTITLES = Pattern.compile(
        "\"type\"\\s*:\\s*\"subtitles\"");

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
        return "mkvmerge";
    }

    @Override
    public boolean alreadyHasLanguageTrack(Path mediaFile, String langCode3) {
        if (mediaFile == null || langCode3 == null || langCode3.isBlank()) {
            return false;
        }
        if (!ensureDetected()) {
            // No tool: we can't introspect, so play it safe and let the merge proceed.
            // The merge will then return SKIPPED_NO_TOOL and the controller will log it.
            return false;
        }

        List<String> cmd = List.of(
            currentToolPath(),
            "--identify",
            "--identification-format", "json",
            mediaFile.toString()
        );

        ProcessRunner.Result result;
        try {
            result = runOp().run(cmd, SubtitleSwap.computeTimeoutSeconds(0L));
        } catch (RuntimeException re) {
            logger.log(Level.FINE, "mkvmerge --identify threw for " + mediaFile, re);
            return false;
        }

        if (result == null || !result.success() || result.output() == null) {
            return false;
        }

        try {
            return jsonHasSubtitleLanguage(result.output(), langCode3);
        } catch (RuntimeException re) {
            // Be conservative: any parse glitch is treated as "not present" so the
            // merge proceeds.  This keeps idempotency best-effort, never blocking.
            logger.log(Level.FINE,
                "Failed to parse mkvmerge --identify output for " + mediaFile, re);
            return false;
        }
    }

    /**
     * Scan the (assumed JSON) {@code output} for any subtitle track whose
     * {@code properties.language} equals {@code langCode3} (case-insensitive).
     *
     * <p>The mkvmerge JSON emits each track as an object with {@code "id"},
     * {@code "properties"}, and {@code "type"} fields, and the field order is
     * <em>not</em> guaranteed across versions: the actual mkvmerge 98+ output we
     * observed places {@code "properties"} (containing {@code "language"})
     * <em>before</em> {@code "type"}, so a regex anchored on {@code "type"} and
     * scanning forward for {@code "language"} matches the next track's language
     * instead of the current track's.  Splitting on {@code "id":} is robust to
     * field-ordering changes: each chunk represents exactly one track, and we
     * check whether any chunk contains both {@code "type":"subtitles"} and a
     * matching language field (either {@code language} or {@code language_ietf}).
     */
    static boolean jsonHasSubtitleLanguage(String output, String langCode3) {
        if (output == null || output.isEmpty() || langCode3 == null) {
            return false;
        }
        // Each track entry contains exactly one "id":N, so splitting on it
        // gives us one element per track (plus a leading prelude with file/container info).
        String[] chunks = output.split("\"id\"\\s*:");
        for (String chunk : chunks) {
            // Must be a subtitles track (exact "type":"subtitles" match,
            // not just any occurrence of the substring "subtitles").
            if (!PATTERN_TYPE_SUBTITLES.matcher(chunk).find()) {
                continue;
            }
            // Either language or language_ietf must match.  Both fields use
            // 2- or 3-letter alpha codes so a single regex covers both.
            Matcher m = PATTERN_LANGUAGE_FIELD.matcher(chunk);
            while (m.find()) {
                String lang = m.group(1);
                if (lang != null && lang.equalsIgnoreCase(langCode3)) {
                    return true;
                }
            }
        }
        return false;
    }

    // merge() lives in AbstractSubtitleMerger (shared skeleton).  Note the
    // temp-file naming changed with the consolidation: previously this class
    // used <full-name>.merging.<ext>; the canonical scheme is now
    // SubtitleSwap.computeTempPath's <base>.merging.<ext>, same as MP4.

    /**
     * Parse a percentage from an mkvmerge {@code --gui-mode} progress line of
     * the form {@code "#GUI#progress 42%"}.  Package-private so tests can pin
     * the parser against real tool output.
     *
     * @param line one line of mkvmerge output
     * @return the clamped percentage [0, 100], or -1 if the line carries no
     *    parseable progress
     */
    static int parseProgressPercent(String line) {
        if (line == null) {
            return -1;
        }
        int idx = line.indexOf("#GUI#progress ");
        if (idx >= 0) {
            int percentEnd = line.indexOf('%', idx);
            if (percentEnd > idx) {
                try {
                    int pct = Integer.parseInt(
                        line.substring(idx + "#GUI#progress ".length(),
                            percentEnd).trim());
                    return Math.max(0, Math.min(100, pct));
                } catch (NumberFormatException ignored) {
                    // Bogus progress line.
                }
            }
        }
        return -1;
    }

    /**
     * Build the mkvmerge command line.  Per-input flags ({@code --language},
     * {@code --track-name}, descriptor flags) precede each subtitle path; mkvmerge
     * applies them to track 0 of the next input file.
     */
    static List<String> buildCommand(String toolPath,
                                     Path mediaFile,
                                     Path tempFile,
                                     List<SubtitleEntry> subtitles) {
        return buildCommand(toolPath, mediaFile, tempFile, subtitles, false);
    }

    /**
     * Variant that optionally adds {@code --gui-mode} so mkvmerge emits
     * parseable {@code #GUI#progress NN%} lines for live progress reporting.
     */
    static List<String> buildCommand(String toolPath,
                                     Path mediaFile,
                                     Path tempFile,
                                     List<SubtitleEntry> subtitles,
                                     boolean withGuiMode) {
        List<String> cmd = new ArrayList<>();
        cmd.add(toolPath);
        if (withGuiMode) {
            cmd.add("--gui-mode");
        }
        cmd.add("-o");
        cmd.add(tempFile.toString());
        cmd.add(mediaFile.toString());

        for (SubtitleEntry entry : subtitles) {
            cmd.add("--language");
            cmd.add("0:" + entry.langCode3());
            cmd.add("--track-name");
            cmd.add("0:" + entry.trackName());
            if (entry.descriptors() != null) {
                if (entry.descriptors().contains(Descriptor.FORCED)) {
                    cmd.add("--forced-display-flag");
                    cmd.add("0:1");
                }
                if (entry.descriptors().contains(Descriptor.SDH)) {
                    cmd.add("--hearing-impaired-flag");
                    cmd.add("0:1");
                }
                if (entry.descriptors().contains(Descriptor.COMMENTARY)) {
                    cmd.add("--commentary-flag");
                    cmd.add("0:1");
                }
                // SIGNS, SONGS, DUB have no mkvmerge per-track flag — they live
                // in the trackName already.
            }
            cmd.add(entry.file().toString());
        }
        return cmd;
    }

    // ---- Tool detection (shared DetectedTool cache) ----

    private static final org.tvrenamer.controller.util.DetectedTool TOOL =
        new org.tvrenamer.controller.util.DetectedTool("mkvmerge", () ->
            ExternalToolDetector.detect(
                new String[] { "mkvmerge" },
                new String[] {
                    "C:\\Program Files\\MKVToolNix\\mkvmerge.exe",
                    "C:\\Program Files (x86)\\MKVToolNix\\mkvmerge.exe"
                },
                new String[] {
                    "/usr/local/bin/mkvmerge",
                    "/opt/homebrew/bin/mkvmerge"
                }
            ));

    static boolean ensureDetected() {
        return TOOL.isAvailable();
    }

    /**
     * Force the cached tool path to a specific value for testing.  Pass an empty
     * string (or null) to simulate "tool not found".  Production never calls this.
     */
    static void setToolPathForTesting(String path) {
        TOOL.setForTesting(path);
    }

    /** Reset the detection cache so the next call re-detects.  Tests only. */
    static void resetDetectionForTesting() {
        TOOL.resetForTesting();
    }

    /** Return the currently cached tool path, or "mkvmerge" as a final fallback. */
    private static String currentToolPath() {
        String cached = TOOL.path();
        return cached.isEmpty() ? "mkvmerge" : cached;
    }

}
