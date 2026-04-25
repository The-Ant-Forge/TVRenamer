package org.tvrenamer.controller.subtitle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.tvrenamer.controller.subtitle.SubtitleMerger.MergeOutcome;
import org.tvrenamer.controller.util.ProcessRunner;

/**
 * Unit tests for {@link MkvSubtitleMerger}.
 *
 * <p>These are pure unit tests — no real {@code mkvmerge} is spawned.  Two
 * indirection seams enable that:
 *
 * <ul>
 *   <li>{@link MkvSubtitleMerger#runProcess(List, int)} is package-private and
 *       overridden by the {@link FakeMerger} subclass below.  The fake records
 *       the command list it was called with and returns canned
 *       {@link ProcessRunner.Result} values; an optional {@link Runnable}
 *       side-effect lets a test materialise a temp file before merge() inspects
 *       it.</li>
 *   <li>{@link MkvSubtitleMerger#setToolPathForTesting(String)} primes the
 *       static detection cache so {@code ensureDetected()} returns true (or
 *       false) without probing PATH.  {@link #resetCaches()} resets it between
 *       tests.</li>
 * </ul>
 *
 * <p>The choice of subclass+override (rather than a {@code BiFunction} static
 * field) keeps the indirection explicit, naturally per-instance, and parallel-
 * test-safe.  Only the detection cache is static, and tests reset it both
 * before and after each method.
 */
class MkvSubtitleMergerTest {

    private static final String FAKE_TOOL_PATH = "mkvmerge";

    @BeforeEach
    void resetCaches() {
        MkvSubtitleMerger.resetDetectionForTesting();
        SubtitleSwap.resetMoveOperation();
    }

    @AfterEach
    void clearCaches() {
        MkvSubtitleMerger.resetDetectionForTesting();
        SubtitleSwap.resetMoveOperation();
    }

    // ---------- supportsContainerExtension / supportsSubtitleExtension ----------

    @Test
    void supportsContainerExtension_acceptsMkvAnyCase() {
        MkvSubtitleMerger m = new MkvSubtitleMerger();
        assertTrue(m.supportsContainerExtension(".mkv"));
        assertTrue(m.supportsContainerExtension(".MKV"));
        assertTrue(m.supportsContainerExtension(".Mkv"));
    }

    @Test
    void supportsContainerExtension_rejectsOtherExtensions() {
        MkvSubtitleMerger m = new MkvSubtitleMerger();
        assertFalse(m.supportsContainerExtension(".mp4"));
        assertFalse(m.supportsContainerExtension(".m4v"));
        assertFalse(m.supportsContainerExtension(".webm"));
    }

    @Test
    void supportsContainerExtension_nullSafe() {
        MkvSubtitleMerger m = new MkvSubtitleMerger();
        assertFalse(m.supportsContainerExtension(null));
    }

    @Test
    void supportsSubtitleExtension_acceptsAllFourFormatsAnyCase() {
        MkvSubtitleMerger m = new MkvSubtitleMerger();
        for (String ext : List.of(".srt", ".ass", ".ssa", ".vtt")) {
            assertTrue(m.supportsSubtitleExtension(ext), "should accept " + ext);
            assertTrue(m.supportsSubtitleExtension(ext.toUpperCase()),
                "should accept " + ext.toUpperCase());
        }
    }

    @Test
    void supportsSubtitleExtension_rejectsOtherExtensions() {
        MkvSubtitleMerger m = new MkvSubtitleMerger();
        assertFalse(m.supportsSubtitleExtension(".txt"));
        assertFalse(m.supportsSubtitleExtension(".sub"));
        assertFalse(m.supportsSubtitleExtension(".idx"));
    }

    @Test
    void supportsSubtitleExtension_nullSafe() {
        MkvSubtitleMerger m = new MkvSubtitleMerger();
        assertFalse(m.supportsSubtitleExtension(null));
    }

    @Test
    void getToolName_returnsMkvmerge() {
        MkvSubtitleMerger m = new MkvSubtitleMerger();
        assertEquals("mkvmerge", m.getToolName());
    }

    // ---------- buildCommand ----------

