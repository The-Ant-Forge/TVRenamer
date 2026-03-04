# Code Improvement Opportunities (Round 2)

Findings from a full codebase review (March 2026). Grouped and prioritised by:
impact > security > robustness > performance > consolidation > cleanliness.

---

## Critical: Security

### 1. XML External Entity (XXE) not disabled in DocumentBuilderFactory
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

### 2. HTTP URLs should be HTTPS
**Files/locations:**
- `TheTVDBProvider.java:43` — `DEFAULT_SITE_URL = "http://thetvdb.com/"` (API calls over plain HTTP; API key visible in transit)
- `build.gradle:134` — `downloadUrl = "http://java.com/download"` (embedded in distributed EXE; user on compromised network could be redirected)
- `Constants.java:66` — `TVRENAMER_LICENSE_URL` uses `http://www.gnu.org/licenses/...`
**Fix:** Change all three to `https://`. The TVDB one is most important as it carries the API key.
**Effort:** Tiny

---

## High: Thread Safety

### 3. TOCTOU race in `Series.createSeries()`
**File:** `Series.java:89-113`
**Issue:** Two separate `synchronized` blocks with a gap — `get` in one, `put` in another. Two threads calling `createSeries(sameId)` concurrently can both create and register separate `Series` objects, with the second overwriting the first. Also, the same-name path creates a duplicate `Series` instead of returning the existing one.
**Fix:** Use `KNOWN_SERIES.computeIfAbsent(idString, k -> new Series(id, name))`. Validate name match after the fact.
**Effort:** Small

### 4. TOCTOU race in `ShowName.mapShowName()` and `QueryString.lookupQueryString()`
**File:** `ShowName.java:186-219`, `QueryString.java:156-163`
**Issue:** Both use `ConcurrentHashMap` but do check-then-act (get → null check → put) non-atomically. Two threads processing files with the same show name can create duplicate `ShowName`/`QueryString` instances.
**Fix:** Replace with `computeIfAbsent()` throughout.
**Effort:** Small

### 5. `apiIsDeprecated` read without synchronisation
**File:** `TheTVDBProvider.java:40`
**Issue:** Field is `static boolean` (not `volatile`), written inside `synchronized isApiDiscontinuedError()`, but read without synchronisation in `getShowSearchXml()` and `getSeriesListingXml()`. Visibility issue — a thread may not see the update.
**Fix:** Declare `volatile`.
**Effort:** Tiny

### 6. `showOptions` LinkedList accessed from multiple threads without synchronisation
**File:** `ShowName.java:324-326`
**Issue:** `getShowOptions()` copies `showOptions` (a `LinkedList`) while `addShowOption()` can modify it concurrently from a provider thread. Race on unsynchronised `LinkedList`.
**Fix:** Use `CopyOnWriteArrayList` or synchronise access.
**Effort:** Small

### 7. `Show.indexEpisodesBySeason()` clears+rebuilds `seasons` map while `getEpisode()` reads it
**File:** `Show.java:287-307`
**Issue:** `seasons` is a `ConcurrentHashMap`, making individual ops atomic, but the `clear()` + rebuild inside `synchronized(this)` in `indexEpisodesBySeason()` races with `getEpisode()` which reads `seasons.get()` outside any lock. Caller may get null mid-rebuild.
**Fix:** Build the new map in a local variable, then assign atomically (swap reference).
**Effort:** Small

---

## High: Robustness / Correctness

### 8. `refreshDestinations()` has early-return bug
**File:** `ResultsTable.java:1444`
**Issue:** If any item's file was moved externally, `deleteTableItem(item)` is called then the method `return`s — remaining items never get refreshed.
**Fix:** Change `return` to `continue`.
**Effort:** Tiny

### 9. `FileEpisode.setChosenEpisode()` NPE risk
**File:** `FileEpisode.java:954`
**Issue:** `actualEpisodes.size()` called without null check. `actualEpisodes` is null when `seriesStatus != GOT_LISTINGS`. Compare with `getActualEpisode()` which does null-check first.
**Fix:** Add `if (actualEpisodes == null) return;` guard.
**Effort:** Tiny

