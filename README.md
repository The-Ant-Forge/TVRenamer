# TVRenamer
[![Windows Build](https://github.com/The-Ant-Forge/TVRenamer/actions/workflows/windows-build.yml/badge.svg?branch=master)](https://github.com/The-Ant-Forge/TVRenamer/actions/workflows/windows-build.yml?query=branch%3Amaster)
![Windows x64](https://img.shields.io/badge/Windows-x64-blue)
![Java](https://img.shields.io/badge/Java-17%2B-blue)

## About
TVRenamer is a Java GUI utility to rename TV episodes from TV listings.
It will take an ugly filename like **Solar.Drift.[3x08].DD51.720p.WEB-DL.AVC-FUSiON.mkv** and rename it to **Solar Drift S03E08 Into the Rings.mkv**

## Fork / provenance
This repository was originally forked from the abandoned yet incredibly useful https://github.com/tvrenamer/tvrenamer

It was then intentionally disconnected from the upstream fork relationship while experimenting with coding agents, to avoid any risk of accidentally pushing changes to the wrong repository and making a mess. This fork has been modernised and updated with current libs and many new features (see below).

## Screenshot (upstream)
The screenshot currently references the upstream wiki:

## [Screenshot](https://github.com/tvrenamer/tvrenamer/wiki/Screenshots)
![Screenshot](https://raw.githubusercontent.com/wiki/tvrenamer/tvrenamer/tvrenamer-0.5b2.png)

## Features
### Core
 * Rename many different shows at once using information from [TheTVDB](http://thetvdb.com/)
 * Customise the format and content of the resulting filename
 * Native look & feel for your operating system (SWT)
 * Drag & Drop or standard 'add file' interface
 * Optionally move renamed files, e.g. a NAS or external HDD

### New / improved in this fork
 * **DVD/BluRay ordering** options in addition to Aired Order
 * **Overrides** to map "show name → show name" when matching is incorrect
 * **Batch show disambiguation**: when TVDB returns multiple candidates for a show, TVRenamer prompts you to select the correct one (shows name/year/id/aliases).
 * **Remembered show selections**: your disambiguation choice is persisted and reused for future lookups of the same show query.
 * **Multi-Episode Files**: Handles multi-episode files (S02E01-02, S02E01-E02, etc.)
 * **SMB/network renaming**: supported for SMB renaming on network shares like NASs (report edge cases)
 * **Theme support**: Light, Dark, and Auto (OS-detected). Requires restart.
 * **Metadata tagging**: Embed TV metadata (show, season, episode, title, air date) directly into video files:
   - **MP4/M4V/MOV**: Built-in support using iTunes-style atoms (works with Plex, Kodi, Jellyfin, iTunes, VLC)
   - **MKV/WebM**: Requires [MKVToolNix](https://mkvtoolnix.download/) installed (uses mkvpropedit)
 * **Always overwrite**: Option to overwrite existing destination files instead of creating versioned copies
 * **Duplicate cleanup**: After moving, optionally delete other video files representing the same episode (same base name or season/episode). User confirmation required before deletion.
 * **Fuzzy episode matching**: Detects files like "S01E02" vs "1x02" as the same episode for conflict detection and duplicate cleanup
 * **Parse failure diagnostics**: When files can't be parsed, shows specific reasons (no show name, no season/episode pattern, etc.) with a summary dialog after batch processing

## Usage & Download

> ## Please Note (Windows EXE)
> Your virus software may display a false positive on the Windows executable. This has been reported historically upstream:
> [#238](https://github.com/tvrenamer/tvrenamer/issues/238)
>
> This software is open source and contains no viruses. You can inspect the source and build it yourself if you're interested.
> If you get a warning from your virus software, please report it to the vendor as a false positive.

### Downloads
This fork currently builds Windows artifacts via GitHub Actions. You can use:
- **GitHub Releases** (if/when published), or
- **GitHub Actions artifacts** from the latest successful run on `master`:
  - `TVRenamer-Windows-Exe` (Windows x64)
  - `TVRenamer-JAR` (fat jar)

### Running on Windows
**Windows x64 only:** this fork builds against SWT `win32.win32.x86_64` and requires a 64-bit Java 17+ runtime.

1. Download the Windows artifact (EXE) or the JAR.
2. Ensure **64-bit Java 17+** is installed (see “Common Problems → Java version issues” below).
3. Run:
   - `TVRenamer.exe` (Windows executable), or
   - `java -jar <jar-file>.jar` (if you downloaded the jar artifact)

> Note: This fork’s build configuration targets Windows x64 SWT (`win32.win32.x86_64`). macOS/Linux packaging instructions from upstream may not apply here.

## Common Problems
### Connectivity Issues
If you receive errors about being unable to connect to the internet, ensure:
- your network allows outbound HTTPS connections, and
- you are running a recent build of this fork.

### Java version issues
**Java 17** is required. Type `java -version` into your terminal and ensure that the output indicates Java 17+, for example:

    $ java -version
    openjdk version "17.0.x" 202x-xx-xx
    OpenJDK Runtime Environment (...)
    OpenJDK 64-Bit Server VM (...)

### x86/ 64 bit architecture version
Ensure that you are running the same architecture of TVRenamer as Java. `java -version` displays the version on the last line, as above. If you don't have it right, you get an unhelpful error message on startup (when running on the terminal), like below:
    Exception in thread "main" java.lang.UnsatisfiedLinkError: Cannot load 32-bit SWT libraries on 64-bit JVM

### "TVRenamer can't be opened because it's from an unidentified developer" error message on OSX Mountain Lion or above.
This is because we have not signed the application with Apple (and because we use Java, they won't allow us to). To get around this, just right-click the app in Finder and select Open. You only need to do this once.
[More information from iMore](http://www.imore.com/how-open-apps-unidentified-developer-os-x-mountain-lion)

## Running in debug mode (logging)
If the application crashes, it helps greatly if you can provide logs and a stacktrace.

This fork supports file logging via a system property:

- Enable debug logging: `-Dtvrenamer.debug=true`
- Log file name: `tvrenamer.log`
- Log location: next to the executable/jar if possible, otherwise `%TEMP%`
- The log is overwritten each run
- Fatal errors will attempt to write an environment summary + stacktrace to the log

### Windows (PowerShell or Command Prompt)
If you are running the JAR:
- `java -Dtvrenamer.debug=true -jar <TVRenamer-jar>.jar`

If you are running the EXE:
- Launch4j-wrapped EXEs may still be launched normally; for JVM flags you may prefer running the JAR build for debugging.

When reporting issues, attach `tvrenamer.log` if it exists.

## Development (this fork)

### Requirements
- Windows environment recommended (CI builds on Windows)
- Java 17+
- Git

### Build from source
From the repository root:

- `./gradlew test`
- `./gradlew build`
- `./gradlew shadowJar`
- `./gradlew createExe`

CI (GitHub Actions) runs a Windows build and uploads artifacts:
- `TVRenamer-Windows-Exe` (`build/launch4j/TVRenamer.exe`)
- `TVRenamer-JAR` (`build/libs/*.jar`)

### Recent library/tooling updates
Current versions are managed via Gradle version catalogs (`gradle/libs.versions.toml`).

Runtime dependencies:
- SWT (Windows x64) 3.133.0
- JDK built-in XML (javax.xml) and HTTP (java.net.http) APIs

Test dependencies:
- JUnit 5.14.3 (Jupiter)

Build tooling:
- Gradle 9.3.1
- Shadow plugin 9.3.2 (GradleUp)
- Launch4j Gradle plugin 4.0.0
- SpotBugs plugin 6.4.8

## Contributions
If you'd like to contribute, open a pull request against the `master` branch.

For feature requests and bug reports, please open an issue in this repository (and attach `tvrenamer.log` if you ran with `-Dtvrenamer.debug=true`).
