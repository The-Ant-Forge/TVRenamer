package org.tvrenamer.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.tvrenamer.model.EpisodeTestData;
import org.tvrenamer.model.FileEpisode;
import org.tvrenamer.model.UserPreferences;

/**
 * Verifies the subtitle-merge hooks wired into {@link FileMover}. None of these
 * tests spawn a real merge tool (mkvmerge / MP4Box). When the merge feature is
 * enabled and no tool is available, {@code SubtitleMergeController} returns
 * {@code NO_TOOL} and the move continues normally — that's exactly the regime
 * we exercise here, focusing on the FileMover-level invariants:
 *
 * <ul>
 *   <li>sibling subtitle files are captured at source time, before any move,</li>
 *   <li>they are deleted only after a successful move, and only when both
 *       {@code mergeSubtitles} and {@code deleteSubtitlesAfterMerge} are on,</li>
 *   <li>a failed move never deletes the user's only subtitle copy.</li>
 * </ul>
 *
 * <p>Real merge-tool invocations belong in a dedicated {@code @Tag("integration")}
 * suite that we'll add separately.
 */
public class FileMoverSubtitleHookTest {

    private static final EpisodeTestData WESTMARK_S01E02 =
        new EpisodeTestData.Builder()
            .inputFilename("westmark academy/Westmark.Academy.S01E02.mkv")
            .filenameShow("westmark academy")
            .properShowName("Westmark Academy")
            .seasonNumString("1")
            .episodeNumString("02")
            .filenameSuffix(".mkv")
            .episodeTitle("The Quiet Ones")
            .episodeId("100102")
            .replacementMask("S%0sE%0e %t")
            .expectedReplacement("S01E02 The Quiet Ones")
            .build();

    @TempDir
    Path tempFolder;

    /** Saved preference values, restored after each test. */
    private boolean savedMergeSubtitles;
    private String savedDefaultSubtitleLanguage;
    private boolean savedDeleteSubtitlesAfterMerge;
    private boolean savedTagVideoMetadata;
    private boolean savedRemoveEmptiedDirectories;
    private String savedDestDir;
    private String savedReplacementString;
    private String savedSeasonPrefix;
    private boolean savedSeasonPrefixLeadingZero;
    private boolean savedMoveSelected;
    private boolean savedRenameSelected;
    private Level savedLogLevel;

    @BeforeEach
    void saveAndConfigurePrefs() {
        UserPreferences prefs = FileMover.userPrefs;
        savedMergeSubtitles = prefs.isMergeSubtitles();
        savedDefaultSubtitleLanguage = prefs.getDefaultSubtitleLanguage();
        savedDeleteSubtitlesAfterMerge = prefs.isDeleteSubtitlesAfterMerge();
        savedTagVideoMetadata = prefs.isTagVideoMetadata();
        savedRemoveEmptiedDirectories = prefs.isRemoveEmptiedDirectories();
        savedDestDir = prefs.getDestinationDirectoryName();
        savedReplacementString = prefs.getRenameReplacementString();
        savedSeasonPrefix = prefs.getSeasonPrefix();
        savedSeasonPrefixLeadingZero = prefs.isSeasonPrefixLeadingZero();
        savedMoveSelected = prefs.isMoveSelected();
        savedRenameSelected = prefs.isRenameSelected();

        // Baseline: merge OFF; tests turn it on as needed.
        prefs.setMergeSubtitles(false);
        prefs.setDeleteSubtitlesAfterMerge(false);
        prefs.setTagVideoMetadata(false);
        prefs.setSeasonPrefix("Season ");
        prefs.setSeasonPrefixLeadingZero(false);
        prefs.setMoveSelected(true);
        prefs.setRenameSelected(true);
        prefs.setRemoveEmptiedDirectories(false);

        savedLogLevel = FileMover.logger.getLevel();
        FileMover.logger.setLevel(Level.SEVERE);
    }

