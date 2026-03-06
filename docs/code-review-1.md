# Code Improvement Opportunities

Findings from a full codebase review (February 2026). Grouped by impact and effort.

---

## Critical: Bug Fix

### 1. ~~`StringUtils.removeLast()` case-sensitivity mismatch~~ COMPLETED
**File:** `StringUtils.java:171-178`
**Issue:** Finds index in lowercased string, applies it to original. If match differs only by case, the substring offsets are wrong and the output is corrupted.
**Called from:** `FilenameParser.java:284-285` (removes "hdtv", "dvdrip" tags).
**Fix:** Search on lowercased input with lowercased match, or use case-insensitive indexOf.
**Effort:** Tiny

---

## High: Consolidation & Reuse

### 2. ~~Extract shared process execution utility~~ COMPLETED
**Files:** `Mp4MetadataTagger.java:226-266`, `MkvMetadataTagger.java:298-346`, `ThemeManager.java:362-410`
**Issue:** Three near-identical implementations of: ProcessBuilder setup, output draining, timeout handling, exit code check, InterruptedException handling. ~48 duplicated lines per site.
**Also:** Process not destroyed on BufferedReader IOException or InterruptedException (resource leak).
**Fix:** Create `ProcessRunner.run(List<String> cmd, int timeoutSec)` utility. Fix process cleanup in finally block.
**Effort:** Small

### 3. ~~Extract shared tool detection utility~~ COMPLETED
**Files:** `Mp4MetadataTagger.java:270-389`, `MkvMetadataTagger.java:133-211`
**Issue:** Both implement identical: double-checked locking, `isExecutableInPath()` (identical), Windows Program Files scanning, macOS Homebrew scanning. ~80 duplicated lines.
**Also:** Mp4 drains output in `isExecutableInPath()` but Mkv doesn't (inconsistent).
**Fix:** Create `ExternalToolDetector` with `detect(String... names)` checking PATH then platform-specific paths.
**Effort:** Small-Medium

### 4. ~~Consolidate filename extension/base-name extraction~~ COMPLETED
**Files:** `StringUtils.java:536-542`, `Mp4MetadataTagger.java:99-101`, `MkvMetadataTagger.java:91-93`, `FileUtilities.java:726-734`, `MoveRunner.java:311-320`
**Issue:** Extension extraction (`lastIndexOf('.')`) reimplemented in 5+ places with slight variations. No shared `getBaseName()` method.
**Fix:** Ensure all code uses `StringUtils.getExtension()`. Add `StringUtils.getBaseName()`.
**Effort:** Small

### 5. ~~Modernise JDK-duplicate utility methods~~ COMPLETED
**File:** `StringUtils.java`
**Issue:** Several methods duplicate standard library:
- `isBlank()` / `isNotBlank()` (lines 600-625) — duplicate of `String.isBlank()` (Java 11+)
- `stringsAreEqual()` (lines 584-589) — duplicate of `Objects.equals()`
- `makeString()` (lines 106-114) — uses deprecated `new String(byte[], String)` instead of `StandardCharsets`
- ThreadLocal initialisers (lines 40-73) — use anonymous classes instead of `ThreadLocal.withInitial()`

**Fix:** Replace with standard library calls. Project targets Java 17.
**Effort:** Small

### 6. ~~DRY up build.gradle~~ COMPLETED
**File:** `build.gradle`
**Issue:**
- `processResources` and `processTestResources` (lines 194-264) are ~90% identical (~70 duplicated lines)
- SWT manifest attributes duplicated in `shadowJar` and `shadowJarVersioned` (lines 280-284, 315-320)
- Duplicate comment on lines 48-49
**Fix:** Extract shared config helper; extract SWT manifest map.
**Effort:** Small

---

## High: Safety & Correctness

### 7. ~~Thread safety in FileEpisode~~ COMPLETED
**File:** `FileEpisode.java`
**Issue:** Only some methods are synchronized (`optionCount()`, `buildReplacementTextOptions()`), but related fields (`actualEpisodes`, `replacementOptions`, `replacementText`) are accessed without synchronization from `getEpisodeTitle()`, `indexOfEpisodeTitle()`, `getActualEpisode()`, `getReplacementText()`.
**Risk:** Data race when listings arrive on background thread while UI reads episode data.
**Fix:** Synchronize all accessors of shared mutable fields, or use CopyOnWriteArrayList.
**Effort:** Medium

### 8. ~~Thread safety in UserPreferences map iteration~~ COMPLETED
**File:** `UserPreferences.java:149-159, 229-240`
**Issue:** `resolveShowName()` and `resolveDisambiguatedSeriesId()` iterate over maps that `setShowNameOverrides()` / `setShowDisambiguationOverrides()` can modify concurrently (clear + putAll). Risk of `ConcurrentModificationException`.
**Fix:** Use ConcurrentHashMap or synchronize iteration.
**Effort:** Small

