# Subtitle Merge — Implementation Plan

Companion document to [`Subtitle Merge Spec.md`](Subtitle%20Merge%20Spec.md). The spec describes *what* we're building; this plan describes *how* and *in what order*.

The plan is structured as **6 phases** ordered to land an end-to-end working feature with test coverage at every step. Each phase is shippable on its own — even before the UI lands, the merge runs from preferences via XML.

---

## Reference points in the existing codebase

| Existing pattern | Use as model for |
|------------------|------------------|
| `MetadataTaggingController` | `SubtitleMergeController` shape (controller + per-format strategies + result enum) |
| `Mp4MetadataTagger`, `MkvMetadataTagger` | per-format mergers (tool detection cache, supported-extensions set, runProcess pattern) |
| `ExternalToolDetector` | tool discovery on PATH (already used for AtomicParsley/ffmpeg/mkvpropedit; reuse) |
| `ProcessRunner` | uniform process spawn + timeout + output capture (reuse) |
| `ReplacementToken` enum | `SubtitleLanguages` constants pattern (centralised list, shared across consumers) |
| `UserPreferences` + `UserPreferencesPersistence` | adding three new persisted fields with reader hooks |
| `PreferencesDialog` General tab "Tag video metadata" group | UI placement and combo wiring for the new "Subtitles" group |
| `FileMover.tagFileIfEnabled(actualDest)` (line 305) | hookup pattern, **but reversed**: subtitle merge runs on the source *before* the move (per spec §"Where this hooks into the pipeline"), not on the destination after |

---

## Phase 1 — Pure logic: `SubtitleLanguages` + `SubtitlePairing`

**Why first:** Pure functions, no I/O beyond `Files.list`, no SWT, no external processes. Fully unit-testable. Everything else depends on this.

### Files to create

1. `src/main/java/org/tvrenamer/controller/subtitle/SubtitleLanguages.java`
   - Public record/class `Language(String code3, String displayName)`.
   - Public `List<Language> ALL` — the 30-entry dropdown catalogue from the spec, in the order they appear there (English first as default).
   - `Optional<Language> findByCode3(String code3)` — case-insensitive lookup.
   - `Optional<String> normalizeFilenameTag(String tag)` — accepts 2-letter ISO-639-1 (`en`), 3-letter B-form (`eng`), 3-letter T-form (`fra`), full English name (`english`), and BCP-47 with region (`en-US`), returns 3-letter B-form lowercase. Backed by a small lookup table; unknown input → `Optional.empty()`.
   - `Language DEFAULT = ALL.get(0);` — English.
   - **Important:** B-form vs T-form coercion table covers the ~17 languages with two codes (fre↔fra, ger↔deu, chi↔zho, dut↔nld, gre↔ell, cze↔ces, rum↔ron, …). Either form decodes; we always emit B-form.

2. `src/main/java/org/tvrenamer/controller/subtitle/SubtitlePairing.java`
   - Public record `SubtitleEntry(Path file, String langCode3, String trackName, EnumSet<Descriptor> descriptors)`.
   - Public enum `Descriptor` — `SDH`, `FORCED`, `COMMENTARY`, `SIGNS`, `SONGS`, `DUB`. (Hearing-impaired collapses into `SDH`; CC, HI, hearingimpaired all map to it.)
   - Static `List<SubtitleEntry> findFor(Path mediaFile, String defaultLangCode3)` — the pairing entry point.
     1. Compute base name from `mediaFile.getFileName()` minus the final extension.
     2. List sibling files via `Files.list(mediaFile.getParent())`.
     3. Filter to files whose name starts with `<base>.` (case-insensitive) and ends with a supported subtitle extension (`.srt`, `.ass`, `.ssa`, `.vtt` — case-insensitive).
     4. For each match, parse the middle segment between the base and the subtitle extension, e.g. `Show.S01E02` + `.en.sdh.` + `srt` → middle segment `en.sdh`.
     5. Tokenise the middle segment by `.`; classify each token as language tag, region modifier, or descriptor; build a `SubtitleEntry`.
     6. Sort: language-tagged entries first (by language code, then by descriptor count), then bare entries.
   - Pure function; deterministic.

### Tests (new)

3. `src/test/java/org/tvrenamer/controller/subtitle/SubtitleLanguagesTest.java`
   - `findByCode3` — both forms for languages with B/T codes.
   - `normalizeFilenameTag` covers: 2-letter, 3-letter B, 3-letter T, English name, BCP-47 region (`en-US`, `pt-BR`, `zh-Hans`), case variants, garbage input.
   - `ALL` size is exactly the spec's 30 entries; English is index 0.

