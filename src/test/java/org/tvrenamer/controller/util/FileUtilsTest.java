package org.tvrenamer.controller.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.tvrenamer.controller.util.FileUtilities.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.tvrenamer.model.util.Environment;

public class FileUtilsTest {

    private static final String PRESUMED_NONEXISTENT_PATH =
        "/Usurs/me/Documents/oops";

    @TempDir
    Path tempFolder;

    @BeforeAll
    public static void setLogging() {
        // No-op: FileUtilities.loggingOff() is deprecated. Logging behavior should be
        // controlled via logging.properties; tests should not mutate global logging.
    }

    @AfterAll
    public static void restoreLogging() {
        // No-op: FileUtilities.loggingOn() is deprecated. Logging behavior should be
        // controlled via logging.properties; tests should not mutate global logging.
    }

    @Test
    public void testExistingAncestor() {
        final String dirname = "folder";

        final Path sandbox = tempFolder;

        final Path dirpath = sandbox.resolve(dirname);
        assertTrue(
            ensureWritableDirectory(dirpath),
            "cannot test existingAncestor because can't ensure writable directory" +
                dirpath
        );

        assertEquals(
            dirpath,
            existingAncestor(dirpath),
            "existingAncestor(Path) failed to recognize path itself exists"
        );

        Path uncreatable = Paths.get(PRESUMED_NONEXISTENT_PATH);
        assertEquals(
            uncreatable.getRoot(),
            existingAncestor(uncreatable),
            "existingAncestor(Path) failed to find root as answer for " +
                uncreatable
        );
    }

    @Test
    public void testExistingAncestorSymlinks() {
        if (!Environment.IS_WINDOWS) {
            final String dirname = "folder";

            final Path sandbox = tempFolder;

            final Path dirpath = sandbox.resolve(dirname);
            assertTrue(
                ensureWritableDirectory(dirpath),
                "cannot test existingAncestor because can't ensure writable directory" +
                    dirpath
            );

            // Create a "normal" symbolic link to dirpath
            Path validLink = sandbox.resolve("slink");
            String firstSubDir = "showname";
            Path toBeUnderLink = validLink
                .resolve(firstSubDir)
                .resolve("season")
                .resolve("episode");
            assertEquals(
                sandbox,
                existingAncestor(validLink),
                "existingAncestor(Path) failed to find " +
                    sandbox +
                    " as answer for " +
                    validLink
            );
            assertEquals(
                sandbox,
                existingAncestor(toBeUnderLink),
                "existingAncestor(Path) failed to find " +
                    sandbox +
                    " as answer for " +
                    toBeUnderLink
            );

            try {
                Files.createSymbolicLink(validLink, dirpath);
            } catch (IOException x) {
                fail(
                    "unable to create link from " + validLink + " to " + dirpath
                );
            }
            assertTrue(
                Files.isSymbolicLink(validLink),
                "did not detect " + validLink + " as a symbolic link"
            );
            assertFalse(
                Files.notExists(dirpath),
                "after creating link, " + dirpath + " not exists"
            );
            assertFalse(
                Files.notExists(validLink),
                "after creating link, " + validLink + " not exists"
            );
            assertEquals(
                dirpath,
                existingAncestor(dirpath),
                "after link, existingAncestor(Path) failed to find itself" +
                    " as answer for " +
                    dirpath
            );
            assertEquals(
                validLink,
                existingAncestor(validLink),
                "after link, existingAncestor(Path) failed to find itself" +
                    " as answer for " +
                    validLink
            );
            assertEquals(
                validLink,
                existingAncestor(toBeUnderLink),
                "existingAncestor(Path) failed to find " +
                    validLink +
                    " as answer for " +
                    toBeUnderLink
            );

            final Path subdir = dirpath.resolve(firstSubDir);
            assertFalse(
                Files.exists(subdir),
                "cannot do ensureWritableDirectory because target already exists"
            );
            assertTrue(
                ensureWritableDirectory(subdir),
                "ensureWritableDirectory returned false"
            );
            assertTrue(
                Files.exists(subdir),
                "dir from ensureWritableDirectory not found"
            );
            assertTrue(
                Files.isDirectory(subdir),
                "dir from ensureWritableDirectory not a directory"
            );

            ////////////////////////////////////////////////////////////////////////////////////////////
            // We're going to do a very bad thing here.  We're going to create a recursive symbolic link.
            // There's no useful purpose for such a thing, and it should never be done, except in this
            // situation: when you want to make sure your code could handle such an erroneous situation,
            // gracefully.  We're making:  <tmpdir>/a -> <tmpdir>/a/b/c/d
            //
            // The somewhat surprising result is, Files.notExists() returns false on the *target*.
            // That is, it says "<tmpdir>/a/b/c/d" does NOT not exist.  (It also says it does not exist.
            // That's the whole reason why there are two methods.  Certain paths may be in a state where
            // they neither "exist" nor "not exist".  To "not exist" means to be completely absent.)
            Path aSubDir = dirpath.resolve("a");
            Path target = aSubDir.resolve("b").resolve("c").resolve("d");
            assertEquals(
                dirpath,
                existingAncestor(target),
                "existingAncestor(Path) failed to find " +
                    dirpath +
                    " as answer for " +
                    target
            );

            try {
                Files.createSymbolicLink(aSubDir, target);
            } catch (IOException x) {
                fail("unable to create link from " + aSubDir + " to " + target);
            }
            assertTrue(
                Files.isSymbolicLink(aSubDir),
                "did not detect " + aSubDir + " as a symbolic link"
            );
            assertFalse(
                Files.notExists(target),
                "after creating link, " + target + " not exists"
            );
            assertEquals(
                target,
                existingAncestor(target),
                "existingAncestor(Path) failed to find itself as answer for " +
                    target
            );
        }
    }

