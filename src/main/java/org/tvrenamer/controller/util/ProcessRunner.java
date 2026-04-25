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
     * Run an external command, streaming combined stdout/stderr to the given
     * line consumer as the process produces output.  The consumer is invoked
     * on the calling thread (the same thread that called this method) once
     * per output line.  Use this variant when the caller wants to react to
     * progress or status lines in real time — e.g. parsing a percentage from
     * tool output to drive a per-row progress label.
     *
     * <p>The consumer receives every line emitted by the process; it must
     * not block (anything slow inside the consumer pauses output draining
     * and could starve the process if its pipe buffer fills).  Exceptions
     * from the consumer are caught and logged at FINE so they don't kill
     * the run.
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

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, -1, output.toString());
            }

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
}
