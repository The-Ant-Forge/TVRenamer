package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dedicated unit tests for the ShowName model class.
 * Covers factory methods, query string derivation, show option management,
 * and selection heuristics.
 *
 * Note: ShowName uses a static SHOW_NAMES cache. Each test uses unique
 * name strings to avoid cross-test interference.
 */
public class ShowNameTest {

    // Generate unique names to avoid cache collisions across tests
    private static int nameCounter = 0;

    private static synchronized String uniqueName(String base) {
        return base + " T" + (nameCounter++);
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("mapShowName returns a ShowName with correct foundName")
        void mapShowNameBasic() {
            String name = uniqueName("Frostbite");
            ShowName sn = ShowName.mapShowName(name);
            assertNotNull(sn);
            assertEquals(name, sn.getExampleFilename());
        }

        @Test
        @DisplayName("mapShowName returns same instance for same string")
        void mapShowNameCached() {
            String name = uniqueName("Frostbite Cached");
            ShowName first = ShowName.mapShowName(name);
            ShowName second = ShowName.mapShowName(name);
            assertSame(first, second);
        }

        @Test
        @DisplayName("lookupShowName creates if missing (with warning)")
        void lookupShowNameCreatesIfMissing() {
            String name = uniqueName("Lookup Miss");
            // lookupShowName logs a warning but still creates the entry
            ShowName sn = ShowName.lookupShowName(name);
            assertNotNull(sn);
            assertEquals(name, sn.getExampleFilename());
        }

        @Test
        @DisplayName("lookupShowName returns existing instance")
        void lookupShowNameExisting() {
            String name = uniqueName("Lookup Hit");
            ShowName created = ShowName.mapShowName(name);
            ShowName found = ShowName.lookupShowName(name);
            assertSame(created, found);
        }
    }

    @Nested
    @DisplayName("Query string derivation")
    class QueryString {

        @Test
        @DisplayName("query string normalises case and punctuation")
        void queryStringNormalized() {
            String name = uniqueName("The.Night.Watch");
            ShowName sn = ShowName.mapShowName(name);
            String query = sn.getQueryString();
            // Dots should be replaced with spaces, lowered
            assertFalse(query.contains("."), "Query should not contain dots");
            assertEquals(query, query.toLowerCase(), "Query should be lowercase");
        }

        @Test
        @DisplayName("different punctuation produces same query string")
        void differentPunctuationSameQuery() {
            // Use base names that differ only in punctuation
            String dotted = uniqueName("solar.drift");
            String spaced = dotted.replace(".", " ");
            // These will create separate ShowName objects...
            ShowName sn1 = ShowName.mapShowName(dotted);
            ShowName sn2 = ShowName.mapShowName(spaced);
            // ...but should share the same query string
            assertEquals(sn1.getQueryString(), sn2.getQueryString());
        }
    }

    @Nested
    @DisplayName("Show options")
    class ShowOptions {

        @Test
        @DisplayName("initially has no show options")
        void noOptionsInitially() {
            String name = uniqueName("Empty Options");
            ShowName sn = ShowName.mapShowName(name);
            assertFalse(sn.hasShowOptions());
            assertTrue(sn.getShowOptions().isEmpty());
        }

        @Test
        @DisplayName("addShowOption adds to the list")
        void addShowOption() {
            String name = uniqueName("With Options");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("1001", "Ridgeline");
            assertTrue(sn.hasShowOptions());
            assertEquals(1, sn.getShowOptions().size());
            assertEquals("Ridgeline", sn.getShowOptions().get(0).getName());
        }

        @Test
        @DisplayName("addShowOption with metadata preserves year and aliases")
        void addShowOptionWithMetadata() {
            String name = uniqueName("Metadata Options");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("1002", "Ridgeline", 2020, List.of("Ridge", "RL"));
            ShowOption opt = sn.getShowOptions().get(0);
            assertEquals(Integer.valueOf(2020), opt.getFirstAiredYear());
            assertEquals(List.of("Ridge", "RL"), opt.getAliasNames());
        }

        @Test
        @DisplayName("clearShowOptions removes all options")
        void clearShowOptions() {
            String name = uniqueName("Clear Options");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("1003", "Skyward");
            sn.addShowOption("1004", "Skyward Reboot");
            assertTrue(sn.hasShowOptions());
            sn.clearShowOptions();
            assertFalse(sn.hasShowOptions());
        }

        @Test
        @DisplayName("getShowOptions returns a defensive copy")
        void getShowOptionsDefensiveCopy() {
            String name = uniqueName("Defensive Copy");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("1005", "Nightwatcher");
            List<ShowOption> options = sn.getShowOptions();
            // Returned list is an unmodifiable copy
            assertThrows(UnsupportedOperationException.class,
                () -> options.add(new ShowOption("1006", "Fake")));
        }
    }

    @Nested
    @DisplayName("Selection heuristics")
    class SelectionHeuristics {

