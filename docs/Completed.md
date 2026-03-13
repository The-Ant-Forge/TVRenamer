# Completed (TVRenamer)

This document is a durable record of completed work: what shipped, why it mattered, and where it lives in the codebase.

It exists to keep `docs/TODO.md` focused on *future work* while preserving the engineering context and implementation details of finished items.

## How to use this file

When you complete an item that was tracked in `docs/TODO.md`:

1. Remove or mark it as completed in `docs/TODO.md` (keep the TODO list clean).
2. Add a new entry here under **Completed Items**, using the template below.
3. If the work changes assumptions (threading, UI thread ownership, encoding rules, persistence keys, etc.), capture that in the entry.

### Entry template

- **Title:** concise name
- **Why:** user impact / risk / motivation
- **Where:** key classes/files (paths helpful)
- **What we did:** bullet list of notable changes / behavior
- **Notes:** (optional) constraints, gotchas, follow-ups, links to specs/release notes

---

## Completed Items

### 1) Make file modification-time behavior configurable
- **Why:** Renaming/moving a file doesn’t change its contents; default behavior should preserve timestamps. Users may prefer setting mtime to “now”.
- **Where:** `org.tvrenamer.controller.FileMover` (`finishMove(...)`) + Preferences UI/prefs model
- **What we did:**
  - Added a preference to preserve original mtime by default.
  - Added an option to set mtime to “now”.

### 2) Fix Preferences dialog token drop insertion position
- **Why:** Drag/drop should insert at caret (or replace selection), not always append.
- **Where:** `org.tvrenamer.view.PreferencesDialog` — `PreferencesDropTargetListener.drop(...)`
- **What we did:**
  - Insert dropped token at current selection/caret and move caret to end of inserted token.

### 3) Thread the preload folder scan
- **Why:** Folder scanning can block UI responsiveness during startup.
- **Where:** `org.tvrenamer.model.EpisodeDb` — `preload()`
- **What we did:**
  - Run preload scanning on a background thread.
- **Notes:**
  - `publish(...)` notifies listeners from that background thread; UI code must marshal to SWT UI thread.

### 4) Harden XPath usage for potential concurrency
- **Why:** Shared `XPath` instances are not guaranteed to be thread-safe.
- **Where:** `org.tvrenamer.controller.util.XPathUtilities`
- **What we did:**
  - Replaced shared static `XPath` with `ThreadLocal<XPath>`.

### 5) Generalize “map to list” helper in MoveRunner
- **Why:** Reduce boilerplate and prefer standard library constructs.
- **Where:** `org.tvrenamer.controller.MoveRunner`
- **What we did:**
  - Replaced custom “get list or create” logic with `Map.computeIfAbsent(...)`.

### 6) Stabilize Windows permission-related tests
- **Why:** Windows “read-only” simulation is unreliable without ACL tooling; tests should not flake.
- **Where:** `org.tvrenamer.controller.TestUtils.setReadOnly(Path)` and move-related tests
- **What we did:**
  - Adopted a pragmatic “verify + skip” strategy when read-only cannot be reliably enforced.
  - Updated move tests to match default mtime preservation.

### 7) Make string handling more explicit (URL vs XML vs display vs filename)
- **Why:** Mixing responsibilities can corrupt provider XML and break URLs/filenames in subtle ways.
- **Where:** `org.tvrenamer.controller.util.StringUtils` and `org.tvrenamer.controller.TheTVDBProvider`
- **What we did:**
  - Use robust URL encode/decode for query parameters.
  - Stop mutating downloaded XML payloads.
  - Treat “special character encoding” as conservative display normalization only.

### 8) Improve move/copy throughput and progress reporting
- **Why:** Copy+delete can be slow; overall progress should be smooth and accurate for multi-file batches.
- **Where:** `org.tvrenamer.controller.util.FileUtilities.copyWithUpdates(...)`, `org.tvrenamer.view.FileMonitor`, `org.tvrenamer.view.ResultsTable`, `org.tvrenamer.view.ProgressBarUpdater`
- **What we did:**
  - Increased copy buffer to 4 MiB and throttled UI progress callbacks to ~4 MiB.
  - Implemented byte-accurate aggregate progress for copy+delete moves only, so the bottom bar advances smoothly across the entire batch and resets after completion.

### 9) Batch “Select Shows” dialog improvements (checkbox selection + streaming ambiguities)
- **Why:** Avoid repeated modal popups; allow partial resolution; make selection UX reliable; reduce blocking.
- **Where:** `org.tvrenamer.view.BatchShowDisambiguationDialog`, `org.tvrenamer.view.ResultsTable`, `org.tvrenamer.model.ShowStore`, `agents.md`
- **What we did:**
  - Reworked candidate selection to use checkboxes (single-choice or none) with row-click toggling.
  - Enabled OK when at least one show is selected (partial resolution).
  - Streamed newly discovered ambiguous shows into an already-open dialog and added a “Downloading …” title indicator.
  - Kept unresolved pending items queued so the dialog can be reopened via the button.
  - Adjusted auto-open behavior to trigger only on an empty→non-empty pending transition (otherwise rely on streaming / explicit button).

### 10) Unified Matching tab (Overrides + Disambiguations) with online validation
- **Why:** Users need one place to view/edit/delete/validate all show-matching rules; reduces confusion and makes troubleshooting easier.
- **Where:** `org.tvrenamer.view.PreferencesDialog` (Matching tab), `org.tvrenamer.model.UserPreferences`, `org.tvrenamer.model.ShowStore`, `docs/Unifying Matches Spec.md`
- **What we did:**
  - Renamed the Preferences “Overrides” tab to “Matching”.
  - Added two editors (Overrides and Disambiguations) using tables.
  - Implemented threaded online validation (TVDB) for new/changed entries with Save gating.
  - Added status icons consistent with the main results table and per-table validation message display.
  - Added Clear All confirmations and adjusted column ordering for cleaner alignment.

### 11) Unified show selection evaluator (runtime + Matching validation)
- **Why:** Avoid drift between runtime selection logic and Preferences validation; reduce duplicated logic; make selection deterministic and explainable.
- **Where:** `org.tvrenamer.model.ShowSelectionEvaluator`, `org.tvrenamer.model.ShowStore`, `org.tvrenamer.view.PreferencesDialog`, `docs/Unified Evaluator Spec.md`
- **What we did:**
  - Introduced a pure evaluator that returns `RESOLVED / AMBIGUOUS / NOT_FOUND` with an explainable reason.
  - Wired runtime selection and Matching-tab override validation to use the evaluator.
  - Implemented deterministic tie-breakers (conservative, spec-driven), including:
    - Prefer base title over parenthetical variants when the base exists.
    - Prefer strict canonical token match over candidates with extra tokens.
    - Prefer `FirstAiredYear ± 1` when extracted includes a year token.
- **Notes:**
  - Further tie-breaker expansion is intentionally deferred; track in `docs/TODO.md`.

