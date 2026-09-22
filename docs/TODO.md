# TVRenamer — Consolidated TODO Notes

Note: Completed work is tracked in `docs/Completed.md`. Keep this file focused on future work only.

This document consolidates "future work" notes from the codebase. Notes are grouped by impact area and prioritized by user value.

---

## User-Facing Features

### Headless CLI mode (automation/pipelines)
**Why:** Enables scripted usage without SWT/GUI — useful for NAS automation, batch processing, CI pipelines.
**Where:** New entry point (e.g., `org.tvrenamer.controller.CliMain`) + separation of UI vs core logic.
**Effort:** Medium/Large

### Allow pinning a show ID by extracted show name
**Context:** Today, disambiguation selections are stored as `query string -> series id`, and name overrides are stored as `extracted show -> override text`. A future enhancement would allow a direct "pin by name" rule that bypasses ambiguity even without crafting/maintaining a query string.
**Why it matters:** Provides a simpler, more robust advanced option for users who know the correct show and want to avoid repeated prompts even if normalization rules change.

- Potential shape:
  - `extracted show (or post-override show text) -> series id`
- Likely UI location:
  - unified "Show Matching Rules" editor alongside Overrides and Disambiguations

### Episode ordering dropdown (deferred)
**Context:** Investigated 2026-09-12: replacing the "Prefer DVD episode order" checkbox with a
dropdown of orderings. TheTVDB v4 defines seven season types (Aired, DVD, Absolute,
Alternate, Regional, Alternate DVD, Alternate Order 2), each selectable through the
`{season-type}` path segment `TheTVDBv4Provider` already uses, so fetching a different
ordering is a one-line change. TVMaze has per-show alternate lists
(`/shows/{id}/alternatelists`), each tagged by kind: DVD release, streaming premiere,
country premiere, broadcast premiere, language premiere, verbatim order.
**Why deferred:** Across 30 real v4 series, DVD existed for 17, Alternate for 1, and the
other three types for none. Streaming-service orders have no dedicated type, so one entered
under a generic type is unlabelled. An advertised type can come back empty, and an Alternate
order can be partial (one series: 32 aired episodes in 5 seasons versus 18 in 2), which the
current empty-list-only fallback to aired cannot handle. A 25-show TVMaze sample found only
country-premiere lists, which mostly change air dates, not numbering.
**If revisited:** Prefer a per-show ordering override to a global setting, since files for
different shows arrive in different orders; add a per-episode fallback for partial
orderings; and check what season numbers v4 returns for Absolute.
**Effort:** Medium (global dropdown) to Large (per-show override)

---

## Code Reliability & Maintenance

### Action button sometimes disabled while rows are matched and ready
**Reported 2026-09-22, not reproduced.** Rows had matched, their checkboxes were ticked,
and the action button showed no hover highlight and did nothing when clicked. No hover
highlight means the button was disabled, and a disabled SWT button fires no event, which
is consistent with none of `renameFiles()`'s skip-path log lines appearing.

**Leading hypothesis:** `ResultsTable.setActionButtonText` disables the button outright
whenever the move half is impossible (`prefs.isMoveEnabled()` false because
`destDirProblem` is set by `UserPreferences.ensureDestDir`), even when Rename is enabled
and renaming in place would still work. `destDirProblem` is set when
`FileUtilities.checkForCreatableDirectory` fails for the destination, and the destination
is re-validated on the preference-change path, so saving Preferences can disable the
button as a side effect. With a network destination that is intermittently unavailable
this would come and go. The tooltip that would explain it is set on a disabled control,
and Windows suppresses tooltips there, so the user sees no reason.

**Evidence so far:** two sessions captured with FINE logging did not reproduce it. In
both, destination validation passed and no move was ever started, so neither candidate
cause was exercised.

**Blocker for self-diagnosis:** `-Dtvrenamer.debug=true` cannot capture this today.
`logging.properties` pins the root logger at `INFO` and `ensureFileLoggingAttached`
deliberately does not raise it, so every `FINE` record is dropped before reaching the log
file. Capturing the evidence currently requires layering a patched logging config onto
the classpath. Fix this first, otherwise the next occurrence is unobservable again.

**Candidate fixes:** raise the log level when debug is enabled; degrade to rename-only
instead of disabling the button when rename is enabled but move is impossible; surface
the reason somewhere visible rather than in a suppressed tooltip; re-check the
destination when it becomes reachable again.
**Effort:** Small (logging) to Medium (button behaviour)

