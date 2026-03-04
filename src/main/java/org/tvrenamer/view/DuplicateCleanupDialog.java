package org.tvrenamer.view;

import static org.tvrenamer.model.util.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Dialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * Modal dialog to confirm deletion of duplicate video files found after moves.
 *
 * <p>Displays a list of duplicate files with checkboxes. Files are unchecked by default
 * for safety — the user must actively select files before deletion is possible.
 *
 * <p>Returns the list of files to delete when the user clicks Delete Selected,
 * or null if cancelled.
 */
public final class DuplicateCleanupDialog extends Dialog {

    private static final String TITLE = "Duplicate Files Found";
    private static final int MIN_WIDTH = 750;
    private static final int MIN_HEIGHT = 300;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Shell parent;
    private final List<Path> duplicates;

    /** Pre-computed file metadata so I/O happens before the dialog is painted. */
    private record FileInfo(Path path, String filename, String folder,
                            String sizeStr, String modifiedStr) {

        static FileInfo of(Path path) {
            String filename = (path.getFileName() != null)
                ? path.getFileName().toString()
                : path.toString();
            String folder = (path.getParent() != null)
                ? path.getParent().toString()
                : "";
            String sizeStr = "";
            String modifiedStr = "";
            try {
                sizeStr = formatFileSize(Files.size(path));
                FileTime mtime = Files.getLastModifiedTime(path);
                modifiedStr = mtime.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .format(DATE_FMT);
            } catch (IOException e) {
                Logger.getLogger(DuplicateCleanupDialog.class.getName())
                    .log(Level.FINE, "Could not read file attributes: " + path, e);
            }
            return new FileInfo(path, filename, folder, sizeStr, modifiedStr);
        }
    }

    private Shell dialogShell;
    private Table table;
    private List<FileInfo> fileInfos;
    private List<Path> result = null;