### 12) Matching tab: fixed Save gating + persistence
- **Why:** Users must be able to save validated rows; Save should not be blocked incorrectly; edits must persist.
- **Where:** `org.tvrenamer.view.PreferencesDialog`
- **What we did:**
  - Fixed Save gating logic where blank status text (icon-only UI) incorrectly blocked Save even when rows were OK.
  - Fixed persistence logic to save the correct key/value columns (status icon column is not data).

### 13) Overrides now apply to provider lookup consistently (without losing extracted name)
- **Why:** Overrides should affect runtime provider queries the same way they validate in Preferences; preserve visibility of what was parsed from filenames.
- **Where:** `org.tvrenamer.model.FileEpisode`, `org.tvrenamer.controller.FilenameParser`, `org.tvrenamer.view.ResultsTable`
- **What we did:**
  - Split “extracted show name” from “effective lookup show name”.
  - Use overrides for the effective provider lookup name.
  - Preserve extracted show name for UI correlation and disambiguation flows.

### 14) Repo hygiene: removed legacy IDE/build artifacts from `etc/`
- **Why:** Reduce maintenance burden and confusion; avoid keeping obsolete Ant/Ivy-era scripts/configs in a Gradle-based repo.
- **Where:** `etc/`
- **What we did:**
  - Removed legacy IntelliJ template files under `etc/idea`.
  - Removed legacy Ant/Ivy-era run scripts and unused config/profile XMLs under `etc/`.
  - Removed unused constant referencing deleted default overrides file.
- **Notes:**
  - A periodic “legacy reference scan” is tracked in `docs/TODO.md` as a hygiene step.



### 15) CI artifact naming: publish stable + versioned fat JARs
- **Why:** Users and scripts benefit from a stable filename (`tvrenamer.jar`), while Releases and side-by-side installs benefit from a versioned filename. CI artifacts should include both to reduce confusion and simplify testing.
- **Where:** `build.gradle` (Shadow outputs), `.github/workflows/windows-build.yml` (artifact upload paths)
- **What we did:**
  - Produce a stable fat jar named `tvrenamer.jar`.
  - Produce an additional versioned fat jar alongside it (e.g., `tvrenamer-<commitCount>.jar`).
  - Upload both jars in CI artifacts so users can choose either.
- **Notes:**
  - Releases should prefer attaching the versioned jar to make the downloaded filename self-describing.

### 16) Hygiene: scanned for legacy Ant/Ivy/out/lib references
- **Why:** After removing legacy scripts/configs, ensure no stale references remain in docs or code.
- **Where:** Repo-wide scan (docs, scripts, configs, source).
- **What we did:**
  - Scanned for `ant`, `ivy`, `build.xml`, `out/`, `lib/` references.
  - Updated stale comment in `logging.properties` that referenced `build.xml` to reference Gradle instead.
  - Confirmed no legacy directories or build files remain.

### 17) Support multi-episode filename parsing (single file contains multiple episodes)
- **Why:** Many libraries include “double episodes” or “range episodes” in a single file. Without explicit support, parsing/renaming is confusing and users may worry the app will rename/move incorrectly.
- **Where:** `org.tvrenamer.controller.FilenameParser`, `org.tvrenamer.model.FileEpisode`, `org.tvrenamer.controller.FilenameParserTest`
- **What we did:**
  - Added parsing support for case-insensitive multi-episode patterns in a single filename:
    - `S01E04E05` / `S01E04E05E06` (explicit list)
    - `S02E04-E06` and `S02E04-06` (inclusive range)
  - When detected, TVRenamer selects the lowest episode (A) for lookup/matching and stores the `(A..B)` span on the `FileEpisode`.
  - Appends a compact suffix `"(A-B)"` (no leading zeros) to the episode title token used for rename output, e.g. `Silver Linings (1-7)`.

### 18) Add SpotBugs static analysis (local)
- **Why:** Catch likely bugs and suspicious patterns early, without turning style discussions into review churn.
- **Where:** `build.gradle`, `agents.md`
- **What we did:**
  - Added SpotBugs integration and documented how to run it locally (`spotbugsMain`) and where the HTML report is generated.
  - Made the initial configuration conservative and non-blocking while we evaluate signal vs noise.

### 19) Release asset hygiene: avoid stale jars in GitHub Releases
- **Why:** Local builds can accumulate older versioned jars under `build/libs/` (especially when `clean` fails due to Windows file locks). Uploading `build/libs/*.jar` can unintentionally attach stale jars to a release.
- **Where:** Release procedure in `agents.md`
- **What we did:**
  - Documented a safer release upload approach: prefer CI artifacts and/or ensure `build/libs/` is clean, and upload explicit jar filenames to avoid stale versioned jars.

### 20) Code consolidation and modernization (Refactor and Consolidate)
- **Why:** Improve maintainability, reduce code duplication, and modernize patterns for easier future development.
- **Where:** Multiple files across `controller`, `model`, `view`, and `controller/util` packages
- **What we did:**
  - Removed deprecated `loggingOff()`/`loggingOn()` methods from `FileUtilities` (unused legacy code)
  - Consolidated duplicate `safePath(Path)` method: made `FileUtilities.safePath()` public and removed duplicate from `FileMover`
  - Moved magic constants (`NO_FILE_SIZE`, `MAX_TITLE_LENGTH`) from `FileEpisode` to `Constants.java`
  - Removed unused `FileStatus` enum and `fileStatus` field from `FileEpisode` (was set but never read; `currentPathMatchesTemplate` retained as it is used)
  - Improved `UpdateChecker` thread safety: replaced mutable static fields with `AtomicReference<VersionCheckResult>` using a record for immutable result caching; narrowed `catch (Exception)` to `catch (RuntimeException)`
  - Removed dead code from `BatchShowDisambiguationDialog`: unused `logger` field, unused `closedViaWindowX` field (set but never read), unused `allResolved()` method
