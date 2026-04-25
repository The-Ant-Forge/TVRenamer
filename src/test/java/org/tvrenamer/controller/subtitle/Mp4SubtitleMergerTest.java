package org.tvrenamer.controller.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.tvrenamer.controller.util.ProcessRunner;

/**
 * Unit tests for {@link Mp4SubtitleMerger}.
 *
 * <p>Tests never spawn the real {@code MP4Box} binary.  Process spawning is
 * intercepted via the constructor-injected {@link ProcessOps.Run} /
 * {@link ProcessOps.Streaming} indirection — the {@link FakeMerger} below
 * implements both and is passed to a held inner {@link Mp4SubtitleMerger}.
 * Detection state is forced via
 * {@link Mp4SubtitleMerger#setToolPathForTesting(String)} so the merger
 * doesn't probe the host's PATH.
 *
 * <p>Test names use plausible fictional show names (Solar Drift, Westmark
 * Academy) per the project's no-real-titles policy.
 */
class Mp4SubtitleMergerTest {

    /** Stand-in MP4Box path the tests pretend was discovered. */
    private static final String FAKE_MP4BOX = "MP4Box";

    private FakeMerger merger;

    @BeforeEach
    void setUp() {
        merger = new FakeMerger();
        Mp4SubtitleMerger.setToolPathForTesting(FAKE_MP4BOX);
    }

    @AfterEach
    void tearDown() {
        Mp4SubtitleMerger.resetDetectionForTesting();
        SubtitleSwap.resetMoveOperation();
    }

    // ---------- supportsContainerExtension ----------

    @Test
    void supportsContainer_lowerAndUpperCase() {
        assertTrue(merger.supportsContainerExtension(".mp4"));
        assertTrue(merger.supportsContainerExtension(".m4v"));
        assertTrue(merger.supportsContainerExtension(".MP4"));
        assertTrue(merger.supportsContainerExtension(".M4V"));
    }

    @Test
    void supportsContainer_unsupportedReturnsFalse() {
        assertFalse(merger.supportsContainerExtension(".mkv"));
        assertFalse(merger.supportsContainerExtension(".mov"));
        assertFalse(merger.supportsContainerExtension(".avi"));
    }

    @Test
    void supportsContainer_nullSafe() {
        assertFalse(merger.supportsContainerExtension(null));
    }

    // ---------- supportsSubtitleExtension ----------

    @Test
    void supportsSubtitle_srtAndVttTrue() {
        assertTrue(merger.supportsSubtitleExtension(".srt"));
        assertTrue(merger.supportsSubtitleExtension(".vtt"));
        assertTrue(merger.supportsSubtitleExtension(".SRT"));
    }

    @Test
    void supportsSubtitle_assAndSsaFalse() {
        // ASS/SSA are unsupported in MP4 - controller is expected to warn and skip.
        assertFalse(merger.supportsSubtitleExtension(".ass"));
        assertFalse(merger.supportsSubtitleExtension(".ssa"));
    }

    @Test
    void supportsSubtitle_otherFalse() {
        assertFalse(merger.supportsSubtitleExtension(".txt"));
    }

    @Test
    void supportsSubtitle_nullSafe() {
        assertFalse(merger.supportsSubtitleExtension(null));
    }

    // ---------- getToolName ----------

    @Test
    void getToolName_isMp4Box() {
        assertEquals("MP4Box", merger.getToolName());
    }

    // ---------- Command construction ----------

    @Test
    void merge_singleSrt_buildsExpectedCommandLine(@TempDir Path dir) throws IOException {
        Path media = writeBytes(dir.resolve("Solar Drift S01E01.mp4"), 1000);
        Path sub = writeBytes(dir.resolve("Solar Drift S01E01.srt"), 200);
        Path expectedTemp = dir.resolve("Solar Drift S01E01.merging.mp4");

        SubtitleEntry entry = new SubtitleEntry(
            sub, "eng", "English", EnumSet.noneOf(Descriptor.class));

        // Fake: copy media into temp so integrity gate passes, exit 0.
        merger.withResult(success(""))
              .withSideEffect(() -> Files.copy(media, expectedTemp));

        SubtitleMerger.MergeOutcome outcome = merger.merge(media, List.of(entry));

        assertEquals(SubtitleMerger.MergeOutcome.SUCCESS, outcome);
        List<String> cmd = merger.lastCommand();
        assertNotNull(cmd, "fake should have observed a command");

        List<String> expected = List.of(
            FAKE_MP4BOX,
            "-add", sub + ":lang=eng:name=English",
            "-out", expectedTemp.toString(),
            media.toString()
        );
        assertEquals(expected, cmd);
    }