    /**
     * Create a new duplicate cleanup dialog.
     *
     * @param parent parent shell
     * @param duplicates list of duplicate file paths to display
     */
    public DuplicateCleanupDialog(final Shell parent, final List<Path> duplicates) {
        super(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        this.parent = parent;
        this.duplicates = (duplicates == null) ? new ArrayList<>() : new ArrayList<>(duplicates);

        // Pre-compute file metadata (I/O) before the dialog UI is built.
        this.fileInfos = new ArrayList<>(this.duplicates.size());
        for (Path p : this.duplicates) {
            fileInfos.add(FileInfo.of(p));
        }
    }

    /**
     * Open the dialog and block until user closes.
     *
     * @return list of files to delete, or null if cancelled
     */
    public List<Path> open() {
        if (duplicates.isEmpty()) {
            return new ArrayList<>();
        }

        dialogShell = new Shell(parent, getStyle());
        dialogShell.setText(TITLE);
        dialogShell.setMinimumSize(MIN_WIDTH, MIN_HEIGHT);

        // Match main window icon.
        dialogShell.setImage(UIStarter.readImageFromPath(APPLICATION_ICON_PATH));

        // Apply theme palette for dark mode support.
        ThemePalette themePalette = ThemeManager.createPalette(dialogShell.getDisplay());
        ThemeManager.applyPalette(dialogShell, themePalette);
        dialogShell.addListener(SWT.Dispose, e -> themePalette.dispose());

        createContents(themePalette);

        dialogShell.pack();
        dialogShell.setSize(
            Math.max(dialogShell.getSize().x, MIN_WIDTH),
            Math.max(dialogShell.getSize().y, MIN_HEIGHT)
        );

        // Position centered over parent with multi-monitor clamping.
        DialogPositioning.positionDialog(dialogShell, parent);

        dialogShell.open();
        DialogHelper.runModalLoop(dialogShell);

        return result;
    }

    private void createContents(ThemePalette themePalette) {
        dialogShell.setLayout(new GridLayout(1, false));

        // Instruction label.
        Label instructionLabel = new Label(dialogShell, SWT.WRAP);
        instructionLabel.setText(
            "The following duplicate video files were found in the destination folder(s). " +
            "Select the files you want to delete."
        );
        GridData instructionData = new GridData(SWT.FILL, SWT.TOP, true, false);
        instructionData.widthHint = MIN_WIDTH - 40;
        instructionLabel.setLayoutData(instructionData);

        // Table with checkboxes.
        table = new Table(dialogShell, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = 200;
        table.setLayoutData(tableData);

        // Apply theme to table.
        table.setBackground(themePalette.getControlBackground());
        table.setForeground(themePalette.getControlForeground());
        table.setHeaderBackground(themePalette.getTableHeaderBackground());
        table.setHeaderForeground(themePalette.getTableHeaderForeground());

        // Columns: checkbox (implicit), filename, folder, size, modified.
        TableColumn filenameColumn = new TableColumn(table, SWT.NONE);
        filenameColumn.setText("Filename");
        filenameColumn.setWidth(220);

        TableColumn folderColumn = new TableColumn(table, SWT.NONE);
        folderColumn.setText("Folder");
        folderColumn.setWidth(250);

        TableColumn sizeColumn = new TableColumn(table, SWT.RIGHT);
        sizeColumn.setText("Size");
        sizeColumn.setWidth(80);

        TableColumn modifiedColumn = new TableColumn(table, SWT.NONE);
        modifiedColumn.setText("Modified");
        modifiedColumn.setWidth(130);

        // Populate table from pre-computed file info (no I/O during UI construction).
        for (FileInfo info : fileInfos) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setChecked(false); // Unchecked by default for safety.
            item.setData(info.path());

            item.setText(0, info.filename());
            item.setText(1, info.folder());
            item.setText(2, info.sizeStr());
            item.setText(3, info.modifiedStr());
        }

        // Right-click context menu: Open Folder.
        Menu contextMenu = new Menu(table);
        table.setMenu(contextMenu);

        MenuItem openFolderItem = new MenuItem(contextMenu, SWT.PUSH);
        openFolderItem.setText("Open Folder");
        openFolderItem.addListener(SWT.Selection, e -> {
            TableItem[] selection = table.getSelection();
            if (selection.length > 0) {
                Object data = selection[0].getData();
                if (data instanceof Path path) {
                    Path parent = path.getParent();
                    if (parent != null) {
                        Program.launch(parent.toString());
                    }
                }
            }
        });

        // Summary label.
        Label summaryLabel = new Label(dialogShell, SWT.NONE);
        summaryLabel.setText(duplicates.size() + " duplicate file(s) found");
        summaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Button composite — create widgets in visual order (SWT lays out by creation order).
        Composite buttonComposite = new Composite(dialogShell, SWT.NONE);
        buttonComposite.setLayout(new GridLayout(5, false));
        buttonComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ThemeManager.applyPalette(buttonComposite, themePalette);

        Button selectAllButton = new Button(buttonComposite, SWT.PUSH);
        selectAllButton.setText("Select All");

        Button selectNoneButton = new Button(buttonComposite, SWT.PUSH);
        selectNoneButton.setText("Select None");

        Label spacer = new Label(buttonComposite, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button deleteButton = new Button(buttonComposite, SWT.PUSH);
        deleteButton.setText("Delete Selected");
        deleteButton.setEnabled(false);

        Button cancelButton = new Button(buttonComposite, SWT.PUSH);
        cancelButton.setText(CANCEL_LABEL);

        // Helper to sync the Delete button's enabled state with checkbox state.
        Runnable syncDeleteEnabled = () -> {
            boolean anyChecked = false;
            for (TableItem item : table.getItems()) {
                if (item.getChecked()) {
                    anyChecked = true;
                    break;
                }
            }
            deleteButton.setEnabled(anyChecked);
        };

        // Wire listeners.
        selectAllButton.addListener(SWT.Selection, e -> {
            for (TableItem item : table.getItems()) {
                item.setChecked(true);
            }
            syncDeleteEnabled.run();
        });

        selectNoneButton.addListener(SWT.Selection, e -> {
            for (TableItem item : table.getItems()) {
                item.setChecked(false);
            }
            syncDeleteEnabled.run();
        });

        table.addListener(SWT.Selection, e -> syncDeleteEnabled.run());

        deleteButton.addListener(SWT.Selection, e -> {
            result = collectCheckedPaths();
            dialogShell.close();
        });

        cancelButton.addListener(SWT.Selection, e -> {
            result = null;
            dialogShell.close();
        });

        // Default button — Cancel is safe (Enter dismisses without deleting).
        dialogShell.setDefaultButton(cancelButton);
    }

    private List<Path> collectCheckedPaths() {
        List<Path> checked = new ArrayList<>();
        for (TableItem item : table.getItems()) {
            if (item.getChecked()) {
                Object data = item.getData();
                if (data instanceof Path path) {
                    checked.add(path);
                }
            }
        }
        return checked;
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
