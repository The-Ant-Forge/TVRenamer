package org.tvrenamer.model.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Environment {

    private static final Logger logger = Logger.getLogger(
        Environment.class.getName()
    );

    public static final String USER_HOME = System.getProperty("user.home");
    public static final String TMP_DIR_NAME = System.getProperty(
        "java.io.tmpdir"
    );
    private static final String OS_NAME = System.getProperty("os.name");

    private enum OSType {
        WINDOWS,
        LINUX,
        MAC,
    }

    private static OSType chooseOSType() {
        if (OS_NAME.contains("Mac")) {
            return OSType.MAC;
        }
        if (OS_NAME.contains("Windows")) {
            return OSType.WINDOWS;
        }
        return OSType.LINUX;
    }

    private static final OSType JVM_OS_TYPE = chooseOSType();
    public static final boolean IS_MAC_OSX = (JVM_OS_TYPE == OSType.MAC);
    public static final boolean IS_WINDOWS = (JVM_OS_TYPE == OSType.WINDOWS);

    // If InputStream.read() fails, it returns -1.  So, anything less than zero is
    // clearly a failure.  But we assume a version must at least be "x.y", so let's
    // call anything less than three bytes a fail.
    private static final int MIN_BYTES_FOR_VERSION = 3;

    private static String readResourceTrimmed(
        final String resourcePath,
        final int minBytes,
        final String notFoundMessage,
        final String tooShortMessage
    ) {
        try (
            InputStream stream = Environment.class.getResourceAsStream(
                resourcePath
            )
        ) {
            if (stream == null) {
                throw new RuntimeException(notFoundMessage);
            }

            byte[] bytes = stream.readAllBytes();
            if (bytes.length < minBytes) {
                throw new RuntimeException(tooShortMessage);
            }

            return new String(bytes, StandardCharsets.US_ASCII).trim();
        } catch (IOException ioe) {
            logger.log(
                Level.WARNING,
                "Exception when reading resource " + resourcePath,
                ioe
            );
            throw new RuntimeException(
                "Exception when reading resource " + resourcePath,
                ioe
            );
        } catch (RuntimeException re) {
            // Preserve the original message for easier diagnostics
            throw re;
        } catch (Exception e) {
            logger.log(
                Level.WARNING,
                "Exception when reading resource " + resourcePath,
                e
            );
            throw new RuntimeException(
                "Exception when reading resource " + resourcePath,
                e
            );
        }
    }

    static String readVersionNumber() {
        return readResourceTrimmed(
            "/tvrenamer.version",
            MIN_BYTES_FOR_VERSION,
            "Version file '/tvrenamer.version' not found on classpath",
            "Unable to extract version from version file"
        );
    }

    /**
     * Read the build date (YYMMDD, UTC) from the generated build metadata resource.
     *
     * @return build date in YYMMDD format, or empty string if unavailable
     */
    public static String readBuildDateYYMMDD() {
        try {
            return readResourceTrimmed(
                "/tvrenamer.builddate",
                1,
                "Build date file '/tvrenamer.builddate' not found on classpath",
                "Unable to extract build date from build date file"
            );
        } catch (RuntimeException ignored) {
            // Best-effort: show nothing rather than crashing the UI.
            return "";
        }
    }
}
