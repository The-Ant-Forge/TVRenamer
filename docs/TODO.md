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

### SWT upgrade guardrail: monitor upstream fat-JAR manifest fix
**Status:** Resolved — upgraded to SWT 3.132.0 (see `docs/Completed.md` #37).

The root cause was commit `360a2702a7` (SWT PR #2054) adding a mandatory `isLoadable()` check
that reads `SWT-OS`/`SWT-Arch` from the JAR manifest — attributes lost when Shadow merges JARs.
Workaround: inject those attributes in `build.gradle` `shadowJar` manifest.

**Remaining:** Monitor upstream issue [#2928](https://github.com/eclipse-platform/eclipse.platform.swt/issues/2928)
for a proper fix (treating missing manifest as allowed). Once fixed, the manifest attributes
in `build.gradle` can be removed — they're harmless but unnecessary after that.

### Episode DB path canonicalization
**Context:** EpisodeDb can detect two strings refer to the same file; it currently chooses not to update the stored key/path even if it knows they match.
**Why it matters:** Normalizing paths can reduce confusion and improve deduplication, but may have pitfalls on Windows/network shares.

- Source:
  - `org.tvrenamer.model.EpisodeDb` — `currentLocationOf(...)`
  - Note: "Though, maybe we should? TODO"

**Potential follow-ups:**
- Decide a consistent canonical form for paths (absolute+normalized vs real path)
- Be careful with UNC/SMB edge cases where "real path" may fail or be slow
- Add tests for path normalization behavior on Windows

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

## Test Suite Improvements

### Document/standardize temp-dir cleanup expectations
**Context:** The test suite places emphasis on cleaning up temp directories and leaving the environment as it was found, with notes about doing a "best-effort rm -rf" style cleanup when teardown fails.

- Source:
  - `org.tvrenamer.model.FileEpisodeTest` — teardown/cleanup commentary.

**Potential follow-ups:**
- Centralize temp-dir creation and teardown helpers so cleanup is consistent across tests.
- Make cleanup more failure-tolerant while still surfacing root-cause failures (e.g., log what couldn't be deleted and why).
- Ensure cleanup behavior is robust on Windows where file-locking is common.

---

## Dependency Status (March 2026)

| Dependency | Version | Latest Available | Status |
|------------|---------|------------------|--------|
| SWT | 3.132.0 | 3.132.0 | ✅ Latest (fat-JAR workaround applied) |
| XStream | 1.4.21 | 1.4.21 | ✅ Latest |
| OkHttp | 5.3.2 | 5.3.2 | ✅ Latest |
| AtomicParsley / ffmpeg | external | — | External tool for MP4 tagging (replaces mp4parser) |
| JUnit | 5.14.2 | 5.14.2 | ✅ Latest (JUnit 5/Jupiter) |
| Gradle | 9.3.1 | 9.3.1 | ✅ Latest |
| Shadow Plugin | 9.3.1 | 9.3.1 | ✅ Latest |
| Launch4j Plugin | 4.0.0 | 4.0.0 | ✅ Latest |
| SpotBugs Plugin | 6.4.8 | 6.4.8 | ✅ Latest |

---

## Backlog suggestions / how to use this file

- Treat sections above as a backlog seed, not a mandate.
- Before implementing a TODO, confirm current behavior, add/expand tests where feasible, and validate on Windows (primary target).
- When completing a TODO, move it to `docs/Completed.md` with context about the implementation.
