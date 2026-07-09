# Code Review Round 4 (July 2026)

Findings from a full codebase review using the 19-category checklist (updated
in commit `2deb057` — desktop threat model, Java type safety, external-tool
integration). First round to cover the subtitle-merge subsystem, which was
built entirely after Round 3 (~40 commits ago). Conducted as four parallel
slice reviews (external tools, view layer, core pipeline/network, build/docs)
and consolidated; findings independently reported by two slices are merged and
noted.

Excludes items already tracked in Code-Review-260210/260304/260313.

**Status:** Implementation in progress, following the phased order below.
- Phase 0 complete (2026-07-09): findings 4, 5, 33, 34, 35, 36, 38, 39, 40,
  41, 42 implemented. The #35 sweep found and fixed more instances than
  listed (EpisodeOptions, StringUtils, the ignore-keywords tooltip, and
  help/index.html). The #36 rewrite found the EpisodeDb canonicalization
  itself had already been implemented — only tests remain (tracked in
  TODO.md).
- Phase 1 complete (2026-07-09): findings 3, 9, 11, 16, 19, 25, 43, 47, 49
  implemented; pinning tests added for 9, 16, and 25 (suite: 350 → 356).
- Phase 2 complete (2026-07-09): findings 1, 2, 51 implemented in the
  Codex-mandated order (drain fix before timeout relaxation).  ProcessRunner
  drains on a dedicated thread with waitFor(timeout) as the primary wait;
  MoveRunner waits untimed (fixed 120 s deadline removed); the executor is
  per-run instead of a static JVM-wide singleton (MoveRunner.shutDown()
  removed, Launcher hook updated).  Suite: 356 → 359; the hung-tool pinning
  test completes in ~2 s vs 60 s pre-fix.
- Phase 3 complete (2026-07-09): findings 6, 7, 8, 10, 12, 13, 15, 17, 18,
  20, 21, 22, 23, 44, 45, 46, 48 implemented (17 findings, 7 commits).
  Notes: #8 deliberately hardens whole path components only (new
  `sanitisePathComponent` at the FileEpisode resolve sites), not
  `sanitiseTitle`, whose fragments legitimately produce empty results; #13
  is a startup warmup thread (the in-dialog "re-detect" action is tracked
  in TODO.md); #20's post-batch fix ticks all outcomes except
  NO_SUBTITLES_FOUND/UNSUPPORTED, which identify exactly the unpredicted
  (unpaired) candidates.  Suite: 359 → 364.
- Phase 4 complete (2026-07-09): findings 29, 30, 31, 32, 50 implemented
  (suite: 365 → 396).  Extractions: collectPostBatchCandidates (batch-scope
  contract pinned with real files), predictMergeUnits over PendingMove value
  tuples (ResultsTable delegates; its duplicate extension sets removed —
  partial #28), sourceSideGroupKey (the #50 language-tag boundary is now an
  explicit pinned test rather than an implicit property), both tool progress
  parsers (parseProgressPercent), plus WorkPlan tests, merger
  swap-exhaustion tests, tagger detection hooks, and controller-test
  isolation.
- Phase 5 complete (2026-07-09): findings 24, 26, 27, 28 (remainder), 37
  implemented.  New `util.DetectedTool` (shared lazy detection cache, all
  four tool classes adopted); `ProcessOps` promoted to `controller.util`
  and injected into the taggers; new `AbstractSubtitleMerger` owns the
  formerly-duplicated ~80-line merge skeleton; temp naming unified on
  `SubtitleSwap.computeTempPath` (`<base>.merging.<ext>`, legacy MKV shape
  still swept for one transition); extension helpers centralised in
  StringUtils; duplicate merge logging removed with the controller test
  pinning the non-duplication.  Suite stays at 396, all green.

**ROUND 4 COMPLETE: 51 of 51 findings resolved** (finding 5 by deleting the
stale documents; all others implemented across Phases 0-5).

---

## Summary table

