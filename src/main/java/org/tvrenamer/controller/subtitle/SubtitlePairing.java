package org.tvrenamer.controller.subtitle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;

import org.tvrenamer.controller.subtitle.SubtitleLanguages.Language;

/**
 * Pure-logic helper that locates and parses sibling subtitle files for a media file.
 *
 * <p>Given a media file at {@code path/to/Show.S01E02.mkv}, this class scans the
 * same directory for files that:
 * <ul>
 *   <li>start with the media file's base name plus a dot (case-insensitive), AND</li>
 *   <li>end with one of the {@link #SUPPORTED_EXTENSIONS} (case-insensitive).</li>
 * </ul>
 *
 * <p>The middle segment between the base name and the extension is tokenised by
 * {@code .} and each token is classified as a language tag, region/script
 * modifier, or {@link Descriptor}. Tokens that match nothing are silently dropped.
 */
public final class SubtitlePairing {

    /** Subtitle file extensions we recognise (lowercase, leading dot). */
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".srt", ".ass", ".ssa", ".vtt");

    /**
     * Track-flavour descriptors recognised in subtitle filenames.
     * Multiple descriptors may apply to the same track (e.g. forced + SDH).
     */
    public enum Descriptor {
        SDH,
        FORCED,
        COMMENTARY,
        SIGNS,
        SONGS,
        DUB;

        /**
         * Map a lowercase filename token to a {@link Descriptor}.
         *
         * @param token a token already lowercased
         * @return the matching descriptor, or empty if not a known descriptor token
         */
        static Optional<Descriptor> fromToken(String token) {
            return switch (token) {
                case "sdh", "cc", "hi", "hearingimpaired" -> Optional.of(SDH);
                case "forced" -> Optional.of(FORCED);
                case "commentary" -> Optional.of(COMMENTARY);
                case "signs" -> Optional.of(SIGNS);
                case "songs" -> Optional.of(SONGS);
                case "dub" -> Optional.of(DUB);
                default -> Optional.empty();
            };
        }

        /** @return the human-readable form for inclusion in track names. */
        String display() {
            return switch (this) {
                case SDH -> "SDH";
                case FORCED -> "Forced";
                case COMMENTARY -> "Commentary";
                case SIGNS -> "Signs";
                case SONGS -> "Songs";
                case DUB -> "Dub";
            };
        }
    }

    /**
     * One paired subtitle file ready to be muxed.
     *
     * @param file        the absolute path of the subtitle file
     * @param langCode3   3-letter ISO 639-2 B-form language code (never blank)
     * @param trackName   the human-readable track name to embed in the container
     * @param descriptors any track flavours parsed from the filename
     */
    public record SubtitleEntry(Path file, String langCode3, String trackName, EnumSet<Descriptor> descriptors) { }

    private SubtitlePairing() {
        // utility class
    }

    /**
     * Find subtitle files paired with the given media file.
     *
     * @param mediaFile         the media file (any container extension)
     * @param defaultLangCode3  the 3-letter B-form code to apply when a sibling
     *                          subtitle has no language tag in its filename
     * @return the matching subtitle entries, ordered: language-tagged first
     *         (by language code, then by ascending descriptor count), then bare
     * @throws UncheckedIOException if listing the parent directory fails
     */
    public static List<SubtitleEntry> findFor(Path mediaFile, String defaultLangCode3) {
        Path parent = mediaFile.getParent();
        if (parent == null) {
            return Collections.emptyList();
        }

        String mediaFileName = mediaFile.getFileName().toString();
        String baseName = stripFinalExtension(mediaFileName);
        if (baseName.isEmpty()) {
            return Collections.emptyList();
        }
        String baseLower = baseName.toLowerCase(Locale.ROOT);

        List<ParsedEntry> parsed = new ArrayList<>();
        try (Stream<Path> siblings = Files.list(parent)) {
            siblings.forEach(sibling -> {
                if (sibling.equals(mediaFile)) {
                    return;
                }
                String name = sibling.getFileName().toString();
                String nameLower = name.toLowerCase(Locale.ROOT);

                // The sibling name must start with "<base>." (case-insensitive)
                // so we can cleanly tokenise the suffix. A sibling that exactly
                // equals the base name with only a subtitle extension also
                // matches (the "bare" case), captured by checking startsWith
                // with the base + ".".
                if (!nameLower.startsWith(baseLower + ".")) {
                    return;
                }
                String matchedExt = matchSupportedExtension(nameLower);
                if (matchedExt == null) {
                    return;
                }

                // Suffix is everything after baseLower (includes leading dot).
                // For a bare match (Show.S01E02.srt) suffix == ".srt"; the
                // middle is empty. For a tagged match (Show.S01E02.en.srt)
                // suffix == ".en.srt"; middle is "en".
                int suffixStart = baseLower.length();
                int extStart = nameLower.length() - matchedExt.length();
                if (extStart <= suffixStart) {
                    // Sibling is exactly "<base><ext>" which means the dot
                    // belongs to the matched extension. That's the bare case.
                    parsed.add(parseEntry(sibling, "", defaultLangCode3));
                    return;
                }
                // Drop the leading "." after the base and the trailing extension.
                String middle = nameLower.substring(suffixStart + 1, extStart);
                parsed.add(parseEntry(sibling, middle, defaultLangCode3));
            });
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }

        // Sort: language-tagged entries first (by langCode3, then ascending
        // descriptor count, then filename for stability); bare entries last.
        parsed.sort(Comparator
            .comparing((ParsedEntry p) -> !p.langTagged) // false (tagged) before true (bare)
            .thenComparing(p -> p.entry.langCode3())
            .thenComparingInt(p -> p.entry.descriptors().size())
            .thenComparing(p -> p.entry.file().getFileName().toString()));

        List<SubtitleEntry> result = new ArrayList<>(parsed.size());
        for (ParsedEntry p : parsed) {
            result.add(p.entry);
        }
        return Collections.unmodifiableList(result);
    }

    /** Internal carrier: pairs the public entry with whether it parsed a language tag. */
    private record ParsedEntry(SubtitleEntry entry, boolean langTagged) { }

    /**
     * Parse the middle-segment tokens between the base name and the subtitle
     * extension and produce a {@link ParsedEntry}.
     */
    private static ParsedEntry parseEntry(Path file, String middle, String defaultLangCode3) {
        String langCode3 = null;
        boolean langTagged = false;
        String regionDisplay = null;
        EnumSet<Descriptor> descriptors = EnumSet.noneOf(Descriptor.class);

        if (!middle.isEmpty()) {
            String[] tokens = middle.split("\\.");
            for (String raw : tokens) {
                if (raw.isEmpty()) {
                    continue;
                }
                String token = raw.toLowerCase(Locale.ROOT);

                // Descriptor classification takes priority over language
                // codes. Notably, "hi" is both a 2-letter ISO 639-1 code for
                // Hindi and a common SDH descriptor token; in subtitle
                // filenames it overwhelmingly means "hearing-impaired".
                Optional<Descriptor> descriptor = Descriptor.fromToken(token);
                if (descriptor.isPresent()) {
                    descriptors.add(descriptor.get());
                    continue;
                }

                // BCP-47 tag with a region/script subtag (e.g. "en-us", "pt-br", "zh-hans").
                int hyphen = token.indexOf('-');
                if (hyphen > 0 && langCode3 == null) {
                    String base = token.substring(0, hyphen);
                    String region = token.substring(hyphen + 1);
                    Optional<String> normalized = SubtitleLanguages.normalizeFilenameTag(base);
                    if (normalized.isPresent()) {
                        langCode3 = normalized.get();
                        langTagged = true;
                        regionDisplay = displayRegion(region);
                        continue;
                    }
                }

                // Plain language tag.
                if (langCode3 == null) {
                    Optional<String> normalized = SubtitleLanguages.normalizeFilenameTag(token);
                    if (normalized.isPresent()) {
                        langCode3 = normalized.get();
                        langTagged = true;
                        continue;
                    }
                }

                // Unknown token — silently dropped.
            }
        }

        if (langCode3 == null) {
            langCode3 = defaultLangCode3;
        }

        String trackName = buildTrackName(langCode3, regionDisplay, descriptors);
        SubtitleEntry entry = new SubtitleEntry(file, langCode3, trackName, descriptors);
        return new ParsedEntry(entry, langTagged);
    }

    /**
     * Compose the human-readable track name from the language, optional region,
     * and any descriptors.
     */
    private static String buildTrackName(String langCode3, String regionDisplay, EnumSet<Descriptor> descriptors) {
        Optional<Language> lang = SubtitleLanguages.findByCode3(langCode3);
        String displayLang = lang.map(Language::displayName).orElse(langCode3);

        boolean hasRegion = regionDisplay != null && !regionDisplay.isEmpty();
        boolean hasDescriptors = !descriptors.isEmpty();

        if (!hasRegion && !hasDescriptors) {
            return displayLang;
        }

        StringJoiner extras = new StringJoiner(", ");
        if (hasDescriptors) {
            // Sort descriptors alphabetically by display form for deterministic output.
            List<String> sorted = new ArrayList<>();
            for (Descriptor d : descriptors) {
                sorted.add(d.display());
            }
            Collections.sort(sorted);
            for (String s : sorted) {
                extras.add(s);
            }
        }
        if (hasRegion) {
            extras.add(regionDisplay);
        }
        return displayLang + " (" + extras + ")";
    }

    /**
     * Render a BCP-47 region or script subtag for inclusion in a track name.
     * Two-letter region codes are uppercased ({@code US}); script codes and
     * numeric subtags are presented as-is title-cased ({@code Hans}, {@code 419}).
     */
    private static String displayRegion(String region) {
        if (region == null || region.isEmpty()) {
            return null;
        }
        if (region.length() == 2 && region.chars().allMatch(Character::isLetter)) {
            return region.toUpperCase(Locale.ROOT);
        }
        if (region.length() >= 3 && region.chars().allMatch(Character::isLetter)) {
            // Script subtag like "hans" -> "Hans".
            return Character.toUpperCase(region.charAt(0)) + region.substring(1).toLowerCase(Locale.ROOT);
        }
        return region;
    }

    /**
     * Return the supported extension that matches the trailing portion of
     * {@code nameLower} (already lowercase), or {@code null} if none.
     */
    private static String matchSupportedExtension(String nameLower) {
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (nameLower.endsWith(ext)) {
                return ext;
            }
        }
        return null;
    }

    /** Strip the final {@code .ext} from the given filename, if any. */
    private static String stripFinalExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

}
