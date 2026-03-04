## Code audit and quality improvements

Comprehensive code quality pass addressing security, thread safety, correctness,
performance, resource management, and dead code across 30 files.

### Security
- XML External Entity (XXE) prevention added to all XML parsing
- HTTP URLs upgraded to HTTPS for API calls, download links, and license references
- Cross-thread visibility fix for API deprecation flag

### Thread safety
- Race conditions fixed in show/series lookup caches using atomic operations
- Thread-safe list for show options (CopyOnWriteArrayList)
- Atomic season index rebuild (volatile reference swap)
- Bounded thread pool (4 threads) replacing unbounded pool for show lookups

### Bug fixes
- Fixed early-return bug in destination refresh that skipped remaining files
- Fixed potential null pointer exceptions in episode listing and selection
- Fixed NUL bytes in version strings from fixed-size buffer reads
- Fixed locale-dependent string normalisation (Turkish locale problem)
- Folder drag no longer adds directories to the file table
- Non-video files in dragged folders no longer trigger modal error popups

### Performance
- 9 regex patterns precompiled as static constants (called per-file)
- Video extension check changed from O(n) stream to O(1) set lookup

### Resource management
- Status icons registered for disposal on shutdown
- Dialog colour palettes disposed when dialogs close
- About dialog icon shared instead of loaded twice

### Build
- SpotBugs plugin version moved to version catalog for consistency
- Build number computed once instead of three times per build
- `./gradlew build` no longer forces versioned fat JAR (faster dev feedback)

### Cleanup
- Removed unused constants, methods, fields, and commented-out code
- Replaced double-brace HashMap anti-pattern with Map.ofEntries
- Consolidated duplicated OS detection logic
- Fixed incorrect javadoc comments
- Test improvements: POSIX test properly skipped on Windows, test state restoration