4. `src/test/java/org/tvrenamer/controller/subtitle/SubtitlePairingTest.java`
   - Use JUnit 5 `@TempDir` for fixture files (touch zero-byte files; pairing logic only checks names).
   - The full matrix from the spec test plan: bare, lang-tagged in 2/3-letter forms, BCP-47 region, mixed case, all descriptors, multiple descriptors combined, default-language fallback, no-match, ordering, unrelated-extension exclusion, filenames with spaces/apostrophes/unicode.

### Done when

- Both unit-test classes pass.
- 100% line coverage on `SubtitleLanguages` + `SubtitlePairing` (these are the highest-risk pure-logic modules; worth being thorough).

---

## Phase 2 — Per-format mergers: `Mp4SubtitleMerger`, `MkvSubtitleMerger`

**Why second:** Once we can pair files, we need to produce muxed output. This is where the temp+swap discipline lives. No UI, no FileMover hookup yet — the mergers can be exercised from test fixtures.

### Files to create

1. `src/main/java/org/tvrenamer/controller/subtitle/SubtitleMerger.java` — interface:
   ```java
   public interface SubtitleMerger {
       boolean supportsContainerExtension(String ext);
       boolean supportsSubtitleExtension(String ext);
       boolean isToolAvailable();
       String  getToolName();
       MergeOutcome merge(Path mediaFile, List<SubtitleEntry> subtitles);
       /** True if the container already has a subtitle track in the given language. */
       boolean alreadyHasLanguageTrack(Path mediaFile, String langCode3);
   }
   ```
   Result type:
   ```java
   public enum MergeOutcome { SUCCESS, FAILED, SKIPPED_NO_TOOL, SKIPPED_ALREADY_PRESENT }
   ```

2. `src/main/java/org/tvrenamer/controller/subtitle/Mp4SubtitleMerger.java`
   - Supported container extensions: `.mp4`, `.m4v`.
   - Supported subtitle extensions: `.srt`, `.vtt` (ASS/SSA → return `false`, the controller logs a per-pair warning).
   - Tool detection: `MP4Box` (Windows: `MP4Box.exe`). Cache result in `static volatile`, double-checked-locking pattern matching `Mp4MetadataTagger`.
   - `alreadyHasLanguageTrack`: spawn `MP4Box -info <src>`, parse stderr/stdout for `Track: ID=...` blocks tagged as Subtitle/TX3G with matching language. (TBD whether `MP4Box -dts` is more reliable — check empirically in phase 5.)
   - `merge`: build `MP4Box -add "<sub>:lang=<lang3>:name=<displayName>" ... -out <tmp> <src>` command. One `-add` per subtitle. Then run integrity gate + atomic swap (see below).
   - Track name format: `displayName` from spec (e.g. "English (Forced, SDH)").

3. `src/main/java/org/tvrenamer/controller/subtitle/MkvSubtitleMerger.java`
   - Supported container extensions: `.mkv`.
   - Supported subtitle extensions: `.srt`, `.ass`, `.ssa`, `.vtt`.
   - Tool detection: `mkvmerge` (Windows: `mkvmerge.exe`).
   - `alreadyHasLanguageTrack`: `mkvmerge --identify --identification-format json <src>`, parse JSON, walk `tracks[]`, count entries with `type == "subtitles"` and `properties.language == <lang3>`.
   - `merge`: build per-subtitle flag groups before each input subtitle:
     ```
     mkvmerge -o <tmp> <src> \
       --language 0:eng --track-name 0:"English" \
         [--forced-display-flag 0:1] [--hearing-impaired-flag 0:1] \
       <sub1> \
       --language 0:fre --track-name 0:"French" <sub2>
     ```
   - Set track flags from the `Descriptor` enum on each `SubtitleEntry`.

4. `src/main/java/org/tvrenamer/controller/subtitle/SubtitleSwap.java` — **shared swap helper.**
   - Static `boolean integrityGate(Path tmp, Path src)` → `Files.exists(tmp) && Files.size(tmp) >= 0.8 * Files.size(src)`.
   - Static `boolean swap(Path tmp, Path src)` — performs the 3× retry at 100/300/1000 ms backoff for `Files.move(REPLACE_EXISTING)`. On retry exhaustion, **does not delete temp**; returns `false` so caller can log the preserved temp path.
   - Static `int computeTimeoutSeconds(long sourceBytes)` → 30 + (bytes / 1_000_000), capped at 600.

