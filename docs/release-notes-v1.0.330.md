A reliability-focused release: the complete implementation of Code Review Round 4 — 51 findings across correctness, process lifecycle, robustness, and internals — validated by live testing. No new headline feature, but nearly every subsystem got measurably safer.

## Reliability: external processes and long operations

- **Large file moves are no longer killed at 120 seconds.** The move pipeline previously enforced a fixed 120-second deadline per task and interrupted anything slower — a big episode copied to a NAS/SMB share routinely hit it, losing the partial copy. Tasks now run untimed: runaway *tools* are bounded by their own size-scaled timeouts, and closing the window still cancels cleanly.
- **A hung external tool can no longer freeze TVRenamer.** Tool output is now drained on a dedicated thread while the timeout clock runs against the process itself. Previously, an MP4Box/mkvmerge that hung with its output pipe open blocked forever — every computed timeout was unreachable. Now the tool is killed when its (size-scaled, 30–600 s) budget expires, and output produced before the hang is still captured for diagnostics.
- **One stuck batch can't wedge future batches.** Each rename run now gets its own worker executor, torn down when the run ends. Previously a single wedged task stalled every subsequent rename until the app was restarted.
- **Transient provider failures no longer disable lookups for the whole session.** A single 404 from the listings provider used to permanently latch "API discontinued". The latch now requires three consecutive 404s (reset by any success), and transient failures (429/5xx/network errors) get one automatic retry.

## Bug fixes

- **Sorting the table no longer corrupts row state.** Sorting recreated rows without their internal identity data — merge progress and icons silently vanished for repositioned rows, episode-chain propagation skipped them, and an already-processed row could be re-queued (double-counting the persistent "Processed" counter). All per-row state now survives sorting.
- **`.jpn.srt` (and eight other 3-letter-tagged subtitle files) now merge with the correct language.** The language lookup accepted `ja` but rejected `jpn` — the tagged file silently fell back to your default language. Every code the lookup can produce is now also accepted as input.
- **Completed rows keep their green check.** With auto-clear off, a moved file with no paired subtitles was downgraded from the COMPLETED check back to the pre-pipeline READY dot by the post-batch merge pass.
- **A failed timestamp write no longer fails a successful move.** On SMB shares that reject attribute writes, the move completed but the row showed a failure and the file was excluded from post-batch merging and duplicate filtering. Now a logged warning only.
- **Two same-episode media files can no longer steal each other's subtitles.** When two media files resolved to the same canonical name, source-side merge muxed the subtitles into an arbitrary one and (with delete-after-merge) deleted them — permanently denying the other file. Such groups now defer to the post-batch phase.
- **Stale rows can't crash the rename action.** A file moved or deleted outside TVRenamer between add and rename previously threw on the UI thread and aborted the whole batch.
- **Progress bar accounting is exact.** Fixed phantom ticks from unpaired media (bar clamped at 100% while merges still ran), missing ticks from failed moves and tool-less tag operations (bar stalled short), the off-by-one jump at batch start, and a wedged bar + disabled Rename button if an end-of-batch step threw.
- **Vanished source files now record an explicit failure reason** instead of an unexplained non-success.

## Robustness and data protection

- **Preferences and overrides are written atomically** (temp file + atomic rename). A crash mid-write previously left a truncated file that was silently "recovered" by resetting all your settings to defaults.
- **Generated path components are hardened**: provider-supplied names of `..` can no longer escape the destination root, Windows reserved device names (`CON`, `NUL`, `COM1`…) are defused, trailing dots/spaces and control characters are stripped. The (previously never-sanitised) season prefix is included.
- **Metadata tagging reports "tool missing" honestly.** A missing AtomicParsley/mkvpropedit previously reported tagging SUCCESS while files silently went untagged; there is now a distinct skip result and a once-per-session notice in the log.
- **UI resource leaks fixed**: table cell editors (leaked on every sort and preference refresh), merge progress overlays (stuck on screen forever if a row disappeared mid-merge), and dialog window icons (one GDI handle per dialog open).
- **Closing the window mid-move no longer throws** on disposed widgets, and the processed-file counter survives a close.

## Performance

- Tool detection (MP4Box, mkvmerge, taggers) is warmed on a background thread at startup — the first Preferences open no longer spawns probe processes on the UI thread.
- Preferences are persisted once per batch instead of once per file (a 500-file batch previously did 500 UI-thread disk writes interleaved with painting).
- Per-percentage-point merge progress updates use a cached row lookup instead of scanning the whole table on the UI thread.
- Provider listings fetches are bounded to 4 concurrent threads (previously unbounded — one thread per unique show).

## Internals (Code Review Round 4)

- **Test suite grew from 350 to 396**, with pinning tests for the batch-scope user-trust contract, the progress-prediction pairing rules, both tool progress parsers, WorkPlan (including under thread contention), swap-exhaustion recovery, and the hung-tool timeout.
- **The four external-tool integrations were consolidated**: one shared detection cache, one process-spawning seam (now injectable in taggers too), one merge skeleton for both subtitle mergers, and one temp-file naming scheme (`<name>.merging.<ext>` — MKV previously used a longer variant; leftovers from older versions are still cleaned up).
- The review checklist itself was upgraded (desktop threat model, Java type safety, a new external-tool-integration category), and the full findings + dispositions live in `docs/Code-Review-260708.md` — including an adversarial second-opinion pass that contributed three findings.
- Help pages now document all preferences (the Subtitles group and six others were missing); README and the agent-orientation docs no longer carry hardcoded version numbers that had drifted twice.
- CI now uploads the versioned fat jar (the upload pattern had been silently matching nothing).

## Dependency updates

| Dependency | From (v1.0.289) | To |
|------------|-----------------|-----|
| Gradle wrapper | 9.5.1 | 9.6.1 |
| JUnit Jupiter | 6.1.0 | 6.1.1 |
| SpotBugs plugin | 6.5.6 | 6.5.8 |
| SWT (win32 x64) | 3.134.0 | 3.134.0 (unchanged) |
| Shadow plugin | 9.4.2 | 9.4.2 (unchanged) |