- **Notes:**
  - `UpdateChecker` now uses lock-free compare-and-set pattern for thread-safe lazy initialization
  - `MoveRunner.existingConflicts()` intentionally retained as infrastructure for future conflict detection (TODO item #2)

### 21) File move enhancements: progress feedback, overwrite option, duplicate cleanup, fuzzy matching
- **Why:** Improve user experience and file management during move/rename operations.
- **Where:** `org.tvrenamer.view.ResultsTable`, `org.tvrenamer.view.ItemState`, `org.tvrenamer.model.UserPreferences`, `org.tvrenamer.view.PreferencesDialog`, `org.tvrenamer.controller.FileMover`, `org.tvrenamer.controller.MoveRunner`, `org.tvrenamer.controller.util.FileUtilities`, `org.tvrenamer.controller.FilenameParser`
- **What we did:**
  1. **Progress tick after completion:** Added `COMPLETED` ItemState with checkmark icon; successful moves now briefly show the checkmark before row is cleared (500ms delay).
  2. **Always overwrite preference:** Added preference to overwrite existing destination files instead of creating versioned suffixes (1), (2). Implemented in FileMover (same-disk rename uses `REPLACE_EXISTING`, cross-disk copy deletes first) and MoveRunner (skips conflict resolution when enabled). **Bug fix:** The early existence check in `tryToMoveFile()` was failing immediately when the destination existed, without checking the overwrite preference. Fixed to allow the move to proceed when overwrite is enabled.
  3. **Duplicate cleanup:** Added preference to delete duplicate video files after successful move. Uses both base-name matching (same name, different extension) and fuzzy episode matching (same season/episode identity). Only video files are deleted (not subtitles like .srt, .sub, .idx). Helper `FileUtilities.deleteDuplicateVideoFiles()` with 15 video extension types.
  4. **Fuzzy episode matching:** Added `FilenameParser.extractSeasonEpisode()` for lightweight season/episode extraction; integrated into both MoveRunner conflict detection and duplicate cleanup to catch files like "S01E02" vs "1x02" that represent the same episode but have different filenames.
- **Notes:**
  - Overwrite and duplicate cleanup preferences default to false (safe behavior).
  - Fuzzy matching supplements base-name matching in both conflict detection and duplicate cleanup.
  - Duplicate cleanup deletes files (relies on OS recycle bin) rather than moving to a subfolder to avoid orphaned folders and media library confusion.

### 22) Tick display fix and duplicate cleanup dialog with user confirmation
- **Why:** Progress tick (checkmark) wasn't visible after copy+delete moves; duplicate cleanup should require user confirmation before deleting files.
- **Where:** `org.tvrenamer.view.FileMonitor`, `org.tvrenamer.view.ResultsTable`, `org.tvrenamer.view.DuplicateCleanupDialog`, `org.tvrenamer.controller.MoveRunner`
- **What we did:**
  1. **Tick display fix:** The progress label (shown during copy operations) used a TableEditor overlay. When the label was disposed, the TableEditor was not disposed, preventing the underlying checkmark icon from showing. Fixed by tracking and disposing both the Label and TableEditor via a new `ProgressLabelResult` record.
  2. **Duplicate cleanup dialog:** Instead of auto-deleting duplicates, now shows a modal dialog after all moves complete:
     - Lists duplicate files in a table with checkboxes (checked = will be deleted)
     - Shows filename and folder columns
     - "Select All" / "Select None" buttons for convenience
     - "Delete Selected" commits deletions; "Cancel" keeps all files
  3. **Aggregate duplicates in MoveRunner:** Added `movers` list to track FileMover instances, `aggregateDuplicates()` method called when all moves complete, and `getFoundDuplicates()` getter for the UI.
  4. **Tick icon consistency:** Removed explicit COMPLETED icon setting after move. The existing SUCCESS icon (ready to rename) now remains visible, ensuring consistent visual feedback.
  5. **Move order:** Fixed moves to execute in table display order (top to bottom). Added `ensureTableSorted()` which applies the current sort column before collecting moves, ensuring items are in the visual order the user sees. MoveRunner now submits moves in original list order instead of arbitrary HashMap iteration order.
- **Notes:**
  - Duplicate cleanup preference must be enabled for duplicates to be detected and shown.
  - Files are only deleted after explicit user confirmation via the dialog.

### 23) JUnit 5 (Jupiter) migration
- **Why:** Modernize test framework to current JUnit 5 (Jupiter) from legacy JUnit 4; benefit from improved annotations, extension model, and better IDE/tooling support.
- **Where:** `build.gradle`, `gradle/libs.versions.toml`, all test files under `src/test/java/org/tvrenamer/`
- **What we did:**
  - Updated `libs.versions.toml`: replaced JUnit 4.13.2 with JUnit 5.11.4 (`junit-jupiter`)
  - Updated `build.gradle`: changed `testImplementation` to use JUnit 5, added `useJUnitPlatform()` to both `test` and `integrationTest` tasks, added `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`
  - Migrated 8 test files with annotation and import changes:
    - `@BeforeClass` → `@BeforeAll`, `@AfterClass` → `@AfterAll`
    - `@Before` → `@BeforeEach`, `@After` → `@AfterEach`
    - `@Ignore` → `@Disabled`
    - `org.junit.*` → `org.junit.jupiter.api.*`
  - Converted `@Rule TemporaryFolder` to `@TempDir Path` annotation (JUnit Jupiter idiom)
  - Reordered assertion parameters (message argument moved from first to last position)
  - Regenerated Gradle dependency lock files with `--write-locks`
- **Notes:**
  - The original TODO.md mentioned "JUnit 6" but that was a mislabel; the described changes (BeforeAll, jupiter imports, etc.) are JUnit 5 features.
  - All tests pass after migration.

### 24) Unit tests for ShowSelectionEvaluator
- **Why:** Prevent regressions in critical show-matching behavior; improve code confidence for future heuristic changes.
- **Where:** `src/test/java/org/tvrenamer/model/ShowSelectionEvaluatorTest.java`
- **What we did:**
  - Created comprehensive test suite with 30+ test cases covering:
    - NOT_FOUND scenarios (null/empty candidates)
    - Pinned ID resolution
    - Exact name matching (case-insensitive)
    - Punctuation-normalized matching
    - Alias matching
    - Parenthetical variant tie-breaker ("Show" vs "Show (IN)")
    - Token set and year tolerance (±1) tie-breakers
    - Single candidate auto-resolution
    - Ambiguous multi-candidate scenarios
    - Edge cases (nulls, blanks, null names in options)
    - Priority ordering (pinned ID > exact name > alias)

### 25) Narrow overly broad `catch (Exception)` blocks
- **Why:** Improve code robustness by catching specific exception types; make error handling more explicit and maintainable.
- **Where:** Multiple files across `controller`, `model`, and `view` packages
- **What we did:**
  - Narrowed `Exception` to `NumberFormatException` in:
    - `UpdateChecker.java:188` (version parsing)
    - `FileEpisode.java:356,361` (season/episode number parsing)
    - `ShowOption.java:91` (show ID parsing)
    - `ShowSelectionEvaluator.java:400` (year parsing)
  - Narrowed `Exception` to `SWTException` in:
    - `ThemeManager.java:166,171` (TabFolder color setters)
  - Used multi-catch for reflection exceptions in:
    - `CocoaUIEnhancer.java:121,287,301` (`NoSuchMethodException | IllegalAccessException | InvocationTargetException`)
  - Added justification comments to intentionally broad catches in `ShowSelectionEvaluator.java` (defensive for external data/utility calls)
- **Notes:**
  - Thread safety nets (top-level UI, background threads) intentionally kept broad
  - Platform compatibility catches (best-effort UI features) documented with comments

### 26) Improve handling of unparsed files (parse failure diagnostics)
- **Why:** Parse failures are a common frustration; users need actionable feedback about WHY parsing failed.
- **Where:** `org.tvrenamer.controller.FilenameParser`, `org.tvrenamer.model.FileEpisode`, `org.tvrenamer.view.ResultsTable`, `org.tvrenamer.model.util.Constants`
- **What we did:**
  - Added `ParseFailureReason` enum to `FilenameParser` with 4 specific failure reasons:
    - `NO_SHOW_NAME` - Could not extract show name from filename
    - `NO_SEASON_EPISODE` - No season/episode pattern found (e.g., S01E02, 1x03)
    - `FILENAME_TOO_SHORT` - Filename too short to parse (< 4 chars)
    - `NO_ALPHANUMERIC` - Filename contains no recognizable text
  - Added `parseFailureReason` field and getter to `FileEpisode`
  - Updated `setFailToParse()` to accept and store a specific reason
  - Added diagnostic logic to `FilenameParser.parseFilename()`:
    - Early validation for too-short/no-alphanumeric filenames
    - `diagnoseFailure()` helper to determine specific failure reason
    - `containsEpisodePattern()` helper to distinguish "no episode pattern" from "no show name"
  - Added summary dialog in `ResultsTable.addEpisodes()`:
    - Tracks parse failures by reason during batch processing
    - Shows non-blocking summary dialog after all files processed (if failures exist)
    - Displays success count, failure count, and breakdown by failure reason
  - Added `PARSE_SUMMARY_TITLE` constant for i18n readiness
- **Notes:**
  - Failure reason is displayed in the "Proposed File Path" column via `getReplacementText()`
  - Summary dialog is non-blocking (shown after parsing completes, not during)
  - All user-facing strings centralized in `Constants.java` for future localization

### 27) MKV metadata tagging via mkvpropedit
- **Why:** Extend metadata tagging to MKV files; MKV is a common container format and users benefit from embedded metadata for media managers.
- **Where:** `org.tvrenamer.controller.metadata.MkvMetadataTagger`, `org.tvrenamer.controller.metadata.MetadataTaggingController`, `org.tvrenamer.model.util.Constants`
- **What we did:**
  - Created `MkvMetadataTagger` implementing `VideoMetadataTagger` interface
  - Uses mkvpropedit CLI from MKVToolNix (external dependency)
  - Detection strategy: checks PATH, Windows Program Files, macOS Homebrew locations
  - Gracefully skips if mkvpropedit not installed (not an error)
  - Writes Matroska tags via XML file at three target levels:
    - Target 70 (Collection): `TITLE`, `COLLECTION`, `CONTENT_TYPE`
    - Target 60 (Season): `PART_NUMBER`, `TITLE`
    - Target 50 (Episode): `TITLE`, `PART_NUMBER`, `DATE_RELEASED`, `DATE_RECORDED`
  - Sets segment `title` (via `--edit info --set title=...`) to filename without extension for display name compatibility
  - Registered in `MetadataTaggingController` alongside `Mp4MetadataTagger`
  - Updated preferences tooltip to mention MKV support and mkvpropedit requirement
- **Notes:**
  - Supported extensions: `.mkv`, `.webm`
  - Requires MKVToolNix installed; detection is cached at startup
  - Proper XML escaping for show/episode titles containing special characters
  - 30-second process timeout with proper cleanup
  - See `docs/Tagging Spec.md` for detailed format documentation

### 28) Embedded help system with static HTML pages
- **Why:** The Help menu existed but was unwired; users need guidance without searching GitHub issues or releases.
- **Where:** `src/main/resources/help/` (HTML files), `org.tvrenamer.controller.HelpLauncher`, `org.tvrenamer.view.UIStarter`
- **What we did:**
  - Created 8 HTML help pages covering all major features:
    - `index.html` - Table of contents and overview
    - `getting-started.html` - First launch and quick start guide
    - `adding-files.html` - Drag-drop, file dialog, preload folder
    - `renaming.html` - Format tokens, customization, conflict handling
    - `preferences.html` - All preferences explained
    - `show-matching.html` - Overrides, disambiguations, troubleshooting matches
    - `metadata-tagging.html` - MP4/MKV tagging, MKVToolNix requirements
    - `troubleshooting.html` - Common issues and debug logging
  - Created `style.css` with light/dark mode support (respects system preference)
  - Created `HelpLauncher` class to extract help from JAR to temp directory and open in browser
  - Wired Help menu item with F1 keyboard shortcut
  - Added documentation maintenance reminder to `agents.md` release process
- **Notes:**
  - Help is embedded in the JAR for offline access
  - Extraction is cached per session for performance
  - Opens in system default browser via `Program.launch()`
  - Temp files marked for deletion on JVM exit

### 29) Extract EpisodeReplacementFormatter from FileEpisode
- **Why:** `FileEpisode` had grown large (~1400 lines); extracting formatting logic improves maintainability and testability.
- **Where:** `org.tvrenamer.model.EpisodeReplacementFormatter` (new), `org.tvrenamer.model.FileEpisode` (refactored)
- **What we did:**
  - Created `EpisodeReplacementFormatter` with extracted static formatting methods:
    - `format()` (was `plugInInformation`) — main token replacement
    - `substituteAirDate()` (was `plugInAirDate`) — date token handling
    - `removeTokens()` — helper for null date cases
  - Modernized the formatting logic:
    - Changed `replaceAll()` to `replace()` — tokens are literals, not regex; eliminates need for `Matcher.quoteReplacement()` and reduces overhead
    - Cached `DateTimeFormatter` instances as static finals (expensive to create)
  - Cleaned up `FileEpisode`:
    - Removed unused `FailureReason` enum (defined but never referenced)
    - Removed unused `setConflict()` method (defined but never called)
    - Simplified `checkFile()` by removing no-op method calls
    - Cleaned up spurious blank lines in multi-line comments
    - Simplified Integer auto-unboxing (`multiEpisodeEnd >= multiEpisodeStart`)
  - Reduced `FileEpisode` from ~1400 to ~1210 lines
- **Notes:**
  - External API unchanged; `setNoFile()`, `setFileVerified()`, `setMoving()` retained as no-ops for API compatibility (called from FileMover)
  - All tests pass after refactoring

### 30) String encoding cleanup and TrickyTitlesTest
- **Why:** Historical code mixed URL encoding, XML post-processing, and filename sanitization in confusing ways. Unused/deprecated methods cluttered `StringUtils`.
- **Where:** `org.tvrenamer.controller.util.StringUtils` (cleaned), `TrickyTitlesTest` (new), `docs/Strings Spec.md` (new)
- **What we did:**
  - Created `docs/Strings Spec.md` documenting encoding responsibilities:
    - URL encoding (query parameters)
    - Filename sanitization (illegal Windows chars)
    - XML handling (never mutate downloaded payloads)
  - Removed unused/deprecated methods from `StringUtils`:
    - `toUpper()` — unused
    - `encodeUrlCharacters()` / `decodeUrlCharacters()` — deprecated wrappers
    - `encodeSpecialCharacters()` / `decodeSpecialCharacters()` — confusing legacy methods
    - Logger field (only used by removed methods)
  - Created `TrickyTitlesTest.java` with 20 tests covering real-world edge cases:
    - Mission: Impossible - Fallout (2018), V/H/S, ? (2021), "What" (2013)
    - S.W.A.T. (2017), *batteries not included, Woodstock '99
    - Unicode characters (em-dash, curly quotes, ellipsis, accented letters)
  - Updated `StringUtilsTest.java` to remove tests for deleted methods
- **Notes:**
  - All encoding responsibilities are now documented in one place
  - Test coverage for tricky titles catches regressions
  - See `docs/Strings Spec.md` for the complete specification

### 31) Preferences dialog drag/drop UX enhancements
- **Why:** Original token list lacked visual affordance; caret didn't track during drag; no click-to-insert option; no preview of format result.
- **Where:** `org.tvrenamer.view.PreferencesDialog` (refactored), `org.tvrenamer.model.util.Constants` (tooltips)
- **What we did:**
  - **Visual feedback during drag:** Added `dragOver()` handler that moves caret to track mouse position, showing the insertion point before drop.
  - **Pill-styled tokens using Canvas:** Replaced plain list items with custom-painted Canvas widgets:
    - Light blue rounded background (RGB 200, 220, 255, 3px corner radius)
    - Darker blue border during drag (RGB 100, 150, 220) for visual feedback
    - Hand cursor indicates interactivity
    - Each token is individually draggable
    - Uses Canvas+PaintListener for reliable background color on Windows (Label.setBackground unreliable)
  - **Vertical layout:** One pill per line using `RowLayout(SWT.VERTICAL)` for narrower dialog.
  - **Click-to-insert:** Added mouse listener so clicking a token inserts it at caret without dragging.
  - **Live preview:** Added preview label below format text field showing real-time result with sample data:
    - Show: "Rover", Season 2, Episode 5, Title: "The Squirrels Episode"
    - Resolution: "720p", Air date: April 26, 2009
    - Updates on every keystroke/drop via ModifyListener
  - **Layout restructure:** Moved "Rename Tokens" title above the token row (was inline) for clearer visual hierarchy.
  - **Tooltip formatting:** Added line breaks to long tooltips for ~70-char max width including bullet points.
  - **Code cleanup:** Removed unused `PreferencesDragSourceListener` class and `addStringsToList()` method; removed unused imports.
- **Notes:**
  - Caret position estimation uses font metrics with GC for reasonably accurate character offset calculation.
  - Sample data uses fictional show to avoid trademark concerns.

### 32) Clarify ignore-keywords semantics and make case-insensitive
- **Why:** Historical code comments mentioned regex but actual implementation uses literal `String.contains()`. Help text incorrectly said "strip from filenames" when files are actually skipped. Case-sensitive matching was confusing (e.g., "Sample" ≠ "sample").
- **Where:** `org.tvrenamer.model.EpisodeDb` (matching), `org.tvrenamer.model.util.Constants` (tooltip), `src/main/resources/help/preferences.html`
- **What we did:**
  - **Case-insensitive matching:** Changed `ignorableReason()` to compare using `toLowerCase(Locale.ROOT)` on both filename and keyword.
  - **Fixed tooltip:** Updated `IGNORE_LABEL_TOOLTIP` to clarify: comma-separated, case-insensitive, files are skipped (not stripped).
  - **Fixed help text:** Rewrote help page section to accurately describe behavior with examples.
  - **Confirmed literals:** Keywords are literal substrings, not regex — left as-is since regex would confuse most users.
- **Notes:**
  - Default keyword remains `sample` (filters preview/sample files).
  - Files containing any keyword show "Ignoring file due to ..." in the Proposed File Path column.

### 33) Optimize writability probes (one per directory instead of per file)
- **Why:** Moving many files to the same destination directory was creating redundant temporary probe files — one per file — causing unnecessary I/O.
- **Where:** `org.tvrenamer.controller.MoveRunner`, `org.tvrenamer.controller.FileMover`
- **What we did:**
  - Added pre-verification in `MoveRunner.setupQueue()`: each unique destination directory is verified once using `FileUtilities.ensureWritableDirectory()`.
  - Added `directoryPreVerified` flag to `FileMover`: when set, `FileMover` skips its own probe check.
  - Verified directories are tracked in a `Set<Path>` and FileMover instances are marked accordingly before submission.
- **Notes:**
  - Reduces I/O overhead for batch moves to the same destination.
  - No behavior change when moving to multiple different directories.

### 34) Fix duplicate cleanup dialog listing just-moved files
- **Why:** After moving files with overwrite enabled, the duplicate cleanup dialog was incorrectly listing the just-moved files as deletion candidates.
- **Where:** `org.tvrenamer.controller.MoveRunner`, `org.tvrenamer.controller.FileMover`
- **What we did:**
  - Added `getActualDestinationIfSuccess()` method to `FileMover` to retrieve the final destination path after a successful move.
  - In `MoveRunner.aggregateDuplicates()`, collect all successfully moved destination paths and filter them out of the duplicate list.
- **Notes:**
  - Duplicates are now correctly limited to files that existed before the move operation.

### 35) Fuzzy matching heuristics for show selection
- **Why:** Reduce disambiguation prompts by auto-selecting shows when there's a clear fuzzy match; improve dialog UX by ranking options by similarity score.
- **Where:** `org.tvrenamer.model.ShowSelectionEvaluator`, `org.tvrenamer.model.ShowStore`, `org.tvrenamer.view.BatchShowDisambiguationDialog`, `org.tvrenamer.view.ResultsTable`
- **What we did:**
  - **Levenshtein distance algorithm:** Added `levenshteinDistance()` for edit distance calculation and `similarity()` for normalized 0.0–1.0 scoring.
  - **ScoredOption class:** New class pairing `ShowOption` with its similarity score, implementing `Comparable` for sorting.
  - **Fuzzy auto-selection (TB7):** After existing tie-breakers, score all candidates. Auto-select if best score ≥ 80% AND gap to second-best ≥ 10%.
  - **Improved disambiguation dialog:** Options sorted by score (best first). Top option marked "★ Recommended (X%)" if score ≥ 70%. Scores ≥ 50% shown as percentages.
  - **Alias support:** Fuzzy scoring also checks show aliases and uses the best score.
  - **Test coverage:** Added 5 new test cases for fuzzy matching behavior.
- **Notes:**
  - Thresholds: 80% + 10% gap for auto-select, 70% for "Recommended" label.
  - Tagged as "Heuristics+" (`git tag Heuristics+`) for rollback capability.

### 36) Fix action button incrementing Processed counter for already-moved files
- **Why:** After moving files, clicking the action button again would increment the "Processed" counter even though nothing was being processed.
- **Where:** `org.tvrenamer.view.ResultsTable`
- **What we did:**
  - Added check for `tvrenamer.moveCompleted` flag in `renameFiles()`: rows that have already been successfully processed are now skipped.
  - The flag was already being set to `Boolean.TRUE` after successful moves; we now read it before adding rows to `pendingMoves`.
- **Notes:**
  - Prevents spurious counter increments when re-clicking the action button.
  - Rows remain checked but are correctly skipped on subsequent clicks.

### 37) Fix SWT 3.130+ fat-JAR incompatibility and upgrade to 3.132.0
- **Why:** SWT 3.130+ added a mandatory `isLoadable()` check (commit `360a2702a7`, PR #2054) that reads `SWT-OS` and `SWT-Arch` from the JAR manifest at startup. In a Shadow/fat JAR the original SWT manifest is replaced by the application's manifest, so these attributes are missing. The check fails and SWT calls `System.exit(1)` with: *"Libraries for platform win32 cannot be loaded because of incompatible environment"*.
- **Where:** `build.gradle` (shadowJar manifest), `gradle/libs.versions.toml` (version bump), dependency lockfiles
- **What we did:**
  - **Root cause identified:** The `Library.isLoadable()` method (called from `C.java` static initializer) opens a `JarURLConnection` to read the main manifest attributes `SWT-OS` and `SWT-Arch`. When these are `null` (as in a merged fat JAR), the equality check against the runtime OS/arch fails.
  - **Workaround applied:** Added `SWT-OS: win32` and `SWT-Arch: x86_64` manifest attributes to both `shadowJar` and `shadowJarVersioned` tasks in `build.gradle`. This makes the `isLoadable()` check pass.
  - **Upgraded SWT** from 3.129.0 to 3.132.0 (latest on Maven Central).
  - **Updated dependency lockfiles** via `./gradlew dependencies --write-locks`.
  - **Verified:** `./gradlew clean build shadowJar createExe` passes; fat JAR manifest confirmed to contain the attributes.
- **Notes:**
  - Upstream issue: [eclipse-platform/eclipse.platform.swt#2928](https://github.com/eclipse-platform/eclipse.platform.swt/issues/2928) (open, no fix merged yet).
  - The manifest workaround is safe since TVRenamer builds exclusively for Windows x86_64.
  - Once the upstream fix lands (treating missing manifest as allowed), the workaround attributes can be removed.

### 38) Episode chain propagation for ambiguous episode titles
- **Why:** When consecutive episodes share overlapping title options (air vs DVD ordering), the user had to manually select the correct title for every ambiguous episode. Chain propagation cascades the selection: picking one title automatically pre-selects the alternative for adjacent episodes.
- **Where:** `FileEpisode.java` (title introspection), `ResultsTable.java` (propagation logic + Combo listener)
- **What we did:**
  - Added `getEpisodeTitle(int)` and `indexOfEpisodeTitle(String)` to `FileEpisode` for title access.
  - Added `propagateEpisodeChain()` recursive method to `ResultsTable` with `Set<FileEpisode> visited` for loop prevention and `propagatingChain` flag for Combo re-entry prevention.
  - Updated the Combo `ModifyListener` to trigger propagation when a 2-option episode is selected.
- **Notes:** See `docs/Episode Chain Spec.md` for the full algorithm and edge cases.

### 39) Fuzzy pre-selection of episode titles from filename
- **Why:** When a filename contains episode title text (e.g. `Road.Watch.S03E18.Off.Road.1080p.WEBRip.mkv`), the correct Combo option can be pre-selected automatically by fuzzy-matching "Off Road" against the two episode title choices — eliminating manual selection in most cases.
- **Where:** `FileEpisode.java` (extraction + scoring), `ResultsTable.java` (hook into Combo setup), `ShowSelectionEvaluator.java` (visibility change)
- **What we did:**
  - Added `extractTitleTextFromFilename()` to `FileEpisode`: strips show name, S##E## pattern, resolution, and codec/source tags from the filename, leaving the title portion.
  - Added `fuzzyPreSelectEpisode()` to `FileEpisode`: scores extracted text against each option using Levenshtein similarity (min score 0.6, min gap 0.15).
  - Made `ShowSelectionEvaluator.similarity()` package-private so `FileEpisode` can call it.
  - Hooked into `ResultsTable.setComboBoxProposedDest()` to run fuzzy pre-selection before the Combo is shown, then chain-propagate the pick.
- **Notes:** Phase 2 of `docs/Episode Chain Spec.md`. Thresholds (0.6 score, 0.15 gap) are conservative and may be tuned with real data.

### 40) Combo widget visual artifact fix (Clear Completed)
- **Why:** When "Clear Completed" removed rows, Combo widgets on remaining rows didn't reposition in sync — causing visible ghosting/overlap.
- **Where:** `ResultsTable.java` — Clear Completed handler
- **What we did:** Wrapped the deletion loop in `swtTable.setRedraw(false)` / `swtTable.setRedraw(true)` to batch all removals into a single repaint.

### 41) Dedupe safety gate — only scan for duplicates when moving video files
- **Why:** Moving subtitle/metadata files (e.g. `.srt`) triggered the duplicate video scan, which surfaced the actual video files as "duplicates" — risking accidental deletion.
- **Where:** `FileMover.java` (`finishMove`)
- **What we did:** Added `FileUtilities.hasVideoExtension()` guard to the existing `isCleanupDuplicateVideoFiles()` check. Non-video moves now skip the duplicate scan entirely.

### 42) Dedupe dialog safety defaults
- **Why:** Files were checked by default and Delete was the default button — an impatient Enter press could delete files without review.
- **Where:** `DuplicateCleanupDialog.java`
- **What we did:**
  - Changed files to unchecked by default (`item.setChecked(false)`).
  - Made Cancel the default button (Enter dismisses safely).
  - Disabled the Delete button until at least one file is checked, via a `syncDeleteEnabled` Runnable wired to table selection and Select All/None buttons.

### 43) Show name similarity for fuzzy duplicate matching
- **Why:** Duplicate detection Check 2 only compared season/episode numbers. In rename-only operations (no move), unrelated files from different shows in the same folder were flagged as duplicates (e.g. `ShowA.S01E01` vs `ShowB.S01E01`).
- **Where:** `FilenameParser.java`, `ShowSelectionEvaluator.java`, `FileUtilities.java`, `FileMover.java`
- **What we did:**
  - Added `ParsedFileIdentity` record and `extractShowAndSeasonEpisode()` to `FilenameParser` for lightweight show name + season/episode extraction from filenames.
  - Made `ShowSelectionEvaluator.similarity()` public so `FileUtilities` can access it.
  - Updated `findDuplicateVideoFiles()` to accept a `movedShowName` parameter and require Levenshtein similarity >= 0.5 between show names before flagging a fuzzy duplicate.
  - Updated `FileMover.finishMove()` to pass the resolved show name.

### 44) MP4 tagger rewrite — external tools replace mp4parser
- **Why:** The mp4parser library (v1.9.56, unmaintained since ~2017) corrupted MP4 files by rewriting the entire box tree with custom ByteBuffer atom construction. MKV tagging worked fine because it used external `mkvpropedit`.
- **Where:** `Mp4MetadataTagger.java`, `build.gradle`, `gradle/libs.versions.toml`, `gradle.lockfile`, help pages, `Constants.java`
- **What we did:**
  - Rewrote `Mp4MetadataTagger` to use external tools: AtomicParsley (preferred, surgical iTunes atom edits) with ffmpeg fallback (`-c copy` container rewrite).
  - Cached tool detection with `volatile` + double-checked locking, checking PATH and common OS install locations.
  - Added `isToolAvailable()` and `getDetectedToolName()` public methods for UI.
  - Removed mp4parser dependency from version catalog, build script, and lockfile.
  - Updated help pages, tooltips, and documentation to reflect external tool requirement.

### 45) Dedupe dialog: file size, date, and Open Folder
- **Why:** The dialog only showed Filename and Folder columns — no context to make informed deletion decisions. Also no way to navigate to a file's location.
- **Where:** `DuplicateCleanupDialog.java`
- **What we did:**
  - Added Size column (human-readable: B/KB/MB/GB) via `Files.size()`.
  - Added Modified column (yyyy-MM-dd HH:mm) via `Files.getLastModifiedTime()`.
  - Added right-click "Open Folder" context menu using `Program.launch()` on the parent directory.
  - Widened dialog minimum width from 600 to 750 to accommodate new columns.

### 46) Codebase quality sweep — 12 improvements across bug fixes, consolidation, modernisation, and safety
- **Why:** Address findings from a full codebase review; reduce duplication, fix bugs, modernise patterns, improve thread safety, and establish conventions.
- **Where:** Multiple files across `controller/util/`, `controller/metadata/`, `model/`, `view/`, `build.gradle`
- **What we did:**
  - **Bug fix:**
    - Fixed `StringUtils.removeLast()` case-sensitivity mismatch — was searching lowercased input but not lowercasing the match string, corrupting output when case differed
  - **New shared utilities:**
    - Created `ProcessRunner` (`controller/util/`) — shared process execution with timeout, output capture, and proper cleanup in `finally` block. Replaced ~48 duplicated lines in each of Mp4MetadataTagger, MkvMetadataTagger, and ThemeManager
    - Created `ExternalToolDetector` (`controller/util/`) — shared tool detection checking PATH then platform-specific paths. Replaced ~80 duplicated lines across both taggers
  - **Consolidation:**
    - Added `StringUtils.getBaseName()` and replaced 5 duplicate `lastIndexOf('.')` sites in Mp4MetadataTagger, MkvMetadataTagger, MoveRunner, and FileUtilities
    - Extracted `ensureOnlyOneChecked()` helper in `BatchShowDisambiguationDialog`, replacing duplicate mutex loops
    - DRY'd `build.gradle`: extracted `swtManifestAttributes` map, `addBuildMetadata` closure, `configureResourceProcessing` closure; fixed duplicate comment
  - **Modernisation:**
    - `ThreadLocal` initializers → `ThreadLocal.withInitial()` lambdas
    - `stringsAreEqual()` → `Objects.equals()`
    - `isBlank()`/`isNotBlank()` → delegate to `String.isBlank()` with null guard
    - `makeString()` → `new String(buffer, StandardCharsets.US_ASCII)` (removed deprecated charset string)
  - **Thread safety:**
    - Changed `UserPreferences` override maps from `LinkedHashMap` to `ConcurrentHashMap` (prevents `ConcurrentModificationException` during iteration)
  - **Null-vs-empty convention:**
    - `EpisodeOptions.getAll()`, `Season.getAll()`, `Show.getEpisodes()` now return `List.of()` instead of null. Updated javadocs: "never null". Simplified caller in `FileEpisode`
  - **Resource management:**
    - Added `titleFont`/`versionFont` fields to `AboutDialog`; added `SWT.Dispose` listener for proper font cleanup
  - **Dead code removal:**
    - Removed no-op methods `setNoFile()`, `setFileVerified()`, `setMoving()` from `FileEpisode` and their 3 call sites in `FileMover`
  - **Performance:**
    - Added per-thread `XPathExpression` cache in `XPathUtilities` to avoid recompiling the same XPath strings repeatedly
- **Notes:**
  - 12 of 24 identified items completed; 2 skipped (XML escaping — hand-rolled version is correct; FileUtilities null handling — current behavior is defensively adequate). 10 remain open (see `docs/code improvement opportunities.md`).
  - All builds and tests pass after changes.

### 47) Codebase quality sweep part 2 — 8 more improvements: decomposition, dialog cleanup, logging, and tests
- **Why:** Continue addressing findings from the full codebase review; improve maintainability, dialog safety, logging performance, and test coverage.
- **Where:** Multiple files across `model/`, `controller/`, `view/`, `controller/util/`, `controller/metadata/`, test classes
- **What we did:**
  - **Dialog boilerplate (#10):**
    - Created `DialogHelper.java` with `runModalLoop(Shell)` — replaced identical 5-line SWT event loops in AboutDialog, BatchShowDisambiguationDialog, DuplicateCleanupDialog, and PreferencesDialog
    - Fixed DuplicateCleanupDialog to use `DialogPositioning.positionDialog()` instead of manual centering (gains multi-monitor clamping)
  - **File I/O off UI thread (#12):**
    - Added `FileInfo` record to DuplicateCleanupDialog — pre-computes `Files.size()` and `Files.getLastModifiedTime()` in the constructor before any UI widgets are created
    - Table population now reads from pre-computed data, not live filesystem calls
  - **Decompose evaluate() (#16):**
    - Extracted 8 named methods from the 310-line `ShowSelectionEvaluator.evaluate()`: `tryPinnedId`, `tryExactNameMatch`, `tryExactAliasMatch`, `tryBaseTitleOverVariants`, `tryTokenSetMatch`, `tryYearTolerance`, `fuzzyMatchOrAmbiguous`, plus shared helpers `matchesExtracted`, `safeNormalize`, `canonicalTokens`, `bestScore`, `safeAliases`, `safeFirstAiredYear`, `isParentheticalVariant`
    - `evaluate()` is now a clean chain of calls, each step independently testable
  - **Standardise logging (#19):**
    - Converted 30+ `logger.fine("msg" + x)` calls to `logger.log(Level.FINE, () -> "msg" + x)` in hot-path model and controller files — avoids string concatenation when FINE logging is disabled
    - Fixed one exception-logging anti-pattern in TheTVDBProvider (was losing stack trace)
  - **FileUtilities null handling (#20):**
    - Added null guards with FINE-level logging to `isDirEmpty()` and `rmdir()` — previously would NPE on null input
  - **XML escaping utility (#21):**
    - Moved `escapeXml()` from MkvMetadataTagger to `StringUtils.escapeXml()` for shared reuse
    - MkvMetadataTagger now delegates to the shared method
  - **Constants organisation (#22):**
    - Added section divider comments to Constants.java: Application identity, URLs, Resource paths, Button/dialog labels, Preferences dialog labels, Update checker messages, Provider/parsing errors, Default values, File system paths, FileEpisode constants
  - **Metadata tagger tests (#23):**
    - Created `ProcessRunnerTest.java` — tests successful/failed commands, output capture, non-zero exit codes, failure sentinel
    - Created `ExternalToolDetectorTest.java` — tests PATH detection, nonexistent tools, multi-name fallback
    - Added `escapeXml` and `getBaseName` tests to `StringUtilsTest.java`
- **Notes:**
  - 20 of 24 total items now completed. 4 remain open: #7 (FileEpisode thread safety), #14 (tagger interface), #15 (TaggingResult enum), #17 (Java record candidates).
  - All builds and tests pass after changes.

### 48) Codebase quality sweep part 3 — final 4 improvements: thread safety, API design, and Java records

Completes the code improvement opportunities document (all 24 items done).

- **#7 FileEpisode thread safety:** Added `synchronized` to all methods that read or write shared
  mutable fields (`actualEpisodes`, `replacementText`, `replacementOptions`, `seriesStatus`,
  `chosenEpisode`, `actualShow`, `baseForRename`). Writers: `setEpisodeShow`, `setFailedShow`,
  `listingsComplete`, `listingsFailed`, `setApiDiscontinued`. Readers: `getEpisodeTitle`,
  `indexOfEpisodeTitle`, `getActualEpisode`, `getReplacementText`, `getActualShow`,
  `setChosenEpisode`, `getChosenEpisode`, `getDestinationBasename`, `fuzzyPreSelectEpisode`,
  `refreshReplacement`, `getMoveToPath`. Removed redundant inner `synchronized (this)` block
  in `listingsComplete()`.
- **#14 Tagger interface tool availability:** Added `isToolAvailable()` and `getToolName()` to
  `VideoMetadataTagger` interface. Implemented in `Mp4MetadataTagger` and `MkvMetadataTagger`.
  Added `getToolSummary()` and `isAnyToolAvailable()` to `MetadataTaggingController` for UI queries.
- **#15 TaggingResult enum:** Replaced `boolean` return from `MetadataTaggingController.tagIfEnabled()`
  with `TaggingResult` enum (`SUCCESS`, `DISABLED`, `UNSUPPORTED`, `FAILED`). Added `isOk()` convenience
  method. Updated `FileMover.tagFileIfEnabled()` caller.
- **#17 Java record candidates:** Converted `EpisodePlacement` from class with public final fields to
  a Java record. Updated all field access sites (`.season` → `.season()`, `.episode` → `.episode()`)
  across 10 files. Converted `ShowSelectionEvaluator.ScoredOption` to a record, updated accessor
  calls (`.getOption()` → `.option()`, `.getScore()` → `.score()`) across 3 files.
  `ShowOption` and `Decision` were left as classes (subclass hierarchy / complex factory methods).

### 49) Native Windows dark mode via OS.setTheme()
- **Why:** Dark mode previously used manual `setBackground`/`setForeground` calls, which left
  native widgets (menu bar, button borders, tab headers, scrollbars) in their default light
  appearance — bright white borders clashing with dark content areas.
- **Where:** `ThemeManager.java`, `UIStarter.java`
- **What we did:**
  - Added `applyNativeTheme(ThemeMode)` to `ThemeManager` — uses reflection to call
    `org.eclipse.swt.internal.win32.OS.setTheme(boolean)`, the same API Eclipse IDE uses.
  - Called from `UIStarter` after `Display` creation but before any widgets are created.
  - Added `nativeDarkModeActive` flag; when true, the manual button border overlay
    (`installButtonBorderPainter`) is skipped to avoid double-painting.
  - Graceful fallback: if reflection fails (older SWT, non-Windows), manual theming continues.
- **Notes:** Requires SWT 3.132.0+. Tab folder headers in Preferences still appear light
  (SWT `TabFolder` limitation on Windows), but all other native widgets are properly dark.

### 50) Promote Preferences to top-level menu bar entry
- **Why:** Preferences was the most-used menu item but buried under File → Preferences.
- **Where:** `UIStarter.java` (`setupMenuBar()`)
- **What we did:**
  - Replaced the "File" cascade menu with a "Preferences" cascade menu on the menu bar.
  - Dropdown contains: Preferences (Ctrl+P), separator, Exit (Ctrl+Q).
  - macOS behaviour unchanged (uses native application menu via `CocoaUIEnhancer`).
  - Updated all help pages (`index.html`, `preferences.html`, `getting-started.html`,
    `adding-files.html`) to reflect the new menu structure.
  - Removed references to a non-existent "File → Add Files" menu item from help.

### 51) Code review round 3 — 23 improvements across concurrency, duplication, error handling, tests, and docs
- **Why:** Third consolidation pass using expanded 18-category checklist. Focused on concurrency safety, XML utility deduplication, error handling precision, test coverage, and documentation accuracy.
- **Where:** Review document: `docs/Code-Review-260313.md` (30 findings, 23 implemented, 7 closed as N/A).
- **What we did:**
  - **Concurrency (#1–#5):** Made UpdateChecker and PreferencesDialog validation threads daemon; added display/widget disposal checks in ResultsTable and PreferencesDialog asyncExec callbacks.
  - **XML duplication (#6–#8):** Extracted `XmlUtilities` class with shared `createDocumentBuilder()` (XXE-hardened) and `parseStringMap()`; removed duplicate `escapeXml()` from both persistence classes.
  - **Error handling (#9–#11):** Narrowed `catch(Exception)` to `catch(IOException)` in FileMover mtime read; merged redundant catch blocks; added HTTP status classification and success logging in HttpConnectionHandler.
  - **File I/O (#12):** Added explicit `StandardCharsets.UTF_8` to `Files.writeString()` calls.
  - **Config hygiene (#16, #18):** Extracted TVDB API key to `Constants.TVDB_API_KEY`; added try-catch for malformed preference values.
  - **Logging (#19):** Added success logging for HTTP downloads with status code.
  - **Documentation (#22–#25):** Updated README dependency list (removed XStream/OkHttp/mp4parser, corrected SWT version); updated TODO.md dependency table; fixed JUnit version in Completed.md; replaced real show name in README example.
  - **Test names (#26):** Replaced 14 real show name references in FilenameParserTest with fictional equivalents.
  - **New tests (#28–#30):** Created `MetadataTaggingControllerTest` (5 tests), `UpdateCheckerTest` (13 tests), `ShowTest` (19 tests), `SeriesTest` (12 tests), `ShowNameTest` (18 tests) — 67 new tests total.
  - **CLAUDE.md:** Expanded code review checklist from 12 to 18 categories (added concurrency, resource lifecycle, file I/O, API contracts, config hygiene, logging).
  - Renamed previous review docs to date-based convention (`Code-Review-260210.md`, `Code-Review-260304.md`).

---

## Related records

- Per-release notes are stored as versioned Markdown files:
  - `docs/release-notes-v1.0.<commitCount>.md`
- Specs:
  - `docs/Unified Evaluator Spec.md`
  - `docs/Unifying Matches Spec.md`
  - `docs/Strings Spec.md`
  - `docs/Episode Chain Spec.md`
