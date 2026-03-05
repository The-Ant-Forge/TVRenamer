# Code Improvement Opportunities (Round 2)

Findings from a full codebase review (March 2026). Grouped and prioritised by:
impact > security > robustness > performance > consolidation > cleanliness.

**Status:** 38 of 40 items completed (34 in v1.0.248, 4 more post-release). Remaining deferred: #12, #15, #32. Note: #35 partial — FilenameParserTest and StringUtilsTest still have real show names.

---

## Critical: Security

### 1. XML External Entity (XXE) not disabled in DocumentBuilderFactory — DONE
**File:** `TheTVDBProvider.java:226-236` (and second instance at ~line 312)
**Issue:** `DocumentBuilderFactory.newInstance().newDocumentBuilder()` is called with default settings. Depending on JDK/parser version, external entity processing may be enabled. Since the XML comes from a network source, a compromised or MITM'd response could exploit XXE to read local files or perform SSRF.
**Fix:** Explicitly disable XXE:
```java
DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
```
Also extract a shared `createDocumentBuilder()` helper (two identical call sites).
**Effort:** Small

### 2. HTTP URLs should be HTTPS — DONE
**Files/locations:**
- `TheTVDBProvider.java:43` — `DEFAULT_SITE_URL = "http://thetvdb.com/"` (API calls over plain HTTP; API key visible in transit)
- `build.gradle:134` — `downloadUrl = "http://java.com/download"` (embedded in distributed EXE; user on compromised network could be redirected)
- `Constants.java:66` — `TVRENAMER_LICENSE_URL` uses `http://www.gnu.org/licenses/...`
**Fix:** Change all three to `https://`. The TVDB one is most important as it carries the API key.
**Effort:** Tiny

---

## High: Thread Safety

### 3. TOCTOU race in `Series.createSeries()` — DONE
**File:** `Series.java:89-113`
**Issue:** Two separate `synchronized` blocks with a gap — `get` in one, `put` in another. Two threads calling `createSeries(sameId)` concurrently can both create and register separate `Series` objects, with the second overwriting the first. Also, the same-name path creates a duplicate `Series` instead of returning the existing one.
**Fix:** Use `KNOWN_SERIES.computeIfAbsent(idString, k -> new Series(id, name))`. Validate name match after the fact.
**Effort:** Small

### 4. TOCTOU race in `ShowName.mapShowName()` and `QueryString.lookupQueryString()` — DONE
**File:** `ShowName.java:186-219`, `QueryString.java:156-163`
**Issue:** Both use `ConcurrentHashMap` but do check-then-act (get → null check → put) non-atomically. Two threads processing files with the same show name can create duplicate `ShowName`/`QueryString` instances.
**Fix:** Replace with `computeIfAbsent()` throughout.
**Effort:** Small

### 5. `apiIsDeprecated` read without synchronisation — DONE
**File:** `TheTVDBProvider.java:40`
**Issue:** Field is `static boolean` (not `volatile`), written inside `synchronized isApiDiscontinuedError()`, but read without synchronisation in `getShowSearchXml()` and `getSeriesListingXml()`. Visibility issue — a thread may not see the update.
**Fix:** Declare `volatile`.
**Effort:** Tiny

### 6. `showOptions` LinkedList accessed from multiple threads without synchronisation — DONE
**File:** `ShowName.java:324-326`
**Issue:** `getShowOptions()` copies `showOptions` (a `LinkedList`) while `addShowOption()` can modify it concurrently from a provider thread. Race on unsynchronised `LinkedList`.
**Fix:** Use `CopyOnWriteArrayList` or synchronise access.
**Effort:** Small

