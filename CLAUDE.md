# Agent Notes (TVRenamer)

Pragmatic orientation for an agent joining cold: build, test, diagnose, and ship changes without guesswork.

---

## Project basics

- **UI framework:** SWT (native UI toolkit)
- **Build system:** Gradle (wrapper included)
- **Primary target OS:** Windows (SWT dependency is configured for Windows in `build.gradle`)
- **Java version (build):** JDK 21 (toolchain enforced)
- **Java version (runtime):** Java 17+ (bytecode compatibility target)
- **Static analysis:** SpotBugs (Gradle task: `spotbugsMain`)

Key files:
- `build.gradle` — build + packaging (ShadowJar, Launch4j) + static analysis (SpotBugs)
- `.github/workflows/windows-build.yml` — Windows CI build + artifact uploads
- `gradlew` / `gradlew.bat` — Gradle wrapper scripts

---

## Local development (Windows)

### Prerequisites
- **Git**, **JDK 17** (CI uses Temurin 17), **Gradle wrapper** included.

### Commands (repo root, use `./gradlew` in bash)

| Purpose | Command |
|---------|---------|
| Fast compile + test | `./gradlew build` or `./gradlew test` |
| SpotBugs | `./gradlew spotbugsMain` |
| Clean build | `./gradlew clean build` |
| Full packaging | `./gradlew clean build shadowJar createExe` |

- `./gradlew build` does NOT rebuild the fat JAR or EXE. Run `shadowJar createExe` when testing UI/packaging changes.
- On Windows, `clean` can fail if the EXE/JAR is locked. Fall back to `./gradlew build shadowJar createExe`.

Artifacts: `build/libs/tvrenamer.jar` (fat JAR), `build/libs/tvrenamer-<commitCount>.jar` (versioned), `build/launch4j/TVRenamer.exe`.

### Debug file logging

Enable with `-Dtvrenamer.debug=true`. In PowerShell, quote it: `java "-Dtvrenamer.debug=true" -jar .\tvrenamer.jar`. Log file is `tvrenamer.log` next to the jar/exe (or `%TEMP%`), overwritten each run.

---

## Working style

### Build loop
1. Make a small, targeted change.
2. `./gradlew build` (minimum) or `./gradlew test` when touching logic with coverage.
3. **Always `./gradlew clean build` before committing/pushing** and before releases.
4. Run `shadowJar createExe` when changing UI, startup, resources, or packaging config.

### Keep diffs focused
- Avoid unrelated reformatting and line-ending churn.

### No real show names or company names in project text
In code comments, commit messages, release notes, documentation, and test data:
- **Never use real TV show names.** Use plausible fictional names instead (e.g., "Westmark Academy", "Solar Drift", "The Quiet Ones").
- **Never name real streaming services, studios, or media companies.** Refer generically or use fictional names.
- **Why:** TVRenamer is a tool for organising legitimately owned media. Referencing real shows or services could imply or encourage unlawful use.

### Balanced analysis
When presenting options or proposals, always include downsides alongside benefits. Don't be a "yes-agent" — honest tradeoffs beat enthusiasm.

---

## TODO + Completed workflow

- **Future work** in `docs/TODO.md`, **completed-work record** in `docs/Completed.md`.
- When implementing a TODO item: do the work, update TODO.md (remove completed), add to Completed.md (Title, Why, Where, What), clean up in-code TODOs.
- Prefer small commits: one per focused item.

---

## Git workflow

Commit and push directly to `master` after a local compile:
```
git add -A && git commit -m "Meaningful summary" && git push
```

For isolated risk, use a PR: `gh pr create --fill`

---

## CI (GitHub Actions)

Workflow: `.github/workflows/windows-build.yml` — triggers on push/PR to `master`/`main`.
Runs `gradle build shadowJar createExe --info` on `windows-latest` with JDK 17 + Gradle 8.5.

Uploads: `TVRenamer-Windows-Exe` (EXE) and `TVRenamer-JAR` (fat JARs).

Useful `gh` commands: `gh run list`, `gh run watch --exit-status`, `gh run view --log-failed`, `gh run download --dir ./artifacts`.

---

## GitHub Release procedure

### Versioning
`v1.0.<commitCount>` where commitCount = `git rev-list --count HEAD`.

### Preconditions
1. HEAD is the commit to release.
2. Successful CI run for that commit on `master`.
3. Tag does not already exist (if it does, stop and ask).

### Steps
1. Compute tag: `git rev-list --count HEAD` → `v1.0.<N>`
2. Download CI artifacts for the exact commit SHA.
3. Tag and push: `git tag v1.0.<N> && git push origin v1.0.<N>`
4. Create GitHub Release, upload EXE + JARs.

### Release notes
Write to `docs/release-notes-v1.0.<N>.md` (no top-level heading — GitHub adds the title). Structure: New features/improvements, then Bug fixes. Publish with `gh release edit v1.0.<N> --notes-file docs/release-notes-v1.0.<N>.md`.

### Artifact hygiene
Do not blindly upload `build/libs/*.jar` from local. Prefer CI artifacts, or ensure a clean build, or upload explicit filenames to avoid stale versioned jars.

### Documentation check before release
Update help files (`src/main/resources/help/*.html`), README, release notes, TODO/Completed as needed.

---

## Diagnosing issues

- CI failures: `gh run view --log-failed`, confirm commit SHA matches.
- Runtime: enable `-Dtvrenamer.debug=true`, check `tvrenamer.log`.
- Do **not** commit temporary debug instrumentation — keep it local until the real fix is ready.

---

## Platform notes (SWT + Windows)

- SWT is platform-native; UI theming varies by OS. Some elements (menus, title bars) remain OS-themed.
- Validate UI changes on Windows first (CI builds on Windows).
- Line endings: avoid churn from LF/CRLF differences.

---

## Code Review Phases

Periodically we do a consolidation review covering all source, tests, build config, and metadata.

### Review Checklist
1. **Dead code** — unused functions, classes, modules, imports, config keys
2. **Dead dependencies** — libraries that are unused or underused relative
   to what we could replace inline
3. **Duplication** — repeated or near-identical logic that should be shared
4. **Naming & consistency** — mixed conventions, unclear names, stale comments
5. **Error handling** — inconsistent patterns, swallowed exceptions, missing
   user-facing messages
6. **Security** — input validation gaps, credential handling, OWASP patterns
7. **Type safety** — missing annotations, `Any` overuse, type errors
8. **Test gaps** — untested code paths, stale tests, missing edge cases
9. **Documentation drift** — specs, docstrings, or README sections that no
   longer match the code
10. **Performance** — unnecessary work, avoidable allocations, slow patterns
11. **Robustness** — race conditions, resource leaks, missing cleanup
12. **TODO/FIXME/HACK audit** — resolve or remove stale markers

### Deliverable
A review document in `docs/` named `Code-Review-YYMMDD.md` (or similar) with:
- Summary table: Category, Description, Action, Impact, Effort, Risk
- Detailed findings grouped by category, ordered by impact then effort
- Out-of-scope items noted for TODO.md

### Process
1. Produce the review document — do NOT implement during review
2. Review and approve findings with the user
3. Implement approved items in focused commits
4. Re-run tests after each change