### 9. ~~Null-vs-empty collection inconsistency~~ COMPLETED
**Files:** `Season.java:71-78`, `Show.java:317-328`, `EpisodeOptions.java:100-111`, `FileEpisode.java:792-794`
**Issue:** Some methods return null for "no data" (Season.getAll, Show.getEpisodes, EpisodeOptions.getAll), others return empty collections (ShowName.getShowOptions). FileEpisode sets `actualEpisodes = null` when list is empty. Forces defensive null checks everywhere.
**Fix:** Establish rule: always return empty collections, never null. Document the convention.
**Effort:** Medium

---

## Medium: UI Layer

### 10. ~~Dialog boilerplate duplication~~ COMPLETED
**Files:** `AboutDialog.java`, `BatchShowDisambiguationDialog.java`, `DuplicateCleanupDialog.java`, `PreferencesDialog.java`
**Issue:**
- Identical SWT event loop in 4+ dialogs (~6 lines each)
- Identical shell setup (icon, theme palette creation, palette application)
- DuplicateCleanupDialog reimplements dialog centering manually instead of using `DialogPositioning.positionDialog()` — missing multi-monitor clamping
- Redundant theme application: `applyPalette()` called on shell, then again on children, plus manual table coloring

**Fix:** Extract `DialogHelper.runModalLoop(Shell)` and `DialogHelper.setupShell(Shell, String title)`. Use `DialogPositioning` consistently.
**Effort:** Small-Medium

### 11. ~~Font resources not disposed in AboutDialog~~ COMPLETED
**File:** `AboutDialog.java:131-151`
**Issue:** Two `Font` objects created (`new Font(...)`) but never disposed. SWT requires explicit font disposal.
**Fix:** Store font references, dispose when dialog closes (or use ThemePalette pattern).
**Effort:** Tiny

### 12. ~~File I/O on UI thread in DuplicateCleanupDialog~~ COMPLETED
**File:** `DuplicateCleanupDialog.java:167-177`
**Issue:** `Files.size()` and `Files.getLastModifiedTime()` called during table population on UI thread. Could freeze dialog on slow/network drives.
**Fix:** Pre-compute file metadata before opening dialog, pass as data objects.
**Effort:** Small

### 13. ~~Checkbox mutex logic duplicated in BatchShowDisambiguationDialog~~ COMPLETED
**File:** `BatchShowDisambiguationDialog.java:340-346, 390-395`
**Issue:** Identical "uncheck all others" logic in both Selection and DefaultSelection listeners.
**Fix:** Extract to `ensureOnlyOneChecked(TableItem)`.
**Effort:** Tiny

---

## Medium: API & Interface Design

### 14. ~~VideoMetadataTagger interface missing tool availability~~ COMPLETED
**Files:** `VideoMetadataTagger.java`, `Mp4MetadataTagger.java:396-413`, `MkvMetadataTagger.java:368`
**Issue:** `isToolAvailable()` and `getDetectedToolName()` are public static on concrete classes but not on the interface. UI must reference concrete types. Methods are also currently unused in the codebase (dead code from incomplete feature).
**Fix:** Add `isToolAvailable()` and `getToolName()` to interface. Wire into MetadataTaggingController for UI queries.
**Effort:** Small

### 15. ~~MetadataTaggingController return type too coarse~~ COMPLETED
**File:** `MetadataTaggingController.java:41-65`
**Issue:** `tagIfEnabled()` returns `true` for three different scenarios (disabled, unsupported format, success). Caller can't distinguish.
**Fix:** Return enum `TaggingResult { SUCCESS, DISABLED, UNSUPPORTED, FAILED }`.
**Effort:** Small

### 16. ~~ShowSelectionEvaluator.evaluate() too long~~ COMPLETED
**File:** `ShowSelectionEvaluator.java:242-552`
**Issue:** 310-line method with 7+ decision branches, inline lambda helpers, nested loops. Hard to test individual tie-breakers.
**Fix:** Extract each tie-breaker to a named method (tryExactMatch, tryAliasMatch, tryBaseTitle, tryTokenMatch, tryYearTolerance, tryFuzzyMatch).
**Effort:** Medium

---

## Medium: Code Modernisation

### 17. ~~Java record candidates~~ COMPLETED
**Files:** `EpisodePlacement.java`, `ShowOption.java`, `ShowSelectionEvaluator.Decision`, `ShowSelectionEvaluator.ScoredOption`, `FilenameParser.ParsedFileIdentity` (already a record)
**Issue:** Several immutable data classes with public final fields or private fields + getters that would be cleaner as Java records (available since Java 14, project targets 17).
**Candidates:**
- `EpisodePlacement` — two public final fields, perfect record candidate
- `ScoredOption` — two fields + Comparable, clean record
- `ShowOption` — four fields, constructor with defensive copy
**Effort:** Small per class

