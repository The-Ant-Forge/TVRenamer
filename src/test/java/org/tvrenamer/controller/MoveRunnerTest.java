package org.tvrenamer.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tvrenamer.controller.MoveRunner.PendingMove;

/**
 * Unit tests for the extracted, pure-logic pieces of {@link MoveRunner}:
 * post-batch candidate selection (Round-4 #29 — pins the batch-scope
 * user-trust contract), merge-unit prediction (#31), and the source-side
 * grouping key that defines the language-tag behaviour boundary (#50).
 */
class MoveRunnerTest {

    private static Path file(Path dir, String name, int size) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, new byte[size]);
        return p;
    }

    // ---------- collectPostBatchCandidates (#29) ----------

    @Test
    void candidates_mediaDestinationIsItsOwnCandidate(@TempDir Path dir)
            throws IOException {
        Path media = file(dir, "Solar Drift S01E01.mkv", 100);

        List<Path> candidates =
            MoveRunner.collectPostBatchCandidates(List.of(media));

        assertEquals(List.of(media), candidates);
    }

    /**
     * THE user-trust contract: files already at the destination that the
     * user never added to the batch must not become merge candidates.
     * (The pre-fix implementation enumerated the whole directory.)
     */
    @Test
    void candidates_preExistingUnrelatedFilesAreNeverTouched(@TempDir Path dir)
            throws IOException {
        // The batch moved exactly one file...
        Path batchMedia = file(dir, "Solar Drift S01E01.mkv", 100);
        // ...into a folder that already contains an unrelated pair.
        Path unrelatedMedia = file(dir, "Westmark Academy S02E02.mp4", 100);
        file(dir, "Westmark Academy S02E02.srt", 30);

        List<Path> candidates =
            MoveRunner.collectPostBatchCandidates(List.of(batchMedia));

        assertEquals(List.of(batchMedia), candidates);
        assertFalse(candidates.contains(unrelatedMedia),
            "pre-existing media the user never added must not be a candidate");
    }

    /**
     * Case B: a subtitle-only batch pairs with an existing media file whose
     * canonical base name prefixes the subtitle's name — the "rename a .srt
     * next to existing media" workflow deliberately kept alive by the fix.
     */
    @Test
    void candidates_subtitleMoverPullsInSameBaseMediaSibling(@TempDir Path dir)
            throws IOException {
        Path existingMedia = file(dir, "Solar Drift S01E03.mp4", 100);
        Path movedSub = file(dir, "Solar Drift S01E03.en.srt", 30);
        // Unrelated media in the same folder must stay untouched.
        Path unrelated = file(dir, "The Quiet Ones S01E01.mp4", 100);

        List<Path> candidates =
            MoveRunner.collectPostBatchCandidates(List.of(movedSub));

        assertEquals(List.of(existingMedia), candidates);
        assertFalse(candidates.contains(unrelated));
    }

    @Test
    void candidates_subtitleWithNoMatchingMediaYieldsNothing(@TempDir Path dir)
            throws IOException {
        Path movedSub = file(dir, "Solar Drift S01E04.en.srt", 30);
        file(dir, "Westmark Academy S02E05.mp4", 100);

        List<Path> candidates =
            MoveRunner.collectPostBatchCandidates(List.of(movedSub));

        assertTrue(candidates.isEmpty());
    }

    @Test
    void candidates_mediaPlusItsSubtitleDeduplicate(@TempDir Path dir)
            throws IOException {
        Path media = file(dir, "Solar Drift S01E05.mkv", 100);
        Path sub = file(dir, "Solar Drift S01E05.en.srt", 30);

        List<Path> candidates =
            MoveRunner.collectPostBatchCandidates(List.of(media, sub));

        assertEquals(List.of(media), candidates,
            "media reached via Case A and Case B must appear exactly once");
    }

    @Test
    void candidates_vanishedAndNullDestinationsAreSkipped(@TempDir Path dir) {
        Path never = dir.resolve("never-existed.mkv");

        List<Path> candidates = MoveRunner.collectPostBatchCandidates(
            java.util.Arrays.asList(never, null));

        assertTrue(candidates.isEmpty());
    }

    // ---------- predictMergeUnits (#31) ----------

    private static PendingMove pm(String srcExt, Path destDir, String destBase) {
        return new PendingMove(srcExt, destDir, destBase);
    }

    @Test
    void predict_pairedMediaCountsOnce(@TempDir Path dir) {
        List<PendingMove> pending = List.of(
            pm(".mkv", dir, "Solar Drift S01E01"),
            pm(".srt", dir, "Solar Drift S01E01.en"),
            pm(".ass", dir, "Solar Drift S01E01.fr")
        );
        assertEquals(1, MoveRunner.predictMergeUnits(pending),
            "two subtitles for one media file is still one merge unit");
    }

    @Test
    void predict_unpairedMediaAndBareSubtitlesCountZero(@TempDir Path dir) {
        assertEquals(0, MoveRunner.predictMergeUnits(List.of(
            pm(".mkv", dir, "Solar Drift S01E01"),
            pm(".mp4", dir, "Solar Drift S01E02")
        )), "media without subtitles predicts no merge units");

        assertEquals(0, MoveRunner.predictMergeUnits(List.of(
            pm(".srt", dir, "Solar Drift S01E01.en")
        )), "a subtitle without media predicts no merge units");
    }

    @Test
    void predict_destinationDirectoryMustMatch(@TempDir Path dir)
            throws IOException {
        Path other = Files.createDirectory(dir.resolve("other"));
        assertEquals(0, MoveRunner.predictMergeUnits(List.of(
            pm(".mkv", dir, "Solar Drift S01E01"),
            pm(".srt", other, "Solar Drift S01E01.en")
        )));
    }

    @Test
    void predict_baseNamePrefixRuleAndMultiplePairs(@TempDir Path dir) {
        List<PendingMove> pending = List.of(
            pm(".mkv", dir, "Solar Drift S01E01"),
            pm(".srt", dir, "Solar Drift S01E01.en"),
            pm(".mp4", dir, "Solar Drift S01E02"),
            pm(".srt", dir, "Solar Drift S01E02"),        // bare match
            pm(".mkv", dir, "Solar Drift S01E03"),
            pm(".srt", dir, "Westmark Academy S01E03.en") // prefix miss
        );
        assertEquals(2, MoveRunner.predictMergeUnits(pending));
    }

    @Test
    void predict_nullsAreTolerated(@TempDir Path dir) {
        assertEquals(0, MoveRunner.predictMergeUnits(null));
        assertEquals(0, MoveRunner.predictMergeUnits(
            java.util.Arrays.asList(null, pm(".mkv", null, "x"),
                pm(".mkv", dir, ""))));
    }

    // ---------- sourceSideGroupKey (#50 behaviour boundary) ----------

    @Test
    void groupKey_bareSubtitleGroupsWithItsMedia(@TempDir Path dir) {
        String mediaKey = MoveRunner.sourceSideGroupKey(dir, "Solar Drift S01E01.mkv");
        String bareSubKey = MoveRunner.sourceSideGroupKey(dir, "Solar Drift S01E01.srt");
        assertEquals(mediaKey, bareSubKey,
            "bare-named subtitle must merge source-side with its media");
    }

    /**
     * Pins the Round-4 #50 boundary: a language-tagged subtitle has a
     * different canonical base than its media, so it does NOT group
     * source-side — it falls through to the post-batch phase, where
     * SubtitlePairing parses the tag.  If this ever changes, the source-side
     * merge must learn to parse language tags (it currently hard-codes the
     * default language for everything in the group).
     */
    @Test
    void groupKey_languageTaggedSubtitleDoesNotGroupSourceSide(@TempDir Path dir) {
        String mediaKey = MoveRunner.sourceSideGroupKey(dir, "Solar Drift S01E01.mkv");
        String taggedSubKey = MoveRunner.sourceSideGroupKey(dir, "Solar Drift S01E01.en.srt");
        assertNotEquals(mediaKey, taggedSubKey,
            "tagged subtitles must defer to post-batch, where tags are parsed");
    }

    @Test
    void groupKey_nullAndEmptyInputsYieldNull(@TempDir Path dir) {
        assertNull(MoveRunner.sourceSideGroupKey(null, "x.mkv"));
        assertNull(MoveRunner.sourceSideGroupKey(dir, ""));
    }
}
