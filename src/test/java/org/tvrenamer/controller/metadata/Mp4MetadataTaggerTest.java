package org.tvrenamer.controller.metadata;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the ffmpeg fallback command line built by {@link Mp4MetadataTagger}.
 */
public class Mp4MetadataTaggerTest {

    @Test
    @DisplayName("ffmpeg fallback maps every input stream so merged subtitle tracks survive tagging")
    void ffmpegCommandMapsAllStreams() {
        List<String> cmd = Mp4MetadataTagger.buildFfmpegCommand(
            "ffmpeg",
            Path.of("Solar Drift 1x01 Pilot.mp4"),
            Path.of(".tvr-tag-1.mp4"),
            "Solar Drift", 1, 1, "Pilot", "Solar Drift 1x01 Pilot", "2020-01-01");

        // Without "-map 0", ffmpeg's default stream selection keeps one video
        // and one audio stream and silently drops subtitle tracks that the
        // MP4Box merge just added.
        int input = cmd.indexOf("-i");
        int map = cmd.indexOf("-map");
        assertTrue(map > input, "expected -map after -i: " + cmd);
        assertEquals("0", cmd.get(map + 1));
        assertEquals(".tvr-tag-1.mp4", cmd.get(cmd.size() - 1));
    }
}
