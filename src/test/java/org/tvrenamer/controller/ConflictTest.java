package org.tvrenamer.controller;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.tvrenamer.model.EpisodeTestData;
import org.tvrenamer.model.util.Constants;

/**
 * ConflictTest -- test file moving functionality when there is a conflict.
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
public class ConflictTest extends MoveTest {

    private static final EpisodeTestData cosmicArray0322 =
        new EpisodeTestData.Builder()
            .inputFilename("cosmic.array.theory.322.mp4")
            .filenameShow("cosmic.array.theory")
            .properShowName("The Cosmic Array Theory")
            .seasonNumString("3")
            .episodeNumString("22")
            .filenameSuffix(".mp4")
            .episodeTitle("The Staircase Implementation")
            .episodeId("900201")
            .replacementMask("%S S%0sE%0e %t")
            .expectedReplacement(
                "The Cosmic Array Theory S03E22 The Staircase Implementation"
            )
            .build();

    private void makeConflict(
        final EpisodeTestData epdata,
        final FileMover mover
    ) {
        Path baseDestDir = mover.getMoveToDirectory();
        Path desiredDestDir = baseDestDir.resolve(
            Constants.DUPLICATES_DIRECTORY
        );
        String desiredFilename =
            epdata.expectedReplacement +
            mover.versionString() +
            epdata.filenameSuffix;

        // Create a file in the way, so that we will not be able to move the
        // source file to the desired destination
        TestUtils.createFile(desiredDestDir, desiredFilename);

        if (mover.destIndex == null) {
            mover.destIndex = 2;
        } else {
            mover.destIndex++;
        }

        expectedDest = desiredDestDir.resolve(desiredFilename);
    }

    @Test
    public void testFileMoverConflict() {
        setValues(cosmicArray0322);
        assertReady();

        // Default behavior now preserves original modification time.
        long originalMtime = 0L;
        try {
            originalMtime = Files.getLastModifiedTime(srcFile).toMillis();
        } catch (IOException ioe) {
            fail("could not obtain timestamp of " + srcFile);
        }

        FileMover mover = new FileMover(episode);
        makeConflict(cosmicArray0322, mover);
        mover.call();

        assertMoved();
        assertTimestamp(originalMtime);
    }

    @Test
    public void testMoveRunnerWithConflict() {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();

        setValues(cosmicArray0322);
        assertReady();

        FileMover mover = new FileMover(episode);
        makeConflict(cosmicArray0322, mover);
        mover.addObserver(new FutureCompleter(future));

        List<FileMover> moveList = new ArrayList<>();
        moveList.add(mover);

        executeMoveRunnerTest(moveList, future);
    }

    @Test
    public void testMoveRunnerWithTwoConflicts() {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();

        setValues(cosmicArray0322);
        assertReady();

        FileMover mover = new FileMover(episode);
        makeConflict(cosmicArray0322, mover);
        makeConflict(cosmicArray0322, mover);
        mover.addObserver(new FutureCompleter(future));

        List<FileMover> moveList = new ArrayList<>();
        moveList.add(mover);

        executeMoveRunnerTest(moveList, future);
    }

    @Test
    public void testMoveRunnerWithThreeConflicts() {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();

        setValues(cosmicArray0322);
        assertReady();

        FileMover mover = new FileMover(episode);
        makeConflict(cosmicArray0322, mover);
        makeConflict(cosmicArray0322, mover);
        makeConflict(cosmicArray0322, mover);
        mover.addObserver(new FutureCompleter(future));

        List<FileMover> moveList = new ArrayList<>();
        moveList.add(mover);

        executeMoveRunnerTest(moveList, future);
    }
}