### 7. `Show.indexEpisodesBySeason()` clears+rebuilds `seasons` map while `getEpisode()` reads it — DONE
**File:** `Show.java:287-307`
**Issue:** `seasons` is a `ConcurrentHashMap`, making individual ops atomic, but the `clear()` + rebuild inside `synchronized(this)` in `indexEpisodesBySeason()` races with `getEpisode()` which reads `seasons.get()` outside any lock. Caller may get null mid-rebuild.
**Fix:** Build the new map in a local variable, then assign atomically (swap reference).
**Effort:** Small

---

## High: Robustness / Correctness

### 8. `refreshDestinations()` has early-return bug — DONE
**File:** `ResultsTable.java:1444`
**Issue:** If any item's file was moved externally, `deleteTableItem(item)` is called then the method `return`s — remaining items never get refreshed.
**Fix:** Change `return` to `continue`.
**Effort:** Tiny

### 9. `FileEpisode.setChosenEpisode()` NPE risk — DONE
**File:** `FileEpisode.java:954`
**Issue:** `actualEpisodes.size()` called without null check. `actualEpisodes` is null when `seriesStatus != GOT_LISTINGS`. Compare with `getActualEpisode()` which does null-check first.
**Fix:** Add `if (actualEpisodes == null) return;` guard.
**Effort:** Tiny

### 10. `FileEpisode.listingsFailed()` calls `getNoListingsPlaceholder()` before null check on `actualShow` — DONE
**File:** `FileEpisode.java:801-816`
**Issue:** `getNoListingsPlaceholder()` calls `actualShow.getName()`. The null check for `actualShow` on line 811 comes after. NPE if `actualShow` is null.
**Fix:** Move null check before `getNoListingsPlaceholder()` call.
**Effort:** Tiny

### 11. `Environment.readResourceTrimmed()` may embed NUL bytes in version string — DONE
**File:** `Environment.java:56-72`
**Issue:** Allocates `new byte[32]`, reads with single `stream.read(buffer)` (not guaranteed to fill), then converts the full 32-byte buffer to String. Trailing NUL bytes survive `.trim()`. Also, single `read()` is not contractually guaranteed to return all available bytes.
**Fix:** Use `stream.readAllBytes()` and trim, or slice buffer to `bytesRead`.
**Effort:** Small

### 12. Magic string coupling: `"show selection required"` — DEFERRED
**File:** `ResultsTable.java:764,1113` ↔ `ShowStore.java:496`
**Issue:** View layer detects a show-selection-required state by substring-matching an exception message. If the message text ever changes, the detection silently breaks.
**Fix:** Use a typed exception (e.g., `ShowSelectionRequiredException`) or a boolean flag on `FileEpisode`.
**Why deferred:** Design-level change affecting multiple layers; better suited for a dedicated refactor.
**Effort:** Small-Medium

---

## Medium: Performance

### 13. `replacePunctuation()` recompiles 9 regex patterns per call — DONE
**File:** `StringUtils.java:353-400`
**Issue:** Called for every file added and during fuzzy matching. Each `String.replaceAll()` recompiles a pattern internally. 9 patterns × many files = significant wasted work.
**Fix:** Pre-compile as `private static final Pattern` constants; use `pattern.matcher(s).replaceAll()`.
**Effort:** Small

### 14. `ShowStore` uses unbounded `newCachedThreadPool()` — DONE
**File:** `ShowStore.java:125-126`
**Issue:** Adding a large directory can spawn one thread per show lookup simultaneously, with no upper bound.
**Fix:** Use `Executors.newFixedThreadPool(N)` or a bounded `ThreadPoolExecutor`.
**Effort:** Small

### 15. Alternating row background recolours entire table on every `SWT.Paint` — DEFERRED
**File:** `ThemeManager.java:241-253`
**Issue:** O(n) work per repaint — iterates all rows setting background/foreground. Causes perceptible lag with large file lists.
**Fix:** Set per-row colours when items are created/added, or use `SWT.EraseItem` listener.
**Why deferred:** Medium effort, requires careful SWT event lifecycle testing to avoid visual regressions.
**Effort:** Medium

