package org.tvrenamer.controller.util;

import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Cached external-tool detection: probes once per JVM (lazily, on first use)
 * via the supplied detector, caches the resulting path, and offers test hooks
 * to prime or reset the cache.
 *
 * <p>Consolidates the four hand-rolled static-volatile + double-checked-lock
 * idioms that had drifted across Mp4SubtitleMerger, MkvSubtitleMerger,
 * Mp4MetadataTagger, and MkvMetadataTagger (Round-4 #24) — one of which
 * additionally held its class monitor on every read (#26).
 *
 * <p>Thread-safe.  The volatile read is the fast path; the probe runs at most
 * once, under the instance lock.
 */
public final class DetectedTool {

    private static final Logger logger = Logger.getLogger(DetectedTool.class.getName());

    private final String toolName;
    private final Supplier<String> detector;
    private final Object lock = new Object();

    /** null = not probed yet; "" = probed and not found; else the tool path. */
    private volatile String path = null;

    /**
     * @param toolName human-readable tool name, for log messages
     * @param detector probe returning the tool path, or null/empty when the
     *    tool is not installed; invoked at most once per JVM (unless reset)
     */
    public DetectedTool(String toolName, Supplier<String> detector) {
        this.toolName = toolName;
        this.detector = detector;
    }

    /**
     * @return the detected tool path, probing on first call; empty string
     *    when the tool is not installed
     */
    public String path() {
        String p = path;
        if (p != null) {
            return p;
        }
        synchronized (lock) {
            if (path == null) {
                String detected = detector.get();
                path = (detected == null) ? "" : detected;
                if (path.isEmpty()) {
                    logger.info(toolName
                        + " not found - dependent features will be disabled");
                } else {
                    logger.info("Found " + toolName + ": " + path);
                }
            }
            return path;
        }
    }

    /** @return whether the tool was found (probes on first call). */
    public boolean isAvailable() {
        return !path().isEmpty();
    }

    /** Force a detection state (null = "not found"); tests only. */
    public void setForTesting(String forcedPath) {
        synchronized (lock) {
            path = (forcedPath == null) ? "" : forcedPath;
        }
    }

    /** Clear the cache so the next call re-probes; tests only. */
    public void resetForTesting() {
        synchronized (lock) {
            path = null;
        }
    }
}
