## New features / improvements

### Native Windows dark mode
Dark mode now uses the SWT native dark mode API (`OS.setTheme`) for proper theming of
all native widgets:

- **Menu bar** is now fully dark (custom owner-drawn rendering in SWT 3.132.0)
- **Button borders** are dark grey instead of bright white
- **Scrollbars** are dark throughout the application
- **Title bar** matches the dark theme
- **Combo dropdowns and text fields** inherit the dark theme

The existing manual theming (control backgrounds, zebra striping, etc.) is retained for
custom-drawn areas. On non-Windows platforms or if the native API is unavailable, manual
theming continues as before.

### Preferences menu promoted
Preferences has been moved from a sub-item under the File menu to its own top-level
menu bar entry, making it directly accessible in one click. Keyboard shortcut
<kbd>Ctrl+P</kbd> continues to work as before.

## Bug fixes

- Help pages updated to reflect the new Preferences menu location (previously referenced
  a non-existent "File > Add Files" menu item)

## Other

- Added episode lookup unit tests (DVD field edge cases: zero, null, empty)
- Repository hygiene: `.claude/` and `.vscode/` directories excluded from version control

---

**Requirements:** Java 17+ runtime

**Artifacts:**
- `TVRenamer.exe` — Windows executable (recommended)
- `tvrenamer-245.jar` — Cross-platform fat JAR
- `tvrenamer.jar` — Stable-named fat JAR (same content as versioned)
