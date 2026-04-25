package org.tvrenamer.controller.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
// Descriptor and SubtitleEntry are top-level types in this same package; no import needed.

class SubtitlePairingTest {

    /**
     * Show name used in fixtures. Fictional per project policy in CLAUDE.md.
     */
    private static final String SHOW_BASE = "Westmark Academy.S01E02";

    private static Path touch(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.createFile(p);
        return p;
    }

    private static SubtitleEntry findByFileName(List<SubtitleEntry> entries, String name) {
        for (SubtitleEntry e : entries) {
            if (e.file().getFileName().toString().equals(name)) {
                return e;
            }
        }
        return null;
    }

    // --- Bare match ---

    @Test
    void bareMatch(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        SubtitleEntry e = result.get(0);
        assertEquals("eng", e.langCode3());
        assertEquals("English", e.trackName());
        assertTrue(e.descriptors().isEmpty());
    }

    // --- Language-tagged matches ---

    @Test
    void twoLetterLangTag(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".en.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "spa");

        assertEquals(1, result.size());
        assertEquals("eng", result.get(0).langCode3());
        assertEquals("English", result.get(0).trackName());
    }

    @Test
    void threeLetterLangTag(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".eng.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "spa");

        assertEquals(1, result.size());
        assertEquals("eng", result.get(0).langCode3());
        assertEquals("English", result.get(0).trackName());
    }

