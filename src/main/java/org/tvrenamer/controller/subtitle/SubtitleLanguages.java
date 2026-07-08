package org.tvrenamer.controller.subtitle;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Catalogue of supported subtitle languages and helpers for normalising
 * filename language tags to canonical ISO 639-2 B-form codes.
 *
 * <p>The {@link #ALL} list backs the Preferences dropdown; the order matches
 * the spec's "Supported languages dropdown" section, with English first.
 *
 * <p>{@link #normalizeFilenameTag(String)} accepts 2-letter ISO 639-1 codes,
 * 3-letter ISO 639-2 B-form codes, 3-letter ISO 639-2 T-form codes, common
 * English language names, and BCP-47 tags with region or script subtags.
 * Unrecognised input returns {@link Optional#empty()}.
 */
public final class SubtitleLanguages {

    /** A single dropdown entry: a 3-letter ISO 639-2 B-form code and a human display name. */
    public record Language(String code3, String displayName) { }

    /**
     * The 30-entry catalogue, in dropdown order. English is first and is the default.
     */
    public static final List<Language> ALL = List.of(
        new Language("eng", "English"),
        new Language("spa", "Spanish"),
        new Language("fre", "French"),
        new Language("ger", "German"),
        new Language("ita", "Italian"),
        new Language("por", "Portuguese"),
        new Language("dut", "Dutch"),
        new Language("pol", "Polish"),
        new Language("rus", "Russian"),
        new Language("ukr", "Ukrainian"),
        new Language("cze", "Czech"),
        new Language("slo", "Slovak"),
        new Language("hun", "Hungarian"),
        new Language("rum", "Romanian"),
        new Language("gre", "Greek"),
        new Language("swe", "Swedish"),
        new Language("dan", "Danish"),
        new Language("nor", "Norwegian"),
        new Language("fin", "Finnish"),
        new Language("tur", "Turkish"),
        new Language("heb", "Hebrew"),
        new Language("ara", "Arabic"),
        new Language("per", "Persian"),
        new Language("hin", "Hindi"),
        new Language("ben", "Bengali"),
        new Language("ind", "Indonesian"),
        new Language("may", "Malay"),
        new Language("tha", "Thai"),
        new Language("vie", "Vietnamese"),
        new Language("chi", "Chinese")
    );

    /** Default language: English. */
    public static final Language DEFAULT = ALL.get(0);

    /**
     * Lookup table mapping every recognised filename tag form (lowercased) to
     * the canonical 3-letter ISO 639-2 B-form code. Built once at class load.
     */
    private static final Map<String, String> TAG_LOOKUP = buildTagLookup();

    private SubtitleLanguages() {
        // utility class
    }

    /**
     * Find a {@link Language} by its 3-letter code, case-insensitive.
     *
     * @param code3 a 3-letter ISO 639-2 B-form code (any case)
     * @return the matching {@link Language}, or empty if not in the catalogue
     */
    public static Optional<Language> findByCode3(String code3) {
        if (code3 == null) {
            return Optional.empty();
        }
        String normalized = code3.toLowerCase(Locale.ROOT);
        for (Language lang : ALL) {
            if (lang.code3().equals(normalized)) {
                return Optional.of(lang);
            }
        }
        return Optional.empty();
    }

    /**
     * Normalise a filename language tag to a canonical 3-letter ISO 639-2 B-form code.
     *
     * <p>Accepts:
     * <ul>
     *   <li>2-letter ISO 639-1 codes ({@code en}, {@code fr}, {@code de}, ...)</li>
     *   <li>3-letter ISO 639-2 B-form codes ({@code eng}, {@code fre}, {@code ger}, ...)</li>
     *   <li>3-letter ISO 639-2 T-form codes ({@code fra} -> {@code fre},
     *       {@code deu} -> {@code ger}, ...)</li>
     *   <li>English language names ({@code english}, {@code french}, ...)</li>
     *   <li>BCP-47 with region or script ({@code en-US} -> {@code eng},
     *       {@code pt-BR} -> {@code por}, {@code zh-Hans} -> {@code chi})</li>
     * </ul>
     *
     * @param tag a filename language tag (any case, may include region/script subtag)
     * @return the canonical 3-letter B-form code, or empty if unrecognised
     */
    public static Optional<String> normalizeFilenameTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return Optional.empty();
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        // Strip everything after the first hyphen (BCP-47 region/script subtag).
        int hyphen = lower.indexOf('-');
        if (hyphen >= 0) {
            lower = lower.substring(0, hyphen);
        }
        if (lower.isEmpty()) {
            return Optional.empty();
        }
        String code = TAG_LOOKUP.get(lower);
        return Optional.ofNullable(code);
    }

    private static Map<String, String> buildTagLookup() {
        Map<String, String> map = new HashMap<>();

        // 3-letter B-form codes map to themselves.
        for (Language lang : ALL) {
            map.put(lang.code3(), lang.code3());
        }

        // 2-letter ISO 639-1 -> 3-letter B-form.
        map.put("en", "eng");
        map.put("es", "spa");
        map.put("fr", "fre");
        map.put("de", "ger");
        map.put("it", "ita");
        map.put("pt", "por");
        map.put("nl", "dut");
        map.put("pl", "pol");
        map.put("ru", "rus");
        map.put("uk", "ukr");
        map.put("cs", "cze");
        map.put("sk", "slo");
        map.put("hu", "hun");
        map.put("ro", "rum");
        map.put("el", "gre");
        map.put("sv", "swe");
        map.put("da", "dan");
        map.put("no", "nor");
        map.put("fi", "fin");
        map.put("tr", "tur");
        map.put("he", "heb");
        map.put("ar", "ara");
        map.put("fa", "per");
        map.put("hi", "hin");
        map.put("bn", "ben");
        map.put("id", "ind");
        map.put("ms", "may");
        map.put("th", "tha");
        map.put("vi", "vie");
        map.put("zh", "chi");
        map.put("ja", "jpn");
        map.put("ko", "kor");

        // 3-letter ISO 639-2 T-form -> B-form coercions (the ~17 dual-coded languages).
        map.put("fra", "fre"); // French
        map.put("deu", "ger"); // German
        map.put("nld", "dut"); // Dutch
        map.put("zho", "chi"); // Chinese
        map.put("ces", "cze"); // Czech
        map.put("ron", "rum"); // Romanian
        map.put("ell", "gre"); // Greek
        map.put("fas", "per"); // Persian
        map.put("mya", "bur"); // Burmese
        map.put("mkd", "mac"); // Macedonian
        map.put("slk", "slo"); // Slovak
        map.put("sqi", "alb"); // Albanian
        map.put("hye", "arm"); // Armenian
        map.put("eus", "baq"); // Basque
        map.put("cym", "wel"); // Welsh
        map.put("isl", "ice"); // Icelandic

        // English language names -> 3-letter B-form.
        map.put("english", "eng");
        map.put("spanish", "spa");
        map.put("french", "fre");
        map.put("german", "ger");
        map.put("italian", "ita");
        map.put("portuguese", "por");
        map.put("dutch", "dut");
        map.put("polish", "pol");
        map.put("russian", "rus");
        map.put("ukrainian", "ukr");
        map.put("czech", "cze");
        map.put("slovak", "slo");
        map.put("hungarian", "hun");
        map.put("romanian", "rum");
        map.put("greek", "gre");
        map.put("swedish", "swe");
        map.put("danish", "dan");
        map.put("norwegian", "nor");
        map.put("finnish", "fin");
        map.put("turkish", "tur");
        map.put("hebrew", "heb");
        map.put("arabic", "ara");
        map.put("persian", "per");
        map.put("hindi", "hin");
        map.put("bengali", "ben");
        map.put("indonesian", "ind");
        map.put("malay", "may");
        map.put("thai", "tha");
        map.put("vietnamese", "vie");
        map.put("chinese", "chi");
        map.put("japanese", "jpn");
        map.put("korean", "kor");

        // Symmetry guarantee: every code this map can PRODUCE must also be
        // accepted as an INPUT.  Without this, a language outside the 30-entry
        // catalogue was one-way — "ja" mapped to "jpn", but a file tagged
        // ".jpn.srt" was unrecognised and silently fell back to the default
        // language, mislabelling the muxed track.
        for (String code3 : java.util.Set.copyOf(map.values())) {
            map.putIfAbsent(code3, code3);
        }

        return Map.copyOf(map);
    }
}
