package org.tvrenamer.controller;

import static org.tvrenamer.model.util.Constants.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.tvrenamer.model.ShowStore;
import org.tvrenamer.view.UIStarter;

/**
 * Application launcher.
 *
 * Logging strategy:
 * - Primary configuration comes from {@code /logging.properties}.
 * - A file log ({@code tvrenamer.log}) is created only when:
 *   - debug is enabled via {@code -Dtvrenamer.debug=true}, OR
 *   - a fatal error occurs (then we write exception + environment summary).
 *
 * The log file is written next to the executable/jar if possible, otherwise to %TEMP%.
 * The log is overwritten each run.
 */
class Launcher {

    private static final Logger logger = Logger.getLogger(
        Launcher.class.getName()
    );

    private static final String DEBUG_PROPERTY = "tvrenamer.debug";
    private static final String LOG_FILENAME = "tvrenamer.log";

    private static volatile FileHandler fileHandler;

    static void initializeLoggingConfig() {
        try (
            InputStream in = Launcher.class.getResourceAsStream(
                LOGGING_PROPERTIES
            )
        ) {
            if (in == null) {
                // Keep default JUL configuration; do not hard-fail startup.
                logger.warning(
                    "logging.properties not found on classpath; using default JDK logging configuration."
                );
                return;
            }
            LogManager.getLogManager().readConfiguration(in);
        } catch (Throwable t) {
            // Logging config failures should not prevent startup.
            // Avoid recursion: write a minimal message to stderr as well.
            System.err.println("Failed to load logging configuration: " + t);
            logger.log(
                Level.WARNING,
                "Failed to load logging configuration",
                t
            );
        }
    }

    private static boolean isDebugEnabled() {
        return Boolean.parseBoolean(
            System.getProperty(DEBUG_PROPERTY, "false")
        );
    }

    private static Path resolveLogDirectory() {
        // 1) Try to locate the directory containing the jar/exe.
        try {
            CodeSource cs =
                Launcher.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                URL location = cs.getLocation();
                if (location != null) {
                    URI uri = location.toURI();
                    Path p = Paths.get(uri).toAbsolutePath().normalize();

                    // If it's a file, use its parent directory. If it's a directory, use it directly.
                    if (Files.isRegularFile(p)) {
                        Path parent = p.getParent();
                        if (parent != null) {
                            return parent;
                        }
                    } else if (Files.isDirectory(p)) {
                        return p;
                    } else {
                        Path parent = p.getParent();
                        if (parent != null) {
                            return parent;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to TEMP.
        }

        // 2) Fallback: %TEMP%
        String tmp = System.getenv("TEMP");
        if (tmp != null && !tmp.isBlank()) {
            return Paths.get(tmp).toAbsolutePath().normalize();
        }

        // 3) Final fallback: java.io.tmpdir
        return Paths.get(System.getProperty("java.io.tmpdir", "."))
            .toAbsolutePath()
            .normalize();
    }

    private static Path resolveLogFilePath() {
        return resolveLogDirectory().resolve(LOG_FILENAME);
    }

    private static synchronized void ensureFileLoggingAttached() {
        if (fileHandler != null) {
            return;
        }

        Path logPath = resolveLogFilePath();

        try {
            // Overwrite each run (append=false)
            fileHandler = new FileHandler(logPath.toString(), false);
            fileHandler.setFormatter(new SimpleFormatter());
            fileHandler.setLevel(Level.ALL);

            Logger root = Logger.getLogger("");
            root.addHandler(fileHandler);

            // Do not force root level here; honor logging.properties.
            logger.info("File logging enabled: " + logPath);
        } catch (IOException ioe) {
            // If we can't create the log file, do not block startup.
            System.err.println(
                "Could not create log file at " +
                    logPath +
                    ": " +
                    ioe.getMessage()
            );
            logger.log(
                Level.WARNING,
                "Could not create log file at " + logPath,
                ioe
            );
        } catch (Throwable t) {
            System.err.println("Unexpected error enabling file logging: " + t);
            logger.log(
                Level.WARNING,
                "Unexpected error enabling file logging",
                t
            );
        }
    }

    private static String buildEnvironmentSummary() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("=== TVRenamer Environment ===\n");
        sb.append("Version: ").append(VERSION_NUMBER).append('\n');
        sb
            .append("Java Version: ")
            .append(System.getProperty("java.version"))
            .append('\n');
        sb
            .append("Java Home: ")
            .append(System.getProperty("java.home"))
            .append('\n');
        sb
            .append("OS: ")
            .append(System.getProperty("os.name"))
            .append(' ')
            .append(System.getProperty("os.arch"))
            .append('\n');
        sb
            .append("Working Directory: ")
            .append(System.getProperty("user.dir"))
            .append('\n');
        sb.append("Log File: ").append(resolveLogFilePath()).append('\n');
        return sb.toString();
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter(4096);
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static void logFatal(String context, Throwable t) {
        // Attach file logging if not already enabled.
        ensureFileLoggingAttached();

        logger.severe("FATAL: " + context);
        logger.severe(buildEnvironmentSummary());
        logger.severe(stackTraceToString(t));
    }

    /**
     * Shut down any threads that we know might be running. Sadly hard-coded.
     */
    private static void tvRenamerThreadShutdown() {
        // MoveRunner executors are per-run daemons now: the active runner is
        // stopped by ResultsTable.requestShutdown() on window close, and any
        // remaining worker threads cannot block JVM exit.
        logger.fine("Cleaning up ShowStore...");
        ShowStore.cleanUp();
        logger.fine("Cleaning up ListingsLookup...");
        ListingsLookup.cleanUp();
        logger.fine("Shutdown complete.");

        FileHandler handler = fileHandler;
        if (handler != null) {
            try {
                handler.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    public static void main(String[] args) {
        // Configure logging from logging.properties (best effort).
        initializeLoggingConfig();

        // If debug enabled, create/overwrite tvrenamer.log immediately.
        if (isDebugEnabled()) {
            ensureFileLoggingAttached();
            logger.info("Debug enabled via -D" + DEBUG_PROPERTY + "=true");
        }

        // Set up global exception handler to catch any uncaught exceptions.
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logFatal(
                "Uncaught exception in thread " + thread.getName(),
                throwable
            );
            tvRenamerThreadShutdown();
        });

        try {
            logger.info("=== TVRenamer Startup ===");
            logger.info("Version: " + VERSION_NUMBER);

            logger.info("Creating UIStarter...");
            UIStarter ui = new UIStarter();

            logger.info("Running UI...");
            int status = ui.run();

            tvRenamerThreadShutdown();
            logger.info("=== TVRenamer Exit ===");
            System.exit(status);
        } catch (Throwable t) {
            logFatal("Exception in main()", t);
            tvRenamerThreadShutdown();
            System.exit(1);
        }
    }
}