| #  | Category | Description | Impact | Effort | Risk |
|----|----------|-------------|--------|--------|------|
| 1  | External tools | ProcessRunner timeout not enforced while draining output | High | Medium | Medium |
| 2  | Concurrency | 120 s future timeout kills long copies and the whole merge phase | High | Small | Medium |
| 3  | Robustness | Table sort silently drops named per-row data | High | Small | Low |
| 4  | Doc drift | CLAUDE.md JDK/Gradle/dependency table wrong in three ways | High | Small | Low |
| 5  | Doc drift | Subtitle Merge Spec described an architecture two redesigns old — **resolved: both docs deleted** | High | Small | Low |
| 6  | Concurrency | MoveRunner submits tasks before listener/WorkPlan are armed | Medium | Small | Low |
| 7  | File I/O | Non-atomic prefs/overrides writes; corruption silently resets settings | Medium | Small | Low |
| 8  | Security | Path sanitisation misses `..`, reserved device names, trailing dots | High | Small | Low |
| 9  | Error handling | Vanished source file fails with no specific reason or explicit state | Low | Small | Low |
| 10 | API contract | One 404 permanently latches "API discontinued"; no retry/backoff | Medium | Medium | Low |
| 11 | Robustness | Exception in end-of-batch steps wedges progress bar and action button | Medium | Small | Low |
| 12 | Concurrency | ListingsLookup uses an unbounded thread pool | Medium | Small | Low |
| 13 | Concurrency | Cold-cache tool detection spawns processes on the SWT UI thread | Medium | Medium | Low |
| 14 | External tools | MP4Box `-add` breaks on subtitle paths containing `#` or `:` | Medium | Small | Low |
| 15 | Error handling | Taggers report SUCCESS when the tagging tool is missing | Medium | Small | Low |
| 16 | Error handling | Language map asymmetry mislabels 3-letter-tagged subtitles as default | Medium | Small | Low |
| 17 | Resource lifecycle | TableEditor instances never disposed when controls are replaced | Medium | Small | Low |
| 18 | Resource lifecycle | Merge progress overlay leaks when a row disappears mid-merge | Medium | Small | Low |
| 19 | UX correctness | Post-batch merge downgrades COMPLETED rows to READY (auto-clear off) | Medium | Small | Low |
| 20 | Robustness | WorkPlan tick accounting drifts in both directions | Medium | Medium | Medium |
| 21 | Type safety | Raw-string `setData` keys repeated across call sites | Medium | Small | Low |
| 22 | Performance | Per-file synchronous prefs write on the UI thread during batches | Medium | Small | Low |
| 23 | Build/CI | Versioned fat jar never built in CI; upload glob matches nothing | Medium | Small | Low |
| 24 | Duplication | Four hand-rolled detection caches + duplicated merge scaffolding | Medium | Large | Medium |
| 25 | Error handling | MkvSubtitleMerger lacks the guards its MP4 twin has | Low | Small | Low |
| 26 | Concurrency | Redundant dual locking in Mp4SubtitleMerger.ensureDetected | Low | Small | Low |
| 27 | Naming | Temp-file naming schemes diverge between the two mergers | Low | Small | Low |
| 28 | Duplication | extOf/stripExt/extension-set duplicated across three classes | Low | Small | Low |
| 29 | Test gaps | Post-batch scope fix and source-side merge have zero coverage | Medium | Medium | Low |
| 30 | Test gaps | Progress parsers, runStreaming, swap-failure paths untested | Medium | Medium | Low |
| 31 | Test gaps | predictMergeUnits/WorkPlan extractable and untested | Medium | Medium | Low |
| 32 | Test gaps | Taggers lack reset hooks; test mutates UserPreferences singleton | Low | Small | Low |
| 33 | Doc drift | README dependency versions stale again (recurred since Round 3) | Medium | Small | Low |
| 34 | Doc drift | preferences.html missing the Subtitles group + several prefs | Medium | Small | Low |
| 35 | Policy | Real show names in comments; real release-group names in help | Medium | Small | Low |
| 36 | TODO audit | TODO.md cites an in-code marker that no longer exists | Low | Small | Low |
| 37 | Logging | Duplicate success INFO + leftover bracket-tagged FINE lines | Low | Small | Low |
| 38 | Dead code | FileUtilities.isWritableDirectory has zero callers | Low | Small | Low |
| 39 | Dead code | MergeOutcome.SKIPPED_ALREADY_PRESENT never produced | Low | Small | Low |
| 40 | Dead code | totalCandidates/currentIndex fields written but never read | Low | Small | Low |
| 41 | Doc drift | MkvSubtitleMergerTest javadoc describes a deleted test seam | Low | Small | Low |
| 42 | Config hygiene | `junit5` version key holds JUnit 6; stale settings.gradle comment | Low | Small | Low |
| 43 | Error handling | mtime-set failure marks a fully successful move as failed | Low | Small | Low |
| 44 | Performance | findItemByPath linear scan per merge progress tick | Low | Small | Low |
| 45 | Concurrency | finishAllMoves touches widgets without disposal guards | Low | Small | Low |
| 46 | Resource lifecycle | Dialog shell icon Image leaked per open; ownership comment wrong | Low | Small | Low |
| 47 | Robustness | renameFiles lacks the episodeMap null guard sibling paths have | Medium | Small | Low |
| 48 | Progress | Cosmetic bar jumps: merge future counted, already-in-place not ticked | Low | Small | Low |
| 49 | Correctness | Source-side merge picks first media in a group; delete-after-merge loses subs for the rest | Medium | Small | Low |
| 50 | Correctness | Source-side merge always uses default language; filename tags may never apply | Medium | Small | Low |
| 51 | Concurrency | Static shared single-thread executor: one stuck task stalls all later batches | Medium | Medium | Low |

---

## Critical / High

