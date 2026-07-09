package org.tvrenamer.controller.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.tvrenamer.controller.subtitle.SubtitleMerger.MergeOutcome;
import org.tvrenamer.model.UserPreferences;

class SubtitleMergeControllerTest {

    /** Fictional show name per project policy. */
    private static final String SHOW_BASE = "Westmark Academy.S01E02";

    /** Captures records emitted on the controller's logger across a single test method. */
    private CapturingHandler capturingHandler;
    private Logger controllerLogger;
    private Level previousLoggerLevel;

    @BeforeEach
    void setUp() {
        // The once-per-session AtomicBoolean gates persist across test methods,
        // so reset them so each test sees a fresh "first time" log opportunity.
        SubtitleMergeController.resetSessionLogGatesForTesting();

        controllerLogger = Logger.getLogger(SubtitleMergeController.class.getName());
        previousLoggerLevel = controllerLogger.getLevel();
        controllerLogger.setLevel(Level.ALL);
        capturingHandler = new CapturingHandler();
        capturingHandler.setLevel(Level.ALL);
        controllerLogger.addHandler(capturingHandler);
        // Keep records local — don't propagate to the noisy console handler.
        controllerLogger.setUseParentHandlers(false);
    }

    @AfterEach
    void tearDown() {
        if (capturingHandler != null && controllerLogger != null) {
            controllerLogger.removeHandler(capturingHandler);
            controllerLogger.setUseParentHandlers(true);
            controllerLogger.setLevel(previousLoggerLevel);
        }
    }

    // ---- Helpers ----

    private static UserPreferences prefs(boolean mergeOn,
                                         String defaultLang,
                                         boolean deleteAfter) {
        UserPreferences p = UserPreferences.fromParsedXml(null, null, null, null);
        p.setMergeSubtitles(mergeOn);
        if (defaultLang != null) {
            p.setDefaultSubtitleLanguage(defaultLang);
        }
        p.setDeleteSubtitlesAfterMerge(deleteAfter);
        return p;
    }

    private static Path touch(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.createFile(p);
        return p;
    }

    private boolean handlerHasRecord(Level level, String containing) {
        for (LogRecord rec : capturingHandler.records) {
            if (rec.getLevel().intValue() >= level.intValue()) {
                String formatted = formatRecord(rec);
                if (formatted.contains(containing)) {
                    return true;
                }
            }
        }
        return false;
    }

    private long countRecords(Level level, String containing) {
        long n = 0;
        for (LogRecord rec : capturingHandler.records) {
            if (rec.getLevel().intValue() >= level.intValue()) {
                String formatted = formatRecord(rec);
                if (formatted.contains(containing)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Build a single string from a LogRecord that includes message + bound parameters. */
    private static String formatRecord(LogRecord rec) {
        String msg = rec.getMessage();
        Object[] params = rec.getParameters();
        if (params == null || params.length == 0) {
            return msg == null ? "" : msg;
        }
        // Replace {0}, {1}, ... with the corresponding parameter's toString().
        // This is sufficient for substring-contains assertions in tests.
        String result = msg == null ? "" : msg;
        for (int i = 0; i < params.length; i++) {
            String token = "{" + i + "}";
            String value = params[i] == null ? "null" : params[i].toString();
            result = result.replace(token, value);
        }
        return result;
    }

    // ---- Test cases ----

    @Test
    void mergeDisabledShortCircuits(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".srt");

        FakeMerger mkv = mkvMerger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(false, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.DISABLED, r);
        assertEquals(0, mkv.mergeCalls, "merger.merge must not be called when prefs are off");
    }

    @Test
    void unsupportedContainerExtension(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".avi");
        touch(dir, SHOW_BASE + ".srt");

        FakeMerger mp4 = mp4Merger();
        FakeMerger mkv = mkvMerger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mp4, mkv),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.UNSUPPORTED, r);
        assertEquals(0, mp4.mergeCalls);
        assertEquals(0, mkv.mergeCalls);
    }

