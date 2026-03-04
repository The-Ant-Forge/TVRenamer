package org.tvrenamer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tvrenamer.controller.util.StringUtils;

/**
 * Pure (side-effect free) evaluator that centralizes show selection decisions.
 *
 * <p>This class intentionally does NOT:
 * <ul>
 *   <li>call the provider</li>
 *   <li>mutate {@link ShowName}, {@link ShowStore}, preferences, or caches</li>
 *   <li>queue disambiguations or interact with UI</li>
 * </ul>
 *
 * <p>It is designed to be used by:
 * <ul>
 *   <li>Runtime selection in {@code ShowStore.downloadShow(...)} to decide whether to auto-resolve or prompt</li>
 *   <li>Preferences Matching tab validation to decide whether an entry is "likely to resolve"</li>
 * </ul>
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@code extractedName}: the string to compare against candidate SeriesName and aliases.
 *       In runtime this should be the extracted show name (as displayed to the user); in override validation
 *       this should be the replacement text.</li>
 *   <li>{@code options}: provider candidates (may be empty).</li>
 *   <li>{@code pinnedId}: optional pinned provider id for this query string; if present and found in candidates,
 *       it wins.</li>
 * </ul>
 */
public final class ShowSelectionEvaluator {

    private ShowSelectionEvaluator() {
        // utility
    }

    public enum OutcomeType {
        RESOLVED,
        AMBIGUOUS,
        NOT_FOUND,
    }

    public static final class Decision {

        private final OutcomeType type;
        private final ShowOption chosen;
        private final String message;
        private final List<ScoredOption> scoredOptions;

        private Decision(
            final OutcomeType type,
            final ShowOption chosen,
            final String message,
            final List<ScoredOption> scoredOptions
        ) {
            this.type = Objects.requireNonNull(type, "type");
            this.chosen = chosen;
            this.message = (message == null) ? "" : message;
            this.scoredOptions = scoredOptions;
        }

        public OutcomeType getType() {
            return type;
        }

        /**
         * @return chosen option when {@link #getType()} is {@link OutcomeType#RESOLVED}; otherwise null.
         */
        public ShowOption getChosen() {
            return chosen;
        }

        /**
         * @return an explainable reason suitable for logs/UI validation messages.
         */
        public String getMessage() {
            return message;
        }

        /**
         * @return sorted list of options with similarity scores (best first), or null if not computed.
         *         Useful for disambiguation dialogs to show ranked options.
         */
        public List<ScoredOption> getScoredOptions() {
            return scoredOptions;
        }

        public boolean isResolved() {
            return type == OutcomeType.RESOLVED;
        }

        public boolean isAmbiguous() {
            return type == OutcomeType.AMBIGUOUS;
        }

        public boolean isNotFound() {
            return type == OutcomeType.NOT_FOUND;
        }

        static Decision resolved(final ShowOption chosen, final String msg) {
            return new Decision(OutcomeType.RESOLVED, chosen, msg, null);
        }

        static Decision ambiguous(final String msg, final List<ScoredOption> scoredOptions) {
            return new Decision(OutcomeType.AMBIGUOUS, null, msg, scoredOptions);
        }

        static Decision notFound(final String msg) {
            return new Decision(OutcomeType.NOT_FOUND, null, msg, null);
        }
    }

    private static final Pattern YEAR_TOKEN = Pattern.compile(
        "(^|\\s|\\()(?<year>19\\d{2}|20\\d{2})(\\s|\\)|$)"
    );

    // Fuzzy matching thresholds
    private static final double FUZZY_AUTO_SELECT_MIN_SCORE = 0.80;
    private static final double FUZZY_AUTO_SELECT_MIN_GAP = 0.10;
    private static final double FUZZY_RECOMMENDED_MIN_SCORE = 0.70;

    /**
     * Holds a ShowOption together with its similarity score for ranking.
     */
    public record ScoredOption(ShowOption option, double score) implements Comparable<ScoredOption> {

        /**
         * @return true if this option's score is high enough to be marked as recommended
         */
        public boolean isRecommended() {
            return score >= FUZZY_RECOMMENDED_MIN_SCORE;
        }

        @Override
        public int compareTo(ScoredOption other) {
            // Higher scores first
            return Double.compare(other.score, this.score);
        }
    }

