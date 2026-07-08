package org.tvrenamer.controller.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared utility for running external processes with timeout and output capture.
 *
 * <p>Handles the common pattern of: start process, drain stdout/stderr,
 * wait with timeout, check exit code, clean up on failure.
 */
public final class ProcessRunner {

    private static final Logger logger = Logger.getLogger(ProcessRunner.class.getName());

    private ProcessRunner() {
        // utility class
    }

    /**
     * Result of running an external process.
     *
     * @param success    true if the process exited with code 0 within the timeout
     * @param exitCode   the process exit code, or -1 if it timed out or failed to start
     * @param output     captured stdout+stderr, or empty string on failure
     */
    public record Result(boolean success, int exitCode, String output) {

        /** Convenience: a failed result for when the process could not start. */
        static Result failure() {
            return new Result(false, -1, "");
        }
    }

    /**
     * Run an external command, capturing combined stdout/stderr.
     *
     * @param command        the command and arguments
     * @param timeoutSeconds maximum time to wait for the process
     * @return a {@link Result} with success status, exit code, and output
     */
    public static Result run(List<String> command, int timeoutSeconds) {
        return runStreaming(command, timeoutSeconds, null);
    }

    /**
     * After the process exits, how long to wait for the drain thread to
     * consume remaining buffered output and reach EOF.  Normally EOF arrives
     * immediately; the bound protects against a child process the tool
     * spawned that inherited (and holds open) the output pipe.
     */
    private static final long DRAIN_JOIN_AFTER_EXIT_MILLIS = 5_000L;

    /**
     * After forcibly destroying a timed-out process, how long to wait for the
     * drain thread to observe the pipe closing and finish.
     */
    private static final long DRAIN_JOIN_AFTER_KILL_MILLIS = 2_000L;

    /**
     * Run an external command, streaming combined stdout/stderr to the given
     * line consumer as the process produces output.  Use this variant when
     * the caller wants to react to progress or status lines in real time —
     * e.g. parsing a percentage from tool output to drive a per-row progress
     * label.
     *
     * <p>The consumer is invoked on a dedicated output-drain thread (NOT the
     * calling thread), once per output line, so it must be thread-safe.  It
     * should also not block: anything slow inside the consumer pauses output
     * draining and could starve the process if its pipe buffer fills.
     * Exceptions from the consumer are caught and logged at FINE so they
     * don't kill the run.
     *
     * <p>The timeout bounds the entire run.  Output is drained concurrently
     * on the dedicated thread while this thread waits on the process itself,
     * so a tool that hangs mid-run with its output pipe open is killed when
     * the timeout expires.  (Previously the drain ran first, on this thread,
     * and a hung tool blocked forever — the timeout only applied after EOF,
     * by which point the process had almost always already exited.)
     *
     * @param command        the command and arguments
     * @param timeoutSeconds maximum time to wait for the process
     * @param onLine         line consumer (may be null to disable streaming)
     * @return a {@link Result} containing the full captured output as well
     */
    public static Result runStreaming(
            List<String> command,
            int timeoutSeconds,
            Consumer<String> onLine) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            process = pb.start();

            // StringBuffer (not StringBuilder): the drain thread writes while
            // this thread may read on join-timeout paths; the join itself
            // provides the happens-before for the normal path.
            final StringBuffer output = new StringBuffer();
            final Process proc = process;
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                        if (onLine != null) {
                            try {
                                onLine.accept(line);
                            } catch (RuntimeException re) {
                                logger.log(Level.FINE,
                                    "ProcessRunner line consumer threw", re);
                            }
                        }
                    }
                } catch (IOException ioe) {
                    // Expected when a timed-out process is destroyed: the pipe
                    // closes under the reader.  Keep whatever was captured.
                    logger.log(Level.FINE, "ProcessRunner drain ended early", ioe);
                }
            }, "tvrenamer-process-drain");
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                logger.warning("Process timed out after " + timeoutSeconds
                    + "s, killing: " + command.get(0));
                process.destroyForcibly();
                joinQuietly(drain, DRAIN_JOIN_AFTER_KILL_MILLIS);
                return new Result(false, -1, output.toString());
            }

            joinQuietly(drain, DRAIN_JOIN_AFTER_EXIT_MILLIS);
            int exitCode = process.exitValue();
            return new Result(exitCode == 0, exitCode, output.toString());

        } catch (IOException e) {
            logger.log(Level.FINE, "Failed to run: " + command, e);
            return Result.failure();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.FINE, "Interrupted running: " + command, e);
            return Result.failure();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Bounded, interrupt-preserving join; the drain thread is daemon anyway. */
    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