    @AfterEach
    void restorePrefs() {
        UserPreferences prefs = FileMover.userPrefs;
        prefs.setMergeSubtitles(savedMergeSubtitles);
        prefs.setDefaultSubtitleLanguage(savedDefaultSubtitleLanguage);
        prefs.setDeleteSubtitlesAfterMerge(savedDeleteSubtitlesAfterMerge);
        prefs.setTagVideoMetadata(savedTagVideoMetadata);
        prefs.setRemoveEmptiedDirectories(savedRemoveEmptiedDirectories);
        if (savedDestDir != null) {
            prefs.setDestinationDirectory(savedDestDir);
        }
        if (savedReplacementString != null) {
            prefs.setRenameReplacementString(savedReplacementString);
        }
        if (savedSeasonPrefix != null) {
            prefs.setSeasonPrefix(savedSeasonPrefix);
        }
        prefs.setSeasonPrefixLeadingZero(savedSeasonPrefixLeadingZero);
        prefs.setMoveSelected(savedMoveSelected);
        prefs.setRenameSelected(savedRenameSelected);

        FileMover.logger.setLevel(savedLogLevel);
    }

    /**
     * Set up a media file + sibling .srt under {@code tempFolder/input}, with a
     * {@code tempFolder/output} destination. Returns the configured FileEpisode.
     */
    private Setup buildSetup() throws IOException {
        Path sandbox = tempFolder.resolve("input");
        Path destDir = tempFolder.resolve("output");

        UserPreferences prefs = FileMover.userPrefs;
        prefs.setDestinationDirectory(destDir.toString());
        prefs.setRenameReplacementString(WESTMARK_S01E02.replacementMask);

        FileEpisode episode = WESTMARK_S01E02.createFileEpisode(sandbox);
        assertNotNull(episode, "failed to create FileEpisode");
        Path srcFile = episode.getPath();
        assertTrue(Files.exists(srcFile), "media source file does not exist");

        // Create a sibling .srt next to the media file. Same base name so the
        // SubtitlePairing helper recognises it as paired.
        Path srcDir = srcFile.getParent();
        assertNotNull(srcDir, "src dir resolved to null");
        String srcName = srcFile.getFileName().toString();
        String base = srcName.substring(0, srcName.lastIndexOf('.'));
        Path srt = srcDir.resolve(base + ".srt");
        Files.writeString(srt, "1\n00:00:01,000 --> 00:00:02,000\nhello\n");

        Path expectedDest = destDir
            .resolve(WESTMARK_S01E02.properShowName)
            .resolve(prefs.getSeasonPrefix() + WESTMARK_S01E02.seasonNum)
            .resolve(WESTMARK_S01E02.expectedReplacement + WESTMARK_S01E02.filenameSuffix);

        return new Setup(episode, srcFile, srt, destDir, expectedDest);
    }

    /** Test fixture record. */
    private record Setup(FileEpisode episode, Path srcFile, Path srtFile, Path destDir, Path expectedDest) { }

    @Test
    void mergeDisabled_subtitleStaysAtSource() throws IOException {
        // mergeSubtitles is OFF (default). Subtitle stays put.
        Setup s = buildSetup();

        boolean moved = new FileMover(s.episode()).call();

        assertTrue(moved, "expected the move to succeed");
        assertTrue(Files.exists(s.expectedDest()), "media should have moved to the destination");
        assertTrue(Files.exists(s.srtFile()),
            "merge disabled: sibling .srt must remain at source location");
    }

    @Test
    void mergeEnabledButDeleteDisabled_subtitleStaysAtSource() throws IOException {
        FileMover.userPrefs.setMergeSubtitles(true);
        FileMover.userPrefs.setDeleteSubtitlesAfterMerge(false);

        Setup s = buildSetup();

        boolean moved = new FileMover(s.episode()).call();

        assertTrue(moved, "expected the move to succeed");
        assertTrue(Files.exists(s.expectedDest()), "media should have moved to the destination");
        assertTrue(Files.exists(s.srtFile()),
            "merge enabled but deleteAfterMerge OFF: sibling .srt must remain at source location");
    }

