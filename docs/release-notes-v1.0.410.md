A right-click menu for fixing a row's show, three bug fixes, and dependency updates.

## New features and improvements

### Right-click a row to choose its show or edit its matching rule

Right-click a row in the main table for two new actions:

- **Choose Show...** searches the data provider again and opens the Select Shows dialog with every result, best match first. Use it to fix a row that matched the wrong show, which before meant typing an override or a series ID into Preferences by hand. Select several rows to choose for several shows in one dialog; rows with the same show name appear once. If the search finds nothing, TVRenamer says so and offers to open Preferences > Matching.
- **Edit Matching Rule...** opens Preferences > Matching with the row's names filled in: the override's **Extracted show** and the disambiguation's **Query string**, so you can see what TVRenamer extracted and searched for. **Replace with** starts as the current name, selected, so you can type over it or edit it.

Right-click the file name: the drop-down or link in the Proposed File Path cell takes its own right-click.

### Unfound shows link straight to Matching

On a row whose show wasn't found, the link now reads **add a rule in Preferences > Matching** and opens the Matching tab directly, with the fields above filled in. The search for that row already came back empty, so the old link's extra step is gone.

### The Select Shows dialog says what a choice affects

The dialog now explains that a choice applies to every file with that show name, and is remembered under Preferences > Matching.

## Bug fixes

### "Remove emptied directories" no longer deletes parent folders

After moving a file out, TVRenamer deleted the emptied folder and then kept going up, deleting each parent that became empty. Processing the last show in a download folder could delete the download folder itself. Now only the folder the file was moved out of is removed, and only if it is empty. With a nested layout such as `Show/Season 1/`, the emptied `Show` folder is now left in place.

### Choosing a different show for a matched row takes effect straight away

Picking a different show for a row that had already matched, whether in the Select Shows dialog or by changing a pin in Preferences, did nothing until TVRenamer was restarted. The row is now looked up again immediately.

### Show names with an inner capital are found again

Before searching, TVRenamer split words at inner capitals, so a filename like `Solar Drift StarBase S01E01` was searched as "solar drift star base", and the provider often found nothing. Splitting now only applies to run-together names with no spaces, dots, underscores or hyphens (`SolarDrift` still becomes "Solar Drift"). If you previously chose a show for a name affected by this, you may be asked to choose it again once.

## Maintenance

- Gradle 9.9.2, Gson 2.15.0, SpotBugs plugin 6.5.13.
