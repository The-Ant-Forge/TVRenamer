## Bug fixes

- **Preferences dialog Matching tab:** The dialog no longer grows beyond the screen when many Overrides or Disambiguations entries are present. Both tables now have a fixed default height with internal scrollbars for overflow.

## Under the hood

- Gradle wrapper 9.3.1 → 9.4.1 (CI now uses the wrapper version directly, single source of truth).
- Added Tauri migration feasibility study (`docs/Tauri-Feasibility-Study.md`) — balanced assessment of staying on Java/SWT vs migrating to Tauri with Rust + Svelte.