### 16. `FileUtilities.hasVideoExtension()` iterates 15 extensions via stream — DONE
**File:** `FileUtilities.java:696-702`
**Issue:** Uses `.stream().anyMatch(lower::endsWith)` when a simple `Set.contains()` on the extracted extension would be O(1).
**Fix:** Extract extension first, then do `VIDEO_EXTENSIONS.contains(ext)`.
**Effort:** Tiny

---

## Medium: Resource Management

### 17. `ItemState` images loaded statically, never disposed — DONE
**File:** `ItemState.java` (static initialiser)
**Issue:** Each `ItemState` creates an SWT `Image` via `UIStarter.readImageFromPath()`. These are held for JVM lifetime and never disposed. SWT logs resource leak warnings at exit.
**Fix:** Register `display.disposeExec(() -> image.dispose())`.
**Effort:** Small

### 18. Dialog `ThemePalette` instances not eagerly disposed — DONE
**Files:** `DuplicateCleanupDialog.java:119-120`, `BatchShowDisambiguationDialog.java:119`
**Issue:** New `ThemePalette` (wrapping ~11 `Color` objects) created each time dialog opens. `display.disposeExec` cleans up at app exit, but not when the dialog closes. Repeated opens leak colours until shutdown.
**Fix:** Add `dialogShell.addListener(SWT.Dispose, e -> themePalette.dispose())`.
**Effort:** Tiny

### 19. `AboutDialog` label Image never disposed — DONE
**File:** `AboutDialog.java:83,132`
**Issue:** `readImageFromPath()` called twice for the same icon — one for the shell (managed), one for a label (unmanaged, never disposed).
**Fix:** Share the Image reference; dispose in shell's Dispose listener.
**Effort:** Tiny

### 20. `ThemePalette` allocates unused colours — DONE
**File:** `ThemePalette.java:44-63`
**Issue:** `getAccentColor()`, `getSelectionBackground()`, `getSelectionForeground()` are defined but never called anywhere. 4-6 `Color` objects allocated and disposed for nothing.
**Fix:** Remove the unused getters and their corresponding colour allocations.
**Effort:** Tiny

---

## Medium: Dependency Reduction

### 21. OkHttp 5.x pulls in Kotlin stdlib (~2-3 MB in fat JAR) — DONE
**File:** `HttpConnectionHandler.java`, `libs.versions.toml`, `build.gradle`
**Issue:** The app uses OkHttp only for simple HTTP GET calls. OkHttp 5 is Kotlin-first and transitively pulls `kotlin-stdlib` (~1.7 MB), `okio`, and `org.jetbrains:annotations`. `java.net.http.HttpClient` (stable since Java 11) could replace it entirely.
**Fix applied:** Rewrote `HttpConnectionHandler` to use `java.net.http.HttpClient`. Removed OkHttp, okio, and kotlin-stdlib from fat JAR (~2 MB savings). Same API contract, same timeouts, same error handling.
**Effort:** Medium

### 22. XStream used for simple preferences persistence — DONE
**File:** `UserPreferencesPersistence.java`, `GlobalOverridesPersistence.java`, `UserPreferences.java`, `GlobalOverrides.java`
**Issue:** XStream is a heavyweight XML serialisation library with a history of deserialization CVEs. Used only for reading/writing `prefs.xml` and `overrides.xml` (simple flat key-value structures). Transitively pulls `mxparser` and `xmlpull`.
**Fix applied:** Replaced with JDK DOM XML parsing (`javax.xml.parsers.DocumentBuilder`) for reading and `StringBuilder` for writing. Added `fromParsedXml()` factory methods to model classes. Backward-compatible with existing XStream-generated XML files (handles legacy field aliases). XXE protections enabled on all parsers.
**Effort:** Medium-Large

---

## Medium: Dead Code Removal