    @Test
    void threeLetterTFormCoercedToBForm(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".fra.srt"); // T-form -> "fre"

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals("fre", result.get(0).langCode3());
        assertEquals("French", result.get(0).trackName());
    }

    // --- BCP-47 region handling ---

    @Test
    void bcp47RegionEnUs(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".en-US.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "spa");

        assertEquals(1, result.size());
        assertEquals("eng", result.get(0).langCode3());
        assertEquals("English (US)", result.get(0).trackName());
    }

    @Test
    void bcp47RegionPtBr(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".pt-BR.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals("por", result.get(0).langCode3());
        assertEquals("Portuguese (BR)", result.get(0).trackName());
    }

    @Test
    void bcp47ScriptZhHans(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".zh-Hans.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals("chi", result.get(0).langCode3());
        assertEquals("Chinese (Hans)", result.get(0).trackName());
    }

    // --- Mixed-case extensions and tags ---

    @Test
    void mixedCaseExtensionSrt(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".SRT");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals("eng", result.get(0).langCode3());
    }

    @Test
    void mixedCaseExtensionAss(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".AsS");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
    }

    @Test
    void mixedCaseLangTag(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".EN.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "spa");

        assertEquals(1, result.size());
        assertEquals("eng", result.get(0).langCode3());
        assertEquals("English", result.get(0).trackName());
    }

    // --- Descriptor coverage ---

    @Test
    void descriptorSdh(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".sdh.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        SubtitleEntry e = result.get(0);
        assertEquals("eng", e.langCode3());
        assertEquals("English (SDH)", e.trackName());
        assertEquals(EnumSet.of(Descriptor.SDH), e.descriptors());
    }

    @Test
    void descriptorCcMapsToSdh(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".cc.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals(EnumSet.of(Descriptor.SDH), result.get(0).descriptors());
        assertEquals("English (SDH)", result.get(0).trackName());
    }

    @Test
    void descriptorHiMapsToSdh(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".hi.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals(EnumSet.of(Descriptor.SDH), result.get(0).descriptors());
    }

    @Test
    void descriptorHearingImpairedMapsToSdh(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".hearingimpaired.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals(EnumSet.of(Descriptor.SDH), result.get(0).descriptors());
    }

    @Test
    void descriptorForced(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".forced.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals(EnumSet.of(Descriptor.FORCED), result.get(0).descriptors());
        assertEquals("English (Forced)", result.get(0).trackName());
    }

    @Test
    void descriptorCommentary(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".commentary.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals(EnumSet.of(Descriptor.COMMENTARY), result.get(0).descriptors());
        assertEquals("English (Commentary)", result.get(0).trackName());
    }

    @Test
    void descriptorSigns(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".signs.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals(EnumSet.of(Descriptor.SIGNS), result.get(0).descriptors());
        assertEquals("English (Signs)", result.get(0).trackName());
    }

    @Test
    void descriptorSongs(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".songs.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals(EnumSet.of(Descriptor.SONGS), result.get(0).descriptors());
        assertEquals("English (Songs)", result.get(0).trackName());
    }

    @Test
    void descriptorDub(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".dub.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals(EnumSet.of(Descriptor.DUB), result.get(0).descriptors());
        assertEquals("English (Dub)", result.get(0).trackName());
    }

    // --- Multiple descriptors combined ---

    @Test
    void multipleDescriptorsSortedAlphabetically(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".en.sdh.forced.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "spa");

        assertEquals(1, result.size());
        SubtitleEntry e = result.get(0);
        assertEquals("eng", e.langCode3());
        // Forced (F) before SDH (S) alphabetically.
        assertEquals("English (Forced, SDH)", e.trackName());
        assertEquals(EnumSet.of(Descriptor.SDH, Descriptor.FORCED), e.descriptors());
    }

    @Test
    void multipleDescriptorsRegardlessOfFilenameOrder(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        // Tokens in reverse order of alphabetical sort.
        touch(dir, SHOW_BASE + ".en.forced.sdh.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals("English (Forced, SDH)", result.get(0).trackName());
    }

    // --- Default-language fallback ---

    @Test
    void defaultLanguageWhenNoTag(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "fre");

        assertEquals(1, result.size());
        assertEquals("fre", result.get(0).langCode3());
        assertEquals("French", result.get(0).trackName());
    }

    @Test
    void defaultLanguageAppliedAlongsideDescriptor(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".forced.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "spa");

        assertEquals(1, result.size());
        assertEquals("spa", result.get(0).langCode3());
        assertEquals("Spanish (Forced)", result.get(0).trackName());
    }

    // --- No match cases ---

    @Test
    void noMatchEmptyDirectory(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertTrue(result.isEmpty());
    }

    @Test
    void noMatchUnrelatedExtension(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".txt");
        touch(dir, SHOW_BASE + ".nfo");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertTrue(result.isEmpty(), ".txt and .nfo siblings must not be paired");
    }

    @Test
    void noMatchDifferentBaseName(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, "Solar Drift.S01E02.srt"); // unrelated show

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertTrue(result.isEmpty());
    }

    // --- Multiple subtitles, deterministic ordering ---

    @Test
    void multipleSubtitlesLangTaggedBeforeBareSortedByLangCode(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        Path bare = touch(dir, SHOW_BASE + ".srt");
        Path frTag = touch(dir, SHOW_BASE + ".fr.srt");
        Path enTag = touch(dir, SHOW_BASE + ".en.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(3, result.size());
        // Tagged first, sorted by langCode3: eng < fre.
        assertEquals(enTag.getFileName(), result.get(0).file().getFileName());
        assertEquals("eng", result.get(0).langCode3());
        assertEquals(frTag.getFileName(), result.get(1).file().getFileName());
        assertEquals("fre", result.get(1).langCode3());
        // Bare last.
        assertEquals(bare.getFileName(), result.get(2).file().getFileName());
        assertEquals("eng", result.get(2).langCode3()); // default applied
    }

    @Test
    void multipleSubtitlesAscendingDescriptorCount(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        Path enSdhForced = touch(dir, SHOW_BASE + ".en.sdh.forced.srt");
        Path enSdh = touch(dir, SHOW_BASE + ".en.sdh.srt");
        Path enPlain = touch(dir, SHOW_BASE + ".en.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "spa");

        assertEquals(3, result.size());
        assertEquals(enPlain.getFileName(), result.get(0).file().getFileName(),
            "0 descriptors first");
        assertEquals(enSdh.getFileName(), result.get(1).file().getFileName(),
            "1 descriptor second");
        assertEquals(enSdhForced.getFileName(), result.get(2).file().getFileName(),
            "2 descriptors last among the tagged group");
    }

    // --- Filenames with spaces, apostrophes, ampersands, unicode ---

    @Test
    void filenamesWithSpecialCharactersAndUnicode(@TempDir Path dir) throws IOException {
        String unusualBase = "Show & Co. — S01E02";
        Path media = touch(dir, unusualBase + ".mkv");
        touch(dir, unusualBase + ".en.srt");
        touch(dir, unusualBase + ".srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(2, result.size());
        SubtitleEntry tagged = findByFileName(result, unusualBase + ".en.srt");
        SubtitleEntry bare = findByFileName(result, unusualBase + ".srt");
        assertNotNull(tagged);
        assertNotNull(bare);
        assertEquals("eng", tagged.langCode3());
        assertEquals("English", tagged.trackName());
        assertEquals("eng", bare.langCode3());
    }

    @Test
    void filenameWithApostrophe(@TempDir Path dir) throws IOException {
        String base = "It's Always Westmark.S01E01";
        Path media = touch(dir, base + ".mkv");
        touch(dir, base + ".en.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals("eng", result.get(0).langCode3());
    }

    // --- Unrecognised tag ---

    @Test
    void unrecognisedTagDroppedDefaultApplied(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".xyz.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        // The file is still paired (it's a subtitle file with the matching base);
        // the unknown token is silently dropped and default lang applied.
        assertEquals(1, result.size());
        SubtitleEntry e = result.get(0);
        assertEquals("eng", e.langCode3());
        assertEquals("English", e.trackName());
        assertTrue(e.descriptors().isEmpty());
    }

    @Test
    void unrecognisedTagAlongsideRecognisedDescriptor(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".xyz.forced.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        SubtitleEntry e = result.get(0);
        assertEquals("eng", e.langCode3());
        assertEquals("English (Forced)", e.trackName());
        assertEquals(EnumSet.of(Descriptor.FORCED), e.descriptors());
    }

    // --- Various subtitle extensions all recognised ---

    @Test
    void allSupportedExtensionsRecognised(@TempDir Path dir) throws IOException {
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".en.srt");
        touch(dir, SHOW_BASE + ".fr.ass");
        touch(dir, SHOW_BASE + ".de.ssa");
        touch(dir, SHOW_BASE + ".it.vtt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(4, result.size());
    }

    // --- Media file itself never paired with itself ---

    @Test
    void mediaFileItselfNotPaired(@TempDir Path dir) throws IOException {
        // The media file with one of the supported extensions would otherwise
        // self-match if we forgot the equals-skip step. We don't actually use
        // .srt as a media extension, so synthesise a degenerate scenario:
        // a sibling that shares the *same* path object can't happen, but make
        // sure the equality check doesn't crash on normal input.
        Path media = touch(dir, SHOW_BASE + ".mkv");
        touch(dir, SHOW_BASE + ".en.srt");

        List<SubtitleEntry> result = SubtitlePairing.findFor(media, "eng");

        assertEquals(1, result.size());
        assertEquals(SHOW_BASE + ".en.srt", result.get(0).file().getFileName().toString());
    }
}
