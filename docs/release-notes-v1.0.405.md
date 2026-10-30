A bug-fix release: five fixes to show matching, episode listings and MP4 subtitle merging, plus a refreshed set of help pages.

## Bug fixes

### Merged MP4 subtitles are no longer removed by tagging

When AtomicParsley isn't installed, TVRenamer tags MP4 files with ffmpeg instead. That step rewrote the file keeping only one video and one audio track, so a subtitle merged in moments earlier was silently dropped, even though the merge reported success. ffmpeg now keeps every track. AtomicParsley users were never affected.

### Series without translated episode titles no longer fail to rename

With TheTVDB (v4) and a title language other than the series' own, a series with no episode titles in that language made every one of its files fail with "Downloading show listings failed". Missing titles are now filled in from the original-language listing, keeping any translations that do exist.

### Pinned series ids work even when the name search misses the series

A remembered disambiguation (a pinned provider id) was only used to choose among the search results. If the provider's name search didn't return that series, renaming failed even with the right id pinned. TVRenamer now fetches a pinned series directly by its id, so you can also add a Disambiguation row by hand for a show whose name search finds nothing.

### Adding a pin retries rows that were already "not found"

A pin added for a show already in the table kept showing "not found" until TVRenamer was restarted. Saving the pin now looks the show up again.

### The Hint link and overrides use the name the row shows

"click to set Hint" on an unfound row now fills in the name exactly as the row shows it, and overrides match loosely (case, dots, underscores and most punctuation are ignored). Before, an override typed from the row's own text never matched.

## Documentation

- Help pages now name the action button correctly (**Rename Checked** / **Rename & Move Checked**, which works on ticked rows) and drop a Ctrl+R shortcut that doesn't exist.
- The full list of rename tokens is documented, including the air-date tokens.
- Subtitle Merging explains how to pair a subtitle whose name doesn't match its video: add both files to TVRenamer.
- Metadata Tagging explains the difference between AtomicParsley and ffmpeg, and that tools are detected at startup.