### 23. Unused constants in `Constants.java` — DONE
- `SEND_SUPPORT_EMAIL` (line 82) — no callers
- `EMAIL_LINK` (line 76) — empty string, no callers
- `BROKEN_PLACEHOLDER_FILENAME` (line 316) — no callers
- `OVERRIDES_FILE_LEGACY` (line 360) — no callers (migration never implemented)
- `ICON_PARENT_DIRECTORY` (line 88) — kept; still referenced in UIStarter
**Effort:** Tiny

### 24. Unused methods and fields elsewhere — DONE
- `Environment.readCommitSha()` — removed (no callers)
- `Environment.IS_UN_X` — removed (truly unused)
- `StringUtils.decodeUrlQueryParam()` — removed (no production callers)
- `StringUtils.makeString()` — removed (dead after Environment changes)
- `ThemePalette.apply(Control, boolean)` — removed (never called)
- `SWTMessageBoxType` — 3 commented-out enum values removed
- `TheTVDBProvider.java:73,77` — commented-out constants removed
**Effort:** Small

---

## Medium: Code Quality

### 25. `StringUtils.SANITISE` uses double-brace HashMap anti-pattern — DONE
**File:** `StringUtils.java:17-37`
**Issue:** Anonymous `HashMap` subclass via double-brace initialisation. Leaks outer class reference, defeats serialisation, creates unnecessary anonymous type.
**Fix:** Use `Map.ofEntries(Map.entry(...), ...)` (Java 9+). Also removes need for `Collections.unmodifiableMap()`.
**Effort:** Tiny

### 26. `StringUtils.toLower()` uses `Locale.getDefault()` — Turkish locale problem — DONE
**File:** `StringUtils.java:15,68`
**Issue:** Lowercase conversion for show name queries uses system default locale. In Turkish locale, `'I'.toLowerCase()` produces `'ı'` not `'i'`, breaking provider lookups.
**Fix:** Use `Locale.ROOT` for query string normalisation.
**Effort:** Tiny

### 27. OS detection duplicated in `ExternalToolDetector` — DONE
**File:** `ExternalToolDetector.java:16-19`
**Issue:** Re-implements OS detection via `System.getProperty("os.name")` instead of using `Environment.IS_WINDOWS` / `IS_MAC_OSX`.
**Fix:** Use `Environment` constants.
**Effort:** Tiny

### 28. `StdOutConsoleHandler` javadoc is backwards — DONE
**File:** `StdOutConsoleHandler.java:8-13`
**Issue:** Comment says "standard ConsoleHandler logs to stdout" — it actually logs to **stderr**. That's the whole reason this class exists: to redirect to stdout.
**Fix:** Correct the comment.
**Effort:** Tiny

---

## Low: Build

### 29. `computeBuildNumber()` called 3 times per build — DONE
**File:** `build.gradle:126,175,248`
**Issue:** Each invocation spawns a `git rev-list --count HEAD` subprocess. Three invocations per build.
**Fix:** Compute once, store in an `ext` property, reference everywhere.
**Effort:** Tiny

### 30. SpotBugs plugin version hardcoded in `build.gradle` instead of `libs.versions.toml` — DONE
**File:** `build.gradle:8`
**Issue:** All other plugins/libraries use the version catalog; SpotBugs is the exception.
**Fix:** Move to version catalog for consistency.
**Effort:** Tiny

### 31. `build` task forces versioned fat-JAR packaging — DONE
**File:** `build.gradle:252-255`
**Issue:** `build` depends on `shadowJarVersioned`, so every `./gradlew build` (intended as fast feedback) produces a versioned fat JAR including a `git rev-list` subprocess.
**Fix:** Remove the dependency; let `shadowJar`/`createExe` be opt-in.
**Effort:** Tiny (but verify no downstream breakage)

---

## Low: Test Quality

