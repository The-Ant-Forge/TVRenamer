package org.tvrenamer.controller.subtitle;

import java.util.Optional;

/**
 * Track-flavour descriptors recognised in subtitle filenames.
 *
 * <p>Multiple descriptors may apply to the same track (e.g. forced + SDH).
 * Filename tokens like {@code .sdh.}, {@code .cc.}, {@code .forced.} are
 * mapped here by {@link SubtitlePairing} and surfaced both in the track
 * display name and (for MKV) in mkvmerge track flags.
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
