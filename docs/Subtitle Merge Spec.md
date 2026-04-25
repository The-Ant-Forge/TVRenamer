# Subtitle Merge Spec

## Overview

When renaming/moving a media file, optionally locate any sibling subtitle file with the matching base name and merge it into the media container as a soft subtitle track. The original subtitle file is removed (or kept, configurable) once merged. Failures on a single pair must not block the overall rename pipeline.

## Goals

- Auto-detect paired subtitle files for an episode being renamed.
- Merge into MP4 with `MP4Box` (GPAC) and into MKV with `mkvmerge` (MKVToolNix).
- Run **before** the file move so I/O happens on the source disk (faster, lower risk than merging on a network destination).
- Strictly serial — one merge at a time. Subtitle work piggybacks on the existing single-thread `MoveRunner` executor.
- Skip on any error; log a clear message; continue with the next pair.
- Surface tool availability in Preferences.

## Non-goals (explicit, for v1)

- Hard-burning subtitles (re-encode + filter) — out of scope.
- Translating, time-shifting, or re-styling subtitles.
- Auto-downloading subtitles from external services.
- Selecting between multiple competing subtitle files for the same media (we'll merge whatever pairs we find, see "Pairing rules" below).
- Subtitle merge for `.mov` (mp4 family) and `.webm` (mkv family) — initial scope is `.mp4`/`.m4v` and `.mkv`. Easy to extend later.

---

## Tool choice

| Container | Tool | Why |
|-----------|------|-----|
| MP4 / M4V | `MP4Box` (GPAC) | User's preferred tool. Auto-converts SRT to TX3G timed text. Stable handling of `:lang=eng:name=English` tagging. |
| MKV | `mkvmerge` (MKVToolNix) | Pure remux (no re-encode), sub-second runtime, zero codec drama. Better choice than ffmpeg for MKV — ffmpeg's subtitle codec negotiation produces frequent failures on perfectly valid SRT/ASS input. |

**Note on ffmpeg:** the user originally proposed ffmpeg for MKV. We're recommending `mkvmerge` instead because (a) it's part of the same MKVToolNix bundle the project already documents for MKV metadata tagging via `mkvpropedit`, so users likely already have it installed, (b) it never re-encodes, and (c) its CLI flags for subtitle language tagging are more straightforward.

If `mkvmerge` is not on the PATH, we **skip the pair and log** — we do not silently fall back to ffmpeg in v1. (We can add a fallback later if real-world telemetry shows it matters.)

### Tool detection

Reuse `ExternalToolDetector.findExecutableOnPath(...)`, which already powers `Mp4MetadataTagger` and `MkvMetadataTagger`. Cache results per JVM (same pattern as `Mp4MetadataTagger.ensureDetected()`).

---

## Subtitle file types

Initial supported extensions (case-insensitive):

| Extension | MP4 (MP4Box) | MKV (mkvmerge) |
|-----------|--------------|-----------------|
| `.srt` | Yes (auto-converts to TX3G) | Yes |
| `.ass` / `.ssa` | **Skip** — MP4Box rejects ASS in MP4 cleanly; warn and skip pair | Yes |
| `.vtt` | Yes | Yes |

If a pair's subtitle format isn't supported by the target container, we log a `WARNING` ("ASS subtitles cannot be muxed into MP4; skipping <subtitle>") and continue with the move.

---

## Pairing rules

Given a source media file `path/to/Show.S01E02.mkv`, look for sibling files in the **same directory** matching the same base name (everything before the final extension), with one of the supported subtitle extensions:

1. **Bare match:** `Show.S01E02.srt`
2. **Language-tagged match:** `Show.S01E02.<tag>.<ext>` where `<tag>` is one of:
   - 2-letter ISO 639-1 (`en`, `fr`, `de` …)
   - 3-letter ISO 639-2 (`eng`, `fre`, `deu` …)
   - Common shorthand (`english`, `french`, `german`, `spanish`, `portuguese` …)
   - **BCP-47 region tag:** `en-US`, `pt-BR`, `zh-Hans`, `es-419` — we strip the region/script suffix and use the base language for the container language code (`eng`, `por`, `zho`, `spa`). Region info is preserved in the track *name* (e.g. "Portuguese (BR)").
3. **Descriptor suffixes (preserved as track name, set track flags where supported):**
   - `sdh` / `cc` / `hi` / `hearingimpaired` → flag track as hearing-impaired (mkvmerge: `--hearing-impaired-flag 0:1`); track name "<lang> (SDH)"
   - `forced` → flag track as forced (mkvmerge: `--forced-display-flag 0:1`); track name "<lang> (Forced)"
   - `signs` / `songs` → no flag; preserved as track name "<lang> (Signs)" / "<lang> (Songs)"
   - `commentary` → mkvmerge `--commentary-flag 0:1` if available; track name "<lang> (Commentary)"
   - `dub` → no flag; track name "<lang> (Dub)"

   Multiple descriptors are joined with spaces in the track name (e.g. `Show.S01E02.en.sdh.forced.srt` → "English (Forced, SDH)"). MP4Box has no equivalent flag fields beyond `name=`; descriptors live in the track name only for MP4.

**Default track flag:** none of the merged tracks are marked `default` automatically. SDH and Forced tracks are explicitly *not* default. Players can pick whichever they prefer.

**Multiple matches:** if more than one subtitle file matches, all are merged. Order: language-tagged matches first (sorted by language code, then by descriptor), then bare matches. This produces a deterministic order that biases toward the user's preferred language being track 1.

**Language resolution per track:**
- If the filename has a language tag, normalize to 3-letter ISO 639-2 B-form (`en` → `eng`, `english` → `eng`, `en-US` → `eng`, `fr` → `fre`, `de` → `ger`).
- Otherwise, use the user's `defaultSubtitleLanguage` preference (default: `eng`). This is selected via dropdown so it's always a valid code — no free-text input, no validation errors.

**ISO 639-2 form choice (B vs T):** about 17 languages have two valid 3-letter codes (`fre`/`fra`, `ger`/`deu`, `chi`/`zho`, `dut`/`nld`, …). We standardize on the **B-form** (English bibliographic) throughout: that's what `mkvmerge` emits by default, what most subtitle filenames in the wild use, and what `MP4Box` accepts. The dropdown values, the language-tag parser, and the persisted preference all use B-form.

**Track name:**
- If filename has no descriptor, use the human-readable language name (e.g. "English").
- If filename has region info, append in parens ("Portuguese (BR)").
- If filename has descriptors, append in parens with comma separation ("English (Forced, SDH)").

---

## Where this hooks into the pipeline

The merge runs as part of `FileMover.call()`, **after** the destination basename has been computed but **before** the actual `Files.move()` call. This is the same architectural slot as `MetadataTaggingController.tagIfEnabled()` already occupies, but ordered ahead of the metadata tagger so the freshly-merged file gets tagged in the same pass.

Sequence inside `FileMover.call()`:

1. Validate destination directory.
2. **NEW — `SubtitleMergeController.mergeIfEnabled(srcMedia, srcSubtitles)`** → operates on the source file in place via temp+swap.
3. `MetadataTaggingController.tagIfEnabled(...)` (existing).
4. `Files.move(srcMedia, dest)` — the move now carries the merged tracks.
5. **NEW — delete sibling subtitle files** if `deleteSubtitlesAfterMerge` is enabled, **only after the move in step 4 has succeeded**. Sibling subtitles must not be deleted by `SubtitleMergeController` itself — deletion is the responsibility of `FileMover` once the entire pipeline has committed. Otherwise a successful merge followed by a failed move would lose the user's only copy of the subtitle file.

Failures in step 2 do not abort steps 3–5 — the source media keeps its original (unmerged) subtitle stream and the move proceeds. Failures in step 4 (move) do leave the *source* with merged tracks; this is acceptable because the merged source remains a valid playable file the user can manually relocate. Documented tradeoff (see "Risks and tradeoffs").

---

## File-handling discipline (temp + swap)

Both `MP4Box` and `mkvmerge` are invoked with an explicit output path. We do *our own* swap rather than letting MP4Box's default in-place rewrite handle it, for three reasons:

1. **Symmetry** — `mkvmerge` always requires an explicit `-o` output. Doing the swap ourselves keeps both backends following the same lifecycle.
2. **Integrity gate** — we can validate the produced container (size, parseability) *before* destroying the original.
3. **Retry policy** — we can retry the swap independently of the merge, which matters under Windows AV/indexer locks.

The merge sequence is:

1. Compute temp path: `<srcMedia>.merging.<ext>` in the same directory.
2. Run tool with input `<srcMedia>` and output `<temp>`.
3. **Integrity gate before swap.** Verify:
   - `<temp>` exists,
   - `<temp>` size is at least, say, 80% of the source size (subtitle muxing should not shrink a file dramatically),
   - container can be inspected without error: `mkvmerge --identify <temp>` for MKV, `MP4Box -info <temp>` for MP4. Exit code zero is the gate.
   If any check fails: delete `<temp>`, log `WARNING`, return `FAILED`. Source is untouched.
4. **Swap.** Attempt `Files.move(temp, srcMedia, REPLACE_EXISTING)`. On Windows this calls `MoveFileEx`, which is reliable for same-volume swaps but **not formally atomic** in all filesystem combinations. To handle transient AV/indexer locks:
   - Retry up to 3 times at 100/300/1000 ms backoff.
   - If all retries fail, **preserve the temp file** (do *not* delete it), log `WARNING`, return `FAILED`. The user (or a manual retry) can then promote the temp file by hand. This is the Codex-recommended posture: "rename failed" is not the same as "merged file is invalid."
5. On non-zero tool exit code from step 2: delete temp, log stderr tail, return `FAILED`.
6. On tool process timeout: kill process tree, delete temp, return `FAILED`.

**Stale temp cleanup.** On `SubtitleMergeController` startup (per session), scan for any orphaned `*.merging.*` files older than 1 hour next to media files we're about to process and delete them. Belt-and-braces for the case where step 4 left a temp file behind permanently.

**Why same-volume only:** the temp lives next to the source, so the swap is always intra-volume — `Files.move(REPLACE_EXISTING)` is reliable on Windows in that case. Cross-volume swaps would need copy+delete and lose the speed advantage.

**Process timeout:** scaled by source file size — 30 seconds base + 1 second per MB of source, capped at 10 minutes. Subtitle muxing is I/O-bound and roughly linear in container size; a fixed 5-minute timeout would false-positive on multi-GB Blu-ray rips on a NAS. Configurable internally via constants.

---

## Failure modes and behaviour

| Failure | Action |
|---------|--------|
| Tool not on PATH | Suppress per-file warnings; log `INFO` once per session ("MP4Box not found, skipping subtitle merge for MP4 files this session"). The file moves normally without merged subtitles. |
| No subtitle file matches | No-op; file moves normally. Don't log (very common). |
| Subtitle codec unsupported by container (e.g. ASS → MP4) | Skip that subtitle, continue with others; log `WARNING` per skipped subtitle. |
| Container already has a subtitle track in the target language (idempotency) | Skip the merge entirely for that pair; log `INFO` ("media already has <lang> subtitle track, skipping"). This makes re-running rename safe — no duplicate language tracks accumulate. Detection: `mkvmerge --identify-format json <src>` for MKV; `MP4Box -info <src>` parsed for `Subtitle` tracks for MP4. |
| Integrity gate fails (temp empty or unparseable) | Delete temp, log `WARNING`, return `FAILED`. Source untouched. |
| Tool exits non-zero | Skip merge; log `WARNING` with stderr tail; original file remains intact, file moves with original (unmerged) subtitles. |
| Tool process times out (size-scaled) | Kill process tree, clean up temp, skip merge; log `WARNING`. |
| Disk full mid-merge | Tool will fail and exit non-zero; cleanup follows the standard failure path. |
| Swap fails after successful merge (AV/indexer lock) | Retry 3× with backoff. If still failing: preserve temp file as `<src>.merging.<ext>`, log `WARNING` with the path, return `FAILED`. User can manually rename to recover. |
| User cancels rename | Existing cancellation path applies; subtitle merge does not have its own "stop" hook in v1 (worst case: one extra merge completes before the next pair is skipped). |

---

## Preferences

Three new fields on `UserPreferences`:

| Field | Type | Default | UI label |
|-------|------|---------|----------|
| `mergeSubtitles` | `boolean` | `false` | "Merge sibling subtitle files into renamed media" |
| `defaultSubtitleLanguage` | `String` (3-letter ISO 639-2 B-form, e.g. `eng`) — picked from dropdown | `"eng"` | "Default language (when filename has no tag)" |
| `deleteSubtitlesAfterMerge` | `boolean` | `false` | "Delete subtitle files after successful merge" |

**Persistence:** add to `UserPreferencesPersistence` reader/writer alongside `tagVideoMetadata`. Match the existing scalar pattern (string in XML, boolean parsing in `fromParsedXml`).

**UI placement (Preferences dialog):**
- Add a new "Subtitles" group on the **General** tab, immediately below the "Tag video metadata" group.
- Layout:
  - Checkbox: "Merge sibling subtitle files into renamed media"
  - Below, indented (visually grouped, enabled only when checkbox above is on):
    - **Combo (read-only dropdown)** with label "Default language" — populated from the supported-languages list (see below), default selection: English. The combo's display values are human-readable language names; the underlying value persisted is the 3-letter B-form code.
    - Checkbox: "Delete subtitle files after successful merge"
  - Status label below the checkbox showing detected tools, mirroring the metadata-tagging area pattern. Examples:
    - "MP4Box: detected · mkvmerge: detected" (good)
    - "MP4Box: not found · mkvmerge: detected" (partial)
    - "Neither MP4Box nor mkvmerge found — install GPAC or MKVToolNix to enable" (none)

**Supported languages dropdown (v1).** Roughly 30 entries covering the dominant global subtitle traffic. Stored as `(displayName, code3)` pairs in a `SubtitleLanguages` constants class so the list is centralised and easy to extend.

| Display name | ISO 639-2 (B-form) |
|--------------|---------------------|
| English (default) | `eng` |
| Spanish | `spa` |
| French | `fre` |
| German | `ger` |
| Italian | `ita` |
| Portuguese | `por` |
| Dutch | `dut` |
| Polish | `pol` |
| Russian | `rus` |
| Ukrainian | `ukr` |
| Czech | `cze` |
| Slovak | `slo` |
| Hungarian | `hun` |
| Romanian | `rum` |
| Greek | `gre` |
| Swedish | `swe` |
| Danish | `dan` |
| Norwegian | `nor` |
| Finnish | `fin` |
| Turkish | `tur` |
| Hebrew | `heb` |
| Arabic | `ara` |
| Persian | `per` |
| Hindi | `hin` |
| Bengali | `ben` |
| Indonesian | `ind` |
| Malay | `may` |
| Thai | `tha` |
| Vietnamese | `vie` |
| Chinese | `chi` |
| Japanese | `jpn` |
| Korean | `kor` |

The list represents *primary languages* — we don't include regional variants (no `pt-BR`, `es-419`, `zh-Hans` etc.) in the dropdown because, as the user noted, the global dominant variant is assumed for each entry. Filename-tagged language hints (which *can* include region info — see "Pairing rules") still get processed correctly per-track; the dropdown only controls the *fallback* when a filename has no tag at all.

**Forward compatibility.** If a saved `defaultSubtitleLanguage` value isn't in the current dropdown list (e.g. a user upgrades after we add or remove an entry), we silently fall back to `eng` and re-save on next preferences write. No error, no migration step.

When the user toggles `mergeSubtitles` on for the first time and no tools are detected, show a one-shot info dialog:

> "Subtitle merging requires MP4Box (from GPAC, gpac.io) for MP4/M4V files and mkvmerge (from MKVToolNix, mkvtoolnix.org) for MKV files. Make sure at least one is on your PATH. The setting is enabled regardless — files in unsupported formats will be skipped."

---

## New code surface

```
controller/
└── subtitle/
    ├── SubtitleMerger.java                 # interface (per-container)
    ├── SubtitleMergeController.java        # orchestrator (analog to MetadataTaggingController)
    ├── SubtitlePairing.java                # pure helper: find/parse subtitle files for a media path
    ├── SubtitleLanguages.java              # the (displayName, code3) catalogue used by the prefs dropdown and the lang-tag normalizer
    ├── Mp4SubtitleMerger.java              # MP4Box backend
    └── MkvSubtitleMerger.java              # mkvmerge backend
```

### `SubtitleMerger` interface

```java
public interface SubtitleMerger {
    boolean supportsContainerExtension(String ext);       // ".mp4", ".mkv" etc.
    boolean supportsSubtitleExtension(String ext);        // varies per container
    boolean isToolAvailable();
    String  getToolName();                                // for UI summary
    /** Mux the given subtitles into mediaFile in place. Throws on failure. */
    boolean merge(Path mediaFile, List<SubtitleEntry> subtitles);
}
```

### `SubtitleEntry` (record)

```java
public record SubtitleEntry(Path file, String langCode3, String trackName) { }
```

### `SubtitleMergeController.mergeIfEnabled(...)`

Mirrors `MetadataTaggingController.tagIfEnabled`:

1. If `userPrefs.isMergeSubtitles()` is false → return `DISABLED`.
2. Lookup merger by container extension. None? → `UNSUPPORTED`.
3. `SubtitlePairing.findSubtitlesFor(mediaFile, prefs.getDefaultSubtitleLanguage())` → list of `SubtitleEntry`.
4. Filter to entries the merger supports for this container; log skipped formats.
5. If list is empty → `NO_SUBTITLES_FOUND`.
6. Call `merger.merge(...)`. On success and `deleteSubtitlesAfterMerge` → delete each entry's file.
7. Return `SUCCESS` / `FAILED`.

### `SubtitlePairing.findSubtitlesFor`

Pure function on a `Path` and a default language code. Lists same-directory siblings with the same base name, parses optional `.<lang>` and `.<sdh|forced|cc>` segments via regex, returns sorted `SubtitleEntry` list. Unit-testable in isolation (no I/O beyond `Files.list`).

### `Mp4SubtitleMerger` command construction

```
MP4Box [-add "<sub>:lang=<lang3>:name=<displayName>"]... -out <tmp> <src>
```

For multiple subtitles, repeat `-add` per file. Use `-out <tmp>` to direct output to the temp path so we never overwrite the source until the swap.

### `MkvSubtitleMerger` command construction

```
mkvmerge -o <tmp> <src> --language 0:<lang3> --track-name 0:<displayName> <sub1> [--language 0:<lang3> --track-name 0:<displayName> <sub2>]...
```

`mkvmerge` per-file flags apply to the next input file, so we apply `--language` and `--track-name` immediately before each subtitle path.

### `FileMover` change

In `FileMover.call()`, after destination path resolution and before the existing `MetadataTaggingController` invocation, add:

```java
SubtitleMergeController subs = new SubtitleMergeController();
SubtitleMergeResult sResult = subs.mergeIfEnabled(currentSourcePath, episode);
if (sResult == SubtitleMergeResult.FAILED) {
    logger.warning("Subtitle merge failed for " + currentSourcePath
                   + "; continuing with move using original subtitles.");
}
```

The merge mutates the source file in place via temp+swap; subsequent `Files.move(currentSourcePath, dest)` carries the merged tracks naturally.

---

## Logging

Use `java.util.logging` (existing convention):

| Level | Event |
|-------|-------|
| FINE  | "Looking for subtitles for <media>" / "Found <n> candidate(s)" |
| FINE  | "Tool detected: MP4Box at <path>" / "mkvmerge at <path>" (once per session) |
| INFO  | "Merged <n> subtitle track(s) into <media>" |
| INFO  | "Subtitle merge tool <X> not found on PATH; skipping merge for <ext> files this session." (once) |
| WARNING | Any per-pair failure (with reason and tool stderr tail) |

No PII or path scrubbing concerns — same posture as the rest of the codebase's file-path logging.

---

## Test plan

### Unit tests

- `SubtitlePairingTest` — pure logic, no I/O via `Files.list`, exercise:
  - bare match
  - lang-tagged matches in 2- and 3-letter forms
  - BCP-47 region tags (`en-US`, `pt-BR`, `zh-Hans`) — region stripped for lang code, retained in track name
  - mixed-case extensions (`.SRT`, `.AsS`) and case-insensitive language tags (`EN`, `Eng`)
  - all descriptor variants (SDH, CC, HI, hearingimpaired, forced, signs, songs, commentary, dub)
  - multiple descriptors combined (`.en.sdh.forced.srt`)
  - default-language fallback when no tag in filename
  - no-match case (returns empty list)
  - deterministic ordering (lang-tagged before bare; sorted by language code then descriptor)
  - subtitle file with same base name but unrelated extension (e.g. `.txt`) — must not match
  - filenames with spaces, apostrophes, ampersands, and unicode characters (`Show & Co. — S01E02.srt`) — must match correctly
  - language-code resolution from filename tags: 2-letter, 3-letter, "english", BCP-47 region (`en-US` → `eng`)
  - dropdown round-trip: saving a `defaultSubtitleLanguage` value persists, then restores the same dropdown selection
  - forward compatibility: a saved value not in the current dropdown list falls back to `eng` silently
- `SubtitleMergeControllerTest` — uses fakes for `SubtitleMerger` and `UserPreferences`:
  - DISABLED short-circuit
  - UNSUPPORTED container extension
  - NO_SUBTITLES_FOUND empty list
  - SUCCESS path
  - FAILED path leaves both source and subtitle files untouched
  - **idempotency:** existing subtitle track in target language → SKIPPED, no duplicate added
  - integrity gate failure (forced via fake merger writing zero-byte temp) → returns FAILED, source untouched
  - swap retry exhaustion → temp preserved with `.merging` suffix, returns FAILED
  - **deletion timing:** `deleteSubtitlesAfterMerge` is enabled but the post-merge move (simulated) fails → subtitle files NOT deleted

### Integration tests (optional, behind a property like the existing `tvdb` integration tests)

- Real `MP4Box`/`mkvmerge` invocations against fixture files in `src/test/resources/subtitles/`.
- Skipped if tools not on PATH.

### Manual smoke test

1. Drop a folder of `Show.S01E0X.mkv` + `Show.S01E0X.srt` pairs.
2. Enable Merge subtitles, language `eng`.
3. Rename. Verify each MKV gains a track via `mkvinfo` or VLC's track list.
4. Repeat for MP4/SRT and MP4/VTT.
5. Disable `deleteSubtitlesAfterMerge` → confirm sibling .srt remains. Enable → confirm it's deleted only on success.
6. Force a failure (corrupt subtitle file, or rename one to ASS for an MP4 target) and confirm: media file moves intact, original subtitle remains, log records the warning.

---

## Risks and tradeoffs

1. **Source mutation before move (deliberate).** Merging happens on the source file before the move, so the source is rewritten with merged tracks even if the move later fails. We chose this for performance — the source disk is usually local and faster than the destination (often a NAS or external drive). The cost: a failed move leaves a still-valid playable source with merged subtitles, which the user can manually relocate. We considered moving first and merging at the destination but rejected it: NAS/external write paths are 5–10× slower, and a merge failure at the destination is harder to spot than at the source. The deletion-after-move discipline (above) keeps the original subtitle file as a recovery point until the entire pipeline succeeds.
2. **Disk space** — temp files double the media file's footprint briefly. Should be acceptable since we're working on the source disk where the file already exists. We could add a free-space check before invocation, but the tool will fail cleanly if space runs out anyway.
3. **AV scanners and indexers on Windows** — Defender, third-party AV, and the search indexer can briefly hold the source file after we close it, blocking the swap. Mitigation: retry the rename 3× with backoff. **Crucially**, on retry exhaustion we *preserve* the temp file rather than deleting it, so the merged output is recoverable manually. Existing `FileUtilities` move logic has a similar retry pattern; we'll reuse if possible, otherwise lift the same approach.
4. **Encoding correctness for SRT** — older SRT files saved as Windows-1252 cause MP4Box to misinterpret characters. Out of scope for v1; if it bites, we can add an opt-in pre-conversion step that runs `iconv` or detects and prepends a UTF-8 BOM.
5. **mkvmerge vs ffmpeg choice** — going `mkvmerge`-only means users without MKVToolNix get no MKV merge support. Counterargument: those users likely already have MKVToolNix because we recommend it for `mkvpropedit` metadata tagging. ffmpeg fallback is captured as out-of-scope follow-up.
6. **Idempotency** — re-running the rename pipeline on an already-merged file would otherwise add duplicate subtitle tracks. The "container already has track in target language" check (see Failure modes table) prevents this. The check costs one quick `mkvmerge --identify` / `MP4Box -info` invocation per file, ~tens of milliseconds, which is acceptable. Stricter idempotency (compare actual subtitle content) is out of scope.
7. **Track flags** — mkvmerge supports rich per-track flags (`--forced-display-flag`, `--hearing-impaired-flag`, etc.) which we set from the filename descriptors. MP4Box exposes `name=` only — descriptors are preserved in track name but no flags. Documented limitation.
8. **Language tag normalization** — we coerce 2-letter, full English names, and BCP-47 region tags down to a 3-letter ISO 639-2 code. Region info is preserved in the track name. A small mapping table (≈30 common languages) is sufficient; an exhaustive ISO 639 dataset is overkill for a media tagger and would bloat the JAR.
9. **Multiple subtitles for the same language** — we don't dedupe across filenames (e.g. `Show.S01E02.srt` and `Show.S01E02.en.srt`). If both exist, both are muxed. Combined with the idempotency check (which prevents *re-runs* from adding duplicates), this means the only way to get duplicate-language tracks is for the user to have multiple subtitle files with identical language at first-pass merge time. Documented behaviour.
10. **Side-channel renames** — if a user has the file open in a player while we're merging, the source-file swap will fail on Windows. Existing `FileMover` handles this with the standard "in use" detection; the subtitle-merge step will fail first with a tool error, which is acceptable.
11. **Argument escaping** — filenames with spaces, ampersands, apostrophes, and unicode characters must round-trip cleanly through `ProcessBuilder` argument lists. Java's `ProcessBuilder` quotes correctly on Windows when each arg is passed as a separate `List<String>` element (which we'll do); the cost is one integration test per backend confirming this.