### 10. `FileEpisode.listingsFailed()` calls `getNoListingsPlaceholder()` before null check on `actualShow`
**File:** `FileEpisode.java:801-816`
**Issue:** `getNoListingsPlaceholder()` calls `actualShow.getName()`. The null check for `actualShow` on line 811 comes after. NPE if `actualShow` is null.
**Fix:** Move null check before `getNoListingsPlaceholder()` call.
**Effort:** Tiny

### 11. `Environment.readResourceTrimmed()` may embed NUL bytes in version string
**File:** `Environment.java:56-72`
**Issue:** Allocates `new byte[32]`, reads with single `stream.read(buffer)` (not guaranteed to fill), then converts the full 32-byte buffer to String. Trailing NUL bytes survive `.trim()`. Also, single `read()` is not contractually guaranteed to return all available bytes.
**Fix:** Use `stream.readAllBytes()` and trim, or slice buffer to `bytesRead`.
**Effort:** Small

### 12. Magic string coupling: `"show selection required"`
**File:** `ResultsTable.java:764,1113` ↔ `ShowStore.java:496`
**Issue:** View layer detects a show-selection-required state by substring-matching an exception message. If the message text ever changes, the detection silently breaks.
**Fix:** Use a typed exception (e.g., `ShowSelectionRequiredException`) or a boolean flag on `FileEpisode`.
**Effort:** Small-Medium

---

## Medium: Performance

### 13. `replacePunctuation()` recompiles 9 regex patterns per call
**File:** `StringUtils.java:353-400`
**Issue:** Called for every file added and during fuzzy matching. Each `String.replaceAll()` recompiles a pattern internally. 9 patterns × many files = significant wasted work.
**Fix:** Pre-compile as `private static final Pattern` constants; use `pattern.matcher(s).replaceAll()`.
**Effort:** Small

### 14. `ShowStore` uses unbounded `newCachedThreadPool()`
**File:** `ShowStore.java:125-126`
**Issue:** Adding a large directory can spawn one thread per show lookup simultaneously, with no upper bound.
**Fix:** Use `Executors.newFixedThreadPool(N)` or a bounded `ThreadPoolExecutor`.
**Effort:** Small

### 15. Alternating row background recolours entire table on every `SWT.Paint`
**File:** `ThemeManager.java:241-253`
**Issue:** O(n) work per repaint — iterates all rows setting background/foreground. Causes perceptible lag with large file lists.
**Fix:** Set per-row colours when items are created/added, or use `SWT.EraseItem` listener.
**Effort:** Medium

### 16. `FileUtilities.hasVideoExtension()` iterates 15 extensions via stream
**File:** `FileUtilities.java:696-702`
**Issue:** Uses `.stream().anyMatch(lower::endsWith)` when a simple `Set.contains()` on the extracted extension would be O(1).
**Fix:** Extract extension first, then do `VIDEO_EXTENSIONS.contains(ext)`.
**Effort:** Tiny

---

## Medium: Resource Management

### 17. `ItemState` images loaded statically, never disposed
**File:** `ItemState.java` (static initialiser)
**Issue:** Each `ItemState` creates an SWT `Image` via `UIStarter.readImageFromPath()`. These are held for JVM lifetime and never disposed. SWT logs resource leak warnings at exit.
**Fix:** Register `display.disposeExec(() -> image.dispose())`.
**Effort:** Small

### 18. Dialog `ThemePalette` instances not eagerly disposed
**Files:** `DuplicateCleanupDialog.java:119-120`, `BatchShowDisambiguationDialog.java:119`
**Issue:** New `ThemePalette` (wrapping ~11 `Color` objects) created each time dialog opens. `display.disposeExec` cleans up at app exit, but not when the dialog closes. Repeated opens leak colours until shutdown.
**Fix:** Add `dialogShell.addListener(SWT.Dispose, e -> themePalette.dispose())`.
**Effort:** Tiny

### 19. `AboutDialog` label Image never disposed
**File:** `AboutDialog.java:83,132`
**Issue:** `readImageFromPath()` called twice for the same icon — one for the shell (managed), one for a label (unmanaged, never disposed).
**Fix:** Share the Image reference; dispose in shell's Dispose listener.
**Effort:** Tiny

