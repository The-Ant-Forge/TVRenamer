package org.tvrenamer.controller.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.tvrenamer.controller.subtitle.SubtitleLanguages.Language;

class SubtitleLanguagesTest {

    @Test
    void allHasThirtyEntriesEnglishFirst() {
        assertEquals(30, SubtitleLanguages.ALL.size(), "ALL must have exactly 30 entries");
        assertEquals("eng", SubtitleLanguages.ALL.get(0).code3());
        assertEquals("English", SubtitleLanguages.ALL.get(0).displayName());
    }

    @Test
    void defaultIsEnglish() {
        assertSame(SubtitleLanguages.ALL.get(0), SubtitleLanguages.DEFAULT);
        assertEquals("eng", SubtitleLanguages.DEFAULT.code3());
    }

    @Test
    void findByCode3IsCaseInsensitive() {
        Optional<Language> lower = SubtitleLanguages.findByCode3("eng");
        Optional<Language> upper = SubtitleLanguages.findByCode3("ENG");
        Optional<Language> mixed = SubtitleLanguages.findByCode3("Eng");
        assertTrue(lower.isPresent());
        assertTrue(upper.isPresent());
        assertTrue(mixed.isPresent());
        assertSame(lower.get(), upper.get());
        assertSame(lower.get(), mixed.get());
    }

    @Test
    void findByCode3UnknownReturnsEmpty() {
        assertTrue(SubtitleLanguages.findByCode3("xxx").isEmpty());
        assertTrue(SubtitleLanguages.findByCode3("").isEmpty());
        assertTrue(SubtitleLanguages.findByCode3(null).isEmpty());
    }

    @Test
    void findByCode3CoversAllCatalogueEntries() {
        for (Language lang : SubtitleLanguages.ALL) {
            Optional<Language> found = SubtitleLanguages.findByCode3(lang.code3());
            assertTrue(found.isPresent(), "Catalogue entry " + lang.code3() + " must be findable");
            assertEquals(lang.displayName(), found.get().displayName());
        }
    }

    // --- normalizeFilenameTag: 2-letter ISO 639-1 ---

