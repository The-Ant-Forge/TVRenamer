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
