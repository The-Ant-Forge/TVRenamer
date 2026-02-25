package org.tvrenamer.view;

import static org.tvrenamer.model.util.Constants.*;

import java.awt.HeadlessException;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.tvrenamer.controller.HelpLauncher;
import org.tvrenamer.controller.UrlLauncher;
import org.tvrenamer.model.ThemeMode;
import org.tvrenamer.model.UserPreferences;
import org.tvrenamer.model.util.Environment;

public final class UIStarter {

    private static final Logger logger = Logger.getLogger(
        UIStarter.class.getName()
    );

    final Shell shell;
    final Display display;
    private final ThemePalette themePalette;
    private final Image appIcon;
    private final ResultsTable resultsTable;

    /*
     * Read an image.
     *
     * @param resourcePath
     *     the relative path to try to locate the file as a resource
     * @param filePath
     *     the path to try to locate the file directly in the file system
     * @return an Image read from the given path
     */
    private static Image readImageFromPath(
        final String resourcePath,
        final String filePath
    ) {
        Display display = Display.getCurrent();
        Image rval = null;
        try (
            InputStream in = UIStarter.class.getResourceAsStream(resourcePath)
        ) {
            if (in != null) {
                rval = new Image(display, in);
            }
        } catch (IOException ioe) {
            logger.warning(
                "exception trying to read image from stream " + resourcePath
            );
        }
        if (rval == null) {
            rval = new Image(display, filePath);
        }
        return rval;
    }

    /**
     * Read an image.
     *
     * @param resourcePath
     *     the relative path to try to locate the file as a resource
     * @return an Image read from the given path
     */
    public static Image readImageFromPath(final String resourcePath) {
        return readImageFromPath(
            resourcePath,
            ICON_PARENT_DIRECTORY + "/" + resourcePath
        );
    }

    /**
     * Determine the system default font
     *
     * @return the system default font
     */
    public FontData getDefaultSystemFont() {
        FontData defaultFont = null;
        try {
            defaultFont = display.getSystemFont().getFontData()[0];
        } catch (Exception e) {
            logger.log(
                Level.WARNING,
                "Error attempting to determine system default font",
                e
            );
        }

        return defaultFont;
    }

    private void showMessageBox(
        final SWTMessageBoxType type,
        final String title,
        final String message,
        final Exception exception
    ) {
        if (shell.isDisposed()) {
            // Shell is gone, try using JOptionPane instead
            try {
                JOptionPane.showMessageDialog(null, message);
                return;
            } catch (HeadlessException he) {
                logger.warning(
                    "Could not show message graphically: " + message
                );
                return;
            }
        }

        display.syncExec(() -> {
            MessageBox msgBox = new MessageBox(shell, type.getSwtIconValue());
            msgBox.setText(title);

            if (exception == null) {
                msgBox.setMessage(message);
            } else {
                msgBox.setMessage(
                    message + "\n" + exception.getLocalizedMessage()
                );
            }

            msgBox.open();
        });
    }

    /**
     * Show a message box of the given type with the given message content and window title.
     *
     * @param type the {@link SWTMessageBoxType} to create
     * @param title the window title
     * @param message the message content
     */
    public void showMessageBox(
        final SWTMessageBoxType type,
        final String title,
        final String message
    ) {
        showMessageBox(type, title, message, null);
    }

    /**
     * Set the Shell's icon.<p>
     *
     * It seems that certain activities cause the icon to be "lost", and this method can
     * be called to re-establish it.
     */
    public void setAppIcon() {
        if (appIcon == null) {
            logger.warning("unable to get application icon");
        } else {
            shell.setImage(appIcon);
        }
    }

    private void positionWindow() {
        // place the window near the lower right-hand corner
        Monitor primary = display.getPrimaryMonitor();
        Rectangle bounds = primary.getBounds();
        Rectangle rect = shell.getBounds();
        int x = bounds.x + (bounds.width - rect.width) - 5;
        int y = bounds.y + (bounds.height - rect.height) - 35;
        shell.setLocation(x, y);
    }

    @SuppressWarnings("SameReturnValue")
    private int onException(Exception exception) {
        logger.log(Level.SEVERE, UNKNOWN_EXCEPTION, exception);
        showMessageBox(
            SWTMessageBoxType.DLG_ERR,
            ERROR_LABEL,
            UNKNOWN_EXCEPTION,
            exception
        );
        shell.dispose();
        return 1;
    }

    void quit() {
        shell.dispose();
    }