    @Test
    void noToolAvailableLogsOncePerSession(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".srt");

        FakeMerger mkv = mkvMerger();
        mkv.toolAvailable = false;

        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r1 = controller.mergeIfEnabled(media, null);
        SubtitleMergeController.Result r2 = controller.mergeIfEnabled(media, null);
        SubtitleMergeController.Result r3 = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.NO_TOOL, r1);
        assertEquals(SubtitleMergeController.Result.NO_TOOL, r2);
        assertEquals(SubtitleMergeController.Result.NO_TOOL, r3);
        assertEquals(0, mkv.mergeCalls);

        long noToolLogCount = countRecords(Level.INFO,
            "mkvmerge not found on PATH; subtitle merging is disabled for .mkv files this session.");
        assertEquals(1L, noToolLogCount,
            "the 'tool not found' INFO must fire exactly once per session");
    }

    @Test
    void noPairedSubtitleSiblings(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        // Touch only an unrelated file so there are no paired siblings.
        touch(dir, "completely.unrelated.srt");

        FakeMerger mkv = mkvMerger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.NO_SUBTITLES_FOUND, r);
        assertEquals(0, mkv.mergeCalls);
    }

    @Test
    void allPairedSubtitlesRejectedByMergerYieldsNoSubtitlesFound(@TempDir Path dir) throws IOException {
        // MP4 container + ASS sibling: Mp4SubtitleMerger refuses ASS, so the
        // controller should warn per file and report NO_SUBTITLES_FOUND.
        Path media = touch(dir, SHOW_BASE + ".mp4");
        Path assFile = touch(dir, SHOW_BASE + ".ass");

        FakeMerger mp4 = mp4Merger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mp4),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.NO_SUBTITLES_FOUND, r);
        assertEquals(0, mp4.mergeCalls);
        // One WARNING per rejected entry that mentions the subtitle filename.
        assertTrue(handlerHasRecord(Level.WARNING, assFile.getFileName().toString()),
            "expected a WARNING mentioning the rejected subtitle filename");
        assertTrue(handlerHasRecord(Level.WARNING, "MP4Box"),
            "expected the WARNING to mention the tool that doesn't support the format");
    }

    @Test
    void idempotencySkipsAllWhenLanguageAlreadyPresent(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".srt"); // bare match -> defaults to "eng"

        FakeMerger mkv = mkvMerger();
        mkv.languagesAlreadyPresent = Set.of("eng");

        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.ALREADY_HAS_LANGUAGE, r);
        assertEquals(0, mkv.mergeCalls,
            "merger.merge must not be called when the only language is already present");
    }

    @Test
    void partialIdempotencyMergesOnlyMissingLanguage(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".en.srt");
        touch(dir, SHOW_BASE + ".fr.srt");

        FakeMerger mkv = mkvMerger();
        mkv.languagesAlreadyPresent = Set.of("eng"); // English already present

        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.SUCCESS, r);
        assertEquals(1, mkv.mergeCalls);
        assertNotNull(mkv.capturedEntries);
        assertEquals(1, mkv.capturedEntries.size(),
            "exactly the missing-language subtitle should reach the merger");
        assertEquals("fre", mkv.capturedEntries.get(0).langCode3());
    }

    @Test
    void happyPathSuccess(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        Path srt = touch(dir, SHOW_BASE + ".srt");

        FakeMerger mkv = mkvMerger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.SUCCESS, r);
        assertEquals(1, mkv.mergeCalls);
        assertNotNull(mkv.capturedEntries);
        assertEquals(1, mkv.capturedEntries.size());
        assertEquals(srt.getFileName().toString(),
            mkv.capturedEntries.get(0).file().getFileName().toString());
        // Round-4 #37: the success INFO is the MERGER's responsibility; the
        // controller must not duplicate it (previously two identical lines
        // were logged per merged file).  The fake merger here doesn't log,
        // so no such record may exist.
        assertFalse(handlerHasRecord(Level.INFO, "Merged 1 subtitle track"));
    }

    @Test
    void mergerReturnsFailedPropagates(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".srt");

        FakeMerger mkv = mkvMerger();
        mkv.outcomeToReturn = MergeOutcome.FAILED;

        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.FAILED, r);
        assertFalse(r.isOk());
        assertEquals(1, mkv.mergeCalls);
    }

    @Test
    void mergerThrowingRuntimeExceptionIsCaught(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".srt");

        FakeMerger mkv = mkvMerger();
        mkv.throwOnMerge = true;

        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", false));

        // Must not propagate the RuntimeException.
        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.FAILED, r);
        assertTrue(handlerHasRecord(Level.WARNING, "Unexpected exception"));
    }

    @Test
    void controllerNeverDeletesSubtitleFilesEvenWhenDeletePrefIsOn(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        Path srt = touch(dir, SHOW_BASE + ".srt");

        FakeMerger mkv = mkvMerger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", true)); // deleteSubtitlesAfterMerge = true

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.SUCCESS, r);
        assertTrue(Files.exists(srt),
            "controller must NEVER delete sibling subtitle files; "
                + "deletion is the responsibility of FileMover after a successful move");
    }

    @Test
    void staleTempOlderThanOneHourIsDeletedBeforeMerge(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        Files.size(media); // touch
        touch(dir, SHOW_BASE + ".srt");

        // <base>.merging.<ext> matches the Mp4-style temp pattern documented in the spec.
        Path stale = touch(dir, SHOW_BASE + ".merging.mkv");
        // Set its mtime to 90 minutes ago — clearly older than the 1-hour cutoff.
        Files.setLastModifiedTime(stale,
            FileTime.from(Instant.now().minus(90, ChronoUnit.MINUTES)));

        FakeMerger mkv = mkvMerger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.SUCCESS, r);
        assertFalse(Files.exists(stale),
            "stale temp older than 1 hour should be cleaned up before the merge runs");
    }

    @Test
    void freshTempLessThanOneHourOldIsLeftAlone(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".srt");

        Path fresh = touch(dir, SHOW_BASE + ".merging.mkv");
        // Set mtime to 5 minutes ago — well within the 1-hour preservation window.
        Files.setLastModifiedTime(fresh,
            FileTime.from(Instant.now().minus(5, ChronoUnit.MINUTES)));

        FakeMerger mkv = mkvMerger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mkv),
            prefs(true, "eng", false));

        SubtitleMergeController.Result r = controller.mergeIfEnabled(media, null);

        assertEquals(SubtitleMergeController.Result.SUCCESS, r);
        assertTrue(Files.exists(fresh),
            "temp younger than 1 hour must be preserved (it may belong to an in-flight merge)");
    }

    @Test
    void getToolSummaryBothDetected() {
        FakeMerger mp4 = mp4Merger();
        FakeMerger mkv = mkvMerger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mp4, mkv),
            prefs(true, "eng", false));

        assertEquals("MP4Box: detected · mkvmerge: detected", controller.getToolSummary());
    }

    @Test
    void getToolSummaryOneMissing() {
        FakeMerger mp4 = mp4Merger();
        mp4.toolAvailable = false;
        FakeMerger mkv = mkvMerger();
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mp4, mkv),
            prefs(true, "eng", false));

        assertEquals("MP4Box: not found · mkvmerge: detected", controller.getToolSummary());
    }

    @Test
    void getToolSummaryBothMissing() {
        FakeMerger mp4 = mp4Merger();
        mp4.toolAvailable = false;
        FakeMerger mkv = mkvMerger();
        mkv.toolAvailable = false;
        SubtitleMergeController controller = new SubtitleMergeController(
            List.of(mp4, mkv),
            prefs(true, "eng", false));

        assertEquals("MP4Box: not found · mkvmerge: not found", controller.getToolSummary());
    }

    @Test
    void isAnyToolAvailableReflectsState() {
        FakeMerger mp4 = mp4Merger();
        FakeMerger mkv = mkvMerger();
        SubtitleMergeController bothOn = new SubtitleMergeController(
            List.of(mp4, mkv),
            prefs(true, "eng", false));
        assertTrue(bothOn.isAnyToolAvailable());

        FakeMerger mp4Off = mp4Merger();
        mp4Off.toolAvailable = false;
        FakeMerger mkvOn = mkvMerger();
        SubtitleMergeController oneOn = new SubtitleMergeController(
            List.of(mp4Off, mkvOn),
            prefs(true, "eng", false));
        assertTrue(oneOn.isAnyToolAvailable());

        FakeMerger mp4Off2 = mp4Merger();
        mp4Off2.toolAvailable = false;
        FakeMerger mkvOff = mkvMerger();
        mkvOff.toolAvailable = false;
        SubtitleMergeController noneOn = new SubtitleMergeController(
            List.of(mp4Off2, mkvOff),
            prefs(true, "eng", false));
        assertFalse(noneOn.isAnyToolAvailable());
    }

    // ---- Fakes ----

    private static FakeMerger mp4Merger() {
        return new FakeMerger("MP4Box",
            Set.of(".mp4", ".m4v"),
            Set.of(".srt", ".vtt"));
    }

    private static FakeMerger mkvMerger() {
        return new FakeMerger("mkvmerge",
            Set.of(".mkv"),
            Set.of(".srt", ".ass", ".ssa", ".vtt"));
    }

    /** In-process stand-in for a {@link SubtitleMerger} backed by canned values. */
    private static final class FakeMerger implements SubtitleMerger {
        final String toolName;
        final Set<String> containerExts;
        final Set<String> subtitleExts;
        boolean toolAvailable = true;
        Set<String> languagesAlreadyPresent = Collections.emptySet();
        MergeOutcome outcomeToReturn = MergeOutcome.SUCCESS;
        boolean throwOnMerge = false;
        List<SubtitleEntry> capturedEntries = null;
        int mergeCalls = 0;

        FakeMerger(String toolName, Set<String> containerExts, Set<String> subtitleExts) {
            this.toolName = toolName;
            this.containerExts = lower(containerExts);
            this.subtitleExts = lower(subtitleExts);
        }

        private static Set<String> lower(Set<String> in) {
            Set<String> out = new HashSet<>(in.size());
            for (String s : in) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
            return out;
        }

        @Override
        public boolean supportsContainerExtension(String containerExtension) {
            return containerExtension != null
                && containerExts.contains(containerExtension.toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean supportsSubtitleExtension(String subtitleExtension) {
            return subtitleExtension != null
                && subtitleExts.contains(subtitleExtension.toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean isToolAvailable() {
            return toolAvailable;
        }

        @Override
        public String getToolName() {
            return toolName;
        }

        @Override
        public boolean alreadyHasLanguageTrack(Path mediaFile, String langCode3) {
            if (langCode3 == null) {
                return false;
            }
            return languagesAlreadyPresent.contains(langCode3.toLowerCase(Locale.ROOT));
        }

        @Override
        public MergeOutcome merge(
                Path mediaFile,
                List<SubtitleEntry> subtitles,
                java.util.function.IntConsumer onProgress) {
            mergeCalls++;
            capturedEntries = (subtitles == null)
                ? Collections.emptyList()
                : new ArrayList<>(subtitles);
            if (throwOnMerge) {
                throw new RuntimeException("simulated merger crash");
            }
            return outcomeToReturn;
        }
    }

    /** Captures all log records emitted on a single Logger for assertion. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record == null) {
                return;
            }
            records.add(record);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            records.clear();
        }
    }

    // Suppress unused warnings for fields we set up but don't currently read.
    @SuppressWarnings("unused")
    private static EnumSet<Descriptor> noDescriptors() {
        return EnumSet.noneOf(Descriptor.class);
    }
}
