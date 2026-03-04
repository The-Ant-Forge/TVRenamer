package org.tvrenamer.controller.util;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.tvrenamer.model.util.Environment;

/**
 * Shared utility for detecting external tool installations.
 *
 * <p>Checks PATH first (via {@code --version}), then platform-specific
 * well-known locations (Windows Program Files, macOS Homebrew).
 */
public final class ExternalToolDetector {

    private ExternalToolDetector() {
        // utility class
    }

    /**
     * Check if an executable is available in PATH by running {@code name --version}.
     *
     * @param executable the executable name to probe
     * @return true if it runs successfully within 5 seconds
     */
    public static boolean isExecutableInPath(String executable) {
        return ProcessRunner.run(List.of(executable, "--version"), 5).success();
    }

    /**
     * Detect an external tool by trying PATH names first, then platform-specific paths.
     *
     * @param pathNames       names to try via PATH (e.g. "AtomicParsley", "atomicparsley")
     * @param windowsPaths    absolute paths to check on Windows (may be empty)
     * @param macPaths        absolute paths to check on macOS (may be empty)
     * @return the detected path/name, or empty string if not found
     */
    public static String detect(String[] pathNames, String[] windowsPaths, String[] macPaths) {
        // Try PATH first
        for (String name : pathNames) {
            if (isExecutableInPath(name)) {
                return name;
            }
        }

        // Windows well-known locations
        if (Environment.IS_WINDOWS) {
            for (String path : windowsPaths) {
                if (Files.isExecutable(Paths.get(path))) {
                    return path;
                }
            }
        }

        // macOS Homebrew / well-known locations
        if (Environment.IS_MAC_OSX) {
            for (String path : macPaths) {
                if (Files.isExecutable(Paths.get(path))) {
                    return path;
                }
            }
        }

        return "";
    }
}
