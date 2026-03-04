package org.tvrenamer.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tvrenamer.model.EpisodeTestData;
import org.tvrenamer.model.FileEpisode;
import org.tvrenamer.model.MoveObserver;

/**
 * MoveTest -- test FileMover and MoveRunner.
 *
 * This file tests the functionality of FileMover and MoveRunner while trying
 * to avoid dependency on other classes as much as possible.
 *
 * The FileMover requires a FileEpisode, which has a whole bunch of information
 * in it, which it obtains from various places, including the user preferences,
 * the FilenameParser class, and the TVDB.  But we already have functionality
 * to create a filled-in FileEpisode without relying on the parser or provider.
 *
 * It does still rely on the user preferences, so we are sure to set all the
 * relevant settings.
 *
 * This class uses the TemporaryFolder functionality of jUnit to create and
 * move files that will be automatically cleaned up after each test completes.
 *
 */
public class MoveTest {

    private static Level savedLogLevel;

    /**
     * The specifics of how we rename and move files is very dependent on the
     * user preferences.  Set the values we expect here, before we run any
     * specific tests.
     *
     */
    @BeforeAll
    public static void initializePrefs() {
        savedLogLevel = FileMover.logger.getLevel();

        FileMover.userPrefs.setCheckForUpdates(false);

        FileMover.userPrefs.setSeasonPrefix("Season ");
        FileMover.userPrefs.setSeasonPrefixLeadingZero(false);
        FileMover.userPrefs.setMoveSelected(true);
        FileMover.userPrefs.setRenameSelected(true);
        FileMover.userPrefs.setRemoveEmptiedDirectories(false);

        // We don't want to see "info" level messages, or even warnings,
        // as we run tests.  Setting the level to "SEVERE" means nothing
        // below that level will be printed.
        FileMover.logger.setLevel(Level.SEVERE);
    }

    @AfterAll
    public static void restorePrefs() {
        FileMover.logger.setLevel(savedLogLevel);
    }

    private static final EpisodeTestData robotChicken0704 =
        new EpisodeTestData.Builder()
            .inputFilename("robot chicken/7x04.Rebel.Appliance.mp4")
            .filenameShow("robot chicken")
            .properShowName("Robot Chicken")
            .seasonNumString("7")
            .episodeNumString("04")
            .filenameSuffix(".mp4")
            .episodeTitle("Rebel Appliance")
            .episodeId("4874676")
            .replacementMask("S%0sE%0e %t")
            .expectedReplacement("S07E04 Rebel Appliance")
            .build();

    @TempDir
    Path tempFolder;

    Path destDir;
    FileEpisode episode;
    Path srcFile;
    Path srcDir;
    Path expectedDest;

    void setValues(final EpisodeTestData epdata) {
        Path sandbox = tempFolder.resolve("input");
        destDir = tempFolder.resolve("output");

        FileMover.userPrefs.setDestinationDirectory(destDir.toString());
        FileMover.userPrefs.setRenameReplacementString(epdata.replacementMask);

        episode = epdata.createFileEpisode(sandbox);
        srcFile = episode.getPath();
        srcDir = srcFile.getParent();
        if (srcDir == null) {
            // This really should not be the problem, but give it a shot.
            srcDir = srcFile.toAbsolutePath().getParent();
        }
        assertNotNull(srcDir);

        String seasonFolder =
            FileMover.userPrefs.getSeasonPrefix() + epdata.seasonNum;
        expectedDest = destDir
            .resolve(epdata.properShowName)
            .resolve(seasonFolder)
            .resolve(epdata.expectedReplacement + epdata.filenameSuffix);
    }

    void assertReady() {
        assertNotNull(episode, "failed to create FileEpisode");
        assertNotNull(srcFile, "FileEpisode does not have path");

        assertTrue(
            Files.exists(srcFile),
            "failed to create file for FileEpisode"
        );
        assertTrue(
            Files.notExists(destDir),
            "output dir exists before it should"
        );
    }

    void assertMoved() {
        assertTrue(
            Files.exists(expectedDest),
            "did not move\n" +
                srcFile +
                "\n to expected destination\n" +
                expectedDest +
                "\n (it appears to now be in\n" +
                episode.getPath() +
                ")"
        );
    }

    private void assertNotMoved() {
        assertTrue(
            Files.exists(srcFile),
            "although set to read-only " + srcFile + " is no longer in place"
        );
        assertTrue(
            Files.notExists(expectedDest),
            "although " +
                srcFile +
                " was read-only, destination " +
                expectedDest +
                " was created"
        );
        // We expect to create the actual dest dir -- the top level.
        // (Though, if not, that's ok, too.)
        // Presumably, in trying to move the file, we created some subdirs.
        // If so, they should be cleaned up by the time we get here.
        assertTrue(
            Files.notExists(destDir) || TestUtils.isDirEmpty(destDir),
            "extra files were created even though couldn't move file"
        );
    }

    void assertTimestamp(long expected) {
        long actualMillis = 0L;
        try {
            FileTime actualTimestamp = Files.getLastModifiedTime(expectedDest);
            actualMillis = actualTimestamp.toMillis();
        } catch (IOException ioe) {
            fail("could not obtain timestamp of " + expectedDest);
        }

        // This fork defaults to preserving original modification time for move/rename.
        // (Setting mtime to "now" is now an opt-in preference.)
        long difference = expected - actualMillis;
        assertTrue(
            Math.abs(difference) < 1000,
            "expected preserved modification time for " +
                expectedDest +
                " to match original; delta was " +
                difference +
                " ms"
        );
    }

