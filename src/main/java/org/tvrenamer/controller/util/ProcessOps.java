package org.tvrenamer.controller.util;

import java.util.List;
import java.util.function.Consumer;

/**
 * Process-spawning indirection used by the external-tool integrations
 * (subtitle mergers and metadata taggers).  Tool classes take instances of
 * these interfaces via their constructors so unit tests can inject fakes
 * without touching static state or subclassing.
 *
 * <p>Production code uses the {@link #REAL} and {@link #REAL_STREAMING}
 * defaults that delegate straight to {@link ProcessRunner}.  The
 * canonical pair-of-constructors pattern in the merger is:
 *
 * <pre>{@code
 * public Mp4SubtitleMerger() {
 *     this(ProcessOps.REAL, ProcessOps.REAL_STREAMING);
 * }
 *
 * Mp4SubtitleMerger(ProcessOps.Run run, ProcessOps.Streaming streaming) {
 *     this.runOp = run;
 *     this.streamingOp = streaming;
 * }
 * }</pre>
 */
public final class ProcessOps {

    private ProcessOps() {
        // namespace only
    }

    /**
     * Single-shot process invocation.  Same signature as
     * {@link ProcessRunner#run(List, int)}.
     */
    @FunctionalInterface
    public interface Run {
        ProcessRunner.Result run(List<String> command, int timeoutSeconds);
    }

    /**
     * Streaming process invocation that delivers each output line to a
     * consumer as the process emits it.  Same signature as
     * {@link ProcessRunner#runStreaming(List, int, Consumer)}.
     */
    @FunctionalInterface
    public interface Streaming {
        ProcessRunner.Result run(
                List<String> command,
                int timeoutSeconds,
                Consumer<String> onLine);
    }

    /** Default {@link Run} backed by {@link ProcessRunner#run}. */
    public static final Run REAL = ProcessRunner::run;

    /** Default {@link Streaming} backed by {@link ProcessRunner#runStreaming}. */
    public static final Streaming REAL_STREAMING = ProcessRunner::runStreaming;
}
