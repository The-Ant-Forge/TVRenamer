A maintenance release: a more usable Matching tab, fixes to when saved Matching changes actually take effect, and a refresh of SWT, Gradle and SpotBugs.

## Matching tab

- **Sortable tables.** Click either value column heading in **Overrides** or **Disambiguations** to sort by it, and click again to reverse. While an entry is being validated against the data provider, the headings are greyed and sorting is unavailable, because reordering rows mid-check could attach a result to the wrong row.
- **New entries appear at the top**, so you can see what you just added without scrolling a long list. Any active sort is cleared at the same time, so the sort arrow never contradicts where the row was placed.

## Episode ordering and Title language now apply to rows already loaded

Changing **Prefer DVD episode order** or the TheTVDB v4 **Title language** previously only affected files added *after* the change. Episode listings are cached per show, so rows already in the table kept the old ordering or language until you restarted.

Both settings now refresh the shows already in the table: their listings are fetched again and their rows re-matched. A save that changes both settings refreshes once rather than twice. Under TVMaze, which has a single ordering and English-only titles, the DVD checkbox is now disabled rather than appearing active but doing nothing.

## Bug fixes

- **"Add / Update" now updates an existing entry** instead of silently adding a duplicate row. The check for an existing entry was looking in the status-icon column rather than the column holding the name, so it never matched.
- **Removing a disambiguation now re-matches the affected rows.** Previously, removing a pinned show selection was the one Matching change that did nothing until the files were re-added.
- **Saving Preferences no longer re-matches when nothing changed.** Every save used to trigger a re-match pass even with no Matching edits.
- **Both Matching lists are applied before re-matching.** They were applied one at a time, so the first re-match pass ran against a half-updated state and was then corrected by a second pass.

## Under the hood

- **SWT 3.135.0**, **Gradle 9.8.0** and the **SpotBugs plugin 6.5.12**.
- A long-standing packaging workaround has been **retired**: the build no longer needs to inject `SWT-OS`/`SWT-Arch` manifest attributes into the fat jar, because SWT 3.135.0 fixes the upstream issue that required them. Verified by launching the packaged executable, not just by building it.

## Known issue

There is one open report that this release does not fix: the action button has been seen disabled (no hover highlight, no response) while rows were matched, ticked and ready. It has not been reproduced yet, and the leading theory is that a momentarily unreachable destination folder disables it. If you hit it, unticking the session **Move** checkbox should make the button usable for renaming in place.