### 32. No tests for `findDuplicateVideoFiles()` or `deleteFiles()` — DEFERRED
**File:** `FileUtilities.java:722-830`
**Issue:** Contains non-trivial logic (fuzzy show name matching, season/episode extraction) and deletes files. Zero test coverage.
**Why deferred:** Medium effort to write comprehensive tests with filesystem mocking; better suited for a dedicated testing pass.
**Effort:** Medium

### 33. `testEnsureWritableDirectoryCantWrite()` silently no-ops on Windows — DONE
**File:** `FileUtilsTest.java:294-343`
**Issue:** Catches `UnsupportedOperationException` from POSIX APIs and silently returns. Test passes without testing anything on Windows (the primary target OS).
**Fix:** Use `@DisabledOnOs(OS.WINDOWS)` or implement Windows-specific permission check.
**Effort:** Small

### 34. `FileEpisodeTest` uses system temp dir instead of JUnit `@TempDir` — DONE
**File:** `FileEpisodeTest.java`
**Issue:** Fixed path under system temp; not cleaned up on interruption.
**Fix:** Migrated to `@TempDir`. Removed `FileDeleter`, `createNewDirectory()`, `setUp()`, `teardown()`, `cleanUp()`.
**Effort:** Small

### 35. Real show names in test data — DONE (partial)
**Files:** `FileEpisodeTest.java`, `ConflictTest.java`, `MoveTest.java`, `ShowSelectionEvaluatorTest.java`
**Fix:** Replaced all real show names and TVDB IDs with fictional equivalents preserving test characteristics (special characters, colons, slashes, parenthetical years, all-caps, numeric names, etc.).
**Remaining:** `FilenameParserTest.java` (~45 real names), `StringUtilsTest.java` (~25 real names), and `TheTVDBProviderTest.java` (needs real names for API tests) still contain real show names.
**Effort:** Small (done portion); Medium (remaining)

### 36. `MoveTest.initializePrefs()` mutates global singleton with no teardown — DONE
**File:** `MoveTest.java:52-65`
**Issue:** Sets `FileMover.userPrefs` and `FileMover.logger.setLevel(SEVERE)` globally in `@BeforeAll` with no `@AfterAll` restore. Pollutes state for any subsequent test classes in the same JVM.
**Fix:** Save and restore original values in `@AfterAll`.
**Effort:** Small

---

## Low: Minor Style / Consistency

### 37. `size() == 0` instead of `isEmpty()` in several places — DONE
- `EpisodeOptions.java:82` — `episodeList.size() == 0`
- `Show.java:339` — `episodes.size() == 0`
- `ShowName.java` — `listeners.size() > 0` instead of `!listeners.isEmpty()`
**Effort:** Tiny

### 38. Inline fully-qualified class names instead of imports — DONE
- `TheTVDBProvider.java:143` — `java.util.ArrayList<String>`
- `ShowSelectionEvaluator.java:486` — `java.util.ArrayList`, `java.util.Collections`
- `UserPreferences.java:86-95` — `java.util.concurrent.ConcurrentHashMap`
**Fix:** Add proper imports.
**Effort:** Tiny

### 39. `CheckboxField.getItemTextValue()` returns "0" for checked, "1" for unchecked — DONE
**File:** `CheckboxField.java:39`
**Issue:** Counter-intuitive boolean-to-sort-value mapping with no documentation.
**Fix:** Add a comment explaining the sort-order convention.
**Effort:** Tiny

### 40. `ThemeManager.applyPalette(Object, ThemePalette)` accepts `Object` instead of `Menu` — DONE
**File:** `ThemeManager.java:178`
**Issue:** No-op overload typed as `Object` to avoid compile errors when called with a `Menu`. Any arbitrary object matches without error.
**Fix:** Type the parameter as `Menu`.
**Effort:** Tiny

---

## Summary

### Deferred items (better suited for dedicated future work):

#12 — Magic string coupling (typed exception; design change)
#15 — O(n) table recolour on paint (medium effort, needs careful SWT testing)
#32 — New tests for findDuplicateVideoFiles (medium effort)

### Task list status