        @Test
        @DisplayName("selectShowOption picks exact case-insensitive match")
        void selectExactMatch() {
            String name = uniqueName("Vortex");
            ShowName sn = ShowName.mapShowName(name);
            // Add a non-matching option first
            sn.addShowOption("2001", "Something Else");
            // Add the exact match (case may differ due to unique suffix)
            sn.addShowOption("2002", name);
            ShowOption selected = sn.selectShowOption();
            assertEquals(name, selected.getName());
        }

        @Test
        @DisplayName("selectShowOption falls back to first option when no exact match")
        void selectFallbackToFirst() {
            String name = uniqueName("Obscure Title");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("3001", "First Option");
            sn.addShowOption("3002", "Second Option");
            ShowOption selected = sn.selectShowOption();
            assertEquals("First Option", selected.getName());
        }

        @Test
        @DisplayName("selectShowOption returns FailedShow when no options")
        void selectNoOptions() {
            String name = uniqueName("No Matches");
            ShowName sn = ShowName.mapShowName(name);
            ShowOption selected = sn.selectShowOption();
            assertTrue(selected.isFailedShow());
        }

        @Test
        @DisplayName("selectShowOption sets matched show on query string")
        void selectSetsMatchedShow() {
            String name = uniqueName("Match Track");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("4001", "Some Show");
            assertNull(sn.getMatchedShow(), "No match before selection");
            sn.selectShowOption();
            assertNotNull(sn.getMatchedShow(), "Match should be set after selection");
        }
    }

    @Nested
    @DisplayName("Failed show creation")
    class FailedShowCreation {

        @Test
        @DisplayName("getFailedShow returns a FailedShow and caches it")
        void getFailedShow() {
            String name = uniqueName("Missing Show");
            ShowName sn = ShowName.mapShowName(name);
            FailedShow failed = sn.getFailedShow(null);
            assertNotNull(failed);
            assertTrue(failed.isFailedShow());
            assertEquals(name, failed.getName());
            // Should be cached as matched show
            assertSame(failed, sn.getMatchedShow());
        }

        @Test
        @DisplayName("getNonCachingFailedShow does not cache")
        void getNonCachingFailedShow() {
            String name = uniqueName("Deferred Show");
            ShowName sn = ShowName.mapShowName(name);
            FailedShow failed = sn.getNonCachingFailedShow(null);
            assertNotNull(failed);
            assertTrue(failed.isFailedShow());
            // Should NOT be cached
            assertNull(sn.getMatchedShow(), "Non-caching failed show should not set matched show");
        }
    }

    @Nested
    @DisplayName("Provider-switch failure invalidation")
    class ProviderSwitchInvalidation {

        @Test
        @DisplayName("forgetFailedLookup clears a cached FailedShow and re-enables querying")
        void forgetClearsCachedFailure() {
            String name = uniqueName("Silent NoOp");
            ShowName sn = ShowName.mapShowName(name);
            // Simulate the previous provider failing to find the show: this is
            // exactly what caches a FailedShow in the query string and makes the
            // subsequent mapStringToShow short-circuit without re-querying.
            sn.getFailedShow(null);
            assertTrue(sn.getMatchedShow().isFailedShow(),
                "precondition: a FailedShow is cached (would short-circuit re-match)");

            boolean cleared = sn.forgetFailedLookup();

            assertTrue(cleared, "a cached failure should be reported as cleared");
            assertNull(sn.getMatchedShow(),
                "cached failure must be gone so mapStringToShow re-queries the new provider");
            assertTrue(sn.needsQuery(),
                "with no cached match and no listeners, a fresh query is needed");
        }

        @Test
        @DisplayName("forgetFailedLookup leaves a successful match untouched")
        void forgetKeepsSuccessfulMatch() {
            String name = uniqueName("Real Match");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("5001", "Real Match Series");
            sn.selectShowOption(); // caches a real (non-failed) match
            ShowOption matched = sn.getMatchedShow();
            assertNotNull(matched);
            assertFalse(matched.isFailedShow(), "precondition: a real match is cached");

            boolean cleared = sn.forgetFailedLookup();

            assertFalse(cleared, "a real match must not be reported as cleared");
            assertSame(matched, sn.getMatchedShow(),
                "successful match must survive forgetFailedLookup");
        }

        @Test
        @DisplayName("forgetFailedLookup is a no-op when nothing is cached")
        void forgetNoOpWhenNothingCached() {
            String name = uniqueName("Never Queried");
            ShowName sn = ShowName.mapShowName(name);
            assertNull(sn.getMatchedShow());
            assertFalse(sn.forgetFailedLookup());
            assertNull(sn.getMatchedShow());
        }