### 20. `ThemePalette` allocates unused colours
**File:** `ThemePalette.java:44-63`
**Issue:** `getAccentColor()`, `getSelectionBackground()`, `getSelectionForeground()` are defined but never called anywhere. 4-6 `Color` objects allocated and disposed for nothing.
**Fix:** Remove the unused getters and their corresponding colour allocations.
**Effort:** Tiny

---

## Medium: Dependency Reduction

### 21. OkHttp 5.x pulls in Kotlin stdlib (~2-3 MB in fat JAR)
**File:** `libs.versions.toml`, `gradle.lockfile`
**Issue:** The app uses OkHttp only for simple HTTP GET calls. OkHttp 5 is Kotlin-first and transitively pulls `kotlin-stdlib` (~1.7 MB), `okio`, and `org.jetbrains:annotations`. `java.net.http.HttpClient` (stable since Java 11) could replace it entirely.
**Pros of replacing:** ~2-3 MB smaller fat JAR, fewer transitive dependencies, no Kotlin stdlib.
**Cons:** Migration effort; OkHttp's connection pooling/retry is battle-tested; `HttpClient` API is more verbose.
**Effort:** Medium

### 22. XStream used for simple preferences persistence
**File:** `libs.versions.toml`, `GlobalOverridesPersistence.java`
**Issue:** XStream is a heavyweight XML serialisation library with a history of deserialization CVEs. Used only for reading/writing `prefs.xml` and `overrides.xml` (simple flat key-value structures). Transitively pulls `mxparser` and `xmlpull`.
**Pros of replacing:** Fewer dependencies, smaller attack surface.
**Cons:** Migration effort; need to handle legacy file format.
**Effort:** Medium-Large

---

## Medium: Dead Code Removal

### 23. Unused constants in `Constants.java`
- `SEND_SUPPORT_EMAIL` (line 82) — no callers
- `EMAIL_LINK` (line 76) — empty string, no callers
- `BROKEN_PLACEHOLDER_FILENAME` (line 316) — no callers
- `OVERRIDES_FILE_LEGACY` (line 360) — no callers (migration never implemented)
- `ICON_PARENT_DIRECTORY` (line 88) — source-tree path that doesn't work in JAR; misleading
**Effort:** Tiny

### 24. Unused methods and fields elsewhere
- `Environment.readCommitSha()` — no callers; build task generating `tvrenamer.commit` resource is wasted
- `Environment.IS_UN_X` — suppressed as `@SuppressWarnings("unused")`, truly unused
- `StringUtils.decodeUrlQueryParam()` — no production callers (test-only roundtrip)
- `ThemePalette.apply(Control, boolean)` — package-private, never called
- `SWTMessageBoxType` — 3 commented-out enum values (`DLG_INFO`, `DLG_QUES`, `DLG_WRKG`)
- `TheTVDBProvider.java:73,77` — commented-out constant declarations
**Effort:** Small

---

## Medium: Code Quality

### 25. `StringUtils.SANITISE` uses double-brace HashMap anti-pattern
**File:** `StringUtils.java:17-37`
**Issue:** Anonymous `HashMap` subclass via double-brace initialisation. Leaks outer class reference, defeats serialisation, creates unnecessary anonymous type.
**Fix:** Use `Map.ofEntries(Map.entry(...), ...)` (Java 9+). Also removes need for `Collections.unmodifiableMap()`.
**Effort:** Tiny

### 26. `StringUtils.toLower()` uses `Locale.getDefault()` — Turkish locale problem
**File:** `StringUtils.java:15,68`
**Issue:** Lowercase conversion for show name queries uses system default locale. In Turkish locale, `'I'.toLowerCase()` produces `'ı'` not `'i'`, breaking provider lookups.
**Fix:** Use `Locale.ROOT` for query string normalisation.
**Effort:** Tiny

### 27. OS detection duplicated in `ExternalToolDetector`
**File:** `ExternalToolDetector.java:16-19`
**Issue:** Re-implements OS detection via `System.getProperty("os.name")` instead of using `Environment.IS_WINDOWS` / `IS_MAC_OSX`.
**Fix:** Use `Environment` constants.
**Effort:** Tiny

