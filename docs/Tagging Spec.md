# Video Metadata Tagging Specification

## Overview

TVRenamer can optionally write TV episode metadata directly into video files. This allows media players and library managers (Plex, Kodi, iTunes, etc.) to display episode information without relying on filename parsing or external NFO files.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    FileEpisode                              │
│  (show name, season, episode, title, air date, etc.)        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              MetadataTaggingController                      │
│  - Checks if tagging enabled in preferences                 │
│  - Selects appropriate tagger by file extension             │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────┐     ┌─────────────────────────┐
│   Mp4MetadataTagger     │     │   MkvMetadataTagger     │
│   (.mp4, .m4v, .mov)    │     │   (.mkv, .webm)         │
│                         │     │   [FUTURE]              │
└─────────────────────────┘     └─────────────────────────┘
```

### Key Classes

| Class | Location | Purpose |
|-------|----------|---------|
| `VideoMetadataTagger` | `controller/metadata/` | Interface for format-specific taggers |
| `MetadataTaggingController` | `controller/metadata/` | Orchestrates tagging, selects tagger by extension |
| `Mp4MetadataTagger` | `controller/metadata/` | MP4/M4V/MOV implementation using AtomicParsley (preferred) or ffmpeg |

---

## MP4 Tagging (Current Implementation)

### Supported Extensions
- `.mp4`
- `.m4v`
- `.mov`

### External Tools
- **AtomicParsley** (preferred) — surgical iTunes atom edits, doesn't rewrite media data
- **ffmpeg** (fallback) — full container rewrite with `-map 0 -c copy`, ubiquitous.
  `-map 0` is required: without it ffmpeg's default stream selection keeps one video and
  one audio stream and drops subtitle tracks, including ones the subtitle merge just added.

### Atoms Written

MP4 uses iTunes-style metadata atoms stored in the `moov/udta/meta/ilst` box hierarchy.

We write multiple atoms for broad compatibility across media managers (Plex, Kodi, Jellyfin, Emby, iTunes, VLC, etc.):

| Atom Code | Constant | Type | Description | Example Value | Used By |
|-----------|----------|------|-------------|---------------|---------|
| `tvsh` | `ATOM_TVSH` | String | TV Show name | "Solar Drift" | iTunes, Plex, Kodi |
| `©alb` | `ATOM_ALB` | String | Album (= show name) | "Solar Drift" | Plex, VLC, generic players |
| `tvsn` | `ATOM_TVSN` | Integer | Season number | 1 | iTunes, Plex, Kodi |
| `tves` | `ATOM_TVES` | Integer | Episode number | 1 | iTunes, Plex, Kodi |
| `tven` | `ATOM_TVEN` | String | Episode title | "Pilot" | iTunes, Plex |
| `©nam` | `ATOM_NAM` | String | Title (filename w/o ext) | "Solar.Drift.S01E01" | VLC, generic players |
| `©day` | `ATOM_DAY` | String | Air date (ISO-8601) | "2008-01-20" | iTunes, Plex |
| `stik` | `ATOM_STIK` | Integer | Media kind (10=TV) | 10 | iTunes, Apple TV |

#### Rationale for Multiple Atoms

Different media managers look for different atoms:

| Field | Primary Atom | Fallback Atom | Notes |
|-------|--------------|---------------|-------|
| Show Name | `tvsh` | `©alb` | Plex may use `©alb` if `tvsh` missing |
| Episode Title | `tven` | `©nam` | `©nam` used as general display title |
| Display Name | `©nam` | - | Shows filename in generic players |

### Atom Structure

Each iTunes metadata atom follows this structure:

```
┌──────────────────────────────────────────┐
│ Outer Box (e.g., "tvsh")                 │
│ ┌──────────────────────────────────────┐ │
│ │ 4 bytes: total size                  │ │
│ │ 4 bytes: atom type (e.g., "tvsh")    │ │
│ │ ┌──────────────────────────────────┐ │ │
│ │ │ Data Box ("data")                │ │ │
│ │ │ 4 bytes: data box size           │ │ │
│ │ │ 4 bytes: "data"                  │ │ │
│ │ │ 4 bytes: type flag               │ │ │
│ │ │   1 = UTF-8 string               │ │ │
│ │ │   21 = signed integer (BE)       │ │ │
│ │ │ 4 bytes: locale (reserved, 0)    │ │ │
│ │ │ N bytes: actual data             │ │ │
│ │ └──────────────────────────────────┘ │ │
│ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