    private void makeMenuItem(
        final Menu parent,
        final String text,
        final Listener listener,
        final char shortcut
    ) {
        MenuItem newItem = new MenuItem(parent, SWT.PUSH);
        newItem.setText(text + "\tCtrl+" + shortcut);
        newItem.addListener(SWT.Selection, listener);
        newItem.setAccelerator(SWT.CONTROL | shortcut);
    }

    private Menu setupHelpMenuBar(final Menu menuBar) {
        MenuItem helpMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        helpMenuHeader.setText("Help");

        Menu helpMenu = new Menu(shell, SWT.DROP_DOWN);
        helpMenuHeader.setMenu(helpMenu);

        MenuItem helpHelpItem = new MenuItem(helpMenu, SWT.PUSH);
        helpHelpItem.setText("Help\tF1");
        helpHelpItem.setAccelerator(SWT.F1);
        helpHelpItem.addSelectionListener(new HelpLauncher());

        MenuItem helpVisitWebPageItem = new MenuItem(helpMenu, SWT.PUSH);
        helpVisitWebPageItem.setText("Visit Web Page");
        helpVisitWebPageItem.addSelectionListener(
            new UrlLauncher(TVRENAMER_PROJECT_URL)
        );

        return helpMenu;
    }

    private void setupMenuBar() {
        Menu menuBarMenu = new Menu(shell, SWT.BAR);
        Menu helpMenu;

        Listener preferencesListener = e -> {
            PreferencesDialog preferencesDialog = new PreferencesDialog(shell);
            preferencesDialog.open();
        };
        Listener aboutListener = e -> {
            AboutDialog aboutDialog = new AboutDialog(this);
            aboutDialog.open();
        };
        Listener quitListener = e -> quit();

        if (Environment.IS_MAC_OSX) {
            // Add the special Mac OSX Preferences, About and Quit menus.
            CocoaUIEnhancer enhancer = new CocoaUIEnhancer();
            enhancer.hookApplicationMenu(
                display,
                quitListener,
                aboutListener,
                preferencesListener
            );

            helpMenu = setupHelpMenuBar(menuBarMenu);
        } else {
            // Preferences is the most-used menu action, so it gets its own
            // top-level menu bar entry for quick access.
            MenuItem prefsMenuItem = new MenuItem(menuBarMenu, SWT.CASCADE);
            prefsMenuItem.setText(PREFERENCES_LABEL);

            Menu prefsMenu = new Menu(shell, SWT.DROP_DOWN);
            prefsMenuItem.setMenu(prefsMenu);

            makeMenuItem(prefsMenu, PREFERENCES_LABEL, preferencesListener, 'P');
            new MenuItem(prefsMenu, SWT.SEPARATOR);
            makeMenuItem(prefsMenu, EXIT_LABEL, quitListener, 'Q');

            helpMenu = setupHelpMenuBar(menuBarMenu);

            // The About item is added to the OSX bar, so we need to add it manually here
            MenuItem helpAboutItem = new MenuItem(helpMenu, SWT.PUSH);
            helpAboutItem.setText("About");
            helpAboutItem.addListener(SWT.Selection, aboutListener);
        }

        shell.setMenuBar(menuBarMenu);
    }

    /**
     * Start up the UI.
     *
     * The UIStarter class is the top level UI driver for the application.  Assuming we are
     * running in UI mode (the only way currently supported), creating a UIStarter should be
     * one of the very first things we do, should only be done once, and the instance should
     * live until the program is being shut down.
     *
     * The UIStarter automatically creates a {@link ResultsTable}, which is the main class
     * that drives all the application-specific action.  This class sets up generic stuff,
     * like the Display, the Shell, the icon, etc.
     *
     */

