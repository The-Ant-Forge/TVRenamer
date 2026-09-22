package org.tvrenamer.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the key lookup used by the Preferences "Matching" tables when deciding
 * whether "Add / Update" should update an existing row or insert a new one.
 *
 * Rows are held as their cell values, where column 0 is the narrow status-icon
 * column (always empty) and column 1 holds the key. Searching column 0 by
 * mistake matches nothing, which turns every update into a duplicate row.
 */
public class MatchingTableKeysTest {

    private static List<String[]> rows(String... keys) {
        List<String[]> list = new ArrayList<>();
        for (String k : keys) {
            list.add(new String[] { "", k, "replacement" });
        }
        return list;
    }

    @Test
    @DisplayName("Finds the row holding the key")
    public void findsRowByKey() {
        List<String[]> data = rows("westmark", "solar drift", "the quiet ones");

        assertEquals(1, MatchingTableKeys.indexOfKey(data, "solar drift"));
    }

    @Test
    @DisplayName("Key matching ignores case")
    public void matchingIgnoresCase() {
        List<String[]> data = rows("westmark", "Solar Drift");

        assertEquals(1, MatchingTableKeys.indexOfKey(data, "solar DRIFT"));
    }

    @Test
    @DisplayName("Surrounding whitespace is ignored on both sides of the comparison")
    public void whitespaceIsIgnored() {
        List<String[]> data = new ArrayList<>();
        data.add(new String[] { "", "  westmark academy  ", "replacement" });

        assertEquals(0, MatchingTableKeys.indexOfKey(data, "westmark academy"));
    }

    @Test
    @DisplayName("A key that is not present returns -1")
    public void absentKeyReturnsNotFound() {
        List<String[]> data = rows("westmark", "solar drift");

        assertEquals(-1, MatchingTableKeys.indexOfKey(data, "the quiet ones"));
    }

    @Test
    @DisplayName("The status-icon column is never searched")
    public void iconColumnIsNotSearched() {
        // A row whose icon cell happens to carry the text must not match: the key
        // lives in column 1. This is the bug that made every update a duplicate.
        List<String[]> data = new ArrayList<>();
        data.add(new String[] { "westmark", "solar drift", "replacement" });

        assertEquals(-1, MatchingTableKeys.indexOfKey(data, "westmark"));
    }

    @Test
    @DisplayName("Null and short rows are skipped rather than throwing")
    public void malformedRowsAreSkipped() {
        List<String[]> data = new ArrayList<>();
        data.add(null);
        data.add(new String[] { "" });
        data.add(new String[] { "", null, "replacement" });
        data.add(new String[] { "", "westmark", "replacement" });

        assertEquals(3, MatchingTableKeys.indexOfKey(data, "westmark"));
    }
}