### Media Kind Values (stik)

| Value | Meaning |
|-------|---------|
| 0 | Movie |
| 1 | Music |
| 2 | Audiobook |
| 6 | Music Video |
| 9 | Short Film |
| 10 | **TV Show** (used by TVRenamer) |
| 11 | Booklet |
| 14 | Ringtone |

### Validation

Before tagging, `Mp4MetadataTagger` validates:
1. File has valid MP4 signature (`ftyp` box at offset 4)
2. Show data is available (`FileEpisode.getActualShow()`)
3. Episode data is available (`FileEpisode.getActualEpisode()`)
4. Episode placement is available (season/episode numbers)

---

## MKV Tagging (Implemented)

### Supported Extensions
- `.mkv`
- `.webm` (if Matroska-based)

### Matroska Target Type Values

Matroska uses a hierarchical tagging system with "Target Types":

| Value | Name | Description |
|-------|------|-------------|
| 70 | COLLECTION | TV Series / Movie Collection |
| 60 | SEASON | Season / Volume / Sequel |
| 50 | EPISODE | Episode / Movie / Concert |
| 40 | PART | Part / Movement |
| 30 | CHAPTER | Chapter / Scene |

### Tags to Write (Comprehensive)

For broad media manager compatibility, we'll write tags at multiple target levels:

#### Series Level (Target 70)

| Tag Name | Description | Example | Used By |
|----------|-------------|---------|---------|
| `TITLE` | Series/collection name | "Solar Drift" | Plex, Kodi, Jellyfin |
| `COLLECTION` | Alternate series name | "Solar Drift" | Kodi, Emby |
| `CONTENT_TYPE` | Media type identifier | "TV Show" | Kodi |
| `TOTAL_PARTS` | Total seasons (if known) | "5" | Some players |

#### Season Level (Target 60)

| Tag Name | Description | Example | Used By |
|----------|-------------|---------|---------|
| `PART_NUMBER` | Season number | "1" | Plex, Kodi, Jellyfin |
| `TITLE` | Season title (optional) | "Season 1" | Plex, Kodi |
| `TOTAL_PARTS` | Episodes in season (if known) | "7" | Some players |

#### Episode Level (Target 50)

| Tag Name | Description | Example | Used By |
|----------|-------------|---------|---------|
| `TITLE` | Episode title | "Pilot" | Plex, Kodi, Jellyfin, VLC |
| `PART_NUMBER` | Episode number | "1" | Plex, Kodi, Jellyfin |
| `DATE_RELEASED` | Original air date (ISO-8601) | "2008-01-20" | Plex, Kodi |
| `DATE_RECORDED` | Alternate date field | "2008-01-20" | Kodi |
| `SUBTITLE` | Episode subtitle/tagline | - | Some players |
| `SYNOPSIS` | Episode description | - | Plex, Kodi (future) |

### Cross-Reference: MP4 to MKV Mapping

| Semantic Field | MP4 Atom(s) | MKV Property/Tag | MKV Target |
|----------------|-------------|------------------|------------|
| Show Name | `tvsh`, `©alb` | `TITLE`, `COLLECTION` | 70 |
| Season Number | `tvsn` | `PART_NUMBER` | 60 |
| Episode Number | `tves` | `PART_NUMBER` | 50 |
| Episode Title | `tven` | `TITLE` | 50 |
| Display Title | `©nam` | Segment `title` | Info |
| Air Date | `©day` | `DATE_RELEASED`, `DATE_RECORDED` | 50 |
| Media Kind | `stik` | `CONTENT_TYPE` | 70 |

