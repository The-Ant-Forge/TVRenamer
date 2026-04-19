## Bug fixes

- **Fixed crash when removing an Overrides or Disambiguation row on the Preferences Matching tab.** Clicking Remove could throw an `SWTException: Widget is disposed` during the table repaint. The zebra-striping code now safely skips items that are in the process of being removed.

## Improvements

- **Unified table styling across all dialogs.** Grid lines have been removed from the Matching tables (Overrides, Disambiguations), the duplicate-cleanup dialog, and the batch show-disambiguation dialog so they match the main file table. Row separation is provided by zebra striping.
