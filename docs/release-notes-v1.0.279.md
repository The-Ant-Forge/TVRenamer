## New features

- **Subtitle merging** — auto-mux sibling subtitle files (`.srt`, `.ass`, `.ssa`, `.vtt`) into the renamed media as soft tracks. Supports MP4/M4V (via MP4Box) and MKV (via mkvmerge). Pairs subtitles to media by canonical destination name, so a `.srt` with a scrambled source filename still pairs with the right episode after rename. Idempotent — re-running rename never adds duplicate language tracks.
- **Local-first I/O architecture** — both subtitle merging and metadata tagging now run on the source disk *before* the move. Only the final, fully-prepared file traverses any network share. Massive speedup for moves to NAS/SMB targets where the merge or tag tool would previously have run over the wire.
- **Live per-row merge progress** — while each file is being merged, the row's status cell shows a percentage counter (parsed from MP4Box's `(NN/100)` and mkvmerge's `--gui-mode #GUI#progress NN%` output).
- **Unified progress bar** — the bottom progress bar now advances continuously across the entire rename action (rename → tag → move → merge), in proportion to how many sub-operations have completed. Each file contributes a tick per move + tag + merge, summed across the batch.
- **Per-row state taxonomy** — distinct icons for each operation phase: arrow-right for moving, tag-with-pencil for tagging, video-frame for merging, green check-circle for completed.

## Required external tools (optional, only when feature enabled)

| Format | Tool | Used for |
|--------|------|----------|
| MP4 / M4V | [MP4Box](https://gpac.io) (from GPAC) | Subtitle merging |
| MKV | [mkvmerge](https://mkvtoolnix.download/) (from MKVToolNix) | Subtitle merging |
| MKV / WebM | mkvpropedit (from MKVToolNix) | Metadata tagging — already required since v1.0.247 |
| MP4 / M4V / MOV | AtomicParsley *or* ffmpeg | Metadata tagging — already required since v1.0.247 |

If a tool isn't on PATH, the corresponding format silently skips that feature (the file moves without merge/tag) and a single notice is logged for the session.

## New preferences (Preferences → General → Subtitles)

- **Merge sibling subtitle files into renamed media** — master toggle.
- **Default language** — picked from a dropdown of 30 common languages; used when a subtitle filename has no language tag.
- **Delete subtitle files after successful merge** — when on, the source `.srt` is removed once it's been embedded.

## Bug fixes

- **MP4Box detection on Windows** — the tool-detection probe was hard-coded to `--version` (double-dash), but GPAC tools use `-version` (single-dash, Unix tradition) and exit non-zero on `--version`. Result: MP4Box was always reported as "not found" even when present. Detection now tries `--version` first and falls back to `-version`.
- **MKV idempotency parser** — the regex used to detect existing subtitle tracks in `mkvmerge --identify` JSON output assumed `"type"` came before `"properties.language"` within each track block. Real mkvmerge 98+ output emits `"properties"` first, then `"type"` — so the regex was matching the next track's language. Now splits on `"id":` and checks each chunk for both fields independently, robust to field-ordering changes.
- **MP4 idempotency parser** — looked for "Subtitle" + lang code on the same line of `MP4Box -info`. Modern MP4Box puts `Media Type: text:tx3g` and `Media Language: English (eng)` on different lines within the same track block. Now splits on `# Track` boundaries and accepts both modern and legacy phrasing.
- **Phantom progress ticks for `.srt` files** — the metadata tag step ticked the work plan for every file, including non-taggable subtitle files (which return `UNSUPPORTED`). The progress bar consequently filled to 100% before merging began. Tick is now gated on actual `SUCCESS` or `FAILED` results.
- **Post-batch over-counting** — the post-batch merge phase visited subtitle files (`.srt`) and called the controller, which returned `UNSUPPORTED` but had already fired the per-row listener and ticked the bar. Now pre-filtered by container extension before any controller call.
- **Path-comparison mismatch in skip-set** — when source-side merge tracked a destination for the post-batch step to skip, raw `Path.equals` could fail to match across different normalisation. Both sides now `.toAbsolutePath().normalize()` before comparing.

## Code updates

- New `WorkPlan` model class — thread-safe unit-counter for unified progress accounting, with retract for over-prediction and `completeAll` as an end-of-run safety net.
- New `RowPhase` enum + `MoveObserver.onPhaseChange` — per-row sub-phase signal that lets `FileMover` notify the UI of move/tag transitions without leaking view-package types into the controller.
- New `SubtitleMergeProgressListener.subtitleMergeFileProgress(Path, int)` — default-no-op callback for per-file percentage ticks; UI implementation creates/updates a percentage Label overlay reusing the existing `FileMonitor` copy-progress machinery.
- `ProcessRunner.runStreaming` — new variant that streams stdout line-by-line to a consumer for live progress parsing; existing `run()` becomes a thin delegator.
- `SubtitleMerger.merge` gains an optional `IntConsumer onProgress` overload; both backends parse their tools' progress lines (`#GUI#progress NN%` for mkvmerge, `(NN/100)` for MP4Box) when a consumer is provided.
- `FileMover.consumedByMerge` flag + `setConsumedByMerge` setter — when set by the source-side merge phase (with delete-after-merge enabled), the FileMover's `call()` short-circuits to a successful no-op while still ticking the work plan to preserve the budgeted unit.
- `MoveRunner` now submits a synthetic `SourceSideMergeTask` to its single-thread executor *before* the per-file FileMovers; FIFO ordering guarantees correct phase ordering.
- Diagnostic INFO logs from this development cycle demoted to FINE so production-level logs remain clean.

## Dependency status

No dependency bumps in this release — all dependencies remained at their v1.0.274 versions:

| Dependency | Version |
|-----------|---------|
| Gradle wrapper | 9.4.1 |
| SWT (win32 x64) | 3.133.0 |
| JUnit 5 | 5.14.3 |
| Shadow | 9.4.1 |
| SpotBugs | 6.5.1 |
| Launch4j | 4.0.0 |