| # | Item | Priority | Effort | Category | Status |
|---|------|----------|--------|----------|--------|
| 1 | XXE not disabled in XML parsing | Critical | Small | Security | DONE |
| 2 | HTTP URLs → HTTPS | Critical | Tiny | Security | DONE |
| 3 | TOCTOU race in `Series.createSeries()` | High | Small | Thread safety | DONE |
| 4 | TOCTOU race in `ShowName`/`QueryString` | High | Small | Thread safety | DONE |
| 5 | `apiIsDeprecated` not volatile | High | Tiny | Thread safety | DONE |
| 6 | `showOptions` LinkedList unsynchronised | High | Small | Thread safety | DONE |
| 7 | `seasons` map rebuild vs read race | High | Small | Thread safety | DONE |
| 8 | `refreshDestinations()` early return bug | High | Tiny | Correctness | DONE |
| 9 | `setChosenEpisode()` NPE risk | High | Tiny | Robustness | DONE |
| 10 | `listingsFailed()` NPE before null check | High | Tiny | Robustness | DONE |
| 11 | Version string NUL bytes | High | Small | Robustness | DONE |
| 12 | Magic string coupling for show selection | High | Small-Med | Design | DEFERRED |
| 13 | 9 regex recompilations in `replacePunctuation()` | Medium | Small | Performance | DONE |
| 14 | Unbounded thread pool in `ShowStore` | Medium | Small | Performance | DONE |
| 15 | O(n) table recolour on every paint | Medium | Medium | Performance | DEFERRED |
| 16 | `hasVideoExtension()` stream vs set lookup | Medium | Tiny | Performance | DONE |
| 17 | `ItemState` images never disposed | Medium | Small | Resources | DONE |
| 18 | Dialog palette not eagerly disposed | Medium | Tiny | Resources | DONE |
| 19 | AboutDialog label Image leak | Medium | Tiny | Resources | DONE |
| 20 | ThemePalette unused colour allocations | Medium | Tiny | Dead code | DONE |
| 21 | OkHttp → HttpClient (remove Kotlin dep) | Medium | Medium | Dependencies | DONE |
| 22 | XStream → simpler persistence | Medium | Med-Large | Dependencies | DONE |
| 23 | Unused constants in Constants.java | Medium | Tiny | Dead code | DONE |
| 24 | Unused methods/fields elsewhere | Medium | Small | Dead code | DONE |
| 25 | SANITISE double-brace anti-pattern | Medium | Tiny | Code quality | DONE |
| 26 | Turkish locale in `toLower()` | Medium | Tiny | Correctness | DONE |
| 27 | OS detection duplication | Medium | Tiny | Consolidation | DONE |
| 28 | StdOutConsoleHandler comment wrong | Medium | Tiny | Documentation | DONE |
| 29 | `computeBuildNumber()` called 3× | Low | Tiny | Build | DONE |
| 30 | SpotBugs version not in catalog | Low | Tiny | Build | DONE |
| 31 | `build` forces fat-JAR packaging | Low | Tiny | Build | DONE |
| 32 | No tests for duplicate/delete logic | Low | Medium | Test coverage | DEFERRED |
| 33 | Writable-dir test no-ops on Windows | Low | Small | Test quality | DONE |
| 34 | FileEpisodeTest not using @TempDir | Low | Small | Test quality | DONE |
| 35 | Real show names in test data | Low | Small | Policy | DONE (partial) |
| 36 | MoveTest global state pollution | Low | Small | Test quality | DONE |
| 37 | `size()==0` vs `isEmpty()` inconsistency | Low | Tiny | Style | DONE |
| 38 | Inline FQ class names instead of imports | Low | Tiny | Style | DONE |
| 39 | CheckboxField sort-value undocumented | Low | Tiny | Documentation | DONE |
| 40 | `applyPalette(Object,...)` type safety | Low | Tiny | Type safety | DONE |