### Tests (new)

5. `src/test/java/org/tvrenamer/controller/subtitle/MkvSubtitleMergerTest.java`
   - **Fully mock-based** for unit-level coverage: subclass `MkvSubtitleMerger` and override `runProcess(...)` to return canned exit codes / stdout. Test command-line construction (snapshot the `List<String>` args). Test integrity gate failure paths. Test idempotency response.
6. `src/test/java/org/tvrenamer/controller/subtitle/Mp4SubtitleMergerTest.java` — same pattern.
7. `src/test/java/org/tvrenamer/controller/subtitle/SubtitleSwapTest.java`
   - `integrityGate` true/false cases.
   - `swap` happy path.
   - `swap` simulating 2 transient failures + success on attempt 3.
   - `swap` exhausting all retries → returns `false`, temp file still exists.
   - `computeTimeoutSeconds` boundaries (10 KB → 30s; 1 GB → 600s cap).

### Done when

- Phase 2 tests pass.
- A small **manual** sanity test: with `mkvmerge` and `MP4Box` on PATH, run a tiny driver `main` (or a JUnit `@Tag("integration")` test) on a fixture MKV+SRT pair from `src/test/resources/subtitles/`, verify track count goes up by one.

---

## Phase 3 — Orchestrator: `SubtitleMergeController`

**Why third:** Sits on top of phases 1 and 2; turns a `(Path, FileEpisode)` into a coordinated merge call.

### File to create

1. `src/main/java/org/tvrenamer/controller/subtitle/SubtitleMergeController.java`
   - `enum Result { SUCCESS, DISABLED, NO_SUBTITLES_FOUND, NO_TOOL, ALREADY_HAS_LANGUAGE, UNSUPPORTED, FAILED }` with `isOk()` returning `true` for everything except `FAILED`.
   - Constructor builds `List<SubtitleMerger>` (Mp4 + Mkv).
   - `Result mergeIfEnabled(Path mediaFile, FileEpisode episode)`:
     1. If `userPrefs.isMergeSubtitles() == false` → `DISABLED`.
     2. Pick merger by container extension; if none → `UNSUPPORTED`.
     3. If `!merger.isToolAvailable()` → log INFO once per session (use a static `AtomicBoolean` flag), return `NO_TOOL`.
     4. Find subtitles: `SubtitlePairing.findFor(mediaFile, prefs.getDefaultSubtitleLanguage())`.
     5. Filter to entries the merger supports for this container; emit a WARNING per filtered-out entry (e.g. ASS → MP4).
     6. If filtered list is empty → `NO_SUBTITLES_FOUND`.
     7. Group filtered list by language; for each language, ask `merger.alreadyHasLanguageTrack`. Drop already-present languages from this run; if all are dropped → `ALREADY_HAS_LANGUAGE`.
     8. Stale-temp scan: delete any `<mediaFile>.merging.<ext>` next to the target older than 1 hour (best-effort).
     9. Invoke `merger.merge(mediaFile, remainingSubtitles)` → return `SUCCESS`/`FAILED` based on outcome.
   - Note: the controller does **not** delete sibling subtitle files. That's `FileMover`'s job, post-move (per spec).

### Tests (new)

2. `src/test/java/org/tvrenamer/controller/subtitle/SubtitleMergeControllerTest.java`
   - Fakes for `SubtitleMerger` (in-process implementation that records calls, returns canned outcomes) and a fake `UserPreferences`.
   - Cover every branch of the `Result` enum:
     - DISABLED, UNSUPPORTED, NO_TOOL, NO_SUBTITLES_FOUND, ALREADY_HAS_LANGUAGE, SUCCESS, FAILED.
   - Verify the controller does NOT delete subtitle files in any branch.
   - Verify a partial idempotency case: 2 subtitles found, only 1 already present → merge invoked with 1 subtitle.

### Done when

- Phase 3 tests pass. Controller branches all covered.

---

## Phase 4 — `UserPreferences` integration

**Why fourth:** Tiny but unblocks both UI (phase 5) and FileMover hookup (phase 6).

### Files to modify

1. `src/main/java/org/tvrenamer/model/UserPreferences.java`
   - Add fields:
     ```java
     private boolean mergeSubtitles = false;
     private String  defaultSubtitleLanguage = "eng";
     private boolean deleteSubtitlesAfterMerge = false;
     ```
   - Getters/setters mirroring `tagVideoMetadata` style (with `valuesAreDifferent` PropertyChange firing).
   - Optional: add to the debug `toString()` listing.

