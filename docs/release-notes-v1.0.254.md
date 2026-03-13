## New features and improvements

- **Concurrency safety:** Background threads (update checker, preference validation) are now daemon threads that won't block JVM shutdown. SWT widget disposal checks prevent crashes when background work completes after the UI is closed.
- **XML utility consolidation:** Extracted shared `XmlUtilities` class with XXE-hardened document builder and map parser, eliminating duplicated code across three persistence/provider classes.
- **HTTP diagnostics:** Download success is now logged with status code; error responses are classified by category (client error, server error) for easier troubleshooting.
- **Error handling precision:** Narrowed overly broad exception catches in file move operations; consolidated redundant catch blocks.
- **UTF-8 persistence:** Preferences and overrides XML files now explicitly use UTF-8 encoding regardless of platform default.
- **67 new unit tests:** Added dedicated test classes for core model (Show, Series, ShowName), update checker version logic, and metadata tagging controller.
- **Test hygiene:** Replaced real TV show names with fictional equivalents in filename parser tests.
- **Documentation:** Updated README dependency list, corrected version references, refreshed TODO and Completed docs.

## Bug fixes

- Fixed potential SWTException when update check or preference validation completes after the application window is closed.
- Fixed non-daemon background threads that could prevent clean JVM shutdown.
