package org.tvrenamer.view;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.tvrenamer.controller.util.ProcessRunner;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.tvrenamer.model.ThemeMode;
import org.tvrenamer.model.UserPreferences;
import org.tvrenamer.model.util.Environment;

/**
 * Central place for resolving the preferred UI theme and provisioning the SWT colours that
 * implement that theme.
 */
public final class ThemeManager {

    private static final Logger logger = Logger.getLogger(
        ThemeManager.class.getName()
    );

    private static final Duration DETECTION_TIMEOUT = Duration.ofSeconds(1);

    /**
     * Whether native Windows dark mode was successfully activated via OS.setTheme().
     * When true, manual workarounds (e.g., button border overlay painting) are skipped
     * because the native rendering already handles them correctly.
     */
    private static volatile boolean nativeDarkModeActive = false;

    private ThemeManager() {
        // Utility class
    }

    /**
     * Apply native OS-level dark/light theming based on the resolved mode.
     * Must be called AFTER Display creation but BEFORE creating any widgets (Shell, etc.)
     * so that all subsequently created native controls inherit the correct theme.
     *
     * <p>On Windows, this uses SWT's internal {@code OS.setTheme(boolean)} via reflection
     * to activate the Windows dark mode API for menus, buttons, tabs, scrollbars, and title bar.
     * On other platforms or if the API is unavailable, this is a silent no-op and manual
     * theming continues as the fallback.</p>
     *
     * @param resolved the resolved theme mode (DARK or LIGHT; never AUTO)
     */
    public static void applyNativeTheme(ThemeMode resolved) {
        if (!Environment.IS_WINDOWS) {
            return;
        }
        boolean dark = (resolved == ThemeMode.DARK);
        try {
            Class<?> osClass = Class.forName("org.eclipse.swt.internal.win32.OS");
            Method setTheme = osClass.getMethod("setTheme", boolean.class);
            setTheme.invoke(null, dark);
            nativeDarkModeActive = dark;
            logger.fine("Native Windows dark mode " + (dark ? "enabled" : "disabled")
                        + " via OS.setTheme()");
        } catch (Exception e) {
            nativeDarkModeActive = false;
            logger.log(Level.FINE,
                        "OS.setTheme() not available; using manual theming only", e);
        }
    }

    /**
     * Resolve the effective theme for the given preference, applying OS detection when AUTO
     * is selected.
     *
     * @param preference requested theme mode
     * @return resolved theme mode (never null)
     */
    public static ThemeMode resolveTheme(ThemeMode preference) {
        ThemeMode requested = preference != null ? preference : ThemeMode.LIGHT;
        if (requested != ThemeMode.AUTO) {
            return requested;
        }

        ThemeMode detected = detectSystemTheme();
        logger.fine("Auto theme resolved to " + detected);
        return detected;
    }

    /**
     * Create a new {@link ThemePalette} for the supplied display, using the user's stored
     * preference (falling back to LIGHT if unset).
     *
     * @param display SWT display (must not be null)
     * @return palette instance that must be {@link ThemePalette#dispose() disposed} when no longer needed
     */
    public static ThemePalette createPalette(Display display) {
        UserPreferences prefs = UserPreferences.getInstance();
        ThemeMode preference = prefs.getThemeMode();
        ThemeMode resolved = resolveTheme(preference);
        return createPalette(display, resolved);
    }

    /**
     * Create a palette for the given display and mode.
     *
     * @param display SWT display (must not be null)
     * @param mode    theme mode to realise
     * @return palette instance
     */
    public static ThemePalette createPalette(Display display, ThemeMode mode) {
        if (display == null) {
            throw new IllegalArgumentException("Display must not be null");
        }
        ThemeMode resolved = resolveTheme(mode);
        return new ThemePalette(display, resolved);
    }