### Implementation: mkvpropedit (MKVToolNix)

**Decision:** Use mkvpropedit CLI only. No pure-Java fallback.

**Rationale:**
- mkvpropedit is mature, well-tested, handles all edge cases
- Pure Java EBML libraries (jebml) are old and focused on file creation, not surgical tag editing
- Writing our own EBML tag editor would be 500+ lines with many edge cases
- Users who want MKV tagging likely already have MKVToolNix installed

**Behavior:**
- MKV tagging is an **optional feature** requiring MKVToolNix
- If mkvpropedit is not found, MKV files are silently skipped (not an error)
- Detection is cached at startup for performance

**Detection Strategy:**
1. Try `mkvpropedit --version` in PATH
2. On Windows, also check `C:\Program Files\MKVToolNix\mkvpropedit.exe`
3. Cache result (static) - don't re-check per file

**Alternatives Considered (rejected):**
- Pure Java EBML: jebml is unmaintained, focused on file creation not tag editing
- FFmpeg: Overkill dependency, may re-mux unnecessarily
- Dual implementation: Two code paths = double maintenance, inconsistent behavior

### Matroska Tag XML Structure (for mkvpropedit)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Tags>
  <!-- Series/Collection Level -->
  <Tag>
    <Targets>
      <TargetTypeValue>70</TargetTypeValue>
      <TargetType>COLLECTION</TargetType>
    </Targets>
    <Simple>
      <Name>TITLE</Name>
      <String>Solar Drift</String>
    </Simple>
    <Simple>
      <Name>COLLECTION</Name>
      <String>Solar Drift</String>
    </Simple>
    <Simple>
      <Name>CONTENT_TYPE</Name>
      <String>TV Show</String>
    </Simple>
  </Tag>

  <!-- Season Level -->
  <Tag>
    <Targets>
      <TargetTypeValue>60</TargetTypeValue>
      <TargetType>SEASON</TargetType>
    </Targets>
    <Simple>
      <Name>PART_NUMBER</Name>
      <String>1</String>
    </Simple>
    <Simple>
      <Name>TITLE</Name>
      <String>Season 1</String>
    </Simple>
  </Tag>

  <!-- Episode Level -->
  <Tag>
    <Targets>
      <TargetTypeValue>50</TargetTypeValue>
      <TargetType>EPISODE</TargetType>
    </Targets>
    <Simple>
      <Name>TITLE</Name>
      <String>Pilot</String>
    </Simple>
    <Simple>
      <Name>PART_NUMBER</Name>
      <String>1</String>
    </Simple>
    <Simple>
      <Name>DATE_RELEASED</Name>
      <String>2008-01-20</String>
    </Simple>
    <Simple>
      <Name>DATE_RECORDED</Name>
      <String>2008-01-20</String>
    </Simple>
  </Tag>
</Tags>
```

### mkvpropedit Command

TVRenamer uses a single mkvpropedit call to set both Matroska tags and the segment title:

```bash
# Write tags from XML file AND set segment title (display name)
mkvpropedit "Show.S01E01.Episode.Title.mkv" \
  --tags global:tags.xml \
  --edit info --set "title=Show.S01E01.Episode.Title"
