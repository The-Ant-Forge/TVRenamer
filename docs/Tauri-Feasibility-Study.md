# Feasibility Study: TVRenamer — Stay on Java/SWT vs Migrate to Tauri

## Context

TVRenamer is a mature Java/SWT desktop app (~21,500 lines, 77 source files) for renaming TV episode files. The app has reached a stable feature plateau. This document evaluates whether migrating to Tauri (Rust + Svelte) is worth the investment, with an honest look at both sides.

---

## The Case for Staying on Java/SWT

### It works, and it's done
- The app is stable, tested (100+ unit tests), and feature-complete for its core use case
- Three rounds of code review have hardened the codebase
- All dependencies are up to date and minimal (just SWT + JDK built-ins)
- The build pipeline, CI, and release process are well-oiled

### SWT is not actually dying
- Eclipse IDE still depends on SWT — it's maintained as long as Eclipse exists
- SWT 3.133.0 shipped March 2026 with new features (dynamic image scaling)
- The "shrinking ecosystem" is relative — SWT was never a mainstream choice, but its users (Eclipse, RCP apps) keep it alive
- Our fat-JAR manifest workaround is 3 lines in `build.gradle` — not a serious maintenance burden

### Java's strengths for this app
- Excellent regex engine (the parser is 500+ lines of regex — Rust's regex crate differs in subtle ways)
- Mature file I/O with `java.nio.file` (Windows UNC paths, atomic moves, file attributes)
- ProcessBuilder for external tool coordination is battle-tested
- Java's garbage collector means no ownership/lifetime complexity for the concurrent show-lookup and file-move pipelines
- JDK 21 virtual threads could simplify our ExecutorService usage if we wanted

### Cost of doing nothing: low
- No active bugs, no user complaints about performance or UX
- Dependency updates are infrequent and mechanical (we just proved this)
- New features (if any) are additive — the architecture supports them

### What you'd lose
- **3 rounds of hardening** — the Java codebase has been through extensive review. A rewrite starts from zero trust.
- **Test coverage** — 100+ tests would need porting. Tests that pass in Java may fail in Rust due to regex/string/path differences.
- **Institutional knowledge** — CLAUDE.md, Completed.md, review docs all reference Java code. A rewrite orphans this documentation.
- **Time** — 30-45 working days is 6-9 weeks. That's time not spent on features or other projects.

---

## The Case for Migrating to Tauri

### Distribution is dramatically simpler
- **Today:** Fat JAR (~15MB) + JRE (~50MB+) or rely on user-installed Java. Launch4j wraps into EXE but still needs JRE.
- **Tauri:** Single binary, 5-8MB, no runtime dependency (WebView2 is pre-installed on Windows 10+, WebKit on macOS)
- Users download one file and run it. No Java version confusion.

### Cross-platform becomes free
- SWT is configured for Windows only (`osgi.platform = win32.win32.x86_64`). Supporting macOS/Linux means separate SWT JARs, separate builds, separate testing.
- Tauri builds for all three platforms from one codebase. GitHub Actions matrix makes CI simple.
- The CocoaUIEnhancer.java (macOS-specific SWT code using reflection) becomes unnecessary — Tauri handles platform menus natively.

### Modern UI capabilities
- SWT's Table widget is functional but limited — no virtual scrolling, no smooth animations, styling requires per-cell Color objects that must be manually disposed
- Web UI: CSS for theming (light/dark via `prefers-color-scheme`), virtual scrolling for performance, reactive updates without SWT's `Display.asyncExec()` ceremony
- Drag-and-drop, progress indicators, inline dropdowns are all simpler in HTML/CSS/JS

### Rust's safety guarantees
- No null pointer exceptions, no unchecked casts, no `catch(Exception)` safety nets
- Ownership model prevents the class of concurrency bugs we've been fixing (disposed widget access, thread lifecycle management)
- Compile-time guarantees replace runtime logging/hoping

### The architecture is migration-ready
- Model layer has **zero** SWT imports — cleanest possible separation
- Controller layer has only **2** trivial SWT imports (HelpLauncher, UrlLauncher)
- Each subsystem (parser, HTTP client, file mover, tagger) has clear boundaries
- This isn't an entangled mess — it's a well-structured app that happens to use an aging toolkit

### Long-term maintainability
- Rust + TypeScript/Svelte has a much larger talent pool than Java + SWT
- Tauri ecosystem is growing (Tauri v2 released 2024, active development)
- Cargo + npm tooling is more modern than Gradle for this type of project

---

## Honest Downsides of Migrating

| Risk | Severity | Mitigation |
|------|----------|------------|
| **Rewrite takes longer than estimated** | High | Always does. 30-45 days could become 60+. |
| **Rust learning curve** | Medium-High | Ownership/lifetimes/async are genuinely hard. Budget extra time. |
| **Regex behaviour differences** | Medium | Java and Rust regex differ on Unicode. Parser tests will catch this but fixing may be fiddly. |
| **Feature parity takes forever** | Medium | The "last 10%" (drag-drop edge cases, keyboard shortcuts, accessibility) always takes disproportionate time. |
| **WebView2 not guaranteed** | Low | Missing on Win10 LTSC/IoT. Tauri can bundle bootstrapper. |
| **TVDB v1 XML API could break** | Low | Same risk on both platforms. Migration to v4 JSON is independent of framework choice. |
| **Second-system effect** | Medium | Temptation to "improve" everything during rewrite. Discipline needed to port, not redesign. |
| **Existing users need to re-import preferences** | Low | One-time XML → TOML migration on first run. |

---

## Decision Matrix

| Factor | Stay (Java/SWT) | Migrate (Tauri) |
|--------|-----------------|-----------------|
| Effort to maintain | Very low (mature, stable) | High upfront, then low |
| Distribution size | ~15MB JAR + JRE | ~5-8MB standalone |
| Cross-platform | Hard (separate SWT builds) | Built-in |
| UI modernization | Limited by SWT | Full web stack |
| Binary startup | ~2-3s (JVM warmup) | <1s |
| Risk of regression | None (don't touch it) | High during rewrite |
| Contributor accessibility | Low (SWT is niche) | Higher (Rust/Svelte) |
| User requires | JRE 17+ installed | Nothing (WebView2 pre-installed) |

---

## Recommendation

**If the app is "done" and serving its purpose:** Stay on Java/SWT. The cost-benefit doesn't justify a rewrite for a stable, working app with no active pain points. Ship fixes as needed, bump dependencies quarterly.

**If you want to learn Rust, go cross-platform, or attract contributors:** Migrate. The architecture is unusually well-suited for it (clean MVC, isolated subsystems, comprehensive test suite to port). But go in with eyes open about the timeline — rewrites always take 1.5-2x the estimate.

**Middle ground:** Keep the Java app as the production release. Start the Tauri version as a separate repo/experiment. When it reaches feature parity and passes the full test suite, switch over. No rush — the Java app isn't going anywhere.

---

## If Migrating: Technical Plan

**Stack:** Tauri v2 + Rust backend + Svelte frontend + TypeScript

**Key decisions:**
- Svelte frontend (smallest bundle, reactive, Tauri-native)
- Keep TVDB v1 XML API for now (migrate to v4 JSON later)
- TOML for preferences (clean break from XML, with one-time import for existing users)
- Cross-platform from day one (Windows + macOS + Linux)

---

## Current Architecture → Tauri Mapping

```
JAVA/SWT                           TAURI/RUST + SVELTE
─────────────────────────────────────────────────────────
view/ (23 files, SWT)          →   src/ (Svelte components)
controller/ (29 files)         →   src-tauri/src/ (Rust commands)
model/ (25 files)              →   src-tauri/src/models/ (Rust structs)
build.gradle                   →   Cargo.toml + package.json + tauri.conf.json
javax.xml                      →   quick-xml crate
java.net.http                  →   reqwest crate
java.nio.file                  →   std::fs + walkdir crate
ProcessBuilder                 →   std::process::Command
java.util.concurrent           →   tokio async runtime
SWT Display.asyncExec()        →   Tauri events (emit/listen)
PropertyChangeSupport          →   Svelte stores (reactive)
java.util.logging              →   tracing crate
```

---

## Project Structure

```
tvrenamer-tauri/
├── src-tauri/
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   └── src/
│       ├── main.rs                 # Tauri app setup
│       ├── commands/               # Tauri IPC commands
│       │   ├── mod.rs
│       │   ├── files.rs            # Add/scan/move files
│       │   ├── shows.rs            # Show lookup, disambiguation
│       │   ├── preferences.rs      # Settings CRUD
│       │   └── updates.rs          # Version check
│       ├── models/                 # Data structures
│       │   ├── mod.rs
│       │   ├── episode.rs          # Episode, Season, EpisodePlacement
│       │   ├── show.rs             # Show, Series, ShowOption, FailedShow
│       │   ├── show_name.rs        # ShowName, query string mapping
│       │   ├── file_episode.rs     # FileEpisode (per-file state)
│       │   ├── preferences.rs      # UserPreferences (TOML-backed)
│       │   └── overrides.rs        # GlobalOverrides
│       ├── parser/                 # Filename parsing
│       │   ├── mod.rs
│       │   └── filename_parser.rs  # Regex-based parser
│       ├── provider/               # TVDB API client
│       │   ├── mod.rs
│       │   ├── tvdb.rs             # XML API interaction
│       │   └── http.rs             # HTTP client wrapper
│       ├── mover/                  # File operations
│       │   ├── mod.rs
│       │   ├── file_mover.rs       # Move/copy with progress
│       │   ├── file_utils.rs       # Utility functions
│       │   └── duplicate.rs        # Duplicate detection
│       ├── tagger/                 # Metadata tagging
│       │   ├── mod.rs
│       │   ├── mkv.rs              # mkvpropedit integration
│       │   ├── mp4.rs              # ffmpeg/AtomicParsley integration
│       │   └── tools.rs            # External tool detection
│       └── util/
│           ├── mod.rs
│           ├── strings.rs          # String normalization/sanitization
│           └── formatting.rs       # Episode replacement formatter
├── src/                            # Svelte frontend
│   ├── App.svelte                  # Root component
│   ├── main.ts                     # Entry point
│   ├── lib/
│   │   ├── components/
│   │   │   ├── FileTable.svelte        # Main results table
│   │   │   ├── FileRow.svelte          # Per-file row with inline controls
│   │   │   ├── ProgressCell.svelte     # Per-file progress indicator
│   │   │   ├── EpisodeSelect.svelte    # Inline episode dropdown
│   │   │   ├── PreferencesDialog.svelte # Settings (tabbed)
│   │   │   ├── AboutDialog.svelte      # Version info
│   │   │   ├── DisambiguationDialog.svelte # Show selection
│   │   │   └── DuplicateDialog.svelte  # Duplicate cleanup
│   │   ├── stores/
│   │   │   ├── files.ts            # File list state (Svelte store)
│   │   │   ├── preferences.ts      # Settings state
│   │   │   └── shows.ts            # Show cache state
│   │   └── types/
│   │       └── index.ts            # TypeScript interfaces matching Rust structs
│   └── styles/
│       ├── theme.css               # CSS variables for light/dark
│       └── table.css               # Table-specific styles
├── package.json
├── svelte.config.js
├── vite.config.ts
└── tsconfig.json
```

---

## Implementation Phases

### Phase 1: Scaffold + Core Model (3-4 days)
- `cargo create-tauri-app` with Svelte template
- Define Rust structs: `Episode`, `Season`, `Show`, `Series`, `ShowOption`, `FileEpisode`
- Derive `serde::Serialize` + `serde::Deserialize` on all structs (for IPC + persistence)
- Port `EpisodePlacement` (record → Rust struct)
- Port enums: `ItemState`, `ThemeMode`, `ReplacementToken`
- Set up `tracing` for logging
- **Test:** Port existing model unit tests (ShowTest, SeriesTest, ShowNameTest — 49 tests)

### Phase 2: Filename Parser (2-3 days)
- Port `FilenameParser` regex patterns to Rust `regex` crate
- Port `StringUtils` (normalization, sanitization, formatting)
- Port `EpisodeReplacementFormatter` (token substitution)
- **Test:** Port all `FilenameParserTest` cases (100+ test inputs)
- **Test:** Port `StringUtilsTest` cases

### Phase 3: TVDB API Client (2-3 days)
- HTTP client wrapper using `reqwest` (async)
- XML response parsing using `quick-xml` + XPath-style navigation
- Port `TheTVDBProvider` episode/series extraction
- `ShowStore` equivalent: async show lookup with caching
- **Test:** Mock HTTP responses, verify parsing

### Phase 4: File Operations (3-4 days)
- Port `FileUtilities`: move, copy, delete, video extension detection, cross-filesystem detection
- Port `FileMover`: move with progress reporting via Tauri events
- Duplicate detection logic
- mtime preservation using `filetime` crate
- **Test:** Port `FileEpisodeTest`, `MoveTest`, `FileUtilsTest`

### Phase 5: Metadata Tagging (2-3 days)
- External tool detection (`which` crate + platform-specific paths)
- Port `MkvMetadataTagger`: construct mkvpropedit arguments
- Port `Mp4MetadataTagger`: construct ffmpeg/AtomicParsley arguments
- Process execution with timeout via `tokio::process::Command`
- **Test:** Port `MetadataTaggingControllerTest`

### Phase 6: Preferences + Persistence (2-3 days)
- `UserPreferences` struct with TOML serialization (`toml` crate)
- One-time XML import: read existing `prefs.xml`, convert to `preferences.toml`
- `GlobalOverrides` with TOML persistence
- Tauri commands: `get_preferences`, `set_preferences`, `get_overrides`, `set_override`
- Config directory: use `dirs` crate for platform-appropriate paths
- **Test:** Round-trip serialization, XML import

### Phase 7: Frontend — Main Table (5-7 days)
- `FileTable.svelte`: virtual-scrolled table with columns
- `FileRow.svelte`: inline episode select, status colouring via CSS classes
- Drag-and-drop file addition (Tauri's `tauri-plugin-dialog` for file picker + HTML5 DnD)
- Progress indicators per-file and overall
- Bulk actions: Rename All, Clear Completed
- Svelte stores for reactive file list state
- Tauri event listeners for backend → frontend progress updates

### Phase 8: Frontend — Preferences (3-4 days)
- Tabbed preferences dialog (destination, formatting, metadata, matching, appearance)
- Drag-and-drop token builder for filename format template
- Live preview of format changes
- Override/disambiguation table editing
- Theme switcher (light/dark/auto via CSS `prefers-color-scheme`)

### Phase 9: Frontend — Other Dialogs (2-3 days)
- About dialog with version, links, update checker
- Batch show disambiguation dialog
- Duplicate cleanup dialog with file details
- Help system (embedded HTML or link to docs)

### Phase 10: Integration + Polish (3-5 days)
- Wire all Tauri commands to frontend
- Error handling: Rust errors → user-facing messages
- Update checker (GitHub Releases API)
- App icon, window title, taskbar integration
- Platform testing: Windows, macOS, Linux
- Installer/bundle configuration in `tauri.conf.json`

### Phase 11: Migration Path for Existing Users (1-2 days)
- Detect existing `prefs.xml` on first run, offer import
- Detect existing `overrides.xml`, import to TOML
- Document migration in README

---

## Cross-Platform Considerations

| Concern | Windows | macOS | Linux |
|---------|---------|-------|-------|
| WebView runtime | WebView2 (Edge, pre-installed Win10+) | WebKit (built-in) | WebKitGTK (package dep) |
| File paths | `\` separator, UNC paths, drive letters | `/` separator, `/Volumes/` | `/` separator, mount points |
| External tools | mkvpropedit.exe, ffmpeg.exe in PATH or common dirs | Homebrew paths (`/opt/homebrew/bin/`) | Package manager paths |
| Dark mode | `prefers-color-scheme` via WebView2 | Native via WebKit | GTK theme detection |
| Drag-and-drop | Explorer → WebView2 | Finder → WebKit | File manager → WebKitGTK |
| App distribution | `.msi` or `.exe` installer | `.dmg` bundle | `.deb` / `.AppImage` |

---

## Rust Crate Dependencies

```toml
[dependencies]
tauri = { version = "2", features = ["tray-icon", "dialog"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
toml = "0.8"
reqwest = { version = "0.12", features = ["blocking"] }
quick-xml = "0.37"
regex = "1"
walkdir = "2"
filetime = "0.2"
which = "7"
tokio = { version = "1", features = ["full"] }
tracing = "0.1"
tracing-subscriber = "0.3"
dirs = "6"
unicode-normalization = "0.1"
```

---

## Additional Technical Risks (beyond the downsides table above)

1. **Table performance** — SWT Table handles thousands of rows natively; web table needs virtual scrolling to match
2. **Drag-and-drop from OS file manager** — works but behaviour varies across WebView implementations; needs per-platform testing
3. **Linux WebKitGTK dependency** — users must install `libwebkitgtk` package; not always pre-installed on minimal distros

---

## Verification

1. **Unit tests:** Port all 100+ existing Java tests to Rust `#[test]` functions
2. **Frontend tests:** Svelte component tests with `@testing-library/svelte`
3. **Integration:** Add files via drag-drop, lookup shows, rename, verify file moved correctly
4. **Preferences migration:** Import existing `prefs.xml` → verify all settings preserved in TOML
5. **Cross-platform:** Build and test on Windows 11, macOS (ARM), Ubuntu 24.04
6. **Binary size:** Target < 10MB (Tauri apps typically 5-8MB)
7. **Startup time:** Target < 1 second (vs current Java ~2-3 seconds)
8. **CI:** GitHub Actions matrix build for all 3 platforms
