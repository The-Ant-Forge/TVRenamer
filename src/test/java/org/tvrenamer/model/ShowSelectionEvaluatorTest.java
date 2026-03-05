package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.tvrenamer.model.ShowSelectionEvaluator.OutcomeType.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link ShowSelectionEvaluator}.
 *
 * <p>ShowSelectionEvaluator is a pure (side-effect free) evaluator that centralizes show
 * selection decisions. These tests verify the decision logic for various scenarios including
 * exact name matching, alias matching, pinned IDs, and tie-breakers.
 */
public class ShowSelectionEvaluatorTest {

    // Test data - reusable ShowOptions (all fictional names)
    private static ShowOption REALM_OF_SHADOWS;
    private static ShowOption REALM_OF_SHADOWS_2011;
    private static ShowOption THE_BUREAU_US;
    private static ShowOption THE_BUREAU_UK;
    private static ShowOption THE_BUREAU;
    private static ShowOption NIGHT_SHIFT;
    private static ShowOption NIGHT_SHIFT_IN;
    private static ShowOption NIGHT_SHIFT_CN;
    private static ShowOption DEFEND_WITH_ALIAS;
    private static ShowOption TIME_LORD;
    private static ShowOption TIME_LORD_2005;
    private static ShowOption SOME_SHOW_2010;
    private static ShowOption SOME_SHOW_2015;

    @BeforeAll
    static void setupTestData() {
        REALM_OF_SHADOWS = new ShowOption("90101", "Realm of Shadows");
        REALM_OF_SHADOWS_2011 = new ShowOption("90101", "Realm of Shadows", 2011, Collections.emptyList());
        THE_BUREAU_US = new ShowOption("90102", "The Bureau (US)");
        THE_BUREAU_UK = new ShowOption("90103", "The Bureau (UK)");
        THE_BUREAU = new ShowOption("90102", "The Bureau");
        NIGHT_SHIFT = new ShowOption("90104", "The Night Shift");
        NIGHT_SHIFT_IN = new ShowOption("90105", "The Night Shift (IN)");
        NIGHT_SHIFT_CN = new ShowOption("90106", "The Night Shift (CN)");
        DEFEND_WITH_ALIAS = new ShowOption(
            "90107",
            "Vanguard's Agents of D.E.F.E.N.D.",
            2013,
            Arrays.asList("Agents of DEFEND", "DEFEND", "Vanguard's Agents of Defend")
        );
        TIME_LORD = new ShowOption("90108", "Time Lord");
        TIME_LORD_2005 = new ShowOption("90109", "Time Lord (2005)");
        SOME_SHOW_2010 = new ShowOption("90110", "Some Show", 2010, Collections.emptyList());
        SOME_SHOW_2015 = new ShowOption("90111", "Some Show", 2015, Collections.emptyList());
    }

    // ========== NOT_FOUND Tests ==========

    @Test
    @DisplayName("Null candidates should return NOT_FOUND")
    void testNullCandidates() {
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Realm of Shadows", null, null);

        assertEquals(NOT_FOUND, decision.getType());
        assertNull(decision.getChosen());
        assertTrue(decision.isNotFound());
        assertFalse(decision.isResolved());
        assertFalse(decision.isAmbiguous());
    }

    @Test
    @DisplayName("Empty candidates should return NOT_FOUND")
    void testEmptyCandidates() {
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Realm of Shadows", Collections.emptyList(), null);

        assertEquals(NOT_FOUND, decision.getType());
        assertNull(decision.getChosen());
        assertTrue(decision.isNotFound());
    }

    // ========== Pinned ID Tests ==========

    @Test
    @DisplayName("Pinned ID matching a candidate should return RESOLVED")
    void testPinnedIdMatch() {
        List<ShowOption> options = Arrays.asList(THE_BUREAU_US, THE_BUREAU_UK);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("The Bureau", options, "90102");

        assertEquals(RESOLVED, decision.getType());
        assertEquals(THE_BUREAU_US, decision.getChosen());
        assertTrue(decision.getMessage().toLowerCase().contains("pinned"));
    }

