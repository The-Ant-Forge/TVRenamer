package org.tvrenamer.view;

import java.util.List;
import org.tvrenamer.controller.util.StringUtils;

/**
 * Key lookup for the Preferences "Matching" tables (Overrides and
 * Disambiguations).
 *
 * Rows are represented as their cell values, where column 0 is the narrow
 * status-icon column and column 1 holds the key (the extracted show name, or
 * the provider query string). Kept free of SWT types so the lookup can be
 * unit-tested without a Display.
 */
final class MatchingTableKeys {

    /** Column holding the row's key; column 0 is the status icon. */
    static final int KEY_COLUMN = 1;

    private MatchingTableKeys() {
        // static helper only
    }

    /**
     * Find the row whose key equals the given one once both are normalized to
     * a query string (case, separators and most punctuation ignored). This is
     * the same comparison runtime override matching uses, so "Solar_Drift" and
     * "solar drift" are one entry, not two that silently compete.
     *
     * @param rows row cell values, in table order
     * @param key  the key to look for
     * @return the index of the matching row, or -1 if there is none
     */
    static int indexOfKey(final List<String[]> rows, final String key) {
        if (rows == null || key == null) {
            return -1;
        }
        final String wanted = StringUtils.makeQueryString(key);
        for (int i = 0; i < rows.size(); i++) {
            final String[] row = rows.get(i);
            // Rows can be shorter than expected, and cells can be null; neither
            // should break the lookup.
            if (row == null || row.length <= KEY_COLUMN) {
                continue;
            }
            final String cell = row[KEY_COLUMN];
            if (cell != null && StringUtils.makeQueryString(cell).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }
}