2. `src/main/java/org/tvrenamer/controller/UserPreferencesPersistence.java`
   - In `fromParsedXml`, alongside the existing `scalars.get("tagVideoMetadata")` block, add reader entries for the three new fields.
   - In the writer (`writeUserPreferencesToXml` / equivalent), append the three new fields to the XML output.
   - On `defaultSubtitleLanguage` read: if value isn't in `SubtitleLanguages.ALL`, silently substitute `"eng"` (the forward-compat behaviour from the spec).

### Tests

3. Extend `UserPreferencesPersistenceTest` (if it exists) or add a new `UserPreferencesSubtitleFieldsTest`:
   - Round-trip the three fields.
   - Forward-compat: a stored `defaultSubtitleLanguage = "klingon"` reads back as `"eng"`.
   - Default values when XML doesn't contain the fields (older prefs file).

### Done when

- Tests pass; existing prefs files load unchanged; new fields persist correctly.

---

## Phase 5 — `FileMover` hookup

**Why fifth:** Now wire the controller into the actual rename pipeline. Important order: **subtitle merge runs on the source before the move**, but **the existing metadata tagger runs on the destination after the move**. Two distinct hook points.

### Changes

1. In `FileMover.java`:
   - Add private method `mergeSubtitlesIfEnabled(final Path sourceFile)`:
     ```java
     private void mergeSubtitlesIfEnabled(final Path sourceFile) {
         if (!userPrefs.isMergeSubtitles()) return;
         try {
             SubtitleMergeController c = new SubtitleMergeController();
             SubtitleMergeController.Result r = c.mergeIfEnabled(sourceFile, episode);
             if (r == SubtitleMergeController.Result.FAILED) {
                 logger.warning("Subtitle merge failed for " + sourceFile + "; continuing with move.");
             }
         } catch (Exception e) {
             logger.log(Level.WARNING, "Exception during subtitle merge for: " + sourceFile, e);
         }
     }
     ```
   - In `doActualMove` (around line 380, just before the rename attempt) call `mergeSubtitlesIfEnabled(srcPath)`.
   - In the "already in place" branch (around line 562), call `mergeSubtitlesIfEnabled(realSrc)` so files that don't need to move still get subtitles merged.
   - **Subtitle file deletion**: only after a successful move. Add a private method `deleteSubtitleSiblingsIfEnabled(final Path originalSourceFile)` that runs after `actualDest != null` confirms a successful move:
     ```java
     if (!userPrefs.isMergeSubtitles() || !userPrefs.isDeleteSubtitlesAfterMerge()) return;
     for (SubtitleEntry e : SubtitlePairing.findFor(originalSourceFile, userPrefs.getDefaultSubtitleLanguage())) {
         try {
             Files.deleteIfExists(e.file());
         } catch (IOException ioe) {
             logger.log(Level.WARNING, "Could not delete subtitle " + e.file(), ioe);
         }
     }
     ```
     But — important — call `findFor(originalSourceFile, ...)` **before** the merge, because after the merge the temp+swap may have changed siblings. Cache the list at the top of `doActualMove` and replay it for deletion at the end.

### Tests