    @Test
    public void testFileMover() {
        setValues(robotChicken0704);
        assertReady();

        // Default behavior now preserves original modification time.
        long originalMtime = 0L;
        try {
            originalMtime = Files.getLastModifiedTime(srcFile).toMillis();
        } catch (IOException ioe) {
            fail("could not obtain timestamp of " + srcFile);
        }

        FileMover mover = new FileMover(episode);
        mover.call();

        assertMoved();
        assertTimestamp(originalMtime);
    }

    @Test
    public void testFileMoverCannotMove() {
        setValues(robotChicken0704);

        // Pragmatic: on some platforms (especially Windows), we cannot reliably enforce
        // read-only behavior in unit tests without ACL manipulation. If we can't make the
        // paths effectively non-writable, skip this test rather than flaking.
        boolean fileReadOnly = TestUtils.setReadOnly(srcFile);
        boolean dirReadOnly = TestUtils.setReadOnly(srcDir);

        boolean enforceable =
            fileReadOnly &&
            dirReadOnly &&
            (!Files.isWritable(srcFile)) &&
            (!Files.isWritable(srcDir));

        if (!enforceable) {
            // Ensure cleanup does not fail if the environment partially applied changes.
            TestUtils.setWritable(srcDir);
            TestUtils.setWritable(srcFile);
            return;
        }

        assertReady();

        FileMover mover = new FileMover(episode);
        boolean didMove = mover.call();

        // Allow the framework to clean up by making the
        // files writable again.
        TestUtils.setWritable(srcDir);
        TestUtils.setWritable(srcFile);

        assertNotMoved();
        assertFalse(didMove, "FileMover.call returned true on read-only file");
    }

    static class FutureCompleter implements MoveObserver {

        private final CompletableFuture<Boolean> future;

        FutureCompleter(final CompletableFuture<Boolean> future) {
            this.future = future;
        }

        public void initializeProgress(long max) {
            // no-op
        }

        public void setProgressValue(long value) {
            // no-op
        }

        public void setProgressStatus(String status) {
            // no-op
        }

        public void finishProgress(FileEpisode episode) {
            future.complete(episode.isSuccess());
        }
    }

    void executeMoveRunnerTest(
        List<FileMover> moveList,
        CompletableFuture<Boolean> future
    ) {
        // Default behavior now preserves original modification time.
        // Capture the original mtime BEFORE starting the move; srcFile may no longer exist after a successful move.
        long originalMtime = 0L;
        try {
            originalMtime = Files.getLastModifiedTime(srcFile).toMillis();
        } catch (IOException ioe) {
            fail("could not obtain timestamp of " + srcFile);
        }

        MoveRunner runner = new MoveRunner(moveList);
        try {
            runner.runThread();
            boolean didMove = future.get(4, TimeUnit.SECONDS);

            assertMoved();
            assertTimestamp(originalMtime);
            assertTrue(
                didMove,
                "got " + didMove + " in finishProgress for successful move"
            );
        } catch (TimeoutException e) {
            String failMsg = "timeout trying to move " + srcFile;
            String exceptionMessage = e.getMessage();
            if (exceptionMessage != null) {
                failMsg += exceptionMessage;
            } else {
                failMsg += "(no message)";
            }
            fail(failMsg);
        } catch (Exception e) {
            fail(
                "failure (possibly interrupted?) trying to move " +
                    srcFile +
                    ": " +
                    e.getMessage()
            );
        }
    }

    @Test
    public void testMoveRunner() {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();

        setValues(robotChicken0704);
        assertReady();

        FileMover mover = new FileMover(episode);
        mover.addObserver(new FutureCompleter(future));

        List<FileMover> moveList = new ArrayList<>();
        moveList.add(mover);

        executeMoveRunnerTest(moveList, future);
    }

    @Test
    public void testMoveRunnerCannotMove() {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();

        setValues(robotChicken0704);

        // Pragmatic: on some platforms (especially Windows), we cannot reliably enforce
        // read-only behavior in unit tests without ACL manipulation. If we can't make the
        // paths effectively non-writable, skip this test rather than flaking.
        boolean fileReadOnly = TestUtils.setReadOnly(srcFile);
        boolean dirReadOnly = TestUtils.setReadOnly(srcDir);

        boolean enforceable =
            fileReadOnly &&
            dirReadOnly &&
            (!Files.isWritable(srcFile)) &&
            (!Files.isWritable(srcDir));

        if (!enforceable) {
            TestUtils.setWritable(srcDir);
            TestUtils.setWritable(srcFile);
            return;
        }

        assertReady();

        FileMover mover = new FileMover(episode);
        mover.addObserver(new FutureCompleter(future));

        List<FileMover> moveList = new ArrayList<>();
        moveList.add(mover);

        MoveRunner runner = new MoveRunner(moveList);
        try {
            runner.runThread();
            boolean didMove = future.get(4, TimeUnit.SECONDS);

            // We expect that the file will not be moved, and that the
            // observer will be called with a negative status.
            assertNotMoved();
            assertFalse(
                didMove,
                "expected to get false in finish progress, but got " + didMove
            );
        } catch (TimeoutException e) {
            String failMsg = "timeout trying to move " + srcFile;
            String exceptionMessage = e.getMessage();
            if (exceptionMessage != null) {
                failMsg += exceptionMessage;
            } else {
                failMsg += "(no message)";
            }
            fail(failMsg);
        } catch (Exception e) {
            fail(
                "failure (possibly interrupted?) trying to move " +
                    srcFile +
                    ": " +
                    e.getMessage()
            );
        } finally {
            // Allow the framework to clean up by making the
            // files writable again.
            TestUtils.setWritable(srcDir);
            TestUtils.setWritable(srcFile);
        }
    }
}