    @Test
    void buildCommand_singleEnglishSrt_noDescriptors_snapshot(@TempDir Path dir) {
        Path media = dir.resolve("Westmark.Academy.S01E01.mkv");
        Path tmp = dir.resolve("Westmark.Academy.S01E01.mkv.merging.mkv");
        Path sub = dir.resolve("Westmark.Academy.S01E01.en.srt");
        SubtitleEntry entry = new SubtitleEntry(sub, "eng", "English",
            EnumSet.noneOf(Descriptor.class));

        List<String> cmd = MkvSubtitleMerger.buildCommand(
            FAKE_TOOL_PATH, media, tmp, List.of(entry));

        String[] expected = {
            "mkvmerge", "-o", tmp.toString(), media.toString(),
            "--language", "0:eng", "--track-name", "0:English",
            sub.toString()
        };
        assertArrayEquals(expected, cmd.toArray(new String[0]));
    }

    @Test
    void buildCommand_multipleSubtitles_mixedLangsAndDescriptors_snapshot(@TempDir Path dir) {
        Path media = dir.resolve("Solar.Drift.S02E03.mkv");
        Path tmp = dir.resolve("Solar.Drift.S02E03.mkv.merging.mkv");
        Path sub1 = dir.resolve("Solar.Drift.S02E03.en.forced.sdh.srt");
        Path sub2 = dir.resolve("Solar.Drift.S02E03.fr.srt");

        SubtitleEntry entry1 = new SubtitleEntry(sub1, "eng", "English (Forced, SDH)",
            EnumSet.of(Descriptor.FORCED, Descriptor.SDH));
        SubtitleEntry entry2 = new SubtitleEntry(sub2, "fre", "French",
            EnumSet.noneOf(Descriptor.class));

        List<String> cmd = MkvSubtitleMerger.buildCommand(
            FAKE_TOOL_PATH, media, tmp, List.of(entry1, entry2));

        String[] expected = {
            "mkvmerge", "-o", tmp.toString(), media.toString(),
            "--language", "0:eng", "--track-name", "0:English (Forced, SDH)",
            "--forced-display-flag", "0:1",
            "--hearing-impaired-flag", "0:1",
            sub1.toString(),
            "--language", "0:fre", "--track-name", "0:French",
            sub2.toString()
        };
        assertArrayEquals(expected, cmd.toArray(new String[0]));
    }

    @Test
    void buildCommand_commentaryDescriptor_setsCommentaryFlag(@TempDir Path dir) {
        Path media = dir.resolve("The.Quiet.Ones.S01E01.mkv");
        Path tmp = dir.resolve("The.Quiet.Ones.S01E01.mkv.merging.mkv");
        Path sub = dir.resolve("The.Quiet.Ones.S01E01.en.commentary.srt");

        SubtitleEntry entry = new SubtitleEntry(sub, "eng", "English (Commentary)",
            EnumSet.of(Descriptor.COMMENTARY));

        List<String> cmd = MkvSubtitleMerger.buildCommand(
            FAKE_TOOL_PATH, media, tmp, List.of(entry));

        // The commentary flag must appear in the output.
        assertTrue(cmd.contains("--commentary-flag"),
            "commentary flag should be present, got " + cmd);
        int idx = cmd.indexOf("--commentary-flag");
        assertEquals("0:1", cmd.get(idx + 1));
        // No forced/sdh flags for a pure-commentary entry.
        assertFalse(cmd.contains("--forced-display-flag"));
        assertFalse(cmd.contains("--hearing-impaired-flag"));
    }

    @Test
    void buildCommand_signsSongsDub_setNoFlags_onlyTrackName(@TempDir Path dir) {
        Path media = dir.resolve("Westmark.Academy.S01E02.mkv");
        Path tmp = dir.resolve("Westmark.Academy.S01E02.mkv.merging.mkv");
        Path subSigns = dir.resolve("Westmark.Academy.S01E02.en.signs.srt");
        Path subSongs = dir.resolve("Westmark.Academy.S01E02.en.songs.srt");
        Path subDub = dir.resolve("Westmark.Academy.S01E02.en.dub.srt");

        List<SubtitleEntry> entries = List.of(
            new SubtitleEntry(subSigns, "eng", "English (Signs)",
                EnumSet.of(Descriptor.SIGNS)),
            new SubtitleEntry(subSongs, "eng", "English (Songs)",
                EnumSet.of(Descriptor.SONGS)),
            new SubtitleEntry(subDub, "eng", "English (Dub)",
                EnumSet.of(Descriptor.DUB))
        );

        List<String> cmd = MkvSubtitleMerger.buildCommand(
            FAKE_TOOL_PATH, media, tmp, entries);

        // None of the three carry an mkvmerge per-track flag.
        assertFalse(cmd.contains("--forced-display-flag"),
            "SIGNS/SONGS/DUB should not produce forced flag, got " + cmd);
        assertFalse(cmd.contains("--hearing-impaired-flag"),
            "SIGNS/SONGS/DUB should not produce SDH flag, got " + cmd);
        assertFalse(cmd.contains("--commentary-flag"),
            "SIGNS/SONGS/DUB should not produce commentary flag, got " + cmd);

        // But each track name should still propagate exactly once.
        assertTrue(cmd.contains("0:English (Signs)"));
        assertTrue(cmd.contains("0:English (Songs)"));
        assertTrue(cmd.contains("0:English (Dub)"));
    }