    @Test
    void normalizeTwoLetterCodes() {
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("en").orElseThrow());
        assertEquals("fre", SubtitleLanguages.normalizeFilenameTag("fr").orElseThrow());
        assertEquals("ger", SubtitleLanguages.normalizeFilenameTag("de").orElseThrow());
        assertEquals("spa", SubtitleLanguages.normalizeFilenameTag("es").orElseThrow());
        assertEquals("ita", SubtitleLanguages.normalizeFilenameTag("it").orElseThrow());
        assertEquals("por", SubtitleLanguages.normalizeFilenameTag("pt").orElseThrow());
        assertEquals("dut", SubtitleLanguages.normalizeFilenameTag("nl").orElseThrow());
        assertEquals("rus", SubtitleLanguages.normalizeFilenameTag("ru").orElseThrow());
        assertEquals("chi", SubtitleLanguages.normalizeFilenameTag("zh").orElseThrow());
        assertEquals("jpn", SubtitleLanguages.normalizeFilenameTag("ja").orElseThrow());
        assertEquals("kor", SubtitleLanguages.normalizeFilenameTag("ko").orElseThrow());
    }

    // --- normalizeFilenameTag: 3-letter B-form ---

    @Test
    void normalizeThreeLetterBForm() {
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("eng").orElseThrow());
        assertEquals("fre", SubtitleLanguages.normalizeFilenameTag("fre").orElseThrow());
        assertEquals("ger", SubtitleLanguages.normalizeFilenameTag("ger").orElseThrow());
        assertEquals("dut", SubtitleLanguages.normalizeFilenameTag("dut").orElseThrow());
        assertEquals("chi", SubtitleLanguages.normalizeFilenameTag("chi").orElseThrow());
    }

    // --- normalizeFilenameTag: 3-letter T-form (all 17 variants required by user) ---

    @Test
    void normalizeThreeLetterTForm_French_fra() {
        assertEquals("fre", SubtitleLanguages.normalizeFilenameTag("fra").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_German_deu() {
        assertEquals("ger", SubtitleLanguages.normalizeFilenameTag("deu").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Dutch_nld() {
        assertEquals("dut", SubtitleLanguages.normalizeFilenameTag("nld").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Chinese_zho() {
        assertEquals("chi", SubtitleLanguages.normalizeFilenameTag("zho").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Czech_ces() {
        assertEquals("cze", SubtitleLanguages.normalizeFilenameTag("ces").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Romanian_ron() {
        assertEquals("rum", SubtitleLanguages.normalizeFilenameTag("ron").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Greek_ell() {
        assertEquals("gre", SubtitleLanguages.normalizeFilenameTag("ell").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Persian_fas() {
        assertEquals("per", SubtitleLanguages.normalizeFilenameTag("fas").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Burmese_mya() {
        assertEquals("bur", SubtitleLanguages.normalizeFilenameTag("mya").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Macedonian_mkd() {
        assertEquals("mac", SubtitleLanguages.normalizeFilenameTag("mkd").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Slovak_slk() {
        assertEquals("slo", SubtitleLanguages.normalizeFilenameTag("slk").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Albanian_sqi() {
        assertEquals("alb", SubtitleLanguages.normalizeFilenameTag("sqi").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Armenian_hye() {
        assertEquals("arm", SubtitleLanguages.normalizeFilenameTag("hye").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Basque_eus() {
        assertEquals("baq", SubtitleLanguages.normalizeFilenameTag("eus").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Welsh_cym() {
        assertEquals("wel", SubtitleLanguages.normalizeFilenameTag("cym").orElseThrow());
    }

    @Test
    void normalizeThreeLetterTForm_Icelandic_isl() {
        assertEquals("ice", SubtitleLanguages.normalizeFilenameTag("isl").orElseThrow());
    }

    @Test
    void freIsCanonicalNotFra() {
        // "fra" must coerce to "fre", and "fre" must round-trip to "fre".
        assertEquals("fre", SubtitleLanguages.normalizeFilenameTag("fra").orElseThrow());
        assertEquals("fre", SubtitleLanguages.normalizeFilenameTag("fre").orElseThrow());
        assertEquals("fre", SubtitleLanguages.normalizeFilenameTag("fr").orElseThrow());
        assertEquals("fre", SubtitleLanguages.normalizeFilenameTag("french").orElseThrow());
        // The catalogue entry is also B-form.
        assertEquals("fre", SubtitleLanguages.findByCode3("fre").orElseThrow().code3());
    }

    // --- normalizeFilenameTag: English language names ---

    @Test
    void normalizeEnglishNames() {
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("english").orElseThrow());
        assertEquals("fre", SubtitleLanguages.normalizeFilenameTag("french").orElseThrow());
        assertEquals("ger", SubtitleLanguages.normalizeFilenameTag("german").orElseThrow());
        assertEquals("spa", SubtitleLanguages.normalizeFilenameTag("spanish").orElseThrow());
        assertEquals("por", SubtitleLanguages.normalizeFilenameTag("portuguese").orElseThrow());
        assertEquals("chi", SubtitleLanguages.normalizeFilenameTag("chinese").orElseThrow());
        assertEquals("jpn", SubtitleLanguages.normalizeFilenameTag("japanese").orElseThrow());
    }

    // --- normalizeFilenameTag: BCP-47 with region/script ---

    @Test
    void normalizeBcp47Region_enUS() {
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("en-US").orElseThrow());
    }

    @Test
    void normalizeBcp47Region_ptBR() {
        assertEquals("por", SubtitleLanguages.normalizeFilenameTag("pt-BR").orElseThrow());
    }

    @Test
    void normalizeBcp47Script_zhHans() {
        assertEquals("chi", SubtitleLanguages.normalizeFilenameTag("zh-Hans").orElseThrow());
    }

    @Test
    void normalizeBcp47Numeric_es419() {
        assertEquals("spa", SubtitleLanguages.normalizeFilenameTag("es-419").orElseThrow());
    }

    // --- Case variations ---

    @Test
    void normalizeCaseVariations() {
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("EN").orElseThrow());
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("Eng").orElseThrow());
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("ENG").orElseThrow());
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("ENGLISH").orElseThrow());
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("English").orElseThrow());
        assertEquals("eng", SubtitleLanguages.normalizeFilenameTag("EN-us").orElseThrow());
        assertEquals("por", SubtitleLanguages.normalizeFilenameTag("PT-br").orElseThrow());
    }

    // --- Garbage input ---

    @Test
    void normalizeGarbageReturnsEmpty() {
        assertTrue(SubtitleLanguages.normalizeFilenameTag("xyz").isEmpty());
        assertTrue(SubtitleLanguages.normalizeFilenameTag("zzz").isEmpty());
        assertTrue(SubtitleLanguages.normalizeFilenameTag("nope").isEmpty());
        assertTrue(SubtitleLanguages.normalizeFilenameTag("klingon").isEmpty());
        assertTrue(SubtitleLanguages.normalizeFilenameTag("").isEmpty());
        assertTrue(SubtitleLanguages.normalizeFilenameTag(null).isEmpty());
        assertTrue(SubtitleLanguages.normalizeFilenameTag("-us").isEmpty(),
            "leading hyphen leaves empty base, no match expected");
        assertFalse(SubtitleLanguages.normalizeFilenameTag("123").isPresent());
    }
}