    @Test
    void merge_multipleSubtitlesMixedLanguages_emitsOneAddPerEntry(@TempDir Path dir)
            throws IOException {
        Path media = writeBytes(dir.resolve("Westmark Academy S02E03.mp4"), 1000);
        Path sub1 = writeBytes(dir.resolve("Westmark Academy S02E03.en.forced.sdh.srt"), 100);
        Path sub2 = writeBytes(dir.resolve("Westmark Academy S02E03.fr.vtt"), 100);
        Path expectedTemp = dir.resolve("Westmark Academy S02E03.merging.mp4");

        SubtitleEntry e1 = new SubtitleEntry(
            sub1, "eng", "English (Forced, SDH)",
            EnumSet.of(Descriptor.FORCED, Descriptor.SDH));
        SubtitleEntry e2 = new SubtitleEntry(
            sub2, "fre", "French", EnumSet.noneOf(Descriptor.class));

        merger.withResult(success(""))
              .withSideEffect(() -> Files.copy(media, expectedTemp));

        SubtitleMerger.MergeOutcome outcome = merger.merge(media, List.of(e1, e2));

        assertEquals(SubtitleMerger.MergeOutcome.SUCCESS, outcome);
        List<String> expected = List.of(
            FAKE_MP4BOX,
            "-add", sub1 + ":lang=eng:name=English (Forced, SDH)",
            "-add", sub2 + ":lang=fre:name=French",
            "-out", expectedTemp.toString(),
            media.toString()
        );
        assertEquals(expected, merger.lastCommand());
    }

    @Test
    void merge_trackNameIsSanitised_colonAndEqualsReplaced(@TempDir Path dir) throws IOException {
        Path media = writeBytes(dir.resolve("Solar Drift S01E02.mp4"), 1000);
        Path sub = writeBytes(dir.resolve("Solar Drift S01E02.srt"), 100);
        Path expectedTemp = dir.resolve("Solar Drift S01E02.merging.mp4");

        SubtitleEntry e = new SubtitleEntry(
            sub, "eng", "Foo: Bar=Baz", EnumSet.noneOf(Descriptor.class));

        merger.withResult(success(""))
              .withSideEffect(() -> Files.copy(media, expectedTemp));

        merger.merge(media, List.of(e));

        // Find the -add entry and assert it embeds the sanitised name.
        List<String> cmd = merger.lastCommand();
        int addIdx = cmd.indexOf("-add");
        assertTrue(addIdx >= 0, "command should contain a -add token");
        String addArg = cmd.get(addIdx + 1);
        // ':' becomes '_', '=' becomes '-' — but the lang= and name= separators
        // we generate ourselves are intact.
        assertTrue(addArg.endsWith(":lang=eng:name=Foo_ Bar-Baz"),
            "expected sanitised modifier suffix in: " + addArg);
    }

    // ---------- alreadyHasLanguageTrack ----------

    /**
     * Realistic MP4Box -info output — captured from GPAC 2.2.1 against a file
     * with video, audio, and a TX3G subtitle track.  Each track block starts
     * with a {@code # Track N Info} header line; per-track fields appear on
     * subsequent lines under the header.
     */
    private static final String REAL_MP4BOX_OUTPUT_WITH_SUBTITLE = """
            # Movie Info - 3 tracks - TimeScale 600
            \tmedia: 10
            # Track 1 Info - ID 1 - TimeScale 24000
            Media Duration 01:28:34.726
            Track flags: Enabled In Movie
            Visual Track layout: x=0 y=0 width=1920 height=1080
            Media Type: vide:hev1
            # Track 2 Info - ID 2 - TimeScale 48000
            Media Duration 01:28:34.794
            Track flags: Enabled In Movie
            Media Language: English (eng)
            Media Type: soun:mp4a
            # Track 3 Info - ID 3 - TimeScale 1000
            Media Duration 01:27:32.334
            Track flags: Enabled In Movie In Preview
            Media Language: English (eng)
            Media Samples: 2877
            Media Type: text:tx3g
            \tUnknown Text Stream
            \tSize 1920 x 1080 - Translation X=0 Y=0 - Layer 0
            """;

