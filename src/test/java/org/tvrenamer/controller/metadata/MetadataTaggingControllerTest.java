package org.tvrenamer.controller.metadata;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.tvrenamer.controller.metadata.MetadataTaggingController.TaggingResult;
import org.tvrenamer.model.UserPreferences;

/**
 * Tests for MetadataTaggingController's coordination logic.
 * Does NOT depend on external tagging tools being installed.
 */
public class MetadataTaggingControllerTest {

    private MetadataTaggingController controller;

    @BeforeEach
    void setUp() {
        controller = new MetadataTaggingController();
        // Ensure tagging is disabled by default for most tests
        UserPreferences.getInstance().setTagVideoMetadata(false);
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
    @DisplayName("getToolSummary returns a non-null string")
    void getToolSummaryReturnsNonNull() {
        String summary = controller.getToolSummary();
        assertNotNull(summary);
        assertFalse(summary.isEmpty());
    }

    @Test
    @DisplayName("isAnyToolAvailable returns a boolean without error")
    void isAnyToolAvailableDoesNotThrow() {
        // Just verify it runs without throwing — result depends on system
        assertDoesNotThrow(() -> controller.isAnyToolAvailable());
    }

    @Test
    @DisplayName("TaggingResult.isOk returns false only for FAILED")
    void taggingResultIsOkSemantics() {
        assertTrue(TaggingResult.SUCCESS.isOk());
        assertTrue(TaggingResult.DISABLED.isOk());
        assertTrue(TaggingResult.UNSUPPORTED.isOk());
        assertFalse(TaggingResult.FAILED.isOk());
    }
}