    @Test
    public void testEnsureWritableDirectory() {
        final String dirname = "folder";

        final Path sandbox = tempFolder;

        final Path dirpath = sandbox.resolve(dirname);
        assertFalse(
            Files.exists(dirpath),
            "cannot test ensureWritableDirectory because target already exists"
        );

        assertTrue(
            ensureWritableDirectory(dirpath),
            "ensureWritableDirectory returned false"
        );
        assertTrue(
            Files.exists(dirpath),
            "dir from ensureWritableDirectory not found"
        );
        assertTrue(
            Files.isDirectory(dirpath),
            "dir from ensureWritableDirectory not a directory"
        );

        assertTrue(rmdir(dirpath), "rmdirs returned false");
        assertFalse(Files.exists(dirpath), "dir from rmdirs not removed");
    }

    @Test
    public void testRemoveIfEmptyLeavesEmptiedParent() throws IOException {
        // downloads/Show/Season 1 -> only "Season 1" goes; "Show" and
        // "downloads" stay even though removing the child empties them.
        final Path downloads = tempFolder.resolve("downloads");
        final Path show = downloads.resolve("Solar Drift");
        final Path season = show.resolve("Season 1");
        Files.createDirectories(season);

        assertTrue(removeIfEmpty(season), "empty leaf dir should be removed");
        assertFalse(Files.exists(season), "leaf dir still exists");
        assertTrue(Files.isDirectory(show), "parent dir must not be removed");
        assertTrue(Files.isDirectory(downloads), "grandparent must not be removed");
    }

    @Test
    public void testRemoveIfEmptyKeepsNonEmptyDir() throws IOException {
        final Path dir = tempFolder.resolve("Westmark Academy");
        Files.createDirectories(dir);
        Files.createFile(dir.resolve("notes.txt"));

        assertFalse(removeIfEmpty(dir), "non-empty dir reported as removed");
        assertTrue(Files.isDirectory(dir), "non-empty dir was removed");
    }

    @Test
    public void testRemoveIfEmptyNullAndMissing() {
        assertFalse(removeIfEmpty(null));
        assertFalse(removeIfEmpty(tempFolder.resolve("does-not-exist")));
    }

    @Test
    public void testEnsureWritableDirectoryAlreadyExists() {
        final Path dirpath = tempFolder;

        assertTrue(
            Files.exists(dirpath),
            "cannot test ensureWritableDirectory because sandbox does not exist"
        );

        assertTrue(
            ensureWritableDirectory(dirpath),
            "ensureWritableDirectory returned false"
        );
        assertTrue(
            Files.exists(dirpath),
            "dir from ensureWritableDirectory not found"
        );
        assertTrue(
            Files.isDirectory(dirpath),
            "dir from ensureWritableDirectory not a directory"
        );
    }

    @Test
    public void testEnsureWritableDirectoryFileInTheWay() {
        final String dirname = "file";
        Path dirpath;

        try {
            dirpath = Files.createFile(tempFolder.resolve(dirname));
        } catch (IOException ioe) {
            fail("cannot test ensureWritableDirectory because newFile failed");
            return;
        }

        assertTrue(
            Files.exists(dirpath),
            "cannot test ensureWritableDirectory because file does not exist"
        );

        assertFalse(
            ensureWritableDirectory(dirpath),
            "ensureWritableDirectory returned true when file was in the way"
        );
        assertTrue(
            Files.exists(dirpath),
            "file from ensureWritableDirectory not found"
        );
        assertFalse(
            Files.isDirectory(dirpath),
            "file from ensureWritableDirectory is a directory"
        );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testEnsureWritableDirectoryCantWrite() {
        final String dirname = "folder";
        Path dirpath;

        try {
            dirpath = Files.createDirectory(tempFolder.resolve(dirname));
        } catch (Exception e) {
            fail(
                "cannot test ensureWritableDirectory because newFolder failed"
            );
            return;
        }
        assertTrue(
            Files.exists(dirpath),
            "cannot test ensureWritableDirectory because folder does not exist"
        );

        try {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(dirpath, perms);
        } catch (IOException ioe) {
            fail("cannot test ensureWritableDirectory because setPosixFilePermissions failed");
            return;
        }

        assertFalse(
            Files.isWritable(dirpath),
            "failed to make temp dir not writable"
        );

        assertFalse(
            ensureWritableDirectory(dirpath),
            "ensureWritableDirectory returned true when folder was not writable"
        );
        assertTrue(
            Files.exists(dirpath),
            "file from ensureWritableDirectory not found"
        );
        assertTrue(
            Files.isDirectory(dirpath),
            "file from ensureWritableDirectory is a directory"
        );
    }
}