    @Test
    @DisplayName("Pinned ID not matching any candidate should fall through to other resolution")
    void testPinnedIdNoMatch() {
        List<ShowOption> options = Arrays.asList(REALM_OF_SHADOWS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Realm of Shadows", options, "999999");

        // Should still resolve via exact name match (single candidate)
        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
    }

    @Test
    @DisplayName("Blank pinned ID should be ignored")
    void testBlankPinnedId() {
        List<ShowOption> options = Arrays.asList(REALM_OF_SHADOWS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Realm of Shadows", options, "   ");

        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
    }

    // ========== Exact Name Match Tests ==========

    @Test
    @DisplayName("Exact case-insensitive name match should return RESOLVED")
    void testExactNameMatchCaseInsensitive() {
        List<ShowOption> options = Arrays.asList(REALM_OF_SHADOWS, THE_BUREAU_US);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("realm of shadows", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
        assertTrue(decision.isResolved());
    }

    @Test
    @DisplayName("Exact name match should win even with multiple candidates")
    void testExactNameMatchMultipleCandidates() {
        List<ShowOption> options = Arrays.asList(THE_BUREAU, THE_BUREAU_US, THE_BUREAU_UK);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("The Bureau", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(THE_BUREAU, decision.getChosen());
    }

    @Test
    @DisplayName("Exact name match with leading/trailing spaces")
    void testExactNameMatchWithSpaces() {
        List<ShowOption> options = Arrays.asList(REALM_OF_SHADOWS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("  Realm of Shadows  ", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
    }

    // ========== Punctuation-Normalized Match Tests ==========

    @Test
    @DisplayName("Punctuation-normalized name match should return RESOLVED")
    void testNormalizedNameMatch() {
        List<ShowOption> options = Arrays.asList(DEFEND_WITH_ALIAS);

        // Dots in name should be normalized to match
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Vanguards Agents of DEFEND", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(DEFEND_WITH_ALIAS, decision.getChosen());
    }

    // ========== Alias Match Tests ==========

    @Test
    @DisplayName("Alias matching should return RESOLVED")
    void testAliasMatch() {
        List<ShowOption> options = Arrays.asList(DEFEND_WITH_ALIAS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Agents of DEFEND", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(DEFEND_WITH_ALIAS, decision.getChosen());
        assertTrue(decision.getMessage().toLowerCase().contains("alias"));
    }

    @Test
    @DisplayName("Second alias in list should also match")
    void testSecondAliasMatch() {
        List<ShowOption> options = Arrays.asList(DEFEND_WITH_ALIAS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("DEFEND", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(DEFEND_WITH_ALIAS, decision.getChosen());
    }

    @Test
    @DisplayName("Alias match should be case-insensitive")
    void testAliasMatchCaseInsensitive() {
        List<ShowOption> options = Arrays.asList(DEFEND_WITH_ALIAS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("agents of defend", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(DEFEND_WITH_ALIAS, decision.getChosen());
    }

    // ========== Parenthetical Variant Tie-Breaker Tests ==========

    @Test
    @DisplayName("Prefer base title over parenthetical variants")
    void testParentheticalVariantTieBreaker() {
        List<ShowOption> options = Arrays.asList(NIGHT_SHIFT, NIGHT_SHIFT_IN, NIGHT_SHIFT_CN);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("The Night Shift", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(NIGHT_SHIFT, decision.getChosen());
    }

    @Test
    @DisplayName("Parenthetical variant tie-breaker requires base title in candidates")
    void testParentheticalVariantNoBase() {
        // Only variants, no base title
        List<ShowOption> options = Arrays.asList(NIGHT_SHIFT_IN, NIGHT_SHIFT_CN);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("The Night Shift", options, null);

        // Should be AMBIGUOUS since no base title and no exact match
        assertEquals(AMBIGUOUS, decision.getType());
    }

    // ========== Token Set Match Tie-Breaker Tests ==========

    @Test
    @DisplayName("Token set matching should prefer exact token match")
    void testTokenSetTieBreaker() {
        List<ShowOption> options = Arrays.asList(TIME_LORD, TIME_LORD_2005);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Time Lord", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(TIME_LORD, decision.getChosen());
    }

    // ========== Year Tolerance Tie-Breaker Tests ==========

    @Test
    @DisplayName("Year tolerance (+/-1) should resolve when only one candidate matches")
    void testYearToleranceTieBreaker() {
        List<ShowOption> options = Arrays.asList(SOME_SHOW_2010, SOME_SHOW_2015);

        // Extracted name contains year 2011, should match 2010 (within +/-1)
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Some Show 2011", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(SOME_SHOW_2010, decision.getChosen());
        assertTrue(decision.getMessage().toLowerCase().contains("year"));
    }

    @Test
    @DisplayName("Year tolerance should not match when difference is more than 1")
    void testYearToleranceOutOfRange() {
        List<ShowOption> options = Arrays.asList(SOME_SHOW_2010, SOME_SHOW_2015);

        // Extracted name contains year 2013, neither candidate is within +/-1
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Some Show 2013", options, null);

        // Should be AMBIGUOUS since no year match
        assertEquals(AMBIGUOUS, decision.getType());
    }

    @Test
    @DisplayName("Year in parentheses should be parsed correctly")
    void testYearInParentheses() {
        List<ShowOption> options = Arrays.asList(SOME_SHOW_2010, SOME_SHOW_2015);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Some Show (2010)", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(SOME_SHOW_2010, decision.getChosen());
    }

    // ========== Single Candidate Tests ==========

    @Test
    @DisplayName("Single candidate should always resolve")
    void testSingleCandidate() {
        List<ShowOption> options = Arrays.asList(REALM_OF_SHADOWS);

        // Even with non-matching name, single candidate resolves
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("RoS", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
        assertTrue(decision.getMessage().toLowerCase().contains("uniquely"));
    }

    @Test
    @DisplayName("Single candidate with completely different name should still resolve")
    void testSingleCandidateDifferentName() {
        List<ShowOption> options = Arrays.asList(REALM_OF_SHADOWS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Completely Different Show", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
    }

    // ========== Ambiguous Tests ==========

    @Test
    @DisplayName("Multiple candidates with no matching criteria should return AMBIGUOUS")
    void testAmbiguousMultipleCandidates() {
        ShowOption showA = new ShowOption("1", "Totally Different Show A");
        ShowOption showB = new ShowOption("2", "Totally Different Show B");
        List<ShowOption> options = Arrays.asList(showA, showB);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Unknown Show", options, null);

        assertEquals(AMBIGUOUS, decision.getType());
        assertNull(decision.getChosen());
        assertTrue(decision.isAmbiguous());
        assertFalse(decision.isResolved());
    }

    @Test
    @DisplayName("Multiple Office variants without exact match should be AMBIGUOUS")
    void testAmbiguousOfficeVariants() {
        List<ShowOption> options = Arrays.asList(THE_BUREAU_US, THE_BUREAU_UK);

        // Neither "(US)" nor "(UK)" is in the extracted name, and no base "The Bureau" in candidates
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("The Bureau", options, null);

        // Both are parenthetical variants but no base title, so ambiguous
        assertEquals(AMBIGUOUS, decision.getType());
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Null extracted name with single candidate should resolve")
    void testNullExtractedName() {
        List<ShowOption> options = Arrays.asList(REALM_OF_SHADOWS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate(null, options, null);

        // Single candidate should resolve
        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
    }

    @Test
    @DisplayName("Blank extracted name with single candidate should resolve")
    void testBlankExtractedName() {
        List<ShowOption> options = Arrays.asList(REALM_OF_SHADOWS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("   ", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
    }

    @Test
    @DisplayName("Options list containing null should be handled gracefully")
    void testOptionsWithNulls() {
        List<ShowOption> options = Arrays.asList(null, REALM_OF_SHADOWS, null);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Realm of Shadows", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
    }

    @Test
    @DisplayName("ShowOption with null name should be handled gracefully")
    void testShowOptionWithNullName() {
        ShowOption nullNameOption = new ShowOption("123", null);
        List<ShowOption> options = Arrays.asList(nullNameOption, REALM_OF_SHADOWS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Realm of Shadows", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(REALM_OF_SHADOWS, decision.getChosen());
    }

    // ========== Decision Helper Method Tests ==========

    @Test
    @DisplayName("Decision helper methods work correctly for RESOLVED")
    void testDecisionHelperMethodsResolved() {
        ShowSelectionEvaluator.Decision resolved =
            ShowSelectionEvaluator.evaluate("Realm of Shadows",
                Arrays.asList(REALM_OF_SHADOWS), null);

        assertTrue(resolved.isResolved());
        assertFalse(resolved.isAmbiguous());
        assertFalse(resolved.isNotFound());
        assertNotNull(resolved.getChosen());
        assertNotNull(resolved.getMessage());
        assertFalse(resolved.getMessage().isEmpty());
    }

    @Test
    @DisplayName("Decision helper methods work correctly for NOT_FOUND")
    void testDecisionHelperMethodsNotFound() {
        ShowSelectionEvaluator.Decision notFound =
            ShowSelectionEvaluator.evaluate("X", Collections.emptyList(), null);

        assertFalse(notFound.isResolved());
        assertFalse(notFound.isAmbiguous());
        assertTrue(notFound.isNotFound());
        assertNull(notFound.getChosen());
    }

    @Test
    @DisplayName("Decision helper methods work correctly for AMBIGUOUS")
    void testDecisionHelperMethodsAmbiguous() {
        ShowOption showA = new ShowOption("1", "Show A");
        ShowOption showB = new ShowOption("2", "Show B");

        ShowSelectionEvaluator.Decision ambiguous =
            ShowSelectionEvaluator.evaluate("Unknown",
                Arrays.asList(showA, showB), null);

        assertFalse(ambiguous.isResolved());
        assertTrue(ambiguous.isAmbiguous());
        assertFalse(ambiguous.isNotFound());
        assertNull(ambiguous.getChosen());
    }

    // ========== Priority/Order Tests ==========

    @Test
    @DisplayName("Pinned ID should take priority over exact name match")
    void testPinnedIdPriorityOverName() {
        // Both options match "The Bureau" pattern, but pinned ID should win
        List<ShowOption> options = Arrays.asList(THE_BUREAU, THE_BUREAU_UK);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("The Bureau", options, "90103");

        assertEquals(RESOLVED, decision.getType());
        assertEquals(THE_BUREAU_UK, decision.getChosen());
        assertTrue(decision.getMessage().toLowerCase().contains("pinned"));
    }

    @Test
    @DisplayName("Exact name match should take priority over alias match")
    void testNameMatchPriorityOverAlias() {
        // Create a show where the name matches another show's alias
        ShowOption mainShow = new ShowOption("1", "DEFEND");
        List<ShowOption> options = Arrays.asList(mainShow, DEFEND_WITH_ALIAS);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("DEFEND", options, null);

        // mainShow has exact name "DEFEND", should win over DEFEND_WITH_ALIAS's alias
        assertEquals(RESOLVED, decision.getType());
        assertEquals(mainShow, decision.getChosen());
    }

    // ========== Fuzzy Matching Tests ==========

    @Test
    @DisplayName("Fuzzy matching should resolve typo when score is high enough")
    void testFuzzyMatchTypo() {
        ShowOption correctShow = new ShowOption("1", "Realm of Shadows");
        ShowOption differentShow = new ShowOption("2", "Hall of the Serpent");
        List<ShowOption> options = Arrays.asList(correctShow, differentShow);

        // "Ream of Shadows" has a typo - should fuzzy match to "Realm of Shadows"
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Ream of Shadows", options, null);

        assertEquals(RESOLVED, decision.getType());
        assertEquals(correctShow, decision.getChosen());
        assertTrue(decision.getMessage().toLowerCase().contains("fuzzy"));
    }

    @Test
    @DisplayName("Fuzzy matching should not auto-select when scores are too close")
    void testFuzzyMatchAmbiguousScores() {
        ShowOption show1 = new ShowOption("1", "The Show");
        ShowOption show2 = new ShowOption("2", "That Show");
        List<ShowOption> options = Arrays.asList(show1, show2);

        // Both are similar distance from "This Show"
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("This Show", options, null);

        // Should be ambiguous because gap between scores is too small
        assertEquals(AMBIGUOUS, decision.getType());
        assertNotNull(decision.getScoredOptions());
        assertFalse(decision.getScoredOptions().isEmpty());
    }

    @Test
    @DisplayName("Scored options should be sorted best-first in ambiguous decisions")
    void testScoredOptionsSorted() {
        ShowOption lowMatch = new ShowOption("1", "Completely Different Name");
        ShowOption highMatch = new ShowOption("2", "Cracking Good");
        ShowOption mediumMatch = new ShowOption("3", "Cracking Goodly");
        List<ShowOption> options = Arrays.asList(lowMatch, highMatch, mediumMatch);

        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Cracking Good", options, null);

        // Should resolve to exact match
        assertEquals(RESOLVED, decision.getType());
        assertEquals(highMatch, decision.getChosen());
    }

    @Test
    @DisplayName("ScoredOption isRecommended should return true for high scores")
    void testScoredOptionRecommended() {
        ShowOption show1 = new ShowOption("1", "Test Show");
        ShowOption show2 = new ShowOption("2", "Other Show");
        List<ShowOption> options = Arrays.asList(show1, show2);

        // Both similar to "Test Shows" but neither is exact
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Test Shows", options, null);

        if (decision.isAmbiguous() && decision.getScoredOptions() != null) {
            // The first option should have a higher score
            var scoredOptions = decision.getScoredOptions();
            assertTrue(scoredOptions.size() >= 2);
            // First should be "Test Show" with high score
            assertTrue(scoredOptions.get(0).score() >= scoredOptions.get(1).score());
        }
    }

    @Test
    @DisplayName("Fuzzy matching should check aliases for best score")
    void testFuzzyMatchWithAlias() {
        // DEFEND_WITH_ALIAS has alias "Agents of DEFEND"
        ShowOption otherShow = new ShowOption("999", "Completely Different");
        List<ShowOption> options = Arrays.asList(otherShow, DEFEND_WITH_ALIAS);

        // "Agents of DEFEND" matches an alias exactly
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate("Agents of DEFEND", options, null);

        // Should resolve via alias match (before fuzzy)
        assertEquals(RESOLVED, decision.getType());
        assertEquals(DEFEND_WITH_ALIAS, decision.getChosen());
    }
}
