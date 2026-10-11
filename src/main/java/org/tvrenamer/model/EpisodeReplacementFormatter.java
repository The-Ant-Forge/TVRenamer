package org.tvrenamer.model;

import static org.tvrenamer.model.ReplacementToken.*;
import static org.tvrenamer.model.util.Constants.*;

import org.tvrenamer.controller.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles replacement token substitution for episode renaming.
 *
 * <p>This class is responsible for taking a user-provided template string
 * (e.g., "%S S%0sE%0e %t") and replacing the tokens with actual episode
 * information to produce the final filename.
 *
 * <p>All methods are static and pure (no side effects), making this class
 * safe to use from any context.
 */
public final class EpisodeReplacementFormatter {

    private static final Logger logger = Logger.getLogger(
        EpisodeReplacementFormatter.class.getName()
    );

    // Cached DateTimeFormatters (thread-safe and expensive to create)
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("d");
    private static final DateTimeFormatter DAY_LZ_FORMAT = DateTimeFormatter.ofPattern("dd");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("M");
    private static final DateTimeFormatter MONTH_LZ_FORMAT = DateTimeFormatter.ofPattern("MM");
    private static final DateTimeFormatter YEAR_FULL_FORMAT = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter YEAR_SHORT_FORMAT = DateTimeFormatter.ofPattern("yy");

    private EpisodeReplacementFormatter() {
        // Utility class - prevent instantiation
    }

    /**
     * Format episode number, handling multi-episode spans.
     *
     * <p>For multi-episode files, the format depends on the span:
     * <ul>
     *   <li>2 episodes (span=1): with e separator, e.g., "04e05" (avoids ambiguity with ep 405)</li>
     *   <li>3+ episodes (span≥2): dash-separated range, e.g., "01-e04"</li>
     * </ul>
     *
     * <p>Combined with template "E%0e", produces Plex-compatible names like "E04e05" or "E01-e04".
     *
     * @param startEp the starting episode number
     * @param endEp the ending episode number (null for single episode)
     * @param zeroPad true to zero-pad numbers (for %0e token)
     * @return formatted episode number string
     */
    private static String formatEpisodeNumber(int startEp, Integer endEp, boolean zeroPad) {
        if (endEp == null || endEp <= startEp) {
            // Single episode
            return zeroPad
                ? StringUtils.zeroPadThreeDigits(startEp)
                : StringUtils.formatDigits(startEp);
        }

        int span = endEp - startEp;
        if (span == 1) {
            // Two consecutive episodes: 04e05 (with e separator to avoid ambiguity with ep 405)
            return zeroPad
                ? StringUtils.zeroPadThreeDigits(startEp) + "e" + StringUtils.zeroPadThreeDigits(endEp)
                : StringUtils.formatDigits(startEp) + "e" + StringUtils.formatDigits(endEp);
        } else {
            // 3+ episodes: 01-e04 (dash-separated range with e prefix on end)
            return zeroPad
                ? StringUtils.zeroPadThreeDigits(startEp) + "-e" + StringUtils.zeroPadThreeDigits(endEp)
                : StringUtils.formatDigits(startEp) + "-e" + StringUtils.formatDigits(endEp);
        }
    }

    /**
     * Replace the control strings in the replacement template with episode information.
     *
     * @param replacementTemplate the template provided by the user via preferences
     * @param show the TV show that matches this file
     * @param episode the episode that matches this file
     * @param placement the season/episode numbers from the filename
     * @param resolution the screen resolution (e.g., "720p") from the filename
     * @param multiEpisodeStart start episode number for multi-episode files (null for single)
     * @param multiEpisodeEnd end episode number for multi-episode files (null for single)
     * @return the template string with episode information substituted
     */
    public static String format(
            final String replacementTemplate,
            final Show show,
            final Episode episode,
            final EpisodePlacement placement,
            final String resolution,
            final Integer multiEpisodeStart,
            final Integer multiEpisodeEnd) {

        final String showName = show.getName();
        // A provider can return an episode with no title (e.g. no translation in
        // the chosen language): leave the title tokens empty rather than fail.
        String episodeTitle = episode.getTitle();
        if (episodeTitle == null) {
            episodeTitle = "";
        }

        if (episodeTitle.length() > MAX_TITLE_LENGTH) {
            final String fullTitle = episodeTitle;
            logger.log(Level.FINE, () -> "truncating episode title: " + fullTitle);
            episodeTitle = episodeTitle.substring(0, MAX_TITLE_LENGTH);
        }

        // Use replace() instead of replaceAll() - tokens are literals, not regex.
        // This avoids regex overhead and the need for Matcher.quoteReplacement().
        String result = replacementTemplate
            .replace(SEASON_NUM.getToken(), String.valueOf(placement.season()))
            .replace(SEASON_NUM_LEADING_ZERO.getToken(), StringUtils.zeroPadTwoDigits(placement.season()))
            .replace(EPISODE_NUM.getToken(),
                formatEpisodeNumber(placement.episode(), multiEpisodeEnd, false))
            .replace(EPISODE_NUM_LEADING_ZERO.getToken(),
                formatEpisodeNumber(placement.episode(), multiEpisodeEnd, true))
            .replace(SHOW_NAME.getToken(), showName)
            .replace(EPISODE_TITLE.getToken(), episodeTitle)
            .replace(EPISODE_TITLE_NO_SPACES.getToken(), StringUtils.makeDotTitle(episodeTitle))
            .replace(EPISODE_RESOLUTION.getToken(), resolution);

        // Handle air date tokens
        final LocalDate airDate = episode.getAirDate();
        if (airDate == null) {
            logger.log(Level.WARNING,
                "Episode air date not found for {0}, {1}, \"{2}\"",
                new Object[]{showName, placement, episodeTitle});
        }

        result = substituteAirDate(airDate, result);

        return StringUtils.sanitiseTitle(result);
    }

    /**
     * Replace date tokens in the template with air date information.
     *
     * @param airDate the episode air date (may be null)
     * @param template the template string (may be partially filled in)
     * @return the template with date tokens replaced
     */
    static String substituteAirDate(final LocalDate airDate, final String template) {
        if (airDate == null) {
            return removeTokens(template,
                DATE_DAY_NUM, DATE_DAY_NUMLZ,
                DATE_MONTH_NUM, DATE_MONTH_NUMLZ,
                DATE_YEAR_FULL, DATE_YEAR_MIN);
        }

        return template
            .replace(DATE_DAY_NUM.getToken(), DAY_FORMAT.format(airDate))
            .replace(DATE_DAY_NUMLZ.getToken(), DAY_LZ_FORMAT.format(airDate))
            .replace(DATE_MONTH_NUM.getToken(), MONTH_FORMAT.format(airDate))
            .replace(DATE_MONTH_NUMLZ.getToken(), MONTH_LZ_FORMAT.format(airDate))
            .replace(DATE_YEAR_FULL.getToken(), YEAR_FULL_FORMAT.format(airDate))
            .replace(DATE_YEAR_MIN.getToken(), YEAR_SHORT_FORMAT.format(airDate));
    }

    /**
     * Remove the specified tokens from the string (used when data is unavailable).
     */
    private static String removeTokens(final String orig, final ReplacementToken... tokens) {
        String result = orig;
        for (ReplacementToken token : tokens) {
            result = result.replace(token.getToken(), "");
        }
        return result;
    }
}