    @Test
    void bothEnabled_successfulMove_deletesSubtitleAtOriginalLocation() throws IOException {
        FileMover.userPrefs.setMergeSubtitles(true);
        FileMover.userPrefs.setDeleteSubtitlesAfterMerge(true);

        Setup s = buildSetup();
        Path srtAtSource = s.srtFile();

        boolean moved = new FileMover(s.episode()).call();

        assertTrue(moved, "expected the move to succeed");
        assertTrue(Files.exists(s.expectedDest()), "media should have moved to the destination");
        // The merge call returns NO_TOOL when neither MP4Box nor mkvmerge is on
        // PATH; the move proceeds normally and the success-path deletion runs.
        assertFalse(Files.exists(srtAtSource),
            "both prefs enabled and move succeeded: sibling .srt must be deleted from source");
    }

    @Test
    void bothEnabled_failedMove_subtitleSurvives() throws IOException {
        FileMover.userPrefs.setMergeSubtitles(true);
        FileMover.userPrefs.setDeleteSubtitlesAfterMerge(true);

        Setup s = buildSetup();

        // Force the move to fail by making the source directory read-only AND
        // turning the source file read-only. If that is unenforceable (e.g.
        // some Windows configurations without ACL helpers), we fall back to
        // pre-creating a non-writable destination directory.
        Path srcDir = s.srcFile().getParent();
        boolean srcDirReadOnly = TestUtils.setReadOnly(srcDir);
        boolean srcFileReadOnly = TestUtils.setReadOnly(s.srcFile());
        boolean enforceableViaReadonly = srcDirReadOnly
            && srcFileReadOnly
            && !Files.isWritable(srcDir)
            && !Files.isWritable(s.srcFile());

        try {
            if (!enforceableViaReadonly) {
                // Restore writability and use a different sabotage strategy:
                // pre-create the destination *file* path as a directory so the
                // rename fails. We can't easily do this through the existing
                // helpers; instead, we manually pre-create a non-writable file
                // at the destination so the rename refuses to overwrite.
                TestUtils.setWritable(srcDir);
                TestUtils.setWritable(s.srcFile());
                Files.createDirectories(s.expectedDest().getParent());
                Files.createDirectory(s.expectedDest()); // a directory at the file path
            }

            boolean moved = new FileMover(s.episode()).call();

            assertFalse(moved, "expected the move to FAIL in this test");
            assertTrue(Files.exists(s.srtFile()),
                "move failed: sibling .srt must NOT be deleted (recovery path)");
        } finally {
            TestUtils.setWritable(srcDir);
            TestUtils.setWritable(s.srcFile());
            // If we pre-created a directory at the dest path, drop it so the
            // @TempDir cleanup doesn't trip on it.
            if (Files.isDirectory(s.expectedDest())) {
                try {
                    Files.deleteIfExists(s.expectedDest());
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    @Test
    void deletionUsesOriginalSourcePaths_notDestinationPaths() throws IOException {
        FileMover.userPrefs.setMergeSubtitles(true);
        FileMover.userPrefs.setDeleteSubtitlesAfterMerge(true);

        Setup s = buildSetup();
        Path srtAtSource = s.srtFile();

        // Capture the destination directory before the move so we can verify
        // afterwards that NO subtitle file was ever created (or deleted) there.
        Path expectedDestDir = s.expectedDest().getParent();
        assertNotNull(expectedDestDir);

        boolean moved = new FileMover(s.episode()).call();

        assertTrue(moved, "expected the move to succeed");
        assertTrue(Files.exists(s.expectedDest()), "media should have moved to the destination");

        // The original .srt path must be gone (deleted at source, not at dest).
        assertFalse(Files.exists(srtAtSource),
            "sibling .srt must be deleted at the captured source path");

        // No phantom .srt should appear in the destination directory.
        Path destSrt = expectedDestDir.resolve(srtAtSource.getFileName().toString());
        assertFalse(Files.exists(destSrt),
            "no .srt should ever appear in the destination — captured paths are source paths");
    }
}