### 28. `StdOutConsoleHandler` javadoc is backwards
**File:** `StdOutConsoleHandler.java:8-13`
**Issue:** Comment says "standard ConsoleHandler logs to stdout" — it actually logs to **stderr**. That's the whole reason this class exists: to redirect to stdout.
**Fix:** Correct the comment.
**Effort:** Tiny

---

## Low: Build

### 29. `computeBuildNumber()` called 3 times per build
**File:** `build.gradle:126,175,248`
**Issue:** Each invocation spawns a `git rev-list --count HEAD` subprocess. Three invocations per build.
**Fix:** Compute once, store in an `ext` property, reference everywhere.
**Effort:** Tiny

### 30. SpotBugs plugin version hardcoded in `build.gradle` instead of `libs.versions.toml`
**File:** `build.gradle:8`
**Issue:** All other plugins/libraries use the version catalog; SpotBugs is the exception.
**Fix:** Move to version catalog for consistency.
**Effort:** Tiny

### 31. `build` task forces versioned fat-JAR packaging
**File:** `build.gradle:252-255`
**Issue:** `build` depends on `shadowJarVersioned`, so every `./gradlew build` (intended as fast feedback) produces a versioned fat JAR including a `git rev-list` subprocess.
**Fix:** Remove the dependency; let `shadowJar`/`createExe` be opt-in.
**Effort:** Tiny (but verify no downstream breakage)

---

## Low: Test Quality

### 32. No tests for `findDuplicateVideoFiles()` or `deleteFiles()`
**File:** `FileUtilities.java:722-830`
**Issue:** Contains non-trivial logic (fuzzy show name matching, season/episode extraction) and deletes files. Zero test coverage.
**Effort:** Medium

### 33. `testEnsureWritableDirectoryCantWrite()` silently no-ops on Windows
**File:** `FileUtilsTest.java:294-343`
**Issue:** Catches `UnsupportedOperationException` from POSIX APIs and silently returns. Test passes without testing anything on Windows (the primary target OS).
**Fix:** Use `@DisabledOnOs(OS.WINDOWS)` or implement Windows-specific permission check.
**Effort:** Small

### 34. `FileEpisodeTest` uses system temp dir instead of JUnit `@TempDir`
**File:** `FileEpisodeTest.java:38,98-101`
**Issue:** Fixed path under system temp; not cleaned up on interruption. Subsequent runs fail if directory already exists. `MoveTest` and `ConflictTest` already use `@TempDir` properly.
**Fix:** Migrate to `@TempDir`.
**Effort:** Small

### 35. Real show names in test data
**Files:** `ConflictTest.java:32-46`, `MoveTest.java:67-79`, `EpisodeLookupTest.java:38`
**Issue:** Violates project policy ("never use real TV show names in code/tests"). Uses real show names and TVDB IDs.
**Fix:** Replace with fictional names and IDs.
**Effort:** Small

### 36. `MoveTest.initializePrefs()` mutates global singleton with no teardown
**File:** `MoveTest.java:52-65`
**Issue:** Sets `FileMover.userPrefs` and `FileMover.logger.setLevel(SEVERE)` globally in `@BeforeAll` with no `@AfterAll` restore. Pollutes state for any subsequent test classes in the same JVM.
**Fix:** Save and restore original values in `@AfterAll`.
**Effort:** Small

---

## Low: Minor Style / Consistency

### 37. `size() == 0` instead of `isEmpty()` in several places
- `EpisodeOptions.java:82` — `episodeList.size() == 0`
- `Show.java:339` — `episodes.size() == 0`
- `ShowName.java` — `listeners.size() > 0` instead of `!listeners.isEmpty()`
**Effort:** Tiny

### 38. Inline fully-qualified class names instead of imports
- `TheTVDBProvider.java:143` — `java.util.ArrayList<String>`
- `ShowSelectionEvaluator.java:486` — `java.util.ArrayList`, `java.util.Collections`
- `UserPreferences.java:86-95` — `java.util.concurrent.ConcurrentHashMap`
**Fix:** Add proper imports.
**Effort:** Tiny

