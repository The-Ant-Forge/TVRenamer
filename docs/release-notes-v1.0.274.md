## New feature: Subtitle merging

TVRenamer can now auto-merge sibling subtitle files (`.srt`, `.ass`, `.ssa`, `.vtt`) into the corresponding renamed media file as soft subtitle tracks. Embedded subtitles mean one less loose file per episode and proper subtitle support in any player.

### How it works

- **Enabled in Preferences → General → Subtitles.** Master toggle, dropdown for the default language (30 entries, English by default), and a "Delete subtitle files after successful merge" option.
- **Pairs subtitle files by base name.** `Westmark Academy.S01E02.mkv` finds `Westmark Academy.S01E02.srt`, `.eng.srt`, `.en.sdh.forced.srt`, `.pt-BR.srt`, etc. Filename language tags (2-letter, 3-letter, or English name) are normalised to the proper container language code; descriptors like SDH, Forced, and Commentary set the appropriate per-track flags on MKV.
- **MP4 / M4V** uses **MP4Box** from GPAC (gpac.io). Supports `.srt` and `.vtt`; `.ass`/`.ssa` are unsupported in MP4 containers and are skipped with a warning.
- **MKV** uses **mkvmerge** from MKVToolNix (mkvtoolnix.org), the same package already used for MKV metadata tagging. Supports all four subtitle formats with full per-track flag control.
- **Idempotent.** The container is inspected before merging; languages already present are skipped, so re-running the rename pipeline never adds duplicate tracks.
- **Safe.** Merge runs against the source file (faster than working over a NAS destination) and uses a temp+swap with integrity gate and 3× retry under transient AV/indexer locks. If the swap genuinely fails, the merged temp file is preserved for manual recovery rather than silently lost. Sibling subtitle files are never deleted unless the move ultimately succeeds.
- **Optional tools.** If MP4Box and/or mkvmerge are not on PATH, the corresponding format simply moves without merged subtitles. A single notice is logged per session.

### Spec, plan, and help

- Full feature spec: `docs/Subtitle Merge Spec.md`
- Implementation plan: `docs/Subtitle Merge Plan.md`
- User-facing help: in-app **Subtitle Merging** page

## Other improvements

- Fixed Gradle wrapper bump (9.3.1 → 9.4.1) version reference in README runtime-dependency note.
- README dependency list now distinguishes optional external tools (mkvpropedit, AtomicParsley/ffmpeg, MP4Box, mkvmerge) from the always-required SWT runtime.
