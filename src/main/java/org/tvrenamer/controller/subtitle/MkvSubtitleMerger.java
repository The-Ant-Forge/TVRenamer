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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tvrenamer.controller.util.ExternalToolDetector;
import org.tvrenamer.controller.util.ProcessRunner;
import org.tvrenamer.controller.util.StringUtils;

import java.util.function.IntConsumer;

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
public final class MkvSubtitleMerger implements SubtitleMerger {

    private static final Logger logger = Logger.getLogger(MkvSubtitleMerger.class.getName());

    private static final Set<String> CONTAINER_EXTENSIONS = Set.of(".mkv");

    /** Subtitle formats mkvmerge ingests natively. */
    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(".srt", ".ass", ".ssa", ".vtt");

    /** Cached mkvmerge path (null = not checked yet, empty = not found). */
    private static volatile String toolPath = null;
    private static final Object DETECTION_LOCK = new Object();

    // ---- Process indirection (constructor-injected) ----

    /** Per-instance process runner for tests to inject fakes via the package-private constructor. */
    private final ProcessOps.Run runOp;

    /** Per-instance streaming process runner — used during the merge for progress parsing. */
    private final ProcessOps.Streaming streamingOp;

    /** Public no-arg constructor for production: routes through {@link ProcessRunner}. */
    public MkvSubtitleMerger() {
        this(ProcessOps.REAL, ProcessOps.REAL_STREAMING);
    }

