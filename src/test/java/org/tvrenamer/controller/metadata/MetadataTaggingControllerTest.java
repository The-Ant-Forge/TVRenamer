package org.tvrenamer.controller.metadata;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.tvrenamer.controller.metadata.MetadataTaggingController.TaggingResult;
import org.tvrenamer.model.UserPreferences;

/**
 * Tests for MetadataTaggingController's coordination logic.
 *
 * <p>Detection caches are primed via the taggers' test hooks so no test ever
 * probes the host's PATH (Round-4 #32 — previously getToolSummary spawned
 * real probe processes and the cached result leaked into every later test in
 * the JVM).  The UserPreferences singleton's tagging flag is saved and
 * restored around each test to avoid order-dependence across the suite.
 */
public class MetadataTaggingControllerTest {

    private MetadataTaggingController controller;
    private boolean savedTagPref;

    @BeforeEach
    void setUp() {
        // Prime detection so no real PATH probes run; individual tests
        // override as needed.
        Mp4MetadataTagger.setToolPathForTesting(null);
        MkvMetadataTagger.setToolPathForTesting(null);

        controller = new MetadataTaggingController();
        savedTagPref = UserPreferences.getInstance().isTagVideoMetadata();
        UserPreferences.getInstance().setTagVideoMetadata(false);
    }

    @AfterEach
    void tearDown() {
        UserPreferences.getInstance().setTagVideoMetadata(savedTagPref);
        Mp4MetadataTagger.resetDetectionForTesting();
        MkvMetadataTagger.resetDetectionForTesting();
    }

    @Test
    @DisplayName("tagIfEnabled returns DISABLED when tagging preference is off")
    void tagIfEnabledReturnsDisabledWhenOff() {
        UserPreferences.getInstance().setTagVideoMetadata(false);

        Path dummyFile = Path.of("Westmark.Academy.S01E01.Pilot.mp4");
        TaggingResult result = controller.tagIfEnabled(dummyFile, null);

        assertEquals(TaggingResult.DISABLED, result);
        assertTrue(result.isOk(), "DISABLED should be considered OK");
    }

    @Test
    @DisplayName("tagIfEnabled returns UNSUPPORTED for unknown file extension")
    void tagIfEnabledReturnsUnsupportedForUnknownExtension() {
        UserPreferences.getInstance().setTagVideoMetadata(true);

        Path txtFile = Path.of("Solar.Drift.S02E03.notes.txt");
        TaggingResult result = controller.tagIfEnabled(txtFile, null);

        assertEquals(TaggingResult.UNSUPPORTED, result);
        assertTrue(result.isOk(), "UNSUPPORTED should be considered OK");
    }

    @Test
    @DisplayName("tagIfEnabled returns NO_TOOL when the format's tool is missing")
    void tagIfEnabledReturnsNoToolWhenToolMissing() {
        UserPreferences.getInstance().setTagVideoMetadata(true);
        // setUp primed both taggers to "not found".

        TaggingResult mp4 = controller.tagIfEnabled(
            Path.of("Westmark.Academy.S01E02.mp4"), null);
        TaggingResult mkv = controller.tagIfEnabled(
            Path.of("Westmark.Academy.S01E02.mkv"), null);

        assertEquals(TaggingResult.NO_TOOL, mp4,
            "supported extension + missing tool must be NO_TOOL, not SUCCESS");
        assertEquals(TaggingResult.NO_TOOL, mkv);
        assertTrue(mp4.isOk(), "NO_TOOL is a skip, not a failure");
    }

    @Test
    @DisplayName("getToolSummary reports no tools when none are detected")
    void getToolSummaryReturnsNonNull() {
        String summary = controller.getToolSummary();
        assertNotNull(summary);
        assertEquals("No tagging tools found", summary);
    }

    @Test
    @DisplayName("getToolSummary lists a detected tool")
    void getToolSummaryListsDetectedTool() {
        MkvMetadataTagger.setToolPathForTesting("mkvpropedit");
        String summary = controller.getToolSummary();
        assertTrue(summary.contains("mkvpropedit"),
            "summary should name the detected tool: " + summary);
    }

    @Test
    @DisplayName("isAnyToolAvailable reflects the primed detection state")
    void isAnyToolAvailableReflectsDetection() {
        assertFalse(controller.isAnyToolAvailable(),
            "both taggers primed to not-found");
        Mp4MetadataTagger.setToolPathForTesting("AtomicParsley");
        assertTrue(controller.isAnyToolAvailable());
    }

    @Test
    @DisplayName("TaggingResult.isOk returns false only for FAILED")
    void taggingResultIsOkSemantics() {
        assertTrue(TaggingResult.SUCCESS.isOk());
        assertTrue(TaggingResult.DISABLED.isOk());
        assertTrue(TaggingResult.UNSUPPORTED.isOk());
        assertTrue(TaggingResult.NO_TOOL.isOk());
        assertFalse(TaggingResult.FAILED.isOk());
    }
}