    /**
     * Convenience helper that applies colours to a control tree.
     *
     * @param control root control
     * @param palette palette to use
     */
    public static void applyPalette(Control control, ThemePalette palette) {
        if (control == null || palette == null) {
            return;
        }
        control.setBackground(palette.getControlBackground());
        control.setForeground(palette.getControlForeground());

        // Best-effort: TabFolder/TabItem headers are often OS/native themed and may ignore these,
        // but where supported this makes the tabs match dark mode.
        if (control instanceof TabFolder tabFolder) {
            installTabFolderHeaderTheming(tabFolder, palette);
        }

        // Table zebra striping improves readability and works reliably across platforms/themes.
        if (control instanceof Table table) {
            installTableAlternatingRowBackground(table, palette);
        }

        // Best-effort border polish for dark mode.
        // When native dark mode is active (OS.setTheme), the OS renders proper dark borders
        // and our overlay PaintListener becomes unnecessary (and potentially harmful).
        if (palette.isDark() && !nativeDarkModeActive) {
            if (control instanceof Button button) {
                installButtonBorderPainter(button, palette);
            }
        }

        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                applyPalette(child, palette);
            }
        }
    }

    /**
     * Menu theming stub.
     *
     * On Windows with SWT 3.132+, menu dark mode is handled natively by
     * {@link #applyNativeTheme(ThemeMode)} via {@code OS.setTheme(true)}, which activates
     * owner-drawn dark menu rendering. No per-menu colour work is needed.
     *
     * On other platforms or older SWT versions, menus remain OS-native and typically
     * ignore programmatic colour changes, so this remains a no-op.
     */
    public static void applyPalette(Menu unusedMenu, ThemePalette palette) {
        // Intentionally empty — menu theming is handled by applyNativeTheme() on Windows,
        // and is not controllable on other platforms.
    }

    private static void installTabFolderHeaderTheming(
        final TabFolder tabFolder,
        final ThemePalette palette
    ) {
        if (tabFolder == null || palette == null || tabFolder.isDisposed()) {
            return;
        }

        // Avoid installing multiple times (applyPalette is recursive and may be called more than once).
        if (
            Boolean.TRUE.equals(
                tabFolder.getData("tvrenamer.tabfolder.theming")
            )
        ) {
            return;
        }
        tabFolder.setData("tvrenamer.tabfolder.theming", Boolean.TRUE);

        // Best-effort: on some SWT/platforms these setters are honored; on others they're ignored.
        try {
            tabFolder.setBackground(palette.getControlBackground());
        } catch (SWTException ex) {
            logger.log(Level.FINEST, "TabFolder background not supported", ex);
        }
        try {
            tabFolder.setForeground(palette.getControlForeground());
        } catch (SWTException ex) {
            logger.log(Level.FINEST, "TabFolder foreground not supported", ex);
        }

        // Best-effort note:
        // This SWT version does not expose TabItem color setters (no setForeground/setBackground),
        // so we can only theme the TabFolder itself and the composites within each tab.
    }

    // Intentionally no custom gridline painter.
    // We rely on zebra striping for row separation, which is more consistent across platforms/themes.

    private static void installTableAlternatingRowBackground(
        final Table table,
        final ThemePalette palette
    ) {
        if (table == null || table.isDisposed() || palette == null) {
            return;
        }
        // Apply in BOTH themes (light and dark).
        if (Boolean.TRUE.equals(table.getData("tvrenamer.table.altrows"))) {
            return;
        }
        table.setData("tvrenamer.table.altrows", Boolean.TRUE);

        Listener refresher = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (table.isDisposed()) {
                    return;
                }
                int count = table.getItemCount();
                for (int i = 0; i < count; i++) {
                    TableItem item = table.getItem(i);
                    if (item == null) {
                        continue;
                    }
                    if ((i % 2) == 1) {
                        item.setBackground(palette.getTableRowAlternate());
                    } else {
                        item.setBackground(palette.getControlBackground());
                    }
                    item.setForeground(palette.getControlForeground());
                }
            }
        };

        // Keep striping correct as rows are added/removed and table is redrawn.
        table.addListener(SWT.Paint, refresher);
        table.addListener(SWT.Resize, refresher);
        table.addListener(SWT.Selection, refresher);

        // Apply once immediately.
        refresher.handleEvent(null);
    }

    private static void installButtonBorderPainter(
        final Button button,
        final ThemePalette palette
    ) {
        if (button == null || button.isDisposed()) {
            return;
        }

        // Don't double-install.
        if (
            Boolean.TRUE.equals(button.getData("tvrenamer.dark.buttonborder"))
        ) {
            return;
        }
        button.setData("tvrenamer.dark.buttonborder", Boolean.TRUE);

        // Native SWT buttons are OS rendered; border drawing isn't always honored.
        // We do a minimal overlay border as best-effort to avoid bright white edges.
        button.addPaintListener(
            new PaintListener() {
                @Override
                public void paintControl(PaintEvent e) {
                    if (button.isDisposed()) {
                        return;
                    }
                    Rectangle r = button.getBounds();
                    if (r.width <= 1 || r.height <= 1) {
                        return;
                    }
                    e.gc.setForeground(palette.getBorderColor());
                    e.gc.drawRectangle(0, 0, r.width - 1, r.height - 1);
                }
            }
        );
    }

    private static ThemeMode detectSystemTheme() {
        try {
            if (Environment.IS_WINDOWS) {
                Boolean usesLight = readWindowsAppsUseLightTheme();
                if (usesLight != null) {
                    return usesLight ? ThemeMode.LIGHT : ThemeMode.DARK;
                }
            } else if (Environment.IS_MAC_OSX) {
                Boolean dark = readMacOsInterfaceStyleDark();
                if (dark != null) {
                    return dark ? ThemeMode.DARK : ThemeMode.LIGHT;
                }
            } else {
                Boolean dark = readFreedesktopDarkPreference();
                if (dark != null) {
                    return dark ? ThemeMode.DARK : ThemeMode.LIGHT;
                }
            }
        } catch (Exception ex) {
            logger.log(
                Level.FINE,
                "Theme detection failed; defaulting to LIGHT",
                ex
            );
        }
        return ThemeMode.LIGHT;
    }

    private static Boolean readWindowsAppsUseLightTheme() {
        String output = runCommand(
            "reg",
            "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v",
            "AppsUseLightTheme"
        );
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("AppsUseLightTheme")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length > 2) {
                    String rawValue = parts[parts.length - 1];
                    try {
                        int value = Integer.decode(rawValue);
                        return value != 0;
                    } catch (NumberFormatException ex) {
                        logger.log(
                            Level.FINE,
                            "Unable to parse AppsUseLightTheme value: " +
                                rawValue,
                            ex
                        );
                    }
                }
            }
        }
        return null;
    }

    private static Boolean readMacOsInterfaceStyleDark() {
        String output = runCommand(
            "defaults",
            "read",
            "-g",
            "AppleInterfaceStyle"
        );
        if (output == null) {
            return null;
        }
        return output.toLowerCase(Locale.ROOT).contains("dark");
    }

    private static Boolean readFreedesktopDarkPreference() {
        String preferDark = System.getenv("GTK_THEME");
        if (
            preferDark != null &&
            preferDark.toLowerCase(Locale.ROOT).contains("dark")
        ) {
            return Boolean.TRUE;
        }
        String kdeLookAndFeel = System.getenv("KDE_FULL_SESSION");
        if (kdeLookAndFeel != null) {
            String plasmaTheme = System.getenv("PLASMA_USE_QT_SCALING");
            if (
                plasmaTheme != null &&
                plasmaTheme.toLowerCase(Locale.ROOT).contains("dark")
            ) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    private static String runCommand(String... command) {
        int timeoutSeconds = (int) DETECTION_TIMEOUT.toSeconds();
        if (timeoutSeconds < 1) {
            timeoutSeconds = 1;
        }
        ProcessRunner.Result result = ProcessRunner.run(
            java.util.List.of(command), timeoutSeconds
        );
        return result.success() ? result.output().trim() : null;
    }
}