---

## Out-of-scope follow-ups (capture in `docs/TODO.md` after v1 lands)

- ffmpeg fallback for MKV when `mkvmerge` is missing.
- `.mov` and `.webm` support.
- Encoding detection / UTF-8 conversion for legacy SRT files.
- "Custom language..." option in the dropdown for users who need a code outside the v1 list (Welsh, Basque, etc.).
- Per-file override of language via filename `.lang` tag is supported in v1; per-file UI override is not.
- Subtitle previews in the main results table.

---

## Build sequence

1. Add new preferences (`mergeSubtitles`, `defaultSubtitleLanguage`, `deleteSubtitlesAfterMerge`) + persistence + getters/setters + validation of language code on save.
2. Add `SubtitleMerger` interface + `SubtitlePairing` helper.
3. Add `Mp4SubtitleMerger`, `MkvSubtitleMerger` with integrity gate + retry-aware swap.
4. Add `SubtitleMergeController` with idempotency check and stale-temp cleanup.
5. Wire into `FileMover.call()` ahead of the metadata tagger; sibling-subtitle deletion happens *after* successful move only.
6. Add Preferences UI (General tab "Subtitles" group).
7. Unit tests (pairing helper, controller).
8. Manual smoke testing per "Test plan" section.
9. Documentation: README dependency note, help page entry.

