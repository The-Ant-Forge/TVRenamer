package org.tvrenamer.controller.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.tvrenamer.controller.util.ExternalToolDetector;
import org.tvrenamer.controller.util.ProcessRunner;
import org.tvrenamer.controller.util.StringUtils;
import org.tvrenamer.model.Episode;
import org.tvrenamer.model.EpisodePlacement;
import org.tvrenamer.model.FileEpisode;
import org.tvrenamer.model.Show;

/**
 * Tags MP4 files with TV episode metadata using an external tool.
 *
 * <p>Prefers AtomicParsley (surgical iTunes atom edits) with ffmpeg as a
 * fallback (full container rewrite with {@code -c copy}).  If neither tool
 * is found, MP4 files are silently skipped.
 *
 * <p>Writes iTunes-style TV atoms for broad media manager compatibility:
 * tvsh, ©alb, tvsn, tves, tven, ©nam, ©day, stik.
 */
public class Mp4MetadataTagger implements VideoMetadataTagger {

    private static final Logger logger = Logger.getLogger(Mp4MetadataTagger.class.getName());

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".mp4", ".m4v", ".mov"
    );

    /** Which external tool was detected. */
    private enum Tool { ATOMIC_PARSLEY, FFMPEG, NONE }

    // Shared detection caches (Round-4 #24 consolidation), one per tool.
    // AtomicParsley is preferred; ffmpeg is the fallback.  The lazy probes
    // preserve the original ordering: ffmpeg is only probed when
    // AtomicParsley is absent.
    private static final org.tvrenamer.controller.util.DetectedTool AP_TOOL =
        new org.tvrenamer.controller.util.DetectedTool(
            "AtomicParsley", Mp4MetadataTagger::detectAtomicParsley);
    private static final org.tvrenamer.controller.util.DetectedTool FFMPEG_TOOL =
        new org.tvrenamer.controller.util.DetectedTool(
            "ffmpeg (MP4-tagging fallback)", Mp4MetadataTagger::detectFfmpeg);

    /** @return which tool tagging will use, probing lazily. */
    private static Tool currentTool() {
        if (AP_TOOL.isAvailable()) {
            return Tool.ATOMIC_PARSLEY;
        }
        if (FFMPEG_TOOL.isAvailable()) {
            return Tool.FFMPEG;
        }
        return Tool.NONE;
    }

    /** @return the active tool's path, or "" when neither is installed. */
    private static String currentToolPath() {
        return switch (currentTool()) {
            case ATOMIC_PARSLEY -> AP_TOOL.path();
            case FFMPEG -> FFMPEG_TOOL.path();
            case NONE -> "";
        };
    }

    /** Reset the cached detection; tests use this to avoid probing the host PATH. */
    static void resetDetectionForTesting() {
        AP_TOOL.resetForTesting();
        FFMPEG_TOOL.resetForTesting();
    }

    /** Force a detection state (null = "no tool found"); tests only. */
    static void setToolPathForTesting(String path) {
        AP_TOOL.setForTesting(path);
        FFMPEG_TOOL.setForTesting(null);
    }

    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    // Process indirection (constructor-injected), mirroring the mergers:
    // tests can now reach the tagging paths without spawning real binaries.
    private final org.tvrenamer.controller.util.ProcessOps.Run runOp;

    /** Production constructor: routes through {@link ProcessRunner}. */
    public Mp4MetadataTagger() {
        this(org.tvrenamer.controller.util.ProcessOps.REAL);
    }

    /** Test constructor: accepts an injected process operation. */
    Mp4MetadataTagger(org.tvrenamer.controller.util.ProcessOps.Run runOp) {
        if (runOp == null) {
            throw new IllegalArgumentException("ProcessOps must not be null");
        }
        this.runOp = runOp;
    }

    @Override
    public boolean supportsExtension(String extension) {
        if (extension == null) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean tagFile(Path videoFile, FileEpisode episode) {

        if (currentTool() == Tool.NONE) {
            logger.log(Level.FINE, () -> "No MP4 tagging tool found - skipping " + videoFile);
            return true; // Not an error, just skip
        }

        // Validate inputs
        Show show = episode.getActualShow();
        Episode ep = episode.getActualEpisode();

        if (show == null) {
            logger.warning("Cannot tag: missing show data for " + videoFile);
            return false;
        }

        if (ep == null) {
            logger.warning("Cannot tag: missing episode data for " + videoFile);
            return false;
        }

        EpisodePlacement placement = episode.getEpisodePlacement();
        if (placement == null) {
            logger.warning("Cannot tag: missing episode placement for " + videoFile);
            return false;
        }

        // Extract metadata
        String showName = show.getName();
        int season = placement.season();
        int episodeNum = placement.episode();
        String episodeTitle = ep.getTitle();
        LocalDate airDate = ep.getAirDate();

        // Filename without extension for the title/display name
        String filename = videoFile.getFileName().toString();
        String filenameNoExt = StringUtils.getBaseName(filename);

        String airDateStr = (airDate != null) ? airDate.toString() : null;

        logger.log(Level.FINE, () -> "Tagging MP4 " + filename + " with: " +
            "show=" + showName + ", S" + season + "E" + episodeNum +
            ", title=" + episodeTitle + ", tool=" + currentTool());

        if (currentTool() == Tool.ATOMIC_PARSLEY) {
            return tagWithAtomicParsley(videoFile, showName, season, episodeNum,
                episodeTitle, filenameNoExt, airDateStr);
        } else {
            return tagWithFfmpeg(videoFile, showName, season, episodeNum,
                episodeTitle, filenameNoExt, airDateStr);
        }
    }

    // ---- AtomicParsley ----

    private boolean tagWithAtomicParsley(Path videoFile, String showName,
            int season, int episodeNum, String episodeTitle,
            String filenameNoExt, String airDateStr) {
        List<String> cmd = new ArrayList<>();
        cmd.add(currentToolPath());
        cmd.add(videoFile.toString());
        cmd.add("--overWrite");

        cmd.add("--TVShowName");
        cmd.add(showName);
        cmd.add("--TVSeasonNum");
        cmd.add(String.valueOf(season));
        cmd.add("--TVEpisodeNum");
        cmd.add(String.valueOf(episodeNum));

        if (episodeTitle != null && !episodeTitle.isBlank()) {
            cmd.add("--TVEpisode");
            cmd.add(episodeTitle);
        }

        cmd.add("--title");
        cmd.add(filenameNoExt);
        cmd.add("--album");
        cmd.add(showName);

        if (airDateStr != null) {
            cmd.add("--year");
            cmd.add(airDateStr);
        }

        cmd.add("--stik");
        cmd.add("TV Show");

        return runProcess(cmd, videoFile);
    }

    // ---- ffmpeg fallback ----

    private boolean tagWithFfmpeg(Path videoFile, String showName,
            int season, int episodeNum, String episodeTitle,
            String filenameNoExt, String airDateStr) {

        // ffmpeg cannot edit in place; write to temp then replace.
        Path tempFile = null;
        try {
            String name = videoFile.getFileName().toString();
            String ext = StringUtils.getExtension(name);
            tempFile = Files.createTempFile(videoFile.getParent(), ".tvr-tag-", ext);

            List<String> cmd = new ArrayList<>();
            cmd.add(currentToolPath());
            cmd.add("-y");            // overwrite temp file
            cmd.add("-i");
            cmd.add(videoFile.toString());
            cmd.add("-c");
            cmd.add("copy");          // no re-encoding

            addFfmpegMeta(cmd, "show", showName);
            addFfmpegMeta(cmd, "season_number", String.valueOf(season));
            addFfmpegMeta(cmd, "episode_sort", String.valueOf(episodeNum));
            addFfmpegMeta(cmd, "title", filenameNoExt);
            addFfmpegMeta(cmd, "album", showName);

            if (episodeTitle != null && !episodeTitle.isBlank()) {
                addFfmpegMeta(cmd, "episode_id", episodeTitle);
            }
            if (airDateStr != null) {
                addFfmpegMeta(cmd, "date", airDateStr);
            }
            addFfmpegMeta(cmd, "media_type", "10");

            cmd.add(tempFile.toString());

            boolean ok = runProcess(cmd, videoFile);
            if (ok) {
                Files.move(tempFile, videoFile, StandardCopyOption.REPLACE_EXISTING);
                tempFile = null; // moved successfully
                return true;
            }
            return false;

        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed ffmpeg tagging for: " + videoFile, e);
            return false;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static void addFfmpegMeta(List<String> cmd, String key, String value) {
        cmd.add("-metadata");
        cmd.add(key + "=" + value);
    }

    private boolean runProcess(List<String> command, Path videoFile) {
        ProcessRunner.Result result = runOp.run(command, PROCESS_TIMEOUT_SECONDS);
        if (!result.success()) {
            if (result.exitCode() == -1) {
                logger.warning(currentTool() + " timed out or failed to run for: " + videoFile);
            } else {
                logger.warning(currentTool() + " failed (exit " + result.exitCode() + ") for: "
                    + videoFile + "\nOutput: " + result.output());
            }
        }
        return result.success();
    }

    // ---- Tool detection ----

    // Detection now lives in the shared DetectedTool caches above
    // (currentTool() / currentToolPath()); probes run lazily on first use.

    private static String detectAtomicParsley() {
        return ExternalToolDetector.detect(
            new String[] { "AtomicParsley", "atomicparsley" },
            new String[] {
                "C:\\Program Files\\AtomicParsley\\AtomicParsley.exe",
                "C:\\Program Files (x86)\\AtomicParsley\\AtomicParsley.exe"
            },
            new String[] {
                "/usr/local/bin/AtomicParsley",
                "/opt/homebrew/bin/AtomicParsley"
            }
        );
    }

    private static String detectFfmpeg() {
        return ExternalToolDetector.detect(
            new String[] { "ffmpeg" },
            new String[] {
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg.exe"
            },
            new String[] {
                "/usr/local/bin/ffmpeg",
                "/opt/homebrew/bin/ffmpeg"
            }
        );
    }

    @Override
    public boolean isToolAvailable() {
        return currentTool() != Tool.NONE;
    }

    @Override
    public String getToolName() {
        return switch (currentTool()) {
            case ATOMIC_PARSLEY -> "AtomicParsley";
            case FFMPEG -> "ffmpeg";
            case NONE -> "none";
        };
    }
}
