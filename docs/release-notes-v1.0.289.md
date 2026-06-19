A maintenance release — no headline new feature. Subtitle-merge scope fix (user-trust bug), refined status icon vocabulary, internal test-code cleanup, and a round of dependency bumps including JUnit 5 → 6 and Gradle 9.4 → 9.5.

## Bug fixes

- **Subtitle merge no longer touches files outside the rename batch** — the post-batch merge phase previously enumerated every regular file in each destination directory the batch landed in, which meant pre-existing media files in those folders could have their sibling subtitle files silently muxed in even though the user never added them to TVRenamer. The candidate list is now built strictly from files in the batch: media-container movers contribute their destination path directly, and subtitle movers contribute only same-base media siblings via a prefix check. Unrelated files in the destination folder are never touched. Damage from the previous behaviour was limited by the merger's idempotency check (`alreadyHasLanguageTrack`) so most users won't have hit it visibly, but the trust violation was real.

## UX improvements

- **Distinct READY (pre-pipeline) and COMPLETED (post-pipeline) status icons** — the row icon vocabulary now reads as a traffic light. A row that has parsed and is staged to run shows a solid green dot (READY); a row that has fully completed shows a green-circle-with-check (COMPLETED); a row that errored shows a solid red dot (FAIL). Previously the same tick icon was used both at parse-time and end-of-pipeline, which made "ready" rows visually identical to "done" rows, and after a successful move the row stayed on the MOVING arrow until the table was cleared.

## Code updates

- **Subtitle merger test-indirection unified** — `Mp4SubtitleMerger` and `MkvSubtitleMerger` previously had two different test seams (static `RunOperation` setter vs. subclass-overridable `runProcess` method). Both now route process spawning through a shared `ProcessOps.Run` / `ProcessOps.Streaming` functional-interface pair, injected via the constructor. Removes static mutable indirection state, makes the seam explicit and per-instance, and lets the test fakes use a single `FakeMerger` shape across both classes.

## Dependency updates

| Dependency | From | To |
|------------|------|----|
| Gradle wrapper | 9.4.1 | 9.5.1 |
| SWT (win32 x64) | 3.133.0 | 3.134.0 |
| JUnit Jupiter | 5.14.3 | 6.1.0 |
| Shadow plugin | 9.4.1 | 9.4.2 |
| SpotBugs plugin | 6.5.1 | 6.5.6 |
| Launch4j plugin | 4.0.0 | 4.0.0 (unchanged) |

The JUnit 5 → 6 jump is a major version bump but went through with zero code changes — the test suite only uses the stable subset of JUnit's API (basic `@Test` / `@BeforeEach` / `@AfterEach` / `@TempDir` / `@Nested` / `@Disabled` / `Assertions.*`), no Mockito, no custom Extensions, no direct JUnit Platform Launcher use. All 350 tests pass unchanged.
