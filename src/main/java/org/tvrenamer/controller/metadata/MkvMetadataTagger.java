package org.tvrenamer.controller.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 * Tags MKV files with TV episode metadata using mkvpropedit (MKVToolNix).
 *
 * This tagger requires mkvpropedit to be installed on the system.
 * If mkvpropedit is not found, MKV files are silently skipped.
 *
 * Writes Matroska tags at multiple target levels for broad compatibility:
 * - Target 70 (Collection): Show name, content type
 * - Target 60 (Season): Season number
 * - Target 50 (Episode): Episode title, number, air date
 */
public class MkvMetadataTagger implements VideoMetadataTagger {

    private static final Logger logger = Logger.getLogger(MkvMetadataTagger.class.getName());

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".mkv", ".webm");

    // Shared detection cache (Round-4 #24 consolidation).
    private static final org.tvrenamer.controller.util.DetectedTool TOOL =
        new org.tvrenamer.controller.util.DetectedTool("mkvpropedit", () ->
            ExternalToolDetector.detect(
                new String[] { "mkvpropedit" },
                new String[] {
                    "C:\\Program Files\\MKVToolNix\\mkvpropedit.exe",
                    "C:\\Program Files (x86)\\MKVToolNix\\mkvpropedit.exe"
                },
                new String[] {
                    "/usr/local/bin/mkvpropedit",
                    "/opt/homebrew/bin/mkvpropedit"
                }
            ));

    /** Reset the cached detection; tests use this to avoid probing the host PATH. */
    static void resetDetectionForTesting() {
        TOOL.resetForTesting();
    }

    /** Force a detection state (null = "no tool found"); tests only. */
    static void setToolPathForTesting(String path) {
        TOOL.setForTesting(path);
    }

    // Process timeout in seconds
    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    // Process indirection (constructor-injected), mirroring the mergers:
    // tests can now reach runMkvpropedit without spawning real binaries.
    private final org.tvrenamer.controller.util.ProcessOps.Run runOp;

    /** Production constructor: routes through {@link ProcessRunner}. */
    public MkvMetadataTagger() {
        this(org.tvrenamer.controller.util.ProcessOps.REAL);
    }

    /** Test constructor: accepts an injected process operation. */
    MkvMetadataTagger(org.tvrenamer.controller.util.ProcessOps.Run runOp) {
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
        // Check if mkvpropedit is available
        String mkvpropedit = getMkvpropeditPath();
        if (mkvpropedit == null || mkvpropedit.isEmpty()) {
            logger.log(Level.FINE, () -> "mkvpropedit not found - skipping MKV tagging for " + videoFile);
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

        // Get filename without extension for segment title (display name)
        String filename = videoFile.getFileName().toString();
        String filenameNoExt = StringUtils.getBaseName(filename);

        logger.log(Level.FINE, () -> "Tagging MKV " + videoFile.getFileName() + " with: " +
            "show=" + showName + ", S" + season + "E" + episodeNum +
            ", title=" + episodeTitle + ", segmentTitle=" + filenameNoExt);

        Path tagsFile = null;
        try {
            // Generate XML tags file
            tagsFile = generateTagsXml(showName, season, episodeNum, episodeTitle, airDate);

            // Run mkvpropedit
            boolean success = runMkvpropedit(mkvpropedit, videoFile, tagsFile, filenameNoExt);

            if (success) {
                logger.log(Level.FINE, () -> "Successfully tagged MKV: " + videoFile.getFileName());
            }
            return success;

        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to tag MKV: " + videoFile, e);
            return false;
        } finally {
            // Clean up temp file
            if (tagsFile != null) {
                try {
                    Files.deleteIfExists(tagsFile);
                } catch (IOException ignored) {
                    // Best effort cleanup
                }
            }
        }
    }

    /**
     * Get the path to mkvpropedit via the shared detection cache.
     *
     * @return path to mkvpropedit executable, or empty string if not found
     */
    private static String getMkvpropeditPath() {
        return TOOL.path();
    }

    /**
     * Generate Matroska tags XML file.
     */
    private Path generateTagsXml(String showName, int season, int episodeNum,
                                  String episodeTitle, LocalDate airDate) throws IOException {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Tags>\n");

        // Series/Collection level (Target 70)
        xml.append("  <Tag>\n");
        xml.append("    <Targets>\n");
        xml.append("      <TargetTypeValue>70</TargetTypeValue>\n");
        xml.append("      <TargetType>COLLECTION</TargetType>\n");
        xml.append("    </Targets>\n");
        xml.append("    <Simple>\n");
        xml.append("      <Name>TITLE</Name>\n");
        xml.append("      <String>").append(escapeXml(showName)).append("</String>\n");
        xml.append("    </Simple>\n");
        xml.append("    <Simple>\n");
        xml.append("      <Name>COLLECTION</Name>\n");
        xml.append("      <String>").append(escapeXml(showName)).append("</String>\n");
        xml.append("    </Simple>\n");
        xml.append("    <Simple>\n");
        xml.append("      <Name>CONTENT_TYPE</Name>\n");
        xml.append("      <String>TV Show</String>\n");
        xml.append("    </Simple>\n");
        xml.append("  </Tag>\n");

        // Season level (Target 60)
        xml.append("  <Tag>\n");
        xml.append("    <Targets>\n");
        xml.append("      <TargetTypeValue>60</TargetTypeValue>\n");
        xml.append("      <TargetType>SEASON</TargetType>\n");
        xml.append("    </Targets>\n");
        xml.append("    <Simple>\n");
        xml.append("      <Name>PART_NUMBER</Name>\n");
        xml.append("      <String>").append(season).append("</String>\n");
        xml.append("    </Simple>\n");
        xml.append("    <Simple>\n");
        xml.append("      <Name>TITLE</Name>\n");
        xml.append("      <String>Season ").append(season).append("</String>\n");
        xml.append("    </Simple>\n");
        xml.append("  </Tag>\n");

        // Episode level (Target 50)
        xml.append("  <Tag>\n");
        xml.append("    <Targets>\n");
        xml.append("      <TargetTypeValue>50</TargetTypeValue>\n");
        xml.append("      <TargetType>EPISODE</TargetType>\n");
        xml.append("    </Targets>\n");
        if (episodeTitle != null && !episodeTitle.isBlank()) {
            xml.append("    <Simple>\n");
            xml.append("      <Name>TITLE</Name>\n");
            xml.append("      <String>").append(escapeXml(episodeTitle)).append("</String>\n");
            xml.append("    </Simple>\n");
        }
        xml.append("    <Simple>\n");
        xml.append("      <Name>PART_NUMBER</Name>\n");
        xml.append("      <String>").append(episodeNum).append("</String>\n");
        xml.append("    </Simple>\n");
        if (airDate != null) {
            String dateStr = airDate.toString(); // ISO-8601
            xml.append("    <Simple>\n");
            xml.append("      <Name>DATE_RELEASED</Name>\n");
            xml.append("      <String>").append(dateStr).append("</String>\n");
            xml.append("    </Simple>\n");
            xml.append("    <Simple>\n");
            xml.append("      <Name>DATE_RECORDED</Name>\n");
            xml.append("      <String>").append(dateStr).append("</String>\n");
            xml.append("    </Simple>\n");
        }
        xml.append("  </Tag>\n");

        xml.append("</Tags>\n");

        // Write to temp file
        Path tagsFile = Files.createTempFile("tvr-mkv-tags-", ".xml");
        Files.writeString(tagsFile, xml.toString(), StandardCharsets.UTF_8);
        return tagsFile;
    }

    /**
     * Run mkvpropedit to apply tags and segment title.
     */
    private boolean runMkvpropedit(String mkvpropedit, Path videoFile, Path tagsFile,
                                    String segmentTitle) {
        List<String> command = List.of(
            mkvpropedit,
            videoFile.toString(),
            "--tags", "global:" + tagsFile.toString(),
            "--edit", "info",
            "--set", "title=" + segmentTitle
        );

        ProcessRunner.Result result = runOp.run(command, PROCESS_TIMEOUT_SECONDS);
        if (!result.success()) {
            if (result.exitCode() == -1) {
                logger.warning("mkvpropedit timed out or failed to run for: " + videoFile);
            } else {
                logger.warning("mkvpropedit failed (exit " + result.exitCode() + ") for: "
                    + videoFile + "\nOutput: " + result.output());
            }
        }
        return result.success();
    }

    /** Delegate to shared XML escaping utility. */
    private static String escapeXml(String s) {
        return StringUtils.escapeXml(s);
    }

    @Override
    public boolean isToolAvailable() {
        String path = getMkvpropeditPath();
        return path != null && !path.isEmpty();
    }

    @Override
    public String getToolName() {
        String path = getMkvpropeditPath();
        return (path != null && !path.isEmpty()) ? "mkvpropedit" : "none";
    }
}
