package org.tvrenamer.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for UpdateChecker's core version-comparison and tag-extraction logic.
 * Uses reflection to access private static methods.
 */
public class UpdateCheckerTest {

    /**
     * Invoke the private static compareVersions(String, String) method.
     */
    private static int compareVersions(String a, String b) throws Exception {
        Method method = UpdateChecker.class.getDeclaredMethod(
            "compareVersions", String.class, String.class
        );
        method.setAccessible(true);
        return (int) method.invoke(null, a, b);
    }

    /**
     * Invoke the private static extractLatestReleaseVersion(String) method.
     */
    private static String extractLatestReleaseVersion(String json) throws Exception {
        Method method = UpdateChecker.class.getDeclaredMethod(
            "extractLatestReleaseVersion", String.class
        );
        method.setAccessible(true);
        return (String) method.invoke(null, json);
    }

    @Nested
    @DisplayName("compareVersions")
    class CompareVersionsTest {

        @Test
        @DisplayName("newer patch version is greater")
        void newerPatchIsGreater() throws Exception {
            assertTrue(compareVersions("1.0.214", "1.0.213") > 0);
        }

        @Test
        @DisplayName("equal versions return zero")
        void equalVersionsReturnZero() throws Exception {
            assertEquals(0, compareVersions("1.0.213", "1.0.213"));
        }

        @Test
        @DisplayName("older patch version is less")
        void olderPatchIsLess() throws Exception {
            assertTrue(compareVersions("1.0.213", "1.0.214") < 0);
        }

        @Test
        @DisplayName("higher major version wins over high minor and patch")
        void higherMajorWins() throws Exception {
            assertTrue(compareVersions("2.0.0", "1.9.999") > 0);
        }

        @Test
        @DisplayName("null treated as 0")
        void nullTreatedAsZero() throws Exception {
            assertTrue(compareVersions("1.0.0", null) > 0);
            assertTrue(compareVersions(null, "1.0.0") < 0);
            assertEquals(0, compareVersions(null, null));
        }

        @Test
        @DisplayName("versions with different segment counts compare correctly")
        void differentSegmentCounts() throws Exception {
            // "1.0" vs "1.0.0" — trailing zeros are equivalent
            assertEquals(0, compareVersions("1.0", "1.0.0"));
            assertTrue(compareVersions("1.0.1", "1.0") > 0);
        }

        @Test
        @DisplayName("build metadata suffix is ignored")
        void buildMetadataIgnored() throws Exception {
            assertEquals(0, compareVersions("1.0.213+abc", "1.0.213"));
        }
    }

    @Nested
    @DisplayName("extractLatestReleaseVersion")
    class ExtractLatestReleaseVersionTest {

        @Test
        @DisplayName("extracts version from tag with v prefix")
        void extractsWithVPrefix() throws Exception {
            String json = "{\"tag_name\":\"v1.0.213\",\"name\":\"Release 1.0.213\"}";
            assertEquals("1.0.213", extractLatestReleaseVersion(json));
        }

        @Test
        @DisplayName("extracts version from tag without v prefix")
        void extractsWithoutVPrefix() throws Exception {
            String json = "{\"tag_name\":\"1.0.213\"}";
            assertEquals("1.0.213", extractLatestReleaseVersion(json));
        }

        @Test
        @DisplayName("returns null for null input")
        void returnsNullForNull() throws Exception {
            assertNull(extractLatestReleaseVersion(null));
        }

        @Test
        @DisplayName("returns null for blank input")
        void returnsNullForBlank() throws Exception {
            assertNull(extractLatestReleaseVersion(""));
            assertNull(extractLatestReleaseVersion("   "));
        }

        @Test
        @DisplayName("returns null when tag_name field is missing")
        void returnsNullWhenNoTagName() throws Exception {
            String json = "{\"name\":\"Some Release\",\"draft\":false}";
            assertNull(extractLatestReleaseVersion(json));
        }

        @Test
        @DisplayName("handles uppercase V prefix")
        void handlesUppercaseVPrefix() throws Exception {
            String json = "{\"tag_name\":\"V2.1.0\"}";
            assertEquals("2.1.0", extractLatestReleaseVersion(json));
        }
    }
}
