package org.tvrenamer.controller.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ProcessRunner}.
 *
 * <p>External commands use only the {@code java} launcher (guaranteed present
 * on the build machine) so the tests stay portable.  The timeout tests run a
 * tiny single-file program via the JDK 11+ source launcher.
 */
public class ProcessRunnerTest {

    @Test
    public void testSuccessfulCommand() {
        // "java -version" should succeed on any system with JDK installed.
        ProcessRunner.Result result = ProcessRunner.run(
            List.of("java", "-version"), 10
        );
        assertTrue(result.success(), "java -version should succeed");
        assertEquals(0, result.exitCode());
    }

    @Test
    public void testFailedCommand() {
        // A command that doesn't exist should fail gracefully.
        ProcessRunner.Result result = ProcessRunner.run(
            List.of("nonexistent_command_12345"), 5
        );
        assertFalse(result.success(), "nonexistent command should fail");
    }

    @Test
    public void testOutputCapture() {
        // "java -version" outputs version info to stderr (merged via redirectErrorStream).
        ProcessRunner.Result result = ProcessRunner.run(
            List.of("java", "-version"), 10
        );
        assertTrue(result.success());
        assertNotNull(result.output());
        assertFalse(result.output().isBlank(), "should capture version output");
    }

    @Test
    public void testFailureResult() {
        ProcessRunner.Result failure = ProcessRunner.Result.failure();
        assertFalse(failure.success());
        assertEquals(-1, failure.exitCode());
        assertEquals("", failure.output());
    }

    @Test
    public void testNonZeroExitCode() {
        // "java --invalid-flag" should fail with non-zero exit.
        ProcessRunner.Result result = ProcessRunner.run(
            List.of("java", "--invalid-flag-xyz"), 10
        );
        assertFalse(result.success());
        assertNotEquals(0, result.exitCode());
    }

    // ---------- Round-4 #1: timeout enforcement while output is open ----------

    /**
     * Pins the drain-thread fix: a process that hangs mid-run while keeping
     * its stdout pipe open must be killed when the timeout expires.  Before
     * the fix, the output drain ran on the calling thread ahead of the
     * timed wait, so this test would block for the full sleep (60 s).
     */
    @Test
    public void testTimeoutKillsHungProcessWithOpenPipe(@TempDir Path dir)
            throws IOException {
        Path sleeper = dir.resolve("Sleeper.java");
        Files.writeString(sleeper, """
            public class Sleeper {
                public static void main(String[] args) throws Exception {
                    System.out.println("started");
                    System.out.flush();
                    Thread.sleep(60_000);
                }
            }
            """);

        long begin = System.nanoTime();
        ProcessRunner.Result result = ProcessRunner.run(
            List.of("java", sleeper.toString()), 2
        );
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000L;

        assertFalse(result.success(), "hung process must be reported as failed");
        assertEquals(-1, result.exitCode(), "timeout is reported as exit -1");
        // Generous bound for slow CI: source-launcher compile + 2s timeout +
        // kill/join overhead.  The essential claim is "nowhere near 60s".
        assertTrue(elapsedMillis < 30_000,
            "timeout must bound the run; took " + elapsedMillis + " ms");
        assertTrue(result.output().contains("started"),
            "output produced before the hang must still be captured");
    }

    /**
     * The streaming consumer receives lines as they are produced (now on the
     * drain thread), and the captured output matches what the consumer saw.
     */
    @Test
    public void testRunStreamingDeliversLinesToConsumer() {
        List<String> seen = new CopyOnWriteArrayList<>();
        ProcessRunner.Result result = ProcessRunner.runStreaming(
            List.of("java", "-version"), 10, seen::add
        );

        assertTrue(result.success());
        assertFalse(seen.isEmpty(), "consumer must receive at least one line");
        for (String line : seen) {
            assertTrue(result.output().contains(line),
                "every consumed line must appear in the captured output");
        }
    }

    /** A throwing consumer must not kill the run or corrupt the result. */
    @Test
    public void testRunStreamingSurvivesThrowingConsumer() {
        ProcessRunner.Result result = ProcessRunner.runStreaming(
            List.of("java", "-version"), 10,
            line -> {
                throw new IllegalStateException("consumer failure");
            }
        );

        assertTrue(result.success(),
            "a throwing line consumer must not fail the process run");
        assertFalse(result.output().isBlank());
    }
}