### 18. ~~Remove dead no-op methods in FileEpisode~~ COMPLETED
**File:** `FileEpisode.java`
**Issue:** Methods `setNoFile()`, `setFileVerified()`, `setMoving()` are no-ops with comments saying "fileStatus tracking removed". Dead code.
**Fix:** Remove the methods and any callers.
**Effort:** Tiny

---

## Low: Polish

### 19. ~~Standardise logging patterns~~ COMPLETED
**Files:** Throughout codebase
**Issue:**
- Mix of `logger.warning(msg)` and `logger.log(Level.WARNING, msg)` styles
- String concatenation in logger calls (evaluated even when level is disabled)
- Inconsistent levels for similar operations
**Fix:** Use `logger.log(Level.X, "message: {0}", param)` parameterised format consistently. Document level conventions (WARNING = user-visible errors, INFO = state changes, FINE = debug).
**Effort:** Medium (many call sites)

### 20. ~~Null handling in FileUtilities~~ COMPLETED
**File:** `FileUtilities.java`
**Issue:** Inconsistent: some methods log warning for null input (`deleteFile`), others return silently (`existingAncestor`), others return empty (`findDuplicateVideoFiles`).
**Fix:** Document and standardise: either all log + return sentinel, or all use `Objects.requireNonNull()`.
**Effort:** Small

### 21. ~~XML escaping in MkvMetadataTagger~~ COMPLETED
**File:** `MkvMetadataTagger.java:351-360`
**Issue:** Hand-rolled XML escaping. Not wrong, but could use standard library.
**Fix:** Consider `javax.xml.stream.XMLStreamWriter` or extract to shared `XmlUtils.escapeXml()` if needed elsewhere.
**Effort:** Tiny

### 22. ~~Constants organisation~~ COMPLETED
**File:** `Constants.java`
**Issue:** 180+ lines of flat constants. Related groups (theme, URLs, move/rename labels, tagging tooltips) are scattered.
**Fix:** Group into inner classes or separate files by domain.
**Effort:** Small

### 23. ~~Missing test coverage for metadata taggers~~ COMPLETED
**Issue:** No unit tests exist for `Mp4MetadataTagger`, `MkvMetadataTagger`, or `MetadataTaggingController`. Process execution, tool detection, temp file cleanup, XML escaping are all untested.
**Fix:** Add tests using mock process builder or test harness. Requires the process utility extraction (#2) to make testable.
**Effort:** Medium-Large

### 24. ~~XPath expression not cached~~ COMPLETED
**File:** `XPathUtilities.java:29-43`
**Issue:** Each call compiles XPath expression from string. Compilation is expensive in loops parsing episode data.
**Fix:** Cache compiled expressions in a `Map<String, XPathExpression>`.
**Effort:** Small

---

## Summary

| # | Item | Impact | Effort | Category | Status |
|---|------|--------|--------|----------|--------|
| 1 | `removeLast()` bug | Critical | Tiny | Bug fix | **DONE** |
| 2 | Shared process execution | High | Small | Consolidation | **DONE** |
| 3 | Shared tool detection | High | Small-Med | Consolidation | **DONE** |
| 4 | Extension/basename extraction | High | Small | Consolidation | **DONE** |
| 5 | Modernise JDK-duplicate utils | High | Small | Modernisation | **DONE** |
| 6 | DRY build.gradle | High | Small | Consolidation | **DONE** |
| 7 | FileEpisode thread safety | High | Medium | Safety | **DONE** |
| 8 | UserPreferences map thread safety | High | Small | Safety | **DONE** |
| 9 | Null-vs-empty collections | High | Medium | Correctness | **DONE** |
| 10 | Dialog boilerplate | Medium | Small-Med | Consolidation | **DONE** |
| 11 | Font disposal | Medium | Tiny | Resource mgmt | **DONE** |
| 12 | File I/O on UI thread | Medium | Small | Performance | **DONE** |
| 13 | Checkbox mutex duplication | Medium | Tiny | Consolidation | **DONE** |
| 14 | Tagger interface + tool availability | Medium | Small | API design | **DONE** |
| 15 | TaggingResult enum | Medium | Small | API design | **DONE** |
| 16 | Decompose evaluate() | Medium | Medium | Maintainability | **DONE** |
| 17 | Java record candidates | Medium | Small | Modernisation | **DONE** |
| 18 | Remove dead no-op methods | Medium | Tiny | Cleanup | **DONE** |
| 19 | Standardise logging | Low | Medium | Consistency | **DONE** |
| 20 | FileUtilities null handling | Low | Small | Consistency | **DONE** |
| 21 | XML escaping utility | Low | Tiny | Consolidation | **DONE** |
| 22 | Constants organisation | Low | Small | Organisation | **DONE** |
| 23 | Metadata tagger tests | Low | Med-Large | Test coverage | **DONE** |
| 24 | XPath caching | Low | Small | Performance | **DONE** |

**All 24 items completed.**