    // ---------- alreadyHasLanguageTrack: JSON parsing ----------

    @Test
    void alreadyHasLanguageTrack_jsonContainsEnglishSubtitleTrack_returnsTrue(
            @TempDir Path dir) throws IOException {
        Path media = touch(dir.resolve("show.mkv"));
        String json = "{\"tracks\":["
            + "{\"id\":0,\"type\":\"video\",\"properties\":{\"language\":\"und\"}},"
            + "{\"id\":1,\"type\":\"audio\",\"properties\":{\"language\":\"eng\"}},"
            + "{\"id\":2,\"type\":\"subtitles\",\"properties\":{\"language\":\"eng\","
                + "\"codec_id\":\"S_TEXT/UTF8\"}}"
            + "]}";
        FakeMerger merger = new FakeMerger().withResult(success(json));
        MkvSubtitleMerger.setToolPathForTesting(FAKE_TOOL_PATH);

        assertTrue(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_subtitleTrackInDifferentLang_returnsFalse(
            @TempDir Path dir) throws IOException {
        Path media = touch(dir.resolve("show.mkv"));
        String json = "{\"tracks\":["
            + "{\"id\":0,\"type\":\"video\",\"properties\":{\"language\":\"und\"}},"
            + "{\"id\":1,\"type\":\"subtitles\",\"properties\":{\"language\":\"fre\"}}"
            + "]}";
        FakeMerger merger = new FakeMerger().withResult(success(json));
        MkvSubtitleMerger.setToolPathForTesting(FAKE_TOOL_PATH);

        assertFalse(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_onlyVideoAndAudio_returnsFalse(
            @TempDir Path dir) throws IOException {
        Path media = touch(dir.resolve("show.mkv"));
        String json = "{\"tracks\":["
            + "{\"id\":0,\"type\":\"video\",\"properties\":{\"language\":\"und\"}},"
            + "{\"id\":1,\"type\":\"audio\",\"properties\":{\"language\":\"eng\"}}"
            + "]}";
        FakeMerger merger = new FakeMerger().withResult(success(json));
        MkvSubtitleMerger.setToolPathForTesting(FAKE_TOOL_PATH);

        assertFalse(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_toolExitsNonZero_returnsFalse(
            @TempDir Path dir) throws IOException {
        Path media = touch(dir.resolve("show.mkv"));
        FakeMerger merger = new FakeMerger().withResult(
            new ProcessRunner.Result(false, 2, "ERROR: file not recognised\n"));
        MkvSubtitleMerger.setToolPathForTesting(FAKE_TOOL_PATH);

        assertFalse(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_malformedJson_returnsFalseWithoutThrowing(
            @TempDir Path dir) throws IOException {
        Path media = touch(dir.resolve("show.mkv"));
        // Truncated, unparseable, but a regex-based scan should still safely
        // not match any subtitles+language pair.
        String junk = "{not really json at all <<<";
        FakeMerger merger = new FakeMerger().withResult(success(junk));
        MkvSubtitleMerger.setToolPathForTesting(FAKE_TOOL_PATH);

        assertFalse(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_emptyOutput_returnsFalse(
            @TempDir Path dir) throws IOException {
        Path media = touch(dir.resolve("show.mkv"));
        FakeMerger merger = new FakeMerger().withResult(success(""));
        MkvSubtitleMerger.setToolPathForTesting(FAKE_TOOL_PATH);

        assertFalse(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    @Test
    void alreadyHasLanguageTrack_noTool_returnsFalse(@TempDir Path dir) throws IOException {
        Path media = touch(dir.resolve("show.mkv"));
        FakeMerger merger = new FakeMerger();
        // Empty path string = "tool not found" cached state.
        MkvSubtitleMerger.setToolPathForTesting("");

        assertFalse(merger.alreadyHasLanguageTrack(media, "eng"));
    }

    // ---------- merge ----------

    @Test
    void merge_emptySubtitleList_returnsSuccessWithoutSpawning(@TempDir Path dir)
            throws IOException {
        Path media = touch(dir.resolve("show.mkv"), 100);
        FakeMerger merger = new FakeMerger();
        // No tool detection necessary — merge() short-circuits first.
        MergeOutcome outcome = merger.merge(media, List.of());

        assertSame(MergeOutcome.SUCCESS, outcome);
        assertEquals(0, merger.callCount(),
            "no process should be spawned for an empty subtitle list");
    }

    @Test
    void merge_toolNotDetected_returnsSkippedNoTool(@TempDir Path dir) throws IOException {
        Path media = touch(dir.resolve("show.mkv"), 100);
        Path sub = touch(dir.resolve("show.en.srt"), 50);
        SubtitleEntry entry = new SubtitleEntry(sub, "eng", "English",
            EnumSet.noneOf(Descriptor.class));
        FakeMerger merger = new FakeMerger();
        // Cache "tool not found".
        MkvSubtitleMerger.setToolPathForTesting("");

        MergeOutcome outcome = merger.merge(media, List.of(entry));

        assertSame(MergeOutcome.SKIPPED_NO_TOOL, outcome);
        assertEquals(0, merger.callCount());
    }

    @Test
    void merge_happyPath_succeedsAndReplacesSource(@TempDir Path dir) throws IOException {
        Path media = touch(dir.resolve("show.mkv"), 100);
        Path sub = touch(dir.resolve("show.en.srt"), 30);
        SubtitleEntry entry = new SubtitleEntry(sub, "eng", "English",
            EnumSet.noneOf(Descriptor.class));
        MkvSubtitleMerger.setToolPathForTesting(FAKE_TOOL_PATH);

        // Side effect: when the fake "process" runs, write the new merged content
        // to the expected temp file location so the integrity gate passes and
        // the swap can succeed.  We use 110 bytes so it's well above the source's
        // 80% threshold; content marker "MERGED" lets us verify the swap landed.
        byte[] mergedPayload = makePayload("MERGED", 110);
        Path expectedTemp = dir.resolve("show.mkv.merging.mkv");

        FakeMerger merger = new FakeMerger()
            .withResult(success(""))
            .withSideEffect(() -> Files.write(expectedTemp, mergedPayload));

        MergeOutcome outcome = merger.merge(media, List.of(entry));

        assertSame(MergeOutcome.SUCCESS, outcome);
        assertEquals(1, merger.callCount(),
            "exactly one mkvmerge invocation expected");
        assertFalse(Files.exists(expectedTemp),
            "temp file should have been swapped away on success");
        assertTrue(Files.exists(media),
            "source path should still exist (now holding merged content)");
        byte[] resulting = Files.readAllBytes(media);
        assertArrayEquals(mergedPayload, resulting,
            "source path should now hold the merged bytes");

        // Spot-check the recorded command: it should start with the tool, -o,
        // the temp path, then the source path, and end with the subtitle path.
        List<String> recorded = merger.lastCommand();
        assertNotNull(recorded);
        assertEquals(FAKE_TOOL_PATH, recorded.get(0));
        assertEquals("-o", recorded.get(1));
        assertEquals(expectedTemp.toString(), recorded.get(2));
        assertEquals(media.toString(), recorded.get(3));
        assertEquals(sub.toString(), recorded.get(recorded.size() - 1));
    }

    @Test
    void merge_toolExitsNonZero_returnsFailedAndLeavesSourceUntouched(
            @TempDir Path dir) throws IOException {
        byte[] originalBytes = makePayload("ORIGINAL", 100);
        Path media = dir.resolve("show.mkv");
        Files.write(media, originalBytes);
        Path sub = touch(dir.resolve("show.en.srt"), 20);
        SubtitleEntry entry = new SubtitleEntry(sub, "eng", "English",
            EnumSet.noneOf(Descriptor.class));
        MkvSubtitleMerger.setToolPathForTesting(FAKE_TOOL_PATH);

        Path expectedTemp = dir.resolve("show.mkv.merging.mkv");
        // Even on failure, write a temp file so we can confirm the production
        // code deletes it on the failure branch.
        FakeMerger merger = new FakeMerger()
            .withResult(new ProcessRunner.Result(false, 2, "ERROR: bad input\n"))
            .withSideEffect(() -> Files.write(expectedTemp, new byte[50]));

        MergeOutcome outcome = merger.merge(media, List.of(entry));

        assertSame(MergeOutcome.FAILED, outcome);
        assertEquals(1, merger.callCount());
        assertTrue(Files.exists(media), "source path should be untouched");
        assertArrayEquals(originalBytes, Files.readAllBytes(media),
            "source content should be unchanged after a failed merge");
        assertFalse(Files.exists(expectedTemp),
            "temp file should have been cleaned up on failure");
    }

    @Test
    void merge_integrityGateFails_returnsFailedAndDeletesTemp(@TempDir Path dir)
            throws IOException {
        byte[] originalBytes = makePayload("ORIGINAL", 100);
        Path media = dir.resolve("show.mkv");
        Files.write(media, originalBytes);
        Path sub = touch(dir.resolve("show.en.srt"), 20);
        SubtitleEntry entry = new SubtitleEntry(sub, "eng", "English",
            EnumSet.noneOf(Descriptor.class));
        MkvSubtitleMerger.setToolPathForTesting(FAKE_TOOL_PATH);

        Path expectedTemp = dir.resolve("show.mkv.merging.mkv");
        // mkvmerge "succeeds" but writes a 0-byte temp file.  0 bytes is far
        // below the 80% floor of the 100-byte source, so the integrity gate
        // must reject and merge() must report FAILED + clean up.
        FakeMerger merger = new FakeMerger()
            .withResult(success(""))
            .withSideEffect(() -> Files.write(expectedTemp, new byte[0]));

        MergeOutcome outcome = merger.merge(media, List.of(entry));

        assertSame(MergeOutcome.FAILED, outcome);
        assertEquals(1, merger.callCount());
        assertTrue(Files.exists(media), "source must be untouched on integrity failure");
        assertArrayEquals(originalBytes, Files.readAllBytes(media));
        assertFalse(Files.exists(expectedTemp),
            "temp file should be deleted after integrity gate failure");
    }

    // ---------- Test helpers ----------

    /** Build a {@link ProcessRunner.Result} representing a successful run. */
    private static ProcessRunner.Result success(String output) {
        return new ProcessRunner.Result(true, 0, output);
    }

    /** Create an empty file at {@code path} and return it. */
    private static Path touch(Path path) throws IOException {
        Files.createFile(path);
        return path;
    }

    /** Create a file at {@code path} with {@code size} arbitrary bytes. */
    private static Path touch(Path path, int size) throws IOException {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        Files.write(path, payload);
        return path;
    }

    /**
     * Build a deterministic payload of the requested size, prefixed with the
     * supplied marker (UTF-8) so test assertions can identify which write
     * produced the bytes on disk.
     */
    private static byte[] makePayload(String marker, int size) {
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) {
            if (i < markerBytes.length) {
                result[i] = markerBytes[i];
            } else {
                result[i] = (byte) (i & 0xFF);
            }
        }
        return result;
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
     *       a held inner {@link MkvSubtitleMerger} that was constructed with
     *       {@code this} injected as both ops.  Tests can call
     *       {@code merger.merge(...)} or {@code merger.alreadyHasLanguageTrack(...)}
     *       directly on the fake and the captures still happen in this
     *       object's lists.</li>
     * </ul>
     *
     * <p>The inner merger is final and constructed eagerly during
     * initialiser execution; {@code this} is well-defined at that point in
     * Java (the FakeMerger object exists and its fields are zero-initialised).
     */
    private static final class FakeMerger
            implements SubtitleMerger, ProcessOps.Run, ProcessOps.Streaming {
        private ProcessRunner.Result canned = success("");
        private IoRunnable sideEffect = null;
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Integer> timeouts = new ArrayList<>();

        private final MkvSubtitleMerger inner =
            new MkvSubtitleMerger(this, this);

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