    /**
     * Calculate Levenshtein (edit) distance between two strings.
     * This is the minimum number of single-character edits (insertions, deletions, substitutions)
     * required to change one string into the other.
     */
    private static int levenshteinDistance(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return (s1 == null && s2 == null) ? 0 : Integer.MAX_VALUE;
        }
        int len1 = s1.length();
        int len2 = s2.length();

        if (len1 == 0) return len2;
        if (len2 == 0) return len1;

        // Use two rows instead of full matrix for space efficiency
        int[] prev = new int[len2 + 1];
        int[] curr = new int[len2 + 1];

        for (int j = 0; j <= len2; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                );
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        return prev[len2];
    }

    /**
     * Calculate normalized similarity score (0.0 to 1.0) between two strings.
     * Uses Levenshtein distance normalized by max length.
     *
     * @return 1.0 for identical strings, 0.0 for completely different strings
     */
    public static double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        String a = s1.toLowerCase(Locale.ROOT);
        String b = s2.toLowerCase(Locale.ROOT);
        if (a.equals(b)) {
            return 1.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        int distance = levenshteinDistance(a, b);
        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Evaluate whether a set of provider candidates can be auto-resolved without prompting,
     * given the extracted show name and optional pinned id.
     *
     * <p>Decision order (high-level):
     * <ol>
     *   <li>If candidates empty → NOT_FOUND</li>
     *   <li>If pinned id matches a candidate → RESOLVED</li>
     *   <li>If candidate SeriesName matches extracted name (case-insensitive) → RESOLVED</li>
     *   <li>If candidate SeriesName matches punctuation-normalized extracted name → RESOLVED</li>
     *   <li>If candidate Alias matches extracted name or normalized extracted name → RESOLVED</li>
     *   <li>Tie-breakers (deterministic; spec-driven)</li>
     *   <li>If exactly one candidate → RESOLVED</li>
     *   <li>Fuzzy string matching with threshold and gap</li>
     *   <li>Otherwise → AMBIGUOUS</li>
     * </ol>
     */
    public static Decision evaluate(
        final String extractedName,
        final List<ShowOption> options,
        final String pinnedId
    ) {
        if (options == null || options.isEmpty()) {
            return Decision.notFound("No matches");
        }

        Decision d;

        // 1) Pinned id wins if it exists in the current candidate set.
        d = tryPinnedId(options, pinnedId);
        if (d != null) {
            return d;
        }

        // Pre-compute normalized forms used by subsequent steps.
        final String extracted = safeTrim(extractedName);
        final String extractedNormalized = safeNormalize(extracted);

        // 2) Exact SeriesName match (raw/normalized).
        d = tryExactNameMatch(options, extracted, extractedNormalized);
        if (d != null) {
            return d;
        }

        // 3) Exact alias match (raw/normalized).
        d = tryExactAliasMatch(options, extracted, extractedNormalized);
        if (d != null) {
            return d;
        }

        // TB1: Prefer base title over parenthetical variants.
        d = tryBaseTitleOverVariants(options, extracted, extractedNormalized);
        if (d != null) {
            return d;
        }

        // TB2/TB6: Strict token-set match.
        d = tryTokenSetMatch(options, extracted, extractedNormalized);
        if (d != null) {
            return d;
        }

        // TB3: Year tolerance (±1).
        d = tryYearTolerance(options, extracted, extractedNormalized);
        if (d != null) {
            return d;
        }

        // 4) Single option resolves uniquely.
        if (options.size() == 1 && options.get(0) != null) {
            return Decision.resolved(options.get(0), "Resolves uniquely");
        }

        // TB7: Fuzzy string matching, or fall through to AMBIGUOUS.
        return fuzzyMatchOrAmbiguous(options, extracted, extractedNormalized);
    }

    // ---- Decomposed decision steps ----

    /** Try to resolve via a pinned provider id. */
    private static Decision tryPinnedId(
        final List<ShowOption> options,
        final String pinnedId
    ) {
        if (pinnedId == null || pinnedId.isBlank()) {
            return null;
        }
        for (ShowOption opt : options) {
            if (opt == null) {
                continue;
            }
            String id = opt.getIdString();
            if (id != null && pinnedId.equals(id)) {
                return Decision.resolved(opt, "Resolved via pinned ID");
            }
        }
        return null;
    }

    /** Try to resolve via exact SeriesName match (raw or normalized). */
    private static Decision tryExactNameMatch(
        final List<ShowOption> options,
        final String extracted,
        final String extractedNormalized
    ) {
        for (ShowOption opt : options) {
            if (opt == null) {
                continue;
            }
            if (matchesExtracted(opt.getName(), extracted, extractedNormalized)) {
                return Decision.resolved(opt, "Resolves via exact name match");
            }
        }
        return null;
    }

    /** Try to resolve via exact alias match (raw or normalized). */
    private static Decision tryExactAliasMatch(
        final List<ShowOption> options,
        final String extracted,
        final String extractedNormalized
    ) {
        for (ShowOption opt : options) {
            if (opt == null) {
                continue;
            }
            List<String> aliases = safeAliases(opt);
            if (aliases.isEmpty()) {
                continue;
            }
            for (String a : aliases) {
                if (matchesExtracted(a, extracted, extractedNormalized)) {
                    return Decision.resolved(opt, "Resolves via exact alias match");
                }
            }
        }
        return null;
    }

    /**
     * Prefer base title when other candidates are only parenthetical variants.
     * Example: "The Night Manager" beats "The Night Manager (IN)" and "(CN)".
     */
    private static Decision tryBaseTitleOverVariants(
        final List<ShowOption> options,
        final String extracted,
        final String extractedNormalized
    ) {
        String compareName = (extractedNormalized != null && !extractedNormalized.isBlank())
            ? extractedNormalized
            : extracted;
        if (compareName == null || compareName.isBlank()) {
            return null;
        }

        ShowOption baseTitle = null;
        int baseTitleCount = 0;
        for (ShowOption opt : options) {
            if (opt == null) {
                continue;
            }
            String name = safeTrim(opt.getName());
            if (name != null && name.equalsIgnoreCase(compareName)) {
                baseTitle = opt;
                baseTitleCount++;
            }
        }
        if (baseTitleCount != 1 || baseTitle == null) {
            return null;
        }

        String baseName = safeTrim(baseTitle.getName());
        if (baseName == null) {
            return null;
        }
        for (ShowOption opt : options) {
            if (opt == null) {
                continue;
            }
            String name = safeTrim(opt.getName());
            if (name == null || name.isBlank() || name.equalsIgnoreCase(baseName)) {
                continue;
            }
            if (isParentheticalVariant(name, baseName)) {
                return Decision.resolved(
                    baseTitle,
                    "Preferred base title over parenthetical variants"
                );
            }
        }
        return null;
    }

    /** Check if {@code name} is {@code baseName} followed by " (...)". */
    private static boolean isParentheticalVariant(String name, String baseName) {
        return name.regionMatches(true, 0, baseName, 0, baseName.length())
            && name.length() > baseName.length() + 3
            && name.charAt(baseName.length()) == ' '
            && name.charAt(baseName.length() + 1) == '('
            && name.endsWith(")");
    }

    /** Prefer candidate whose canonical tokens exactly equal the extracted tokens. */
    private static Decision tryTokenSetMatch(
        final List<ShowOption> options,
        final String extracted,
        final String extractedNormalized
    ) {
        String extractedTokens = canonicalTokens(extracted);
        String normalizedTokens = canonicalTokens(extractedNormalized);
        String tokenBasis = !normalizedTokens.isBlank() ? normalizedTokens : extractedTokens;
        if (tokenBasis.isBlank()) {
            return null;
        }

        ShowOption tokenExact = null;
        int count = 0;
        for (ShowOption opt : options) {
            if (opt == null) {
                continue;
            }
            String candTokens = canonicalTokens(opt.getName());
            if (!candTokens.isBlank() && candTokens.equals(tokenBasis)) {
                tokenExact = opt;
                count++;
            }
        }
        if (count == 1 && tokenExact != null) {
            return Decision.resolved(tokenExact, "Preferred exact token match over extra tokens");
        }
        return null;
    }

    /** Resolve via FirstAiredYear (±1) if extracted contains a year token. */
    private static Decision tryYearTolerance(
        final List<ShowOption> options,
        final String extracted,
        final String extractedNormalized
    ) {
        Integer extractedYear = parseYearFromText(extracted);
        if (extractedYear == null) {
            extractedYear = parseYearFromText(extractedNormalized);
        }
        if (extractedYear == null) {
            return null;
        }

        ShowOption yearHit = null;
        int count = 0;
        for (ShowOption opt : options) {
            if (opt == null) {
                continue;
            }
            Integer y = safeFirstAiredYear(opt);
            if (y != null && Math.abs(y - extractedYear) <= 1) {
                yearHit = opt;
                count++;
            }
        }
        if (count == 1 && yearHit != null) {
            return Decision.resolved(yearHit, "Resolved via FirstAiredYear (±1) match");
        }
        return null;
    }

    /**
     * Score all candidates by fuzzy similarity. Auto-select if the best score
     * exceeds the threshold with sufficient gap to the runner-up; otherwise AMBIGUOUS.
     */
    private static Decision fuzzyMatchOrAmbiguous(
        final List<ShowOption> options,
        final String extracted,
        final String extractedNormalized
    ) {
        final String compareText = (extracted != null && !extracted.isBlank())
            ? extracted
            : extractedNormalized;

        List<ScoredOption> scored = new ArrayList<>();
        for (ShowOption opt : options) {
            if (opt == null) {
                continue;
            }
            double score = bestScore(opt, compareText);
            scored.add(new ScoredOption(opt, score));
        }
        Collections.sort(scored);

        if (!scored.isEmpty()) {
            double bestScore = scored.get(0).score();
            double secondScore = scored.size() > 1 ? scored.get(1).score() : 0.0;

            if (bestScore >= FUZZY_AUTO_SELECT_MIN_SCORE
                    && (bestScore - secondScore) >= FUZZY_AUTO_SELECT_MIN_GAP) {
                return Decision.resolved(
                    scored.get(0).option(),
                    String.format("Fuzzy match: %.0f%% (gap: %.0f%%)",
                        bestScore * 100, (bestScore - secondScore) * 100)
                );
            }
        }

        return Decision.ambiguous("Still ambiguous (would prompt)", scored);
    }

    // ---- Shared helpers ----

    /** Best similarity score for a candidate (name + aliases). */
    private static double bestScore(ShowOption opt, String compareText) {
        double score = similarity(compareText, opt.getName());
        for (String alias : safeAliases(opt)) {
            double aliasScore = similarity(compareText, alias);
            if (aliasScore > score) {
                score = aliasScore;
            }
        }
        return score;
    }

    /** Does a candidate name match extracted (raw or normalized, case-insensitive)? */
    private static boolean matchesExtracted(
        String candidateName,
        String extracted,
        String extractedNormalized
    ) {
        if (candidateName == null) {
            return false;
        }
        if (extracted != null && !extracted.isBlank()
                && candidateName.equalsIgnoreCase(extracted)) {
            return true;
        }
        return extractedNormalized != null && !extractedNormalized.isBlank()
            && candidateName.equalsIgnoreCase(extractedNormalized);
    }

    /** Normalize a string for comparison: replace punctuation and trim. */
    private static String safeNormalize(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return safeTrim(StringUtils.replacePunctuation(s));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Canonicalize for token-set comparison: normalize, lowercase, collapse spaces. */
    private static String canonicalTokens(String s) {
        if (s == null) {
            return "";
        }
        String norm;
        try {
            norm = StringUtils.replacePunctuation(s);
        } catch (Exception ignored) {
            norm = s;
        }
        norm = safeTrim(norm);
        if (norm == null || norm.isBlank()) {
            return "";
        }
        return norm.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Safely get aliases from a ShowOption. */
    private static List<String> safeAliases(ShowOption opt) {
        try {
            List<String> aliases = opt.getAliasNames();
            return (aliases != null) ? aliases : List.of();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** Safely get FirstAiredYear from a ShowOption. */
    private static Integer safeFirstAiredYear(ShowOption opt) {
        try {
            return opt.getFirstAiredYear();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer parseYearFromText(final String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        Matcher m = YEAR_TOKEN.matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group("year"));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String safeTrim(final String s) {
        if (s == null) {
            return null;
        }
        return s.trim();
    }
}