    public UIStarter() {
        logger.fine("=== UIStarter constructor begin ===");
        logger.fine("Setting SWT application name to: " + APPLICATION_NAME);
        Display.setAppName(APPLICATION_NAME);

        try {
            logger.fine("Creating SWT Display instance...");

            // SWT can fail very early with an Error (not Exception) if the native layer cannot be loaded.
            // We want to capture *all* of those failures in tvrenamer.log, not just stdout/stderr.
            display = new Display();

            // Activate native OS-level dark/light theming before any widgets are created.
            // On Windows this calls OS.setTheme() for native dark menus, buttons, tabs, etc.
            UserPreferences prefs = UserPreferences.getInstance();
            ThemeMode resolved = ThemeManager.resolveTheme(prefs.getThemeMode());
            ThemeManager.applyNativeTheme(resolved);

            themePalette = ThemeManager.createPalette(display);

            logger.fine("Display created successfully: " + display);
        } catch (Throwable t) {
            // High-signal diagnostics for SWT native load failures.
            // Log at SEVERE so it is captured under the default INFO root level when a crash occurs.
            StringBuilder diag = new StringBuilder(1024);
            diag.append("Failed to create SWT Display.\n");

            // JVM / OS basics
            diag
                .append("java.version=")
                .append(System.getProperty("java.version"))
                .append('\n');
            diag
                .append("java.vendor=")
                .append(System.getProperty("java.vendor"))
                .append('\n');
            diag
                .append("java.home=")
                .append(System.getProperty("java.home"))
                .append('\n');
            diag
                .append("os.name=")
                .append(System.getProperty("os.name"))
                .append('\n');
            diag
                .append("os.version=")
                .append(System.getProperty("os.version"))
                .append('\n');
            diag
                .append("os.arch=")
                .append(System.getProperty("os.arch"))
                .append('\n');
            diag
                .append("sun.arch.data.model=")
                .append(System.getProperty("sun.arch.data.model"))
                .append('\n');

            // SWT info (best-effort)
            try {
                diag
                    .append("SWT.getPlatform()=")
                    .append(org.eclipse.swt.SWT.getPlatform())
                    .append('\n');
            } catch (Throwable swtInfoErr) {
                diag
                    .append("SWT.getPlatform()=<error: ")
                    .append(swtInfoErr)
                    .append(">\n");
            }
            try {
                diag
                    .append("SWT.getVersion()=")
                    .append(org.eclipse.swt.SWT.getVersion())
                    .append('\n');
            } catch (Throwable swtInfoErr) {
                diag
                    .append("SWT.getVersion()=<error: ")
                    .append(swtInfoErr)
                    .append(">\n");
            }

            // Native library paths
            diag
                .append("java.library.path=")
                .append(System.getProperty("java.library.path"))
                .append('\n');
            diag
                .append("user.dir=")
                .append(System.getProperty("user.dir"))
                .append('\n');
            diag.append("PATH=").append(System.getenv("PATH")).append('\n');

            logger.log(Level.SEVERE, diag.toString(), t);

            // Re-throw preserving the original type.
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new RuntimeException(t);
        }

        logger.fine("Creating SWT Shell...");
        shell = new Shell(display);

        logger.fine("Shell created: " + shell);

        shell.setBackground(themePalette.getShellBackground());
        shell.setForeground(themePalette.getShellForeground());

        logger.fine("Setting shell title to application name.");
        shell.setText(APPLICATION_NAME);

        logger.fine(
            "Loading application icon from path: " + APPLICATION_ICON_PATH
        );
        appIcon = readImageFromPath(APPLICATION_ICON_PATH);

        logger.fine("Application icon loaded: " + (appIcon != null));

        logger.fine("Applying application icon to shell.");
        setAppIcon();

        logger.fine("Configuring shell grid layout.");
        GridLayout shellGridLayout = new GridLayout(3, false);

        shell.setLayout(shellGridLayout);

        logger.fine(
            "Shell layout configured with columns=" + shellGridLayout.numColumns
        );

        logger.fine("Creating ResultsTable...");
        resultsTable = new ResultsTable(this);
        ThemeManager.applyPalette(shell, themePalette);

        logger.fine("ResultsTable created successfully.");

        logger.fine("Setting up application menu bar...");
        setupMenuBar();

        logger.fine("Menu bar setup complete.");
        logger.fine("=== UIStarter constructor end ===");
    }

    /**
     * Run the UI/event loop.
     *
     * @return 0 on normal exit, nonzero on error
     */

    public int run() {
        logger.fine("UIStarter.run() invoked.");
        try {
            logger.fine("Packing shell (compute trim)...");
            shell.pack(true);

            logger.fine("Positioning shell on screen...");
            positionWindow();

            // Start the shell

            logger.fine("Packing shell before open...");
            shell.pack();

            logger.fine("Opening shell...");
            shell.open();

            logger.fine("Calling ResultsTable.ready()...");

            resultsTable.ready();
            logger.fine("ResultsTable.ready() completed.");

            logger.fine("Entering SWT event loop.");
            while (!shell.isDisposed()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }

            logger.fine("Shell disposed. Disposing display...");
            display.dispose();

            logger.fine("Display disposed. Exiting run() normally.");
            return 0;
        } catch (Exception exception) {
            logger.log(
                Level.SEVERE,
                "Exception during UIStarter.run()",
                exception
            );
            return onException(exception);
        }
    }
}
