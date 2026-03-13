# Code Review Round 3

Findings from a full codebase review (March 2026) using the expanded 18-category
checklist. Grouped by impact then effort. Excludes items already in Code-Review-260304.md
(deferred #12, #15, #32 carried forward separately).

**Status:** Complete. 23 of 30 items implemented; 7 closed as not-applicable (already mitigated or false positive: #13, #14, #15, #17, #20, #21, #27).

---

## Critical: Concurrency & Shutdown

### 1. Non-daemon thread in UpdateChecker blocks JVM shutdown
**File:** `UpdateChecker.java:119`
**Issue:** Version-check thread is not a daemon thread. If the HTTP request is slow
or hangs, JVM shutdown blocks indefinitely. Thread is also not tracked by
`Launcher.tvRenamerThreadShutdown()`.
**Fix:** Set `thread.setDaemon(true)` before start. Register with shutdown sequence.
**Impact:** High | **Effort:** Small

### 2. Non-daemon validation thread in PreferencesDialog
**File:** `PreferencesDialog.java:2033-2098`
**Issue:** Provider-matching validation thread is not a daemon thread and is not
cancelled on dialog close. Can block JVM shutdown and call syncExec/asyncExec
after the dialog is disposed.
**Fix:** Set daemon flag; consider using a managed ExecutorService with lifecycle.
**Impact:** High | **Effort:** Medium

### 3. asyncExec without disposal check from background thread
**File:** `ResultsTable.java:1939`
**Issue:** Background callback from UpdateChecker calls `display.asyncExec()` without
checking `display.isDisposed()`. If the update check completes after the user closes
the window, this throws SWTException.
**Fix:** Guard with `if (display != null && !display.isDisposed())` before asyncExec.
**Impact:** High | **Effort:** Small

### 4. Widget captured in lambda may outlive its lifecycle
**File:** `ResultsTable.java:1927-1939`
**Issue:** Local `updatesAvailableLink` widget is captured in a lambda that fires from
a background thread. If the dialog closes before the thread completes, calling
`setVisible()` on a disposed widget throws SWTException.
**Fix:** Check `updatesAvailableLink.isDisposed()` inside the lambda.
**Impact:** Medium | **Effort:** Small

### 5. Incomplete shutdown coordination
**File:** `Launcher.java:212-229`
**Issue:** `tvRenamerThreadShutdown()` does not shut down UpdateChecker or
PreferencesDialog validation threads. Only MoveRunner executor is managed.
**Fix:** Register all background threads/executors for orderly shutdown.
**Impact:** Medium | **Effort:** Medium

---

## High: Duplication — XML Utility Consolidation

### 6. Duplicated escapeXml() implementations
**Files:** `GlobalOverridesPersistence.java:156-165`, `UserPreferencesPersistence.java:293-302`
**Issue:** Both have identical private `escapeXml()` methods. `StringUtils.escapeXml()`
already exists as the public utility.
**Fix:** Delete both private copies; call `StringUtils.escapeXml()` instead.
**Impact:** Medium | **Effort:** Small

### 7. Duplicated parseStringMap() implementations
**Files:** `GlobalOverridesPersistence.java:134-154`, `UserPreferencesPersistence.java:238-258`
**Issue:** Identical private methods parsing XML container tags into `Map<String,String>`.
**Fix:** Extract to a shared utility (e.g., `XmlUtilities.parseStringMap()`).
**Impact:** Medium | **Effort:** Small

### 8. Duplicated XXE-hardened DocumentBuilder setup
**Files:** `GlobalOverridesPersistence.java:92-97`, `UserPreferencesPersistence.java:140-145`
**Issue:** Both inline the same XXE-hardened `DocumentBuilderFactory` initialisation.
`TheTVDBProvider.createDocumentBuilder()` already has this as a helper.
**Fix:** Extract shared `XmlUtilities.createDocumentBuilder()` used by all three classes.
**Impact:** Medium | **Effort:** Small

---

## Medium: Error Handling

### 9. Broad catch(Exception) masks bugs in FileMover
**File:** `FileMover.java:357, 390`
**Issue:** Two `catch (Exception e)` blocks catch all exceptions including unchecked
ones (OOM, StackOverflow). Metadata tagging and file-time reads should only throw
IOException.
**Fix:** Narrow to `catch (IOException e)` or the specific checked exceptions.
**Impact:** Medium | **Effort:** Small

### 10. Redundant catch ordering in FileMover
**File:** `FileMover.java:598, 606`
**Issue:** `catch (RuntimeException)` followed by `catch (Exception)` — the second
block is unreachable for RuntimeExceptions. Confusing control flow.
**Fix:** Consolidate into a single `catch (Exception e)` with differentiated handling
inside, or use specific types.
**Impact:** Medium | **Effort:** Small

### 11. HTTP error responses not differentiated
**File:** `HttpConnectionHandler.java:87-99`
**Issue:** All non-2xx/non-404 status codes (401, 403, 429, 500, 503) are logged
identically and treated the same. No distinction for auth errors, rate limits, or
server errors. Callers cannot react appropriately.
**Fix:** Log status code prominently; consider throwing typed exceptions or returning
a result object that includes the status.
**Impact:** Medium | **Effort:** Medium

---

## Medium: File I/O Correctness

### 12. Files.writeString() without explicit charset
**Files:** `UserPreferencesPersistence.java:105`, `GlobalOverridesPersistence.java:57`
**Issue:** `Files.writeString(path, xml)` uses platform default charset. On non-UTF-8
systems, the XML encoding declaration could mismatch the actual bytes.
**Fix:** Add `StandardCharsets.UTF_8` as third argument.
**Impact:** Medium | **Effort:** Small

### 13. Incomplete move-failure recovery in FileUtilities
**File:** `FileUtilities.java:277`
**Issue:** After `Files.move()` fails, recovery checks `Files.exists(srcFile) &&
Files.notExists(destFile)` but doesn't account for partial state where dest was
created but src not yet removed.
**Fix:** Also check if `actualDest != null && Files.exists(actualDest)` before
returning null.
**Impact:** Medium | **Effort:** Small

---

## Medium: Security — Command Argument Construction

### 14. MkvMetadataTagger command argument concatenation
**File:** `MkvMetadataTagger.java:262, 264`
**Issue:** File paths and segment titles embedded in ProcessBuilder arguments via
string concatenation (`"--set" + "title=" + value`). Special characters (colons,
equals) in filenames may cause mkvpropedit parsing issues.
**Fix:** Separate key=value into distinct arguments where the tool API allows it.
**Impact:** Medium | **Effort:** Small

### 15. Mp4MetadataTagger ffmpeg metadata argument concatenation
**File:** `Mp4MetadataTagger.java:214`
**Issue:** ffmpeg metadata constructed as `key + "=" + value`. Special characters
in metadata values (quotes, newlines) could be misinterpreted.
**Fix:** Use proper escaping or split into separate arguments per ffmpeg conventions.
**Impact:** Medium | **Effort:** Small

---

## Medium: Configuration Hygiene

### 16. Hardcoded API key in source
**File:** `TheTVDBProvider.java:40`
**Issue:** API key `"4A9560FF0B2670B2"` hardcoded in source. While it's a public API
key, this couples all deployments to one account with no override mechanism.
**Fix:** Extract to a constant in Constants.java or a properties file; document that
forks should supply their own key.
**Impact:** Medium | **Effort:** Small

### 17. No validation of UserPreferences interdependencies
**File:** `UserPreferences.java:103-130`
**Issue:** 14+ fields initialised with defaults but no cross-validation (e.g.,
`moveSelected=true` with invalid `destDir`). Invalid combos fail silently downstream.
**Fix:** Add a `validateState()` method called after loading; log warnings for
unsupported combinations.
**Impact:** Medium | **Effort:** Medium

### 18. No error handling for malformed preference values
**File:** `UserPreferencesPersistence.java:151-157`
**Issue:** Scalar fields parsed without type validation. If `processedFileCount`
contains non-numeric text, `Long.parseLong()` throws uncaught NumberFormatException.
**Fix:** Add try-catch around type conversions; apply safe defaults on parse failure.
**Impact:** Medium | **Effort:** Small

---

## Medium: Logging & Diagnosability

### 19. No success logging for HTTP downloads
**File:** `HttpConnectionHandler.java:72`
**Issue:** "Downloading URL" logged at FINE level on entry, but successful completion
is silent. Makes it hard to distinguish slow requests from silent successes in logs.
**Fix:** Log success with status code and elapsed time at FINE level.
**Impact:** Medium | **Effort:** Small

### 20. Generic logging for preference parse failures
**File:** `UserPreferencesPersistence.java:169-189`
**Issue:** Broad exception catch with generic fallback logging. If XML contains
invalid values (e.g., boolean "maybe"), the original error is lost and caller gets
null silently.
**Fix:** Add specific catch blocks for parse errors; log the original exception
message and include the problematic XML value.
**Impact:** Medium | **Effort:** Small

---

## Medium: Type Safety — Unchecked XML Casts

### 21. Unchecked casts from DOM NodeList/Node
**Files:** `XPathUtilities.java:49,54`, `GlobalOverridesPersistence.java:139,143`,
`UserPreferencesPersistence.java:222,243,247`
**Issue:** Seven unchecked casts from `Node` to `Element` and from XPath `evaluate()`
to `NodeList`/`Node`. Logically safe but generate compiler warnings and lack
`@SuppressWarnings` annotations.
**Fix:** Add `@SuppressWarnings("unchecked")` at method level, or extract a typed
helper method that centralises the cast and suppression.
**Impact:** Low | **Effort:** Small

---

## Low: Documentation Drift

### 22. README lists replaced dependencies
**File:** `README.md:137-141`
**Issue:** Lists SWT 3.129.0 (actual: 3.132.0), XStream 1.4.21 (removed), OkHttp
5.3.2 (removed), mp4parser 1.9.56 (removed).
**Fix:** Update to actual deps: SWT 3.132.0; note JDK built-in XML/HTTP APIs.
**Impact:** Low | **Effort:** Small

### 23. TODO.md lists replaced dependencies
**File:** `TODO.md:96-97`
**Issue:** Dependency table still includes XStream and OkHttp as active.
**Fix:** Remove or mark as "replaced by JDK built-in APIs".
**Impact:** Low | **Effort:** Tiny

### 24. Completed.md JUnit version mismatch
**File:** `Completed.md`
**Issue:** References JUnit "5.14.2" but actual version is 5.14.3.
**Fix:** Correct the version number.
**Impact:** Low | **Effort:** Tiny

### 25. README example uses a real show name
**File:** `README.md:8`
**Issue:** Example references a real TV show, contradicting CLAUDE.md policy.
**Fix:** Replace with a fictional show name.
**Impact:** Low | **Effort:** Tiny

---

## Low: Test Gaps — Real Show Names in Tests

### 26. FilenameParserTest contains real show names
**File:** `FilenameParserTest.java` (lines 288, 392, 404, 416, 610, 1017, 1032, 1425+)
**Issue:** Partially addressed by Code-Review-260304 #35 but many real show names remain.
**Fix:** Replace all with fictional equivalents preserving test characteristics
(punctuation, colons, special chars).
**Impact:** Low | **Effort:** Medium

### 27. StringUtilsTest contains real show names
**File:** `StringUtilsTest.java` (various)
**Issue:** Some test strings reference real show names in utility tests.
**Fix:** Replace with fictional names.
**Impact:** Low | **Effort:** Small

---

## Low: Test Gaps — Missing Test Classes

### 28. No tests for metadata tagging classes
**Files:** `MkvMetadataTagger.java`, `Mp4MetadataTagger.java`, `MetadataTaggingController.java`
**Issue:** Tagging logic (command construction, tool detection, fallback, error
handling) is entirely untested. These classes interact with external tools via
ProcessRunner, which is mockable.
**Fix:** Create test classes with mocked ProcessRunner covering success, missing
tool, invalid paths, and XML escaping scenarios.
**Impact:** Medium | **Effort:** Medium

### 29. No tests for UpdateChecker
**File:** `UpdateChecker.java`
**Issue:** Version parsing and update-availability logic untested.
**Fix:** Create UpdateCheckerTest with mocked HTTP responses.
**Impact:** Low | **Effort:** Small

### 30. Core model classes lack dedicated unit tests ✅
**Files:** `Series.java`, `Show.java`, `ShowName.java`
**Issue:** Only tested indirectly via FilenameParser and provider tests. Edge cases
(null seasons, missing episodes, name selection heuristics) not isolated.
**Fix:** Created `ShowTest.java` (19 tests), `SeriesTest.java` (12 tests), and
`ShowNameTest.java` (18 tests) — 49 total. Covers construction, episode management,
DVD/air ordering, download state machine, selection heuristics, failed show creation,
and edge cases (null season/episode, season 0 specials, duplicate IDs).
**Impact:** Low | **Effort:** Large

---

## Carried Forward (from Code-Review-260304)

These items remain deferred from the previous review:

- **#12** — Magic string coupling for "show selection required" UI state
- **#15** — O(n) table recolour triggered on every paint event
- **#32** — findDuplicateVideoFiles/deleteFiles lack test coverage
- **#35 partial** — Real show names in FilenameParserTest and StringUtilsTest
  (now tracked as #26 and #27 above)

---

## Naming note (CocoaUIEnhancer)

`CocoaUIEnhancer.java:46-47` has fields with trailing underscores
(`sel_preferencesMenuItemSelected_`). This is macOS JNI interop code and the naming
likely matches Objective-C selector conventions. Not flagged as an action item.

---

## Category coverage

| # | Category | Findings |
|---|----------|----------|
| 1 | Dead code | None found (clean) |
| 2 | Dead dependencies | None (all current) |
| 3 | Duplication | #6, #7, #8 |
| 4 | Naming & consistency | CocoaUIEnhancer (noted, no action) |
| 5 | Error handling | #9, #10, #11 |
| 6 | Security | #14, #15 |
| 7 | Type safety | #21 |
| 8 | Test gaps | #26, #27, #28, #29, #30 |
| 9 | Documentation drift | #22, #23, #24, #25 |
| 10 | Performance | None new (deferred #15 carried) |
| 11 | Robustness | #13 |
| 12 | Concurrency & UI thread safety | #1, #2, #3, #4, #5 |
| 13 | Resource lifecycle & disposal | Covered in #1-#5 |
| 14 | File I/O correctness | #12, #13 |
| 15 | API contract correctness | #11 |
| 16 | Configuration hygiene | #16, #17, #18 |
| 17 | Logging & diagnosability | #19, #20 |
| 18 | TODO/FIXME/HACK audit | None found (clean) |