    @Test
    void alreadyHasLanguageTrack_subtTx3gWithEng_returnsTrueForEng(@TempDir Path dir)
            throws IOException {
        Path media = Files.createFile(dir.resolve("Solar Drift.mp4"));
        merger.withResult(success(REAL_MP4BOX_OUTPUT_WITH_SUBTITLE));

        assertTrue(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_onlyEngPresent_returnsFalseForFre(@TempDir Path dir)
            throws IOException {
        Path media = Files.createFile(dir.resolve("Solar Drift.mp4"));
        merger.withResult(success(REAL_MP4BOX_OUTPUT_WITH_SUBTITLE));

        assertFalse(merger.alreadyHasLanguageTrack(media, "fre"));
    }

    @Test
    void alreadyHasLanguageTrack_onlyVideoAndAudio_returnsFalse(@TempDir Path dir)
            throws IOException {
        Path media = Files.createFile(dir.resolve("Solar Drift.mp4"));
        // Same shape as the realistic output, but with the subtitle track removed.
        String info = """
            # Movie Info - 2 tracks - TimeScale 600
            # Track 1 Info - ID 1 - TimeScale 24000
            Media Type: vide:hev1
            # Track 2 Info - ID 2 - TimeScale 48000
            Media Language: English (eng)
            Media Type: soun:mp4a
            """;
        merger.withResult(success(info));

        assertFalse(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_nonZeroExit_returnsFalse(@TempDir Path dir) throws IOException {
        Path media = Files.createFile(dir.resolve("Solar Drift.mp4"));
        // Output mentions Subtitle + eng but the exit code is non-zero - should be ignored.
        String info = "Media Type \"subt:tx3g\" - Subtitle in language: eng\n";
        merger.withResult(new ProcessRunner.Result(false, 1, info));

        assertFalse(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_emptyOutput_returnsFalse(@TempDir Path dir) throws IOException {
        Path media = Files.createFile(dir.resolve("Solar Drift.mp4"));
        merger.withResult(success(""));

        assertFalse(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_legacyWording_stillMatches(@TempDir Path dir)
            throws IOException {
        Path media = Files.createFile(dir.resolve("Solar Drift.mp4"));
        // Older GPAC versions emitted "Subtitle in language: <code>" in the Media Type line
        // rather than the modern "Media Type: text:<codec>" plus separate "Media Language" line.
        String info = """
            # Movie Info - 2 tracks
            # Track 1 Info - ID 1 - TimeScale 24000
            Media Type: vide:avc1
            # Track 2 Info - ID 2 - TimeScale 1000
            Media Type: subt:tx3g - Subtitle in language: eng
            """;
        merger.withResult(success(info));

        assertTrue(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_caseInsensitiveLanguageMatch(@TempDir Path dir)
            throws IOException {
        Path media = Files.createFile(dir.resolve("Solar Drift.mp4"));
        // Upper-case language code in the output, lower-case query.
        String info = """
            # Track 1 Info - ID 1
            Media Type: text:tx3g
            Media Language: English (ENG)
            """;
        merger.withResult(success(info));

        assertTrue(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    // ---------- merge: failure modes ----------

    @Test
    void merge_emptySubtitleList_returnsSuccessAndDoesNotInvokeTool() {
        SubtitleMerger.MergeOutcome outcome = merger.merge(Path.of("ignored.mp4"), List.of());

        assertEquals(SubtitleMerger.MergeOutcome.SUCCESS, outcome);
        assertEquals(0, merger.callCount(),
            "tool must not be invoked when subtitle list is empty");
    }

    @Test
    void merge_toolNotDetected_returnsSkippedNoTool(@TempDir Path dir) throws IOException {
        // Force "not detected" state.
        Mp4SubtitleMerger.setToolPathForTesting(null);

        Path media = writeBytes(dir.resolve("Solar Drift.mp4"), 100);
        Path sub = writeBytes(dir.resolve("Solar Drift.srt"), 50);
        SubtitleEntry e = new SubtitleEntry(
            sub, "eng", "English", EnumSet.noneOf(Descriptor.class));

        SubtitleMerger.MergeOutcome outcome = merger.merge(media, List.of(e));

        assertEquals(SubtitleMerger.MergeOutcome.SKIPPED_NO_TOOL, outcome);
    }

    @Test
    void merge_happyPath_swapsTempOverSource(@TempDir Path dir) throws IOException {
        Path media = writeBytes(dir.resolve("Solar Drift S01E04.mp4"), 1000);
        Path sub = writeBytes(dir.resolve("Solar Drift S01E04.srt"), 200);
        Path expectedTemp = dir.resolve("Solar Drift S01E04.merging.mp4");

        // Capture original media bytes so we can assert the swap actually happened.
        byte[] originalMediaBytes = Files.readAllBytes(media);

        SubtitleEntry e = new SubtitleEntry(
            sub, "eng", "English", EnumSet.noneOf(Descriptor.class));

        // Fake MP4Box: write a slightly larger temp (1100 bytes >= 80% of 1000).
        merger.withResult(success(""))
              .withSideEffect(() -> Files.write(expectedTemp, new byte[1100]));

        SubtitleMerger.MergeOutcome outcome = merger.merge(media, List.of(e));

        assertEquals(SubtitleMerger.MergeOutcome.SUCCESS, outcome);
        assertTrue(Files.exists(media), "source path must still exist after swap");
        assertFalse(Files.exists(expectedTemp), "temp must have been moved away after swap");
        // Source should now hold the new (1100-byte) content, not the original bytes.
        assertEquals(1100L, Files.size(media), "source should hold the merged temp content");
        assertFalse(java.util.Arrays.equals(originalMediaBytes, Files.readAllBytes(media)),
            "merged file should differ from original");
    }

    @Test
    void merge_toolExitsNonZero_returnsFailedAndSourceUntouched(@TempDir Path dir)
            throws IOException {
        Path media = writeBytes(dir.resolve("Solar Drift.mp4"), 1000);
        Path sub = writeBytes(dir.resolve("Solar Drift.srt"), 50);
        Path tempPath = dir.resolve("Solar Drift.merging.mp4");
        byte[] originalBytes = Files.readAllBytes(media);

        SubtitleEntry e = new SubtitleEntry(
            sub, "eng", "English", EnumSet.noneOf(Descriptor.class));

        merger.withResult(new ProcessRunner.Result(false, 7, "MP4Box: simulated error"));

        SubtitleMerger.MergeOutcome outcome = merger.merge(media, List.of(e));

        assertEquals(SubtitleMerger.MergeOutcome.FAILED, outcome);
        assertTrue(Files.exists(media), "source must remain on tool failure");
        assertEquals(originalBytes.length, (int) Files.size(media));
        assertFalse(Files.exists(tempPath), "temp must be cleaned up on tool failure");
    }

    @Test
    void merge_integrityGateFails_returnsFailedAndDeletesTemp(@TempDir Path dir)
            throws IOException {
        Path media = writeBytes(dir.resolve("Solar Drift.mp4"), 1000);
        Path sub = writeBytes(dir.resolve("Solar Drift.srt"), 50);
        Path tempPath = dir.resolve("Solar Drift.merging.mp4");
        byte[] originalBytes = Files.readAllBytes(media);

        SubtitleEntry e = new SubtitleEntry(
            sub, "eng", "English", EnumSet.noneOf(Descriptor.class));

        // Fake exit-0 but write a 0-byte temp - integrity gate should reject it.
        merger.withResult(success(""))
              .withSideEffect(() -> Files.write(tempPath, new byte[0]));

        SubtitleMerger.MergeOutcome outcome = merger.merge(media, List.of(e));

        assertEquals(SubtitleMerger.MergeOutcome.FAILED, outcome);
        assertTrue(Files.exists(media), "source must remain when integrity gate rejects temp");
        assertEquals(originalBytes.length, (int) Files.size(media));
        assertFalse(Files.exists(tempPath), "temp must be deleted when integrity gate fails");
    }

    // ---------- helpers ----------

    /** Build a {@link ProcessRunner.Result} representing a successful run. */
    private static ProcessRunner.Result success(String output) {
        return new ProcessRunner.Result(true, 0, output);
    }

    private static Path writeBytes(Path path, int size) throws IOException {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        Files.write(path, payload);
        return path;
    }

    /**
     * Sanity-check helper not used directly; left as a documented utility for future tests
     * that want to write text fixtures with deterministic encoding.  Kept private to avoid
     * polluting the suite's API.
     */
    @SuppressWarnings("unused")
    private static void writeText(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    /** Side-effect hook that may throw {@link IOException}. */
    @FunctionalInterface
    interface IoRunnable {
        void run() throws IOException;
    }

    /**
     * Test fake that combines two roles in one object so existing tests
     * don't have to thread a separate recorder alongside the merger:
     *
     * <ul>
     *   <li>Implements {@link ProcessOps.Run} and {@link ProcessOps.Streaming}
     *       — these are the recording hooks the inner merger calls when it
     *       would normally spawn a real process.</li>
     *   <li>Implements {@link SubtitleMerger} by delegating every method to
     *       a held inner {@link Mp4SubtitleMerger} that was constructed with
     *       {@code this} injected as both ops.  Tests can call
     *       {@code merger.merge(...)} or {@code merger.alreadyHasLanguageTrack(...)}
     *       directly on the fake and the captures still happen in this
     *       object's lists.</li>
     * </ul>
     */
    private static final class FakeMerger
            implements SubtitleMerger, ProcessOps.Run, ProcessOps.Streaming {
        private ProcessRunner.Result canned = success("");
        private IoRunnable sideEffect = null;
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Integer> timeouts = new ArrayList<>();

        private final Mp4SubtitleMerger inner =
            new Mp4SubtitleMerger(this, this);

        FakeMerger withResult(ProcessRunner.Result result) {
            this.canned = result;
            return this;
        }

        FakeMerger withSideEffect(IoRunnable effect) {
            this.sideEffect = effect;
            return this;
        }

        int callCount() {
            return commands.size();
        }

        List<String> lastCommand() {
            return commands.isEmpty() ? null : commands.get(commands.size() - 1);
        }

        // ---- ProcessOps recording ----

        @Override
        public ProcessRunner.Result run(List<String> command, int timeoutSeconds) {
            commands.add(List.copyOf(command));
            timeouts.add(timeoutSeconds);
            if (sideEffect != null) {
                try {
                    sideEffect.run();
                } catch (IOException ioe) {
                    throw new RuntimeException("test side-effect failed", ioe);
                }
            }
            return canned;
        }

        @Override
        public ProcessRunner.Result run(List<String> command, int timeoutSeconds,
                                        java.util.function.Consumer<String> onLine) {
            // Streaming variant — reuse the same recording + canned result.
            return run(command, timeoutSeconds);
        }

        // ---- SubtitleMerger pass-through ----

        @Override
        public boolean supportsContainerExtension(String ext) {
            return inner.supportsContainerExtension(ext);
        }
        @Override
        public boolean supportsSubtitleExtension(String ext) {
            return inner.supportsSubtitleExtension(ext);
        }
        @Override
        public boolean isToolAvailable() {
            return inner.isToolAvailable();
        }
        @Override
        public String getToolName() {
            return inner.getToolName();
        }
        @Override
        public boolean alreadyHasLanguageTrack(Path mediaFile, String langCode3) {
            return inner.alreadyHasLanguageTrack(mediaFile, langCode3);
        }
        @Override
        public MergeOutcome merge(Path mediaFile, List<SubtitleEntry> subtitles,
                                  java.util.function.IntConsumer onProgress) {
            return inner.merge(mediaFile, subtitles, onProgress);
        }
    }
}