### 39. `CheckboxField.getItemTextValue()` returns "0" for checked, "1" for unchecked
**File:** `CheckboxField.java:39`
**Issue:** Counter-intuitive boolean-to-sort-value mapping with no documentation.
**Fix:** Add a comment explaining the sort-order convention.
**Effort:** Tiny

### 40. `ThemeManager.applyPalette(Object, ThemePalette)` accepts `Object` instead of `Menu`
**File:** `ThemeManager.java:178`
**Issue:** No-op overload typed as `Object` to avoid compile errors when called with a `Menu`. Any arbitrary object matches without error.
**Fix:** Type the parameter as `Menu`.
**Effort:** Tiny

---

## Summary

| # | Item | Priority | Effort | Category |
|---|------|----------|--------|----------|
| 1 | XXE not disabled in XML parsing | Critical | Small | Security |
| 2 | HTTP URLs → HTTPS | Critical | Tiny | Security |
| 3 | TOCTOU race in `Series.createSeries()` | High | Small | Thread safety |
| 4 | TOCTOU race in `ShowName`/`QueryString` | High | Small | Thread safety |
| 5 | `apiIsDeprecated` not volatile | High | Tiny | Thread safety |
| 6 | `showOptions` LinkedList unsynchronised | High | Small | Thread safety |
| 7 | `seasons` map rebuild vs read race | High | Small | Thread safety |
| 8 | `refreshDestinations()` early return bug | High | Tiny | Correctness |
| 9 | `setChosenEpisode()` NPE risk | High | Tiny | Robustness |
| 10 | `listingsFailed()` NPE before null check | High | Tiny | Robustness |
| 11 | Version string NUL bytes | High | Small | Robustness |
| 12 | Magic string coupling for show selection | High | Small-Med | Design |
| 13 | 9 regex recompilations in `replacePunctuation()` | Medium | Small | Performance |
| 14 | Unbounded thread pool in `ShowStore` | Medium | Small | Performance |
| 15 | O(n) table recolour on every paint | Medium | Medium | Performance |
| 16 | `hasVideoExtension()` stream vs set lookup | Medium | Tiny | Performance |
| 17 | `ItemState` images never disposed | Medium | Small | Resources |
| 18 | Dialog palette not eagerly disposed | Medium | Tiny | Resources |
| 19 | AboutDialog label Image leak | Medium | Tiny | Resources |
| 20 | ThemePalette unused colour allocations | Medium | Tiny | Dead code |
| 21 | OkHttp → HttpClient (remove Kotlin dep) | Medium | Medium | Dependencies |
| 22 | XStream → simpler persistence | Medium | Med-Large | Dependencies |
| 23 | Unused constants in Constants.java | Medium | Tiny | Dead code |
| 24 | Unused methods/fields elsewhere | Medium | Small | Dead code |
| 25 | SANITISE double-brace anti-pattern | Medium | Tiny | Code quality |
| 26 | Turkish locale in `toLower()` | Medium | Tiny | Correctness |
| 27 | OS detection duplication | Medium | Tiny | Consolidation |
| 28 | StdOutConsoleHandler comment wrong | Medium | Tiny | Documentation |
| 29 | `computeBuildNumber()` called 3× | Low | Tiny | Build |
| 30 | SpotBugs version not in catalog | Low | Tiny | Build |
| 31 | `build` forces fat-JAR packaging | Low | Tiny | Build |
| 32 | No tests for duplicate/delete logic | Low | Medium | Test coverage |
| 33 | Writable-dir test no-ops on Windows | Low | Small | Test quality |
| 34 | FileEpisodeTest not using @TempDir | Low | Small | Test quality |
| 35 | Real show names in test data | Low | Small | Policy |
| 36 | MoveTest global state pollution | Low | Small | Test quality |
| 37 | `size()==0` vs `isEmpty()` inconsistency | Low | Tiny | Style |
| 38 | Inline FQ class names instead of imports | Low | Tiny | Style |
| 39 | CheckboxField sort-value undocumented | Low | Tiny | Documentation |
| 40 | `applyPalette(Object,...)` type safety | Low | Tiny | Type safety |