        @Test
        @DisplayName("ShowStore.forgetFailedLookup makes a failed name re-queryable")
        void showStoreForgetReenablesQuery() {
            String name = uniqueName("Store Level");
            ShowName sn = ShowName.mapShowName(name);
            // Cache a failure exactly as a failed provider lookup would.
            sn.getFailedShow(null);
            assertNotNull(sn.getMatchedShow());

            // Provider-switch path invalidates the cached failure by filename show.
            ShowStore.forgetFailedLookup(name);

            // Null cached match + needsQuery() means mapStringToShow takes the
            // download branch (queries the current provider) rather than replaying
            // the cached failure via listener.downloadFailed.
            assertNull(sn.getMatchedShow(),
                "ShowStore.forgetFailedLookup must clear the cached failure");
            assertTrue(sn.needsQuery(),
                "cleared failure must leave the name in a re-queryable state");
        }

        @Test
        @DisplayName("ShowStore.forgetFailedLookup tolerates null and unknown names")
        void showStoreForgetHandlesNullAndUnknown() {
            assertDoesNotThrow(() -> ShowStore.forgetFailedLookup(null));
            assertDoesNotThrow(() -> ShowStore.forgetFailedLookup(uniqueName("Unknown")));
        }
    }

    @Nested
    @DisplayName("Re-choosing a matched show")
    class RechooseInvalidation {

        @Test
        @DisplayName("a cached match that is not the pinned series is forgotten")
        void forgetsMatchThatDiffersFromPin() {
            String name = uniqueName("Westmark Academy");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("7001", "Westmark Academy");
            sn.selectShowOption(); // caches series 7001
            assertEquals("7001", sn.getMatchedShow().getIdString());

            // The user now picks series 7002 for this name.
            assertTrue(sn.forgetMatchIfNotPinned("7002"),
                "a stale match must be reported as cleared");
            assertNull(sn.getMatchedShow(),
                "stale match must be gone so the re-match asks the provider");
            assertTrue(sn.needsQuery());
        }

        @Test
        @DisplayName("a cached match that is the pinned series is kept")
        void keepsMatchThatIsPinned() {
            String name = uniqueName("Westmark Academy Kept");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("7101", "Westmark Academy");
            sn.selectShowOption();
            ShowOption matched = sn.getMatchedShow();

            assertFalse(sn.forgetMatchIfNotPinned("7101"));
            assertSame(matched, sn.getMatchedShow());
        }

        @Test
        @DisplayName("no pin, or nothing cached, leaves the cache alone")
        void noPinOrNoMatchIsNoOp() {
            String name = uniqueName("Solar Drift Unpinned");
            ShowName sn = ShowName.mapShowName(name);
            assertFalse(sn.forgetMatchIfNotPinned("7201"), "nothing cached");

            sn.addShowOption("7202", "Solar Drift");
            sn.selectShowOption();
            ShowOption matched = sn.getMatchedShow();
            assertFalse(sn.forgetMatchIfNotPinned(null));
            assertFalse(sn.forgetMatchIfNotPinned(" "));
            assertSame(matched, sn.getMatchedShow());
        }

        @Test
        @DisplayName("a cached failure is forgotten once a show is pinned")
        void forgetsFailureWhenPinned() {
            String name = uniqueName("Solar Drift Failed");
            ShowName sn = ShowName.mapShowName(name);
            sn.getFailedShow(null);

            assertTrue(sn.forgetMatchIfNotPinned("7301"));
            assertNull(sn.getMatchedShow());
        }

        @Test
        @DisplayName("ShowStore.forgetLookupIfNotPinned clears a stale match by name")
        void showStoreForgetsStaleMatch() {
            String name = uniqueName("Westmark Store");
            ShowName sn = ShowName.mapShowName(name);
            sn.addShowOption("7401", "Westmark Academy");
            sn.selectShowOption();

            ShowStore.forgetLookupIfNotPinned(name, "7402");

            assertNull(sn.getMatchedShow());
            assertDoesNotThrow(() -> ShowStore.forgetLookupIfNotPinned(null, "1"));
        }

        @Test
        @DisplayName("searchOnly ShowName is not the cached instance")
        void searchOnlyIsDetached() {
            String name = uniqueName("Detached Search");
            ShowName cached = ShowName.mapShowName(name);
            ShowName search = ShowName.searchOnly(name);
            assertNotSame(cached, search);
            assertEquals(cached.getQueryString(), search.getQueryString());

            search.addShowOption("7501", "Detached Search");
            assertFalse(cached.hasShowOptions(),
                "options found by a one-off search must not leak into the cached name");
        }
    }

    @Nested
    @DisplayName("Listener integration")
    class ListenerIntegration {

        @Test
        @DisplayName("needsQuery returns true initially (no listeners)")
        void needsQueryInitially() {
            String name = uniqueName("Query Check");
            ShowName sn = ShowName.mapShowName(name);
            assertTrue(sn.needsQuery());
        }

        @Test
        @DisplayName("toString includes the foundName")
        void toStringIncludesName() {
            String name = uniqueName("ToString Test");
            ShowName sn = ShowName.mapShowName(name);
            assertTrue(sn.toString().contains(name));
        }
    }
}