2. `FileMoverSubtitleIntegrationTest` (under `@Tag("integration")` so it doesn't run on CI by default; behind a flag like the existing TVDB integration tests):
   - With `mkvmerge` on PATH and a fixture MKV+SRT pair, exercise the full pipeline; verify the moved file has the merged subtitle and that with `deleteSubtitlesAfterMerge` the SRT is gone post-move.
   - Repeat the same with the move *forced to fail* (e.g. dest dir read-only): verify SRT is **not** deleted.

### Done when

- Integration test passes locally with the tools installed.
- Manual smoke test from the spec passes.

---

## Phase 6 — Preferences UI

**Why last:** UI work depends on every layer below; doing it last means each commit produces a working app.

### Changes

1. `PreferencesDialog.java` — General tab, beneath the existing "Tag video metadata" group:
   - New `Group` widget labelled "Subtitles".
   - Checkbox: "Merge sibling subtitle files into renamed media" (`mergeSubtitlesCheckbox`).
   - Indented child controls, enabled only when the checkbox is on:
     - `Combo` (read-only) labelled "Default language", populated from `SubtitleLanguages.ALL.stream().map(Language::displayName)`. Initial selection from `prefs.getDefaultSubtitleLanguage()` (find by `code3`); fall back to English if not found.
     - Checkbox: "Delete subtitle files after successful merge" (`deleteSubtitlesAfterMergeCheckbox`).
   - Status label below the group, populated from a single new method `SubtitleMergeController.getToolSummary()` (mirrors `MetadataTaggingController.getToolSummary()`):
     - "MP4Box: detected · mkvmerge: detected"
     - "MP4Box: not found · mkvmerge: detected"
     - "Neither MP4Box nor mkvmerge found — install GPAC or MKVToolNix to enable"
   - Wire change listeners that call `setMergeSubtitles`, `setDefaultSubtitleLanguage`, `setDeleteSubtitlesAfterMerge` on `Save`.
   - One-shot info dialog when the user enables the merge checkbox AND no tool is detected: show the "needs MP4Box from GPAC / mkvmerge from MKVToolNix" message from the spec; track-once via a session flag so we don't nag on every checkbox toggle.

2. `SubtitleMergeController.getToolSummary()` — public helper returning the human-readable tool detection summary.

### Tests

No new automated tests for the UI (consistent with the rest of the project). Manual smoke test:
- Open Preferences → General → Subtitles.
- Toggle the merge checkbox; confirm the language dropdown and delete checkbox enable/disable correctly.
- Pick "French" from dropdown, save, reopen, confirm "French" is restored.
- Disable mkvmerge (rename it temporarily on PATH); confirm tool-status label updates.

### Done when

- App opens, all controls behave, settings persist across restarts.

---

## Phase 7 (Documentation polish)

After phases 1–6 land:

1. Update `README.md` runtime dependencies note: "MP4Box (GPAC) for MP4 subtitle merging, mkvmerge (MKVToolNix) for MKV subtitle merging — both optional, only used when the merge feature is enabled."
2. Add a help page entry: `src/main/resources/help/subtitles.html` with a short user-facing explanation.
3. Move the "Subtitle merge" entry from `docs/TODO.md` (if present) to `docs/Completed.md` with the standard Title / Why / Where / What / Notes structure.
4. Bump version, write release notes, ship.

---

## Risk register / mitigation summary

| Risk | Mitigation in plan |
|------|---------------------|
| Phase 1 pairing parser misclassifies a real-world filename | Phase 1 unit tests cover the entire descriptor matrix + filename-edge-case matrix from the spec test plan |
| Phase 2 swap retry behaviour wrong on Windows AV scanners | `SubtitleSwapTest` simulates transient failures; manual smoke test with Defender enabled before shipping |
| Phase 3 controller silently swallowing partial-success cases | The `Result` enum has 7 explicit values + `isOk()` semantics; tests cover all 7 |
| Phase 4 UserPreferences XML migration breaks older files | Forward-compat test asserts older XML loads with new defaults |
| Phase 5 deletion order races the merge | Subtitle file list cached before merge runs; deletion happens *after* move success only — integration test covers the failed-move case |
| Phase 6 dropdown changes break saved preferences | Forward-compat fallback in phase 4 means dropping a language from `ALL` won't break existing users |

---

## Estimated scope (rough order-of-magnitude)

| Phase | New files | Modified files | New tests | Hours (est) |
|-------|-----------|-----------------|-----------|-------------|
| 1 | 2 | 0 | 2 | 4–6 |
| 2 | 4 | 0 | 3 | 6–10 |
| 3 | 1 | 0 | 1 | 2–3 |
| 4 | 0 | 2 | 1 | 1–2 |
| 5 | 0 | 1 | 1 | 2–4 |
| 6 | 0 | 1 | 0 | 3–4 |
| 7 | 0 | 3 | 0 | 1 |
| **Total** | **7** | **7** | **8** | **19–30** |

Numbers assume one human implementer reasonably familiar with the existing codebase patterns. Lower bound if everything mirrors `MetadataTaggingController` cleanly; upper bound if `MP4Box -info` parsing for the idempotency check turns out to need a real grammar (it shouldn't — the output format is stable).

---

## What we're explicitly *not* doing in v1 (deferred to TODO.md)

- ffmpeg fallback for MKV when `mkvmerge` is missing.
- `.mov` and `.webm` container support.
- "Custom language…" dropdown entry for Welsh, Basque, Estonian, etc.
- SRT encoding detection / UTF-8 conversion.
- Per-file UI override of language (filename `.lang` tag override is supported).
- Subtitle preview column in the main results table.
