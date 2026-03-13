package org.tvrenamer.controller;

import static org.tvrenamer.model.util.Constants.*;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tvrenamer.model.TVRenamerIOException;
import org.tvrenamer.model.UserPreferences;

public class UpdateChecker {

    private static final Logger logger = Logger.getLogger(
        UpdateChecker.class.getName()
    );

    /**
     * Cached result of version check. Null means not yet checked.
     * Contains both the availability flag and the version string for thread-safe access.
     */
    private static final AtomicReference<VersionCheckResult> cachedResult =
        new AtomicReference<>(null);

    /** Holds the result of a version check (immutable). */
    private record VersionCheckResult(boolean updateAvailable, String latestVersion) {}

    // Best-effort extraction of "tag_name" from GitHub Releases API JSON:
    // {"tag_name":"v1.0.213", ...}
    private static final Pattern GITHUB_TAG_NAME_PATTERN = Pattern.compile(
        "\"tag_name\"\\s*:\\s*\"([^\"]+)\""
    );

    /**
     * Performs the actual version check against the GitHub API.
     *
     * For this fork, we use the GitHub Releases API endpoint in {@link Constants#TVRENAMER_VERSION_URL}
     * (expected to be: https://api.github.com/repos/&lt;owner&gt;/&lt;repo&gt;/releases/latest).
     *
     * We extract the latest release tag (e.g. "v1.0.213") and compare it against the local
     * {@link Constants#VERSION_NUMBER} (e.g. "1.0.213") using numeric parsing.
     *
     * @return a VersionCheckResult with the check outcome
     */
    private static VersionCheckResult performVersionCheck() {
        try {
            final String response = new HttpConnectionHandler().downloadUrl(
                TVRENAMER_VERSION_URL
            );
            String latestVersion = extractLatestReleaseVersion(response);

            if (latestVersion == null || latestVersion.isBlank()) {
                logger.warning(
                    "Unable to determine latest version from GitHub releases response."
                );
                return new VersionCheckResult(false, null);
            }

            boolean available = compareVersions(latestVersion, VERSION_NUMBER) > 0;
            return new VersionCheckResult(available, latestVersion);
        } catch (TVRenamerIOException e) {
            logger.log(
                Level.SEVERE,
                "Exception when downloading version info " +
                    TVRENAMER_VERSION_URL,
                e
            );
        } catch (RuntimeException e) {
            logger.log(
                Level.SEVERE,
                "Unexpected exception when checking for updates",
                e
            );
        }

        return new VersionCheckResult(false, null);
    }

    /**
     * Checks if a newer version is available.
     * Thread-safe: uses compare-and-set to ensure only one check is performed.
     *
     * @return true if a new version is available, false if there is no new version or if an error has occurred
     */
    public static boolean isUpdateAvailable() {
        // Use compareAndSet pattern for thread-safe lazy initialization
        VersionCheckResult result = cachedResult.get();
        if (result == null) {
            VersionCheckResult newResult = performVersionCheck();
            // Only set if still null (first thread wins)
            if (!cachedResult.compareAndSet(null, newResult)) {
                // Another thread already set it, use their result
                result = cachedResult.get();
            } else {
                result = newResult;
            }
        }

        if (result.updateAvailable()) {
            logger.info(
                "There is a new version available, running " +
                    VERSION_NUMBER +
                    ", new version is " +
                    result.latestVersion()
            );
            return true;
        }
        logger.finer("You have the latest version!");
        return false;
    }

    /**
     * Notifies the listener whether or not a new version is available
     *
     * @param listener the listener to update of whether or not an update is available
     */
    public static void notifyOfUpdate(final UpdateListener listener) {
        Thread updateCheckThread = new Thread(() -> {
            boolean doNotify = false;
            if (UserPreferences.getInstance().checkForUpdates()) {
                doNotify = isUpdateAvailable();
            }
            listener.notifyUpdateStatus(doNotify);
        });
        updateCheckThread.setDaemon(true);
        updateCheckThread.setName("tvrenamer-update-check");
        updateCheckThread.start();
    }

    private static String extractLatestReleaseVersion(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Matcher m = GITHUB_TAG_NAME_PATTERN.matcher(json);
        if (!m.find()) {
            return null;
        }
        String tag = m.group(1);
        if (tag == null) {
            return null;
        }
        tag = tag.trim();

        // Accept tags like "v1.0.213" or "1.0.213"
        if (tag.startsWith("v") || tag.startsWith("V")) {
            tag = tag.substring(1);
        }
        return tag.trim();
    }

    /**
     * Compare two dotted version strings (e.g. "1.0.213").
     *
     * @return positive if a > b, 0 if equal, negative if a < b
     */
    private static int compareVersions(final String a, final String b) {
        int[] av = parseVersion(a);
        int[] bv = parseVersion(b);

        // Compare up to the longest
        int max = Math.max(av.length, bv.length);
        for (int i = 0; i < max; i++) {
            int ai = (i < av.length) ? av[i] : 0;
            int bi = (i < bv.length) ? bv[i] : 0;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    private static int[] parseVersion(final String v) {
        if (v == null) {
            return new int[] { 0 };
        }
        String cleaned = v.trim();

        // Drop any build metadata suffix if present (e.g. "1.0.213+abc")
        int plus = cleaned.indexOf('+');
        if (plus >= 0) {
            cleaned = cleaned.substring(0, plus);
        }

        String[] parts = cleaned.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }
}