### Verify SWT-OS/SWT-Arch manifest workaround can be removed
**Context:** `build.gradle` injects `SWT-OS`/`SWT-Arch` manifest attributes into
both fat jars as a workaround for SWT's `isLoadable()` check (background in
`docs/Completed.md` #37). Upstream issue
[#2928](https://github.com/eclipse-platform/eclipse.platform.swt/issues/2928) was
closed 2026-06-01, and SWT 3.134.0 (released 2026-06-05) was expected to contain
the fix.

**Tested 2026-09-05 — the workaround is STILL REQUIRED.** Removing both
`manifest { attributes(swtManifestAttributes) }` blocks and rebuilding
(`clean build shadowJar createExe`) produces a jar whose manifest correctly lacks
the attributes, but the application then dies at startup, immediately after
"Creating UIStarter...", with:

    Libraries for platform win32 cannot be loaded because of incompatible environment

Verified against SWT 3.134.0, Shadow 9.6.1, Gradle 9.7.1. The workaround was
restored and the app confirmed working again. So either the upstream fix does not
cover the Shadow-repackaged case, or it still relies on these attributes being
present.

**Action:** Do NOT retry on the current SWT version — the answer is known. Re-test
only after the next SWT upgrade, using the same procedure: delete the two manifest
blocks and the `swtManifestAttributes` definition, rebuild, and actually launch the
jar/EXE (a green build proves nothing here; the failure is at runtime).
**Effort:** Small

### Episode DB path canonicalization — add tests
**Context:** The canonicalization itself has been implemented since this entry
was written: `EpisodeDb.canonicalizeKey(...)` defines the canonical form
(deliberately avoiding `toRealPath` — see its comment for the UNC/SMB
rationale), and `currentLocationOf(...)` migrates non-canonical legacy keys
and normalizes the stored key when two paths refer to the same file.
**Remaining:** No tests cover `currentLocationOf`/`canonicalizeKey` (verified
2026-07-09). Add unit tests for the key-migration and same-file-normalization
paths, with Windows-specific cases (case differences, mixed separators).
**Effort:** Small

### Parsing fallbacks and "should never happen" paths
**Context:** Parser code contains "this should never happen" style comments indicating areas where behavior could be tightened or more explicitly treated as errors.

- Source:
  - `org.tvrenamer.controller.FilenameParser` — comment noting a mismatch of expected matcher group counts "should never happen", but currently ignored.

**Potential follow-ups:**
- Add structured logging / telemetry for these "should never happen" cases.
- Add unit tests for unexpected matcher behavior.
- Consider turning the branch into a parse-failure with user-visible diagnostic.

### Clarify future listener semantics for show information
**Context:** There's commentary that if callbacks need to send additional information later, the listener interface and code paths should change.

- Source:
  - `org.tvrenamer.model.ShowStore` — comment around `mapStringToShow(...)` noting that in the future, if the listener expands to deliver more information later, current immediate-callback clauses would need to be updated.

**Potential follow-ups:**
- Define whether show mapping is strictly one-shot or can be incremental/async.
- If async, design a listener contract that supports partial updates and finalization.

### TheTVDB v4 provider follow-ups
**Context:** The switchable data-provider feature (`docs/Completed.md` #58, since
updated to TVMaze/TheTVDB v4 by #60) shipped with three items intentionally deferred
rather than gold-plated:

- **Diagnosability nudge for a degraded provider.** Today, a provider whose search
  returns empty for every query (the kind of silent index outage that motivated this
  feature originally) is only explained via a static help-page note
  (`troubleshooting.html`). Consider detecting the pattern in-app (e.g. N consecutive
  empty-search rows) and surfacing a one-time suggestion to try the other provider,
  instead of relying on the user to find the help page.
- **Pagination cap logging.** `TheTVDBv4Provider.fetchAll()` stops at `MAX_PAGES = 20`
  (`org.tvrenamer.controller.TheTVDBv4Provider`) as a safety cap. If a series genuinely has
  more than 20 pages of episodes, listings are silently truncated. Add a log line (and/or a
  user-visible note) when the cap is actually hit, rather than truncating without a trace.
- **Validate button race.** `PreferencesDialog.validateTvdbV4KeyOnline()` has no
  stale-result guard, unlike the mirrored `validateMatchingRowOnline()` (which stamps a
  per-row token). Rapid double-clicks with different keys pasted in between could show the
  first click's result after the second click's request also completes, if ordering is
  unlucky. Add a monotonic token (or disable the button while a validation is in flight) to
  match the existing pattern.

**Effort:** Small (each item independently)

### TVMaze rate limit on very large batches
**Context:** TVMaze (`docs/Completed.md` #60, the default provider) rate-limits
unauthenticated traffic to roughly 20 requests/10s. `TvMazeClient` retries a single
HTTP 429 once with a short backoff, which is enough for occasional bursts, but a very
large batch (hundreds of distinct shows added at once) could still exhaust the retry
and surface individual rows as failed lookups rather than transparently pacing
requests to stay under the limit.
**Action:** If large-batch users report failures, consider a request-pacing/queueing
layer in front of `TvMazeClient` (or a longer backoff/second retry) instead of the
current single-retry-then-fail behavior. Known limitation for now, not yet reported
as an actual problem.
**Effort:** Small/Medium

---

## Backlog suggestions / how to use this file

- Treat sections above as a backlog seed, not a mandate.
- Before implementing a TODO, confirm current behavior, add/expand tests where feasible, and validate on Windows (primary target).
- When completing a TODO, move it to `docs/Completed.md` with context about the implementation.