---

## Codex review notes

This spec was reviewed by an external second-opinion model after the initial draft. The following changes were made in response:

| Codex flag | Resolution |
|------------|------------|
| Subtitle deletion timing inconsistent | `SubtitleMergeController` no longer deletes; `FileMover` deletes only after successful move. |
| "Atomic swap" wording overstated | Softened to "reliable for same-volume swaps but not formally atomic"; added explicit retry policy. |
| No integrity gate before swap | Added: temp existence check, size sanity check, container-parse check (`mkvmerge --identify`, `MP4Box -info`). |
| Pairing rules too narrow (BCP-47 region tags) | Added handling for `en-US`/`pt-BR`/`zh-Hans` style tags (region preserved in track name, base lang used for code). |
| Descriptor list incomplete (HI, signs, songs, commentary, dub) | Expanded; mkvmerge track flags set where supported. |
| Idempotency missing — re-runs add duplicate tracks | Added "container already has language track" check; pair is skipped if present. |
| Timeout fixed at 5 min, would false-fail on NAS Blu-ray | Now scaled: 30s base + 1s/MB, capped at 10 min. |
| Mutation before move could surprise users on move failure | Documented as deliberate tradeoff in "Risks and tradeoffs" #1. |
| Argument-escaping safety not tested | Added unicode/spaces/apostrophes test row to test plan. |
| Language preference free-text input could emit invalid codes | **Resolved more cleanly than Codex suggested:** replaced the free-text field with a read-only dropdown of ~30 primary languages (English default). No validation needed because the user can't pick an invalid value. |

Codex suggestions explicitly **not** taken:

| Codex suggestion | Reason rejected |
|------------------|-----------------|
| Move first, merge at destination | User explicitly chose source-side merge for performance; tradeoff documented instead. |
| AtomicParsley for MP4 subtitle path | Codex confirmed AtomicParsley is metadata-only and irrelevant for subtitles; we already use it only for metadata tagging. |
| ffmpeg fallback for MKV | Captured in out-of-scope follow-ups; v1 stays mkvmerge-only since project already requires MKVToolNix for `mkvpropedit`. |
