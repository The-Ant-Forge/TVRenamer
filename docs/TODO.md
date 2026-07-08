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

---

## Code Reliability & Maintenance

### Verify SWT-OS/SWT-Arch manifest workaround can be removed
**Context:** `build.gradle`'s `shadowJar` block injects `SWT-OS`/`SWT-Arch` manifest
attributes as a workaround for SWT's `isLoadable()` check (background in
`docs/Completed.md` #37). Upstream issue
[#2928](https://github.com/eclipse-platform/eclipse.platform.swt/issues/2928) was
closed 2026-06-01, and we now ship SWT 3.134.0 (released 2026-06-05) which likely
contains the fix.
**Action:** Remove the two manifest attributes from `build.gradle`, run
`./gradlew clean build shadowJar createExe`, and launch the EXE. If SWT loads its
native libraries without them, delete the workaround for good; if not, restore and
re-check on the next SWT bump.
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

---

## Backlog suggestions / how to use this file

- Treat sections above as a backlog seed, not a mandate.
- Before implementing a TODO, confirm current behavior, add/expand tests where feasible, and validate on Windows (primary target).
- When completing a TODO, move it to `docs/Completed.md` with context about the implementation.