### 1. ProcessRunner timeout is not enforced while draining output
**File:** `ProcessRunner.java:84-104`
**Category:** External tool integration (#19)
The read loop (`while ((line = reader.readLine()) != null)`) runs to stream EOF
*before* `process.waitFor(timeoutSeconds, ...)` is reached. A tool that hangs
mid-run while keeping stdout open (corrupt input, lock wait) blocks
`readLine()` indefinitely — the computed 30–600 s timeouts from
`SubtitleSwap.computeTimeoutSeconds` never apply, and the
`finally { destroyForcibly() }` is unreachable. Undermines every timeout in
the subsystem, including `ExternalToolDetector`'s documented 5 s probe bound.
**Fix:** Drain output on a separate thread; make `waitFor(timeout)` the
primary wait, then `destroyForcibly()` and join the drain thread on expiry.
**Impact:** High | **Effort:** Medium | **Risk:** Medium

### 2. 120-second future timeout cancels legitimate long-running work
**Files:** `MoveRunner.java:42,115-126,492`
**Category:** Concurrency / config hygiene — *found independently by two slices*
`run()` awaits each future with `future.get(120s)` then `future.cancel(true)`.
A cross-filesystem copy of a large file to a NAS routinely exceeds 120 s — the
copy is interrupted (`copyWithUpdates` checks `isInterrupted()` and aborts),
the partial destination deleted, the move reported failed. Worse, the entire
source-side merge phase is a single future, so a batch of remuxes shares one
120 s budget while each individual tool run is allowed up to 600 s by
`SubtitleSwap.computeTimeoutSeconds` — the two timeout regimes contradict each
other, and cancellation can land mid-swap.
**Fix:** Exempt the merge future and copy tasks from the fixed timeout (or
scale the wait to the sum of per-file computed budgets) and rely on
`requestShutdown` for cancellation.
**Impact:** High | **Effort:** Small | **Risk:** Medium

### 3. Table sort silently drops named per-row data
**File:** `ResultsTable.java:1358-1387` (also rebuild loop 1441-1462)
**Category:** Robustness / correctness
`setSortedItem` copies check state, text, icon, and the unnamed control, but
none of the named keys: `EPISODE_DATA_KEY`, `SELECT_SHOW_PENDING_KEY`,
`"tvrenamer.moveCompleted"`, `"tvrenamer.pendingAutoClear"`. Since
`ensureTableSorted()` runs at the start of every rename batch, any row
repositioned by the sort loses its `FileEpisode` back-reference —
`findItemByPath` returns null (merge icons/overlays never appear), episode
chains and override retries skip the row, and a lost `moveCompleted` flag lets
an already-completed row be re-queued, re-incrementing the persistent
Processed counter.
**Fix:** Copy all named data keys in `setSortedItem`, or re-set
`EPISODE_DATA_KEY` from `episodeMap` in the post-sort rebuild loop.
**Impact:** High | **Effort:** Small | **Risk:** Low

### 4. CLAUDE.md build prerequisites and dependency table are wrong
**Files:** `CLAUDE.md` (Prerequisites, CI section, dependency table)
**Category:** Documentation drift
Says "JDK 17 (CI uses Temurin 17)" and "JDK 17 + Gradle 8.5" — the workflow
actually provisions Temurin **21** and the wrapper-driven Gradle **9.6.1**;
`build.gradle` enforces a JDK 21 toolchain with no auto-provisioning, so a
local JDK 21 is genuinely required. Every changed row of the dependency table
is stale (SWT 3.134.0, JUnit 6.1.1, Shadow 9.4.2, SpotBugs 6.5.8). This file
is the primary orientation document for cold-starting agents.
**Fix:** Correct JDK/Gradle statements; replace the hardcoded version table
with a pointer to `gradle/libs.versions.toml`.
**Impact:** High | **Effort:** Small | **Risk:** Low

### 5. Subtitle Merge Spec no longer describes the shipped architecture — RESOLVED
**Files:** `docs/Subtitle Merge Spec.md`, `docs/Subtitle Merge Plan.md` (both deleted)
**Category:** Documentation drift
The spec still described a single merge inside `FileMover.call()` before the
move, with FileMover deleting sibling subtitles — two redesigns behind the
shipped MoveRunner two-phase pipeline. Anyone implementing from the spec would
have rebuilt the wrong design.
**Resolution (2026-07-08, user decision):** both documents deleted rather than
updated — the implemented feature made them design-time artifacts, the code
plus `docs/Completed.md` #54/#55 are the source of truth, and the originals
remain in git history. Completed.md annotated accordingly.
**Impact:** High | **Effort:** Small | **Risk:** Low

---

## Medium: correctness and robustness

### 6. MoveRunner submits tasks before listener/WorkPlan are armed
**Files:** `MoveRunner.java:492-503`; `ResultsTable.java:1270-1311`
The constructor submits the merge task and movers to the live static
single-thread EXECUTOR; ResultsTable only calls `setSubtitleListener` and
`setWorkPlan` after construction. `runSourceSideMerge` reads both fields
(plain, non-volatile) from the executor thread — early tasks can run with a
null plan/listener (lost ticks, missing row status) and nothing guarantees the
later writes become visible.
**Fix:** Defer submission to `runThread()`, or pass plan/listener as
constructor arguments; at minimum make the fields volatile.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 7. Non-atomic prefs/overrides writes with silent-default recovery
**Files:** `UserPreferencesPersistence.java:112`, `GlobalOverridesPersistence.java:56`
`Files.writeString` truncates in place; a crash mid-write leaves a truncated
file. On next start `retrieve()` catches the parse error and returns null
("assume defaults"), and the next `store()` permanently overwrites the user's
settings with defaults.
**Fix:** Write to a sibling temp file and `Files.move(..., ATOMIC_MOVE,
REPLACE_EXISTING)`; optionally keep a `.bak` of the last good file.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 8. Path sanitisation misses `..`, reserved device names, trailing dots/spaces
**Files:** `StringUtils.java:18-30`, `Show.java:67`, `FileEpisode.java:838`, `UserPreferences.java:1128-1134`
*(Upgraded Medium → High on Codex second-opinion concurrence: the traversal
component is provider-controlled and writes outside the destination root.)*
`sanitiseTitle` replaces `\ / : | * ? < > "` only. `Show.dirName` comes from
the network response; a value of `..` survives and
`destPath.resolve(dirName)` escapes the destination root — provider-controlled
path traversal. Windows reserved names (`CON`, `NUL`, `COM1`…) and trailing
dots/spaces produce failing or silently-renamed directories; control chars
aren't stripped. `seasonPrefix` is not sanitised at all (user-supplied).
**Fix:** Extend sanitisation: reject/rewrite `.`/`..`, strip control chars and
trailing dots/spaces, prefix-mangle reserved device names.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 9. Vanished source file fails with no specific reason or explicit state
**File:** `FileMover.java:565-568`
`if (Files.notExists(srcPath)) { logger.info(...); return; }` — no
`setFailToMove()` is called. *(Corrected per Codex verification: the episode
still reads as a failure because `isSuccess()` returns
`currentPathMatchesTemplate`, which stays false — so the user does see a
generic failure, not a silent success.)* Remaining issue: the episode is never
explicitly marked failed and carries no reason, so the failure surfaces with
no diagnosis. Combines with source-side merge: if the subtitle delete succeeds
but `setConsumedByMerge(true)` is skipped by an exception, the subtitle's
mover lands exactly here.
**Fix:** Mark the episode failed with a distinct "source missing" reason.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 10. One 404 permanently latches "API discontinued"; no retry/backoff
**Files:** `TheTVDBProvider.java:209-221`, `HttpConnectionHandler.java:100-101`
Any 404 throws `FileNotFoundException`; `isApiDiscontinuedError` finds it in
the cause chain and sets the static `apiIsDeprecated = true` — never reset. A
single transient 404 (proxy, CDN hiccup) disables all lookups until restart
with a misleading diagnosis. No retry for 429/5xx anywhere.
**Fix:** Latch only on repeated/specific evidence (N consecutive 404s or a
404 on the API root); add one bounded retry with backoff for 429/5xx/IOException.
**Impact:** Medium | **Effort:** Medium | **Risk:** Low

### 11. Exception in end-of-batch steps wedges the UI
**File:** `MoveRunner.java:99-109,145-156`
`runPostBatchSubtitleMerge()`'s candidate-building phase (927-1005) is not
inside a catch; an unexpected throw propagates out of `run()` and the
`finally` only calls `updater.finish()` when `shutdownRequested` — the
progress bar never completes and the action button stays disabled.
**Fix:** Call `workPlan.completeAll()` and `updater.finish()` unconditionally
in the `finally`.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 12. ListingsLookup uses an unbounded thread pool
**File:** `ListingsLookup.java:27-33`
Round 2 bounded ShowStore's pool to 4; ListingsLookup still creates
`newCachedThreadPool` — a large folder triggers simultaneous listings fetches
per unique series (rate-limit exposure, arbitrary thread counts).
**Fix:** Small fixed pool consistent with ShowStore.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 13. Cold-cache tool detection runs on the SWT UI thread
**File:** `PreferencesDialog.java:773,903`
`getToolSummary()` / `isAnyToolAvailable()` trigger `ensureDetected()` →
`ExternalToolDetector.detect()` — up to two probe process spawns per tool —
synchronously on the UI thread the first time Preferences opens. Combined with
finding 1, a hung probe freezes the UI unboundedly. Also: no production
re-detect path — a user who installs MP4Box after the warning sees "not found"
until restart.
**Fix:** Detect on a background thread (startup or async dialog label); add a
"re-detect" action that invalidates the per-JVM caches.
**Impact:** Medium | **Effort:** Medium | **Risk:** Low

### 14. MP4Box `-add` breaks on subtitle paths containing `#` or `:`
**File:** `Mp4SubtitleMerger.java:326`
The subtitle path is concatenated raw into MP4Box's `:`-separated modifier
syntax where `#` is also a fragment separator. A filename containing `#`
(legal on Windows) or `:` (legal on POSIX) makes MP4Box misparse. Only the
track name is sanitised. The MKV side is immune (each option its own argv).
**Fix:** Detect `#`/`:` (beyond the drive colon) and skip with a WARNING, or
copy to a safe temp name before invoking.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 15. Taggers report SUCCESS when the tool is missing
**Files:** `Mp4MetadataTagger.java:66`, `MkvMetadataTagger.java:60`, `MetadataTaggingController.java:71-73`
Both return `true` on missing tool; the controller maps `true` →
`TaggingResult.SUCCESS`. Inconsistent with the subtitle subsystem's
`SKIPPED_NO_TOOL` and undiagnosable without FINE logs.
**Fix:** Add `NO_TOOL` to `TaggingResult` (or return an enum mirroring
`MergeOutcome`).
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 16. Language map asymmetry silently mislabels subtitle tracks
**File:** `SubtitleLanguages.java:128-222`; consumer `SubtitlePairing.java:182-184`
Nine 3-letter codes are produced as outputs but rejected as inputs (`ja` →
`jpn` works; a `.jpn.srt` file is unrecognised and falls back to the
*default* language) — a Japanese subtitle gets muxed tagged as English with no
warning. Same for `kor`, `bur`, `mac`, `alb`, `arm`, `baq`, `wel`, `ice`.
**Fix:** Self-map every code that appears as a map value.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 17. TableEditor instances never disposed when controls are replaced
**Files:** `ResultsTable.java:453-461` (creators: 390, 494, 1382)
`deleteItemControl` disposes the Control but not its TableEditor; every
`refreshDestinations()` and `sortTable()` leaks editors holding Table
listeners for the app lifetime.
**Fix:** Store the editor alongside its control and dispose both.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 18. Merge progress overlay leaks when a row disappears mid-merge
**File:** `ResultsTable.java:2704-2802`
`subtitleMergeFileFinished` early-returns on a disposed item *before*
disposing the overlay, and `subtitleMergeFinished` never sweeps the map. Row
deletion / Clear List / auto-clear during a merge leaves the Label (child of
the Table, not the item) painted at its last position forever.
**Fix:** Tear down the overlay before the item-null check; sweep leftovers in
`subtitleMergeFinished`.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 19. Post-batch merge downgrades COMPLETED rows to READY when auto-clear is off
**File:** `ResultsTable.java:2806-2818`
The `default ->` branch sets READY for no-op merge results, justified by the
`pendingAutoClear` timer — which only exists when `deleteRowAfterMove` is on.
With auto-clear off, every moved file with no paired subtitles permanently
reverts from COMPLETED to the pre-pipeline green circle (and flashes MERGING +
"0%" for nothing).
**Fix:** Restore COMPLETED when `"tvrenamer.moveCompleted"` is TRUE; READY
only in the source-side (pre-move) context.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 20. WorkPlan tick accounting drifts in both directions
**Files:** `MoveRunner.java:1090,1017`, `FileMover.java:406-408`, `ResultsTable.java:2581`
Over-tick: the post-batch loop ticks per *candidate* while `predictMergeUnits`
counts only *paired* media — with unpaired media the bar clamps at 100% while
merges still run. *(Contested by Codex second opinion and re-verified: the
over-tick claim stands — candidate Case A at MoveRunner.java:941-954 adds
every moved container with no pairing check, and the tick at 1088-1092 fires
regardless of outcome; Codex had only examined Case B.)* Under-tick: tag ops
tick only on SUCCESS/FAILED and a failed move never ticks, with no retract —
the bar stalls until `completeAll()` snaps it. The reconcile retract compares
against the inflated candidate count so it almost never fires.
**Fix:** Tick only paired candidates (or retract unpaired at reconcile);
tick/retract skipped-tag and failed-move units.
**Impact:** Medium | **Effort:** Medium | **Risk:** Medium

### 21. Raw-string `setData` keys repeated across call sites
**Files:** `ResultsTable.java` ×14 call sites, `PreferencesDialog.java` ×3
`"tvrenamer.moveCompleted"` ×5, `"tvrenamer.pendingAutoClear"` ×5,
`"tvrenamer.clearCompletedButton"` ×2, `"tvrenamer.processedLabel"` ×2,
`"tvrenamer.matching.validationMessage"` ×3. A typo silently breaks Clear
Completed or auto-clear. `EPISODE_DATA_KEY` models the right pattern in the
same file.
**Fix:** Promote all five to `private static final String` constants.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 22. Per-file synchronous prefs write on the UI thread during batches
**File:** `ResultsTable.java:1942-1943`
`finishMove` (UI thread) runs `incrementProcessedFileCount(1);
UserPreferences.store(prefs)` per successful file — 500 files = 500 disk
writes interleaved with paint events, plus an O(rows) button-state scan each.
**Fix:** Accumulate during the batch; persist once in `finishAllMoves`.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 23. Versioned fat jar never built in CI; upload glob matches nothing
**Files:** `build.gradle:247,307-309`, `.github/workflows/windows-build.yml:45`
`shadowJarVersioned` is wired to nothing (the comment claiming it runs "via
createExe" is wrong); CI's `build/libs/tvrenamer-*.jar` glob silently matches
nothing — confirmed against the latest run artifact. The release procedure
assumes CI provides the versioned jar, which it can't.
**Fix:** Add `shadowJarVersioned` to the CI invocation (or hook it to
`createExe`); fix the stale comment.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

---

## Duplication & consolidation

### 24. Four hand-rolled detection caches + duplicated merge scaffolding
**Files:** `Mp4SubtitleMerger`, `MkvSubtitleMerger`, `Mp4MetadataTagger`, `MkvMetadataTagger`
Each implements its own static volatile cache + lock + double-checked
`ensureDetected()` in three different idioms. The two mergers duplicate
`tailOf`, `deleteQuietly`, and the entire ~80-line merge body (temp → size →
timeout → run → fail-log+delete → gate → swap). MKVToolNix Program Files
paths are hard-coded twice. `ProcessOps` is package-private to the subtitle
package, so the taggers can't reuse the injection seam and remain untestable.
**Fix:** Shared `DetectedTool` cache utility in `controller.util`; promote
`ProcessOps` there; extract the merge skeleton. Enables findings 25-27, 30, 32.
**Impact:** Medium | **Effort:** Large | **Risk:** Medium

### 25. MkvSubtitleMerger lacks the guards its MP4 twin has
**File:** `MkvSubtitleMerger.java:208,252-254`
No `mediaFile == null` check (NPE at `getFileName()`) and no
try/catch-RuntimeException around the process invocation (a throwing op leaks
the temp file and propagates). Mp4SubtitleMerger has both.
**Fix:** Mirror the MP4 guards.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 26. Redundant dual locking in Mp4SubtitleMerger.ensureDetected
**File:** `Mp4SubtitleMerger.java:387-391`
`static synchronized` method + inner `synchronized (DETECTION_LOCK)` — every
post-detection read acquires the class monitor, defeating the volatile fast
path; two monitors guard the same fields. The MKV classes implement the
pattern correctly.
**Fix:** Drop the method-level `synchronized` (folds into finding 24).
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 27. Temp-file naming schemes diverge between the two mergers
**Files:** `Mp4SubtitleMerger.java:350-357` (`<base>.merging.<ext>`) vs `MkvSubtitleMerger.java:211-213` (`<full-name>.merging.<ext>`)
*Found independently by two slices.* The controller's stale-temp cleanup must
enumerate both shapes; the help page documents only the MKV form.
**Fix:** One scheme in a shared helper (part of finding 24).
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 28. extOf/stripExt/extension-set duplicated across three classes
**Files:** `ResultsTable.java:2506-2545`, `MoveRunner.java:594-595,816-835`, `SubtitlePairing.SUPPORTED_EXTENSIONS`
Verbatim helper duplication, plus the pairing predicate in
`predictMergeUnits` re-implements pairing rules MoveRunner/SubtitlePairing
own — drift here feeds directly into finding 20.
**Fix:** Move helpers to `StringUtils`/`FileUtilities`; share one extension
constant.
**Impact:** Low | **Effort:** Small | **Risk:** Low

---

## Test gaps

### 29. Post-batch scope fix and source-side merge have zero coverage
**Files:** `MoveRunner.java:612-813,899-1110`; `MoveTest.java`
Nothing pins the strictly-batch-scope behaviour that fixed the user-trust bug
(a7de9d8), nor source-side grouping, `consumedByMerge` short-circuit, the
skip-set, copy-and-delete, or overwrite mode. No WorkPlanTest exists.
**Fix:** Extract candidate selection into a testable package-private method;
add pinning tests; add WorkPlan unit tests.
**Impact:** Medium | **Effort:** Medium | **Risk:** Low

### 30. Progress parsers, runStreaming, and swap-failure paths untested
**Files:** `Mp4SubtitleMerger.java:243-263`, `MkvSubtitleMerger.java:230-247`, `ProcessRunnerTest`
Both tool progress parsers are inline lambdas; both test fakes discard
`onLine`, so no test exercises them — exactly the "parsing resilience to tool
drift" risk. `runStreaming`, the timeout path, and swap-exhaustion branches
are also uncovered.
**Fix:** Extract parsers to package-private static methods with direct tests;
add runStreaming/timeout tests.
**Impact:** Medium | **Effort:** Medium | **Risk:** Low

### 31. predictMergeUnits and WorkPlan are extractable and untested
**Files:** `ResultsTable.java:2554-2649`, `model/WorkPlan.java`
Near-pure logic encoding the pairing rules the progress bar depends on — the
finding-20 drift would have been caught by a unit test. The view package has
zero tests.
**Fix:** Extract prediction over plain value tuples; unit-test it and WorkPlan.
**Impact:** Medium | **Effort:** Medium | **Risk:** Low

### 32. Taggers lack test-reset hooks; controller test mutates the prefs singleton
**Files:** `Mp4MetadataTagger`, `MkvMetadataTagger`, `MetadataTaggingControllerTest.java:26-59`
The controller test spawns real PATH probes (cached for the JVM) and sets
`UserPreferences.getInstance().setTagVideoMetadata(...)` with no restore —
order-dependence across the suite. SubtitleMergeControllerTest models the
right isolation pattern.
**Fix:** Add reset/set hooks (or the finding-24 shared cache); restore prefs
in teardown.
**Impact:** Low | **Effort:** Small | **Risk:** Low

---

## Docs, hygiene, dead code

### 33. README dependency versions stale again (recurring)
**File:** `README.md:146-162`
Same drift class as Round 3 #22, recurred within four months. Hardcoded
version lists are structurally unmaintainable here.
**Fix:** Replace with a pointer to `gradle/libs.versions.toml`.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 34. preferences.html missing the Subtitles group and several other prefs
**File:** `src/main/resources/help/preferences.html`
No mention of the three subtitle-merge preferences; also omits Season Prefix,
Check for updates, Recursive add, Remove emptied dirs, Preserve mtime, Delete
row after move. (subtitle-merge.html itself is accurate and current.)
**Fix:** Add a Subtitles section (cross-link subtitle-merge.html) + one-liners
for the rest.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 35. Real show names in comments; real release-group names in help
**Files:** `MoveRunner.java:237-238`, `Series.java:25-26`, `ShowStore.java:20-104`, `help/preferences.html:134-135`
*Found independently by two slices.* Two main-source comments and a class
javadoc use real show names; the help page's ignore-keywords example uses real
piracy release-group names — at odds with the "legitimately owned media"
posture. Real media-player names (compatibility docs) are arguably a
legitimate exception but the rule as written forbids them.
**Fix:** Fictional names in comments; neutral tokens (`sample,proof,extras`)
in help; document an explicit player-name exception in CLAUDE.md.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 36. TODO.md cites an in-code marker that no longer exists
**File:** `docs/TODO.md:43-49`
The EpisodeDb "Though, maybe we should? TODO" comment is gone from the code
(src/ is otherwise clean of TODO/FIXME/HACK). The backlog item may stand; the
reference doesn't.
**Fix:** Refresh the source reference.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 37. Duplicate success INFO + leftover bracket-tagged FINE lines
**Files:** `Mp4SubtitleMerger.java:309`, `MkvSubtitleMerger.java:289`, `SubtitleMergeController.java:235`, `MoveRunner.java:728,754,1040-1052`
"Merged N track(s)" logged at INFO twice per file on the post-batch path;
`[SOURCE-SIDE]`/`[POST-BATCH]` FINE lines log the same event 2-3 times.
**Fix:** Drop the controller-level INFO; collapse the FINE duplicates.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 38. Dead code: `FileUtilities.isWritableDirectory`
**File:** `FileUtilities.java:517` — zero callers repo-wide.
**Fix:** Delete.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 39. Dead code: `MergeOutcome.SKIPPED_ALREADY_PRESENT` never produced
**File:** `SubtitleMerger.java:74`
Idempotency skips are controller-side; both mapping arms are unreachable.
**Fix:** Remove the constant and its switch arms.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 40. Dead fields: `totalCandidates`/`currentIndex` in TableSubtitleMergeListener
**File:** `ResultsTable.java:2696-2697,2717-2718,2786`
Written (unsynchronized, from the worker thread) but never read — pre-WorkPlan
leftovers.
**Fix:** Delete.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 41. MkvSubtitleMergerTest javadoc describes a deleted test seam
**File:** `MkvSubtitleMergerTest.java:28-48`
Describes a subclass-override `runProcess` mechanism that no longer exists
(replaced by ProcessOps injection) and makes an incorrect parallel-safety
claim.
**Fix:** Rewrite to describe the ProcessOps seam.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 42. `junit5` version key holds JUnit 6; stale settings.gradle comment
**Files:** `gradle/libs.versions.toml:11`, `settings.gradle` header
`junit5 = "6.1.1"` / alias `junit5Jupiter` mislead post-migration.
settings.gradle claims a `dependencyResolutionManagement` block it doesn't
contain.
**Fix:** Rename key/alias to `junit`/`junitJupiter` (+ `--write-locks`); trim
the comment.
**Impact:** Low | **Effort:** Small | **Risk:** Low

---

## Low / cosmetic

### 43. mtime-set failure marks a fully successful move as failed
**File:** `FileMover.java:335-344`
`setLastModifiedTime` IOException → `setFailToMove()` even though the file is
at its destination; the row shows failure, and
`getActualDestinationIfSuccess()` returns null, excluding the file from
post-batch merge and duplicate filtering. Common on SMB shares that reject
attribute writes.
**Fix:** Warn but keep success (or a distinct "moved, attributes not set"
state).
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 44. findItemByPath linear scan per merge progress tick
**File:** `ResultsTable.java:2657-2674`
Per percentage point per file, an asyncExec scans all TableItems — O(100 ×
files × rows) marshalled to the UI thread per batch.
**Fix:** Resolve the item once in `subtitleMergeFileStarted`; cache Path →
TableItem until `subtitleMergeFinished`.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 45. finishAllMoves touches widgets without disposal guards
**File:** `ResultsTable.java:1809-1838` (also `finishMove`:1947)
Runs via asyncExec queued from the worker (including the shutdown path);
`actionButton.setEnabled`, `shell.getData`, dialog opens — none guarded by
`shell.isDisposed()`. Close-mid-move can throw SWTException.
**Fix:** Early-return when `shell.isDisposed()`.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 46. Dialog shell icon Image leaked per open
**Files:** `BatchShowDisambiguationDialog.java:113-116`, `DuplicateCleanupDialog.java:116`
Fresh Image per `open()`; the "Shell takes ownership" comment is wrong —
`Shell.setImage` never disposes. One GDI handle per dialog open.
**Fix:** Dispose in the existing SWT.Dispose listener, or share one cached
app-icon Image.
**Impact:** Low | **Effort:** Small | **Risk:** Low

### 47. renameFiles lacks the episodeMap null guard sibling paths have
**File:** `ResultsTable.java:1200-1202`
`episodeMap.get(fileName)` unguarded (siblings `refreshDestinations`,
`markAllSelectShowPending` guard); an externally moved file NPEs
`episode.optionCount()` inside the action-button handler on the UI thread,
aborting the entire rename action. *(Upgraded Low → Medium on Codex
concurrence — the blast radius is the whole batch, not one row.)*
**Fix:** Add the null guard.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 48. Cosmetic progress-bar jumps at batch start and for in-place files
**Files:** `MoveRunner.java:89-91`, `FileMover.java:626-634`
`remaining = numMoves + 1` on the first pass (merge future in queue but not in
`numMoves`); the already-in-place path tags but never ticks its budgeted move
unit. Both masked by clamping.
**Fix:** Exclude the merge future from the count; tick the in-place path.
**Impact:** Low | **Effort:** Small | **Risk:** Low

---

## Codex second-opinion additions (49-51)

### 49. Source-side merge picks the first media in a group; delete-after-merge loses subtitles for the rest
**File:** `MoveRunner.java:640-657,796-809`
When multiple media movers share a grouping key `(destDir, canonicalBaseName)`
— e.g. two qualities of the same episode resolving to the same desired name —
the code comments "we merge into the first" and proceeds. With
delete-after-merge on, the sibling subtitles are then deleted and their movers
marked consumed, so the *other* media files in the group permanently lose
their chance at those subtitles. A multi-media group is also a destination
conflict in its own right; merging into an arbitrary member compounds it.
**Fix:** Skip source-side merge (fall through to post-batch) when a group
contains more than one media file, and log why.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 50. Source-side merge always uses the default language; filename tags may never apply
**File:** `MoveRunner.java:671-694,765-772`
Source-side builds every `SubtitleEntry` with the default language; the
comment defers tag parsing to the post-batch path — but on SUCCESS the
destination joins the skip-set, so post-batch never re-examines that file.
Whether real tag loss occurs depends on grouping: a subtitle whose *desired
dest name* carries a language tag (`<base>.en.srt`) has a different canonical
base than its media and won't group source-side (post-batch handles it with
tag parsing intact); the exposure is subtitles whose dest name is bare but
whose *source* name carried a tag that the rename dropped. Needs a pinning
test to establish intended behaviour either way (ties into finding 29).
**Fix:** Parse language tags from the subtitle's source filename in the
source-side path (reusing `SubtitlePairing`'s token parser), or document that
dest-name tags are the only supported tagging channel.
**Impact:** Medium | **Effort:** Small | **Risk:** Low

### 51. Static shared single-thread executor: one stuck task stalls all later batches
**File:** `MoveRunner.java:46-47,492`
The EXECUTOR is a static JVM-wide single thread shared by every MoveRunner
across the app's lifetime. Combined with finding 1 (unbounded drain hang), a
single stuck tool invocation permanently wedges not just the current batch
but every subsequent rename the user attempts until restart — the cancel in
`run()` interrupts the future but a thread blocked in `readLine()` on a live
pipe does not respond to interrupts.
**Fix:** Per-run executor (created in the constructor, shut down in `run()`'s
finally), or a watchdog that replaces the worker thread on cancellation
timeout. Sequence after finding 1.
**Impact:** Medium | **Effort:** Medium | **Risk:** Low

---

## Verified clean (non-findings worth recording)

- **XXE/doctype hardening is complete and correct** — `XmlUtilities.createDocumentBuilder`
  disables doctypes and external entities, and all four parse sites (provider,
  both persistence classes) use it.
- `sourceMergedDestinations` is per-MoveRunner-instance (no cross-run leak);
  paths normalised on both insert and lookup.
- `consumedByMerge` volatile handoff is sound given FIFO single-thread executor.
- `EpisodeDb` key canonicalisation is consistent.
- Worker→UI thread hops in the view layer are consistently guarded (Round 3
  fixes verified in place); WorkPlan itself is a clean thread-safe primitive.
- Type safety in the tool subsystem is clean: no raw types, no unchecked casts.
- gradle.lockfile consistent with the catalog; no dead dependencies; workflow
  permissions minimal and actions current.
- docs/Completed.md numbering intact (1-55, no gaps); Tagging Spec matches the
  implementation; subtitle-merge.html is accurate.
- Zero TODO/FIXME/HACK markers in src/.
- All ItemState constants are live after the icon rework.

## Out-of-scope items for TODO.md

- Re-detect action for external tools (part of finding 13's fix, but the UI
  affordance is a small feature, not a defect).
- ResultsTable decomposition: at ~2,860 lines it owns rendering, sorting,
  progress prediction, overlay lifecycle, and disambiguation orchestration.
  Extraction (findings 21, 28, 31) nibbles at this; a deliberate split is a
  larger refactor for a future round.
- SWT-OS/SWT-Arch manifest workaround removal (already in TODO.md; upstream
  fix shipped).

## Suggested implementation order

*(Revised after Codex second opinion: #1 must land before or with #2 —
relaxing the 120 s backstop while ProcessRunner's drain hang is unfixed makes
hung tools block indefinitely. #15 must land with, or after, #20 — mapping
missing-tool to NO_TOOL changes which tag outcomes tick, regressing the bar if
the accounting fix hasn't landed.)*

1. **Process-lifecycle pair, in this order:** 1 (ProcessRunner drain thread),
   then 2 (120 s timeout policy), then 51 (executor blast radius)
2. **Correctness, small:** 3 (sort data loss), 11 (finally), 19 (COMPLETED
   downgrade), 9 (vanished-source reason), 16 (language map), 25 (MKV guards),
   43 (mtime), 47 (null guard), 49 (multi-media group guard)
3. **Docs batch:** 4, 33, 34, 35, 36, 41, 42 (all Small; 5 already resolved)
4. **Leaks/perf batch:** 17, 18, 22, 44, 45, 46, 21
5. **Robustness batch:** 6, 7, 8, 10, 12, 13, 23, 48, then 20 + 15 together
   (tick accounting and NO_TOOL mapping are coupled)
6. **Tests:** 29, 30, 31, 32, 50 (after the code they pin has stabilised)
7. **Consolidation (discuss scope first):** 24 + folded 26, 27; then 28, 37,
   38, 39, 40

## Codex second-opinion record

The completed document was passed to OpenAI Codex (GPT-5.3) for adversarial
verification against the code. Disposition of its nine points:

- **Accepted:** finding 9 corrected and downgraded (vanished source reads as
  failure via `isSuccess()`, not silent success); finding 8 upgraded to High;
  finding 47 upgraded to Medium; new findings 49-51 added; two
  implementation-order corrections applied (1 before 2; 15 coupled with 20).
- **Rejected after re-verification:** Codex's claim that finding 20's
  over-tick was wrong — it had only examined candidate Case B; Case A
  (MoveRunner.java:941-954) adds every moved container unpaired, and the tick
  at 1088-1092 fires regardless of outcome.
