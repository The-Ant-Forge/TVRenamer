package org.tvrenamer.model;

import java.util.Locale;

/** Which episode-data provider TVRenamer uses (TVMaze or TheTVDB v4). */
public enum EpisodeDataProviderType {
    TVMAZE("TVMaze"),
    TVDB_V4("TheTVDB (v4)");

    private final String label;

    EpisodeDataProviderType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * Whether this provider honours the episode-ordering and title-language
     * preferences.
     *
     * @return true if those settings affect the listings this provider returns
     */
    public boolean supportsOrderingAndLanguage() {
        // TVMaze has a single aired ordering and English-only titles; only
        // TheTVDB v4 fetches by season type (aired or DVD) and by language.
        return this == TVDB_V4;
    }

    public static EpisodeDataProviderType fromString(String value) {
        if (value == null) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (upper.isEmpty()) {
            return null;
        }
        for (EpisodeDataProviderType t : values()) {
            if (t.name().equals(upper)
                || t.label.toUpperCase(Locale.ROOT).equals(upper)) {
                return t;
            }
        }
        return null;
    }
}