    /** Test constructor: package-private, accepts injected process operations. */
    MkvSubtitleMerger(ProcessOps.Run runOp, ProcessOps.Streaming streamingOp) {
        if (runOp == null || streamingOp == null) {
            throw new IllegalArgumentException("ProcessOps must not be null");
        }
        this.runOp = runOp;
        this.streamingOp = streamingOp;
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
            result = runOp.run(cmd, SubtitleSwap.computeTimeoutSeconds(0L));
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

    @Override
    public MergeOutcome merge(
            Path mediaFile,
            List<SubtitleEntry> subtitles,
            IntConsumer onProgress) {
        if (subtitles == null || subtitles.isEmpty()) {
            // Defensive: the controller filters before calling, so this is a vacuous
            // success rather than an error.
            return MergeOutcome.SUCCESS;
        }
        if (!ensureDetected()) {
            return MergeOutcome.SKIPPED_NO_TOOL;
        }
        if (mediaFile == null) {
            // Mirror Mp4SubtitleMerger: fail cleanly rather than NPE below.
            return MergeOutcome.FAILED;
        }

        String fileName = mediaFile.getFileName().toString();
        String ext = StringUtils.getExtension(fileName);
        Path parent = mediaFile.getParent();
        Path tempFile = (parent != null)
            ? parent.resolve(fileName + ".merging" + ext)
            : Path.of(fileName + ".merging" + ext);

        long sourceBytes;
        try {
            sourceBytes = Files.size(mediaFile);
        } catch (IOException ioe) {
            logger.log(Level.WARNING, "Could not size source file: " + mediaFile, ioe);
            return MergeOutcome.FAILED;
        }

        List<String> cmd = buildCommand(currentToolPath(), mediaFile, tempFile,
            subtitles, onProgress != null);
        int timeoutSeconds = SubtitleSwap.computeTimeoutSeconds(sourceBytes);

        // Parse #GUI#progress NN% lines from --gui-mode output and forward
        // the percentage to the caller.  Errors in the consumer are caught
        // by ProcessRunner.runStreaming, so we don't have to.
        java.util.function.Consumer<String> lineSink = (onProgress == null)
            ? null
            : line -> {
                int idx = line.indexOf("#GUI#progress ");
                if (idx >= 0) {
                    int percentEnd = line.indexOf('%', idx);
                    if (percentEnd > idx) {
                        try {
                            int pct = Integer.parseInt(
                                line.substring(idx + "#GUI#progress ".length(),
                                    percentEnd).trim());
                            onProgress.accept(Math.max(0, Math.min(100, pct)));
                        } catch (NumberFormatException ignored) {
                            // Bogus progress line; ignore.
                        }
                    }
                }
            };

        // When no progress consumer is requested we use the original
        // non-streaming runProcess overload — keeps test fakes that only
        // override the 2-arg version working unchanged.
        ProcessRunner.Result result;
        try {
            result = (lineSink == null)
                ? runOp.run(cmd, timeoutSeconds)
                : streamingOp.run(cmd, timeoutSeconds, lineSink);
        } catch (RuntimeException re) {
            // Mirror Mp4SubtitleMerger: a throwing process invocation (e.g.
            // SecurityException from ProcessBuilder.start) must not leak the
            // temp file or propagate out of the merger.
            logger.log(Level.WARNING, "mkvmerge threw while merging " + mediaFile, re);
            tryDelete(tempFile);
            return MergeOutcome.FAILED;
        }

        if (result == null || !result.success()) {
            int exitCode = (result == null) ? -1 : result.exitCode();
            String output = (result == null) ? "" : result.output();
            logger.warning("mkvmerge failed (exit " + exitCode + ") for: " + mediaFile
                + "\nOutput: " + tail(output, 1000));
            tryDelete(tempFile);
            return MergeOutcome.FAILED;
        }

        boolean intact;
        try {
            intact = SubtitleSwap.integrityGate(tempFile, mediaFile);
        } catch (IOException ioe) {
            logger.log(Level.WARNING,
                "Integrity gate IO error for " + mediaFile + " (temp: " + tempFile + ")", ioe);
            tryDelete(tempFile);
            return MergeOutcome.FAILED;
        }
        if (!intact) {
            logger.warning("Integrity gate failed for " + mediaFile
                + "; temp file " + tempFile + " was below the 80% size floor.");
            tryDelete(tempFile);
            return MergeOutcome.FAILED;
        }

        boolean swapped = SubtitleSwap.swap(tempFile, mediaFile);
        if (!swapped) {
            logger.warning("Atomic swap failed for " + mediaFile
                + "; merged temp file is preserved at " + tempFile + " for manual recovery.");
            return MergeOutcome.FAILED;
        }

        int n = subtitles.size();
        logger.info("Merged " + n + " subtitle track" + (n == 1 ? "" : "s")
            + " into " + mediaFile);
        return MergeOutcome.SUCCESS;
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

    // ---- Tool detection (cache once per JVM) ----

    /**
     * Resolve mkvmerge's path once and cache.  Returns {@code true} if a usable
     * mkvmerge was found (path non-empty).  Mirrors the double-checked pattern
     * in {@link org.tvrenamer.controller.metadata.Mp4MetadataTagger#ensureDetected()}.
     */
    static boolean ensureDetected() {
        String cached = toolPath;
        if (cached != null) {
            return !cached.isEmpty();
        }

        synchronized (DETECTION_LOCK) {
            if (toolPath != null) {
                return !toolPath.isEmpty();
            }

            String detected = detectMkvmerge();
            toolPath = detected;
            if (detected.isEmpty()) {
                logger.info("mkvmerge not found - MKV subtitle merging will be disabled");
            } else {
                logger.info("Found mkvmerge: " + detected);
            }
            return !toolPath.isEmpty();
        }
    }

    private static String detectMkvmerge() {
        return ExternalToolDetector.detect(
            new String[] { "mkvmerge" },
            new String[] {
                "C:\\Program Files\\MKVToolNix\\mkvmerge.exe",
                "C:\\Program Files (x86)\\MKVToolNix\\mkvmerge.exe"
            },
            new String[] {
                "/usr/local/bin/mkvmerge",
                "/opt/homebrew/bin/mkvmerge"
            }
        );
    }

    /**
     * Force the cached tool path to a specific value for testing.  Pass an empty
     * string to simulate "tool not found"; pass any non-empty value to simulate
     * a detected install at that path.  Production code never calls this.
     */
    static void setToolPathForTesting(String path) {
        toolPath = path;
    }

    /** Reset the detection cache so the next call re-detects.  Tests only. */
    static void resetDetectionForTesting() {
        toolPath = null;
    }

    /** Return the currently cached tool path, or "mkvmerge" as a final fallback. */
    private static String currentToolPath() {
        String cached = toolPath;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return "mkvmerge";
    }

    // ---- Helpers ----

    /** Best-effort cleanup of the temp file; failures are silent. */
    private static void tryDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Return the last {@code maxChars} characters of {@code s}, or {@code s} if shorter. */
    private static String tail(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(s.length() - maxChars);
    }
}