```

The segment `title` (set via `--edit info --set title=...`) is used by media players as the display name, equivalent to MP4's `©nam` atom.

**Note:** The segment title is set to the filename without extension, providing a fallback display name for players that don't read Matroska tags.

---

## Semantic Metadata Model

All taggers receive metadata via `FileEpisode`, which provides:

| Method | Returns | Used For | MP4 | MKV |
|--------|---------|----------|-----|-----|
| `getActualShow().getName()` | String | Show/series name | `tvsh`, `©alb` | `TITLE`@70, `COLLECTION`@70 |
| `getEpisodePlacement().season` | int | Season number | `tvsn` | `PART_NUMBER`@60 |
| `getEpisodePlacement().episode` | int | Episode number | `tves` | `PART_NUMBER`@50 |
| `getActualEpisode().getTitle()` | String | Episode title | `tven` | `TITLE`@50 |
| `getActualEpisode().getAirDate()` | LocalDate | Original air date | `©day` | `DATE_RELEASED`@50 |
| `videoFile.getFileName()` | String | Filename (no ext) | `©nam` | Segment `title` |

### Future: Explicit Metadata Record

If needed, we could introduce a format-agnostic record:

```java
public record TvEpisodeMetadata(
    String showName,
    int season,
    int episode,
    String episodeTitle,
    LocalDate airDate,
    String network,        // Future
    String genre,          // Future
    String description     // Future
) {
    public static TvEpisodeMetadata from(FileEpisode ep) { ... }
}
```

---

## User Preferences

| Preference | Key | Default | Description |
|------------|-----|---------|-------------|
| Enable tagging | `tagVideoMetadata` | `false` | Master toggle for metadata tagging |

---

## Error Handling

- **Unsupported format**: Silently skipped (returns `true`)
- **Invalid container**: Logged at FINE level, skipped (returns `true`)
- **Missing metadata**: Logged at WARNING level (returns `false`)
- **Write failure**: Logged at WARNING level with exception (returns `false`)
- **Temp file cleanup**: Best-effort in finally block

---

## Testing

### Manual Testing
```bash
# View MP4 metadata (requires AtomicParsley or similar)
AtomicParsley file.mp4 -t

# View MKV metadata (requires mkvinfo from MKVToolNix)
mkvinfo file.mkv

# Cross-platform: ffprobe
ffprobe -show_format -show_streams file.mp4
```

### Automated Testing
- Unit tests should mock file I/O
- Integration tests can use small test video files
- Verify atoms are readable by external tools

---

## Media Manager Compatibility

### Tested Compatibility

| Manager | MP4 | MKV | Notes |
|---------|-----|-----|-------|
| **Plex** | ✅ | ✅ | Uses `tvsh`, `tvsn`, `tves`, `tven`; falls back to `©alb` |
| **Kodi** | ✅ | ✅ | Reads most iTunes atoms; prefers Matroska tags for MKV |
| **Jellyfin** | ✅ | ✅ | Similar to Plex behavior |
| **Emby** | ✅ | ✅ | Similar to Plex behavior |
| **iTunes/Apple TV** | ✅ | ❌ | Native support for all atoms; no MKV support |
| **VLC** | ✅ | ✅ | Uses `©nam` for display; reads MKV segment title |
| **Infuse** | ✅ | ✅ | Full iTunes atom support |
| **Windows Explorer** | ⚠️ | ⚠️ | Limited; shows `©nam`/segment title |

Legend: ✅ Supported | ⚠️ Partial | ❌ Not applicable

**Note:** MKV tagging requires MKVToolNix (mkvpropedit) to be installed on the system.

### Known Quirks

- **Plex**: Prefers embedded metadata but can be overridden by NFO/agent. May cache old metadata.
- **Kodi**: Honors `stik=10` to categorize as TV show. Reads `©alb` as album/show name.
- **VLC**: Displays `©nam` in the playlist/title bar; `tven` ignored.
- **Jellyfin**: May prefer scraped metadata over embedded tags depending on library settings.

---

## References

- [MP4 Atom Specifications](https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/Metadata/Metadata.html)
- [iTunes Metadata Atoms](https://mutagen-specs.readthedocs.io/en/latest/mp4/ilst.html)
- [Matroska Tags Specification](https://www.matroska.org/technical/tagging.html)
- [Matroska Tag Names](https://www.matroska.org/technical/elements.html)
- [MKVToolNix Documentation](https://mkvtoolnix.download/docs.html)
- [AtomicParsley](https://github.com/wez/atomicparsley)
- [Plex Metadata Agents](https://support.plex.tv/articles/200241558-agents/)
- [Kodi NFO/Tag Support](https://kodi.wiki/view/NFO_files)
