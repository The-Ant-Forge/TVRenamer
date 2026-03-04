package org.tvrenamer.view;

import static org.tvrenamer.model.util.Constants.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Dialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.tvrenamer.model.ShowOption;
import org.tvrenamer.model.ShowSelectionEvaluator;
import org.tvrenamer.model.ShowStore;

import java.util.logging.Logger;

/**
 * Modal dialog used to resolve all ambiguous show lookups in a single batch.
 *
 * <p>Left side: list of ambiguous extracted show names (one row per provider query string).
 * Right side: show an example filename (bleeds off; tooltip contains the full name)
 * and up to 5 provider candidates with metadata (name, year, id, aliases).
 *
 * <p>Returns a map of queryString -> chosen provider series id for any rows the user resolved.
 * If the user cancels, returns null.
 */
public final class BatchShowDisambiguationDialog extends Dialog {

    private static final Logger logger = Logger.getLogger(
        BatchShowDisambiguationDialog.class.getName()
    );

    private static final int MAX_OPTIONS_PER_SHOW = 5;

    private static final String TITLE_BASE = "Select Shows";
    private static final String TITLE_DOWNLOADING_PREFIX =
        "Select Shows (Downloading ";
    private static final String TITLE_DOWNLOADING_SUFFIX = ")";

    // Use Unicode escape sequences to avoid source-encoding issues on Windows (e.g., cp1252).
    // Frames are quadrant circle characters (escaped).
    private static final char[] DOWNLOADING_FRAMES = new char[] {
        '\u25D0',
        '\u25D3',
        '\u25D1',
        '\u25D2',
    };
    private int downloadingFrameIdx = 0;
    private boolean downloading = false;

    private final Shell parent;
    private final Map<String, ShowStore.PendingDisambiguation> pending;

    private Shell dialogShell;
    private ThemePalette themePalette;

    private Table leftTable;
    private Table rightCandidatesTable;

    private Label exampleFileValueLabel;
    private Label showNameValueLabel;

    // queryString -> chosenId
    private final Map<String, String> selections = new LinkedHashMap<>();

    private Button okButton;

    /**
     * Create a new batch dialog.
     *
     * @param parent parent shell
     * @param pendingDisambiguations map of queryString -> pending disambiguation
     */
    public BatchShowDisambiguationDialog(
        final Shell parent,
        final Map<
            String,
            ShowStore.PendingDisambiguation
        > pendingDisambiguations
    ) {
        super(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        this.parent = parent;
        this.pending = (pendingDisambiguations == null)
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(pendingDisambiguations);
    }

    /**
     * Open the dialog and block until user closes.
     *
     * @return map of queryString -> chosen provider series id, or null if cancelled
     */
    public Map<String, String> open() {
        if (pending.isEmpty()) {
            return new LinkedHashMap<>();
        }

        dialogShell = new Shell(parent, getStyle());
        dialogShell.setText(TITLE_BASE);

        // Match main window icon (best-effort).
        // We intentionally load a fresh Image here; SWT Shell takes ownership for display purposes.
        dialogShell.setImage(
            UIStarter.readImageFromPath(APPLICATION_ICON_PATH)
        );

        // Apply theme palette so controls/tables inherit dark mode styling.
        themePalette = ThemeManager.createPalette(dialogShell.getDisplay());
        ThemeManager.applyPalette(dialogShell, themePalette);
        dialogShell.addListener(SWT.Dispose, e -> themePalette.dispose());

        // Treat closing the window via the title-bar X as "OK" if at least one selection exists.
        // Otherwise treat it as Cancel to avoid returning an empty selection map.
        dialogShell.addListener(SWT.Close, e -> {
            // Best-effort: persist any currently highlighted candidate for the currently selected row.
            applyCurrentSelectionOnly();

            if (hasAnySelections()) {
                cancelled = false;
                // keep selections
            } else {
                cancelled = true;
                selections.clear();
            }
        });

        createContents(dialogShell);

        dialogShell.setMinimumSize(900, 370);
        dialogShell.pack();

        // Position relative to the parent (main window) so it doesn't appear in an OS-random place.
        DialogPositioning.positionDialog(dialogShell, parent);

        dialogShell.open();

        // Start in downloading mode; caller will stop it when discovery completes.
        setDownloading(true);

        DialogHelper.runModalLoop(dialogShell);

        // If dialogShell closed by cancel path or by window X, we return null.
        if (cancelled) {
            return null;
        }

        return new LinkedHashMap<>(selections);
    }

    private boolean cancelled = false;

    private void createContents(final Shell shell) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 12;
        layout.verticalSpacing = 10;
        shell.setLayout(layout);

        Label header = new Label(shell, SWT.WRAP);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        header.setText(
            "Some shows matched multiple results. Showing first " +
                MAX_OPTIONS_PER_SHOW +
                " results per show. Please choose the correct show for each."
        );

        SashForm sash = new SashForm(shell, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite left = new Composite(sash, SWT.NONE);
        left.setLayout(new GridLayout(1, false));

        Composite right = new Composite(sash, SWT.NONE);
        right.setLayout(new GridLayout(1, false));

        sash.setWeights(new int[] { 35, 65 });

        createLeftPane(left);
        createRightPane(right);

        createButtons(shell);

        // Ensure children created inside panes inherit palette (tables, labels, buttons).
        ThemeManager.applyPalette(shell, themePalette);
        ThemeManager.applyPalette(left, themePalette);
        ThemeManager.applyPalette(right, themePalette);

        populateLeftTable();

        // Select first unresolved row by default
        if (leftTable.getItemCount() > 0) {
            leftTable.setSelection(0);
            onLeftSelectionChanged();
        }

        updateOkEnabled();
    }

    private void createLeftPane(final Composite parent) {
        Label label = new Label(parent, SWT.NONE);
        label.setText("Ambiguous Shows");

        leftTable = new Table(
            parent,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE
        );
        leftTable.setHeaderVisible(true);
        leftTable.setLinesVisible(true);

        // Theme table + header for dark mode (match main table behavior).
        leftTable.setBackground(themePalette.getControlBackground());
        leftTable.setForeground(themePalette.getControlForeground());
        leftTable.setHeaderBackground(themePalette.getTableHeaderBackground());
        leftTable.setHeaderForeground(themePalette.getTableHeaderForeground());

        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.minimumHeight = 100;
        leftTable.setLayoutData(gd);

        TableColumn colShow = new TableColumn(leftTable, SWT.LEFT);
        colShow.setText("Extracted show");

        TableColumn colFiles = new TableColumn(leftTable, SWT.LEFT);
        colFiles.setText("Files");

        TableColumn colStatus = new TableColumn(leftTable, SWT.LEFT);
        colStatus.setText("Status");

        leftTable.addListener(SWT.Selection, e -> onLeftSelectionChanged());
    }

    private void createRightPane(final Composite parent) {
        Composite meta = new Composite(parent, SWT.NONE);
        meta.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout metaLayout = new GridLayout(2, false);
        metaLayout.marginWidth = 0;
        metaLayout.marginHeight = 0;
        metaLayout.horizontalSpacing = 8;
        meta.setLayout(metaLayout);

        Label exampleFileLabel = new Label(meta, SWT.NONE);
        exampleFileLabel.setText("Example file:");

        exampleFileValueLabel = new Label(meta, SWT.NONE);
        exampleFileValueLabel.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false)
        );
        exampleFileValueLabel.setText("");
        exampleFileValueLabel.setToolTipText("");

        Label showNameLabel = new Label(meta, SWT.NONE);
        showNameLabel.setText("Extracted show:");

        showNameValueLabel = new Label(meta, SWT.NONE);
        showNameValueLabel.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false)
        );
        showNameValueLabel.setText("");
        showNameValueLabel.setToolTipText("");

        Label candidatesLabel = new Label(parent, SWT.NONE);
        candidatesLabel.setText("Click a match:");

        rightCandidatesTable = new Table(
            parent,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.CHECK
        );
        rightCandidatesTable.setHeaderVisible(true);
        rightCandidatesTable.setLinesVisible(true);

        // Theme table + header for dark mode (match main table behavior).
        rightCandidatesTable.setBackground(themePalette.getControlBackground());
        rightCandidatesTable.setForeground(themePalette.getControlForeground());
        rightCandidatesTable.setHeaderBackground(
            themePalette.getTableHeaderBackground()
        );
        rightCandidatesTable.setHeaderForeground(
            themePalette.getTableHeaderForeground()
        );

        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.minimumHeight = 100;
        rightCandidatesTable.setLayoutData(tableData);

        TableColumn colName = new TableColumn(rightCandidatesTable, SWT.LEFT);
        colName.setText("Name");

        TableColumn colYear = new TableColumn(rightCandidatesTable, SWT.LEFT);
        colYear.setText("Year");

        TableColumn colId = new TableColumn(rightCandidatesTable, SWT.LEFT);
        colId.setText("ID");

        TableColumn colAliases = new TableColumn(
            rightCandidatesTable,
            SWT.LEFT
        );
        colAliases.setText("Aliases");

        // Cap the aliases column width: aliases can be extremely long.
        // Prefer horizontal scrolling over an overly wide table.
        colAliases.setWidth(260);

        // Checkbox-based selection:
        // - Each candidate row has a checkbox.
        // - At most one can be checked at a time.
        // - It can be unchecked so that none are selected.
        // - Clicking anywhere on a row toggles its checkbox.
        rightCandidatesTable.addListener(SWT.Selection, e -> {
            // Clicking a row (not just the checkbox) should toggle the checkbox.
            // SWT will also send events where detail==SWT.CHECK for direct checkbox clicks.
            TableItem clicked = (e == null) ? null : (TableItem) e.item;
            if (clicked == null) {
                return;
            }

            // Toggle checkbox state on row click as well as checkbox click.
            // If the user clicked the checkbox, SWT may have already toggled it.
            // If the user clicked the row, it won't toggle automatically, so we toggle here.
            if (e.detail != SWT.CHECK) {
                clicked.setChecked(!clicked.getChecked());
            }

            // Enforce at most one checked at a time.
            if (clicked.getChecked()) {
                ensureOnlyOneChecked(clicked);
            }

            // Apply/clear selection for the currently selected ambiguous show.
            int leftIdx = leftTable.getSelectionIndex();
            if (leftIdx < 0) {
                updateOkEnabled();
                return;
            }
            TableItem leftItem = leftTable.getItem(leftIdx);
            Object data = leftItem.getData();
            if (!(data instanceof String)) {
                updateOkEnabled();
                return;
            }
            String queryString = (String) data;

            if (!clicked.getChecked()) {
                selections.remove(queryString);
                leftItem.setText(2, "Not selected");
                updateOkEnabled();
                return;
            }

            Object candData = clicked.getData();
            if (candData instanceof ShowOption) {
                ShowOption chosen = (ShowOption) candData;
                selections.put(queryString, chosen.getIdString());
                leftItem.setText(2, "Selected");
            }

            updateOkEnabled();
        });

        // Double-click (default selection) is an expert shortcut:
        // check the row, apply selection, and advance.
        rightCandidatesTable.addListener(SWT.DefaultSelection, e -> {
            int idx = rightCandidatesTable.getSelectionIndex();
            if (idx < 0) {
                return;
            }
            TableItem ti = rightCandidatesTable.getItem(idx);
            if (ti == null) {
                return;
            }
            ti.setChecked(true);
            ensureOnlyOneChecked(ti);
            applyCurrentSelectionAndAdvance();
        });
    }

    private void createButtons(final Composite parent) {
        Composite buttons = new Composite(parent, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        GridLayout gl = new GridLayout(2, true);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        gl.horizontalSpacing = 8;
        buttons.setLayout(gl);

        Button cancelButton = new Button(buttons, SWT.PUSH);
        cancelButton.setText(CANCEL_LABEL);
        cancelButton.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false)
        );
        cancelButton.addListener(SWT.Selection, e -> {
            cancelled = true;
            selections.clear();
            close();
        });

        okButton = new Button(buttons, SWT.PUSH);
        okButton.setText("OK");
        okButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        okButton.addListener(SWT.Selection, e -> {
            // With checkbox-based selection, the selections map is updated on checkbox toggles.
            // Keep a best-effort apply for keyboard-only flows where the user might not toggle
            // the checkbox explicitly.
            applyCurrentSelectionOnly();

            // Enable OK when at least one show has a selection, even if not all are resolved.
            if (!hasAnySelections()) {
                return;
            }

            cancelled = false;
            // Do NOT clear selections here; caller expects selections to be returned from open().
            close();
        });

        dialogShell.setDefaultButton(okButton);
    }

    private void close() {
        if (dialogShell != null && !dialogShell.isDisposed()) {
            dialogShell.close();
        }
    }

    private void populateLeftTable() {
        leftTable.removeAll();

        for (Map.Entry<
            String,
            ShowStore.PendingDisambiguation
        > entry : pending.entrySet()) {
            String queryString = entry.getKey();
            ShowStore.PendingDisambiguation pd = entry.getValue();

            TableItem item = new TableItem(leftTable, SWT.NONE);
            item.setData(queryString);

            String extracted = (pd == null) ? "" : safe(pd.extractedShowName);
            String exampleFile = (pd == null) ? "" : safe(pd.exampleFileName);

            String filesCol = exampleFile.isEmpty() ? "" : "1 file";
            String statusCol = selections.containsKey(queryString)
                ? "Selected"
                : "Not selected";

            item.setText(new String[] { extracted, filesCol, statusCol });
            // TableItem does not support tooltips consistently across SWT versions;
            // keep the display concise and rely on selection context on the right pane.
        }

        for (TableColumn c : leftTable.getColumns()) {
            c.pack();
        }
    }

    /**
     * Merge newly discovered pending disambiguations into the dialog without resetting user choices.
     * New items are appended at the bottom in discovery order.
     */
    public void mergePending(
        final Map<String, ShowStore.PendingDisambiguation> latest
    ) {
        if (latest == null || latest.isEmpty()) {
            return;
        }
        if (dialogShell == null || dialogShell.isDisposed()) {
            return;
        }
        if (leftTable == null || leftTable.isDisposed()) {
            return;
        }

        boolean addedAny = false;

        for (Map.Entry<
            String,
            ShowStore.PendingDisambiguation
        > entry : latest.entrySet()) {
            String queryString = entry.getKey();
            ShowStore.PendingDisambiguation pd = entry.getValue();
            if (queryString == null || queryString.isBlank() || pd == null) {
                continue;
            }

            if (pending.containsKey(queryString)) {
                continue;
            }

            pending.put(queryString, pd);

            TableItem item = new TableItem(leftTable, SWT.NONE);
            item.setData(queryString);

            String extracted = safe(pd.extractedShowName);
            String exampleFile = safe(pd.exampleFileName);

            String filesCol = exampleFile.isEmpty() ? "" : "1 file";
            String statusCol = selections.containsKey(queryString)
                ? "Selected"
                : "Not selected";

            item.setText(new String[] { extracted, filesCol, statusCol });
            addedAny = true;
        }

        if (addedAny) {
            for (TableColumn c : leftTable.getColumns()) {
                c.pack();
            }
            leftTable.getParent().layout(true, true);

            // Force paint: some SWT themes won't repaint immediately on background-driven updates.
            leftTable.redraw();
            leftTable.update();
            dialogShell.redraw();
            dialogShell.update();

            updateOkEnabled();
        }
    }

    private void onLeftSelectionChanged() {
        int idx = leftTable.getSelectionIndex();
        if (idx < 0) {
            clearRightPane();
            return;
        }

        TableItem selectedItem = leftTable.getItem(idx);
        Object data = selectedItem.getData();
        if (!(data instanceof String)) {
            clearRightPane();
            return;
        }

        String queryString = (String) data;
        ShowStore.PendingDisambiguation pd = pending.get(queryString);
        if (pd == null) {
            clearRightPane();
            return;
        }

        showNameValueLabel.setText(safe(pd.extractedShowName));

        String exampleFile = safe(pd.exampleFileName);
        exampleFileValueLabel.setText(exampleFile);
        exampleFileValueLabel.setToolTipText(exampleFile);

        populateCandidates(pd.options, pd.scoredOptions);

        // If already selected for this query, check it in candidate table.
        // Otherwise leave everything unchecked.
        String chosenId = selections.get(queryString);
        if (chosenId != null && !chosenId.isBlank()) {
            selectCandidateById(chosenId);
        } else {
            rightCandidatesTable.deselectAll();
            for (TableItem ti : rightCandidatesTable.getItems()) {
                ti.setChecked(false);
            }
        }

        rightCandidatesTable.getParent().layout(true, true);
    }

    private void clearRightPane() {
        showNameValueLabel.setText("");
        exampleFileValueLabel.setText("");
        exampleFileValueLabel.setToolTipText("");
        rightCandidatesTable.removeAll();
    }

    private void populateCandidates(
            final List<ShowOption> options,
            final List<ShowSelectionEvaluator.ScoredOption> scoredOptions) {
        rightCandidatesTable.removeAll();

        // Build display list: use scored options if available (already sorted best-first),
        // otherwise fall back to original options order.
        List<ShowOption> displayOptions = new ArrayList<>();
        java.util.Map<String, Double> scoreMap = new java.util.HashMap<>();

        if (scoredOptions != null && !scoredOptions.isEmpty()) {
            for (ShowSelectionEvaluator.ScoredOption so : scoredOptions) {
                if (so != null && so.option() != null) {
                    displayOptions.add(so.option());
                    String id = so.option().getIdString();
                    if (id != null) {
                        scoreMap.put(id, so.score());
                    }
                }
            }
        } else if (options != null) {
            displayOptions.addAll(options);
        }

        if (displayOptions.size() > MAX_OPTIONS_PER_SHOW) {
            displayOptions = displayOptions.subList(0, MAX_OPTIONS_PER_SHOW);
        }

        boolean firstRow = true;
        for (ShowOption opt : displayOptions) {
            if (opt == null) {
                continue;
            }

            TableItem item = new TableItem(rightCandidatesTable, SWT.NONE);
            item.setData(opt);

            String name = safe(opt.getName());
            String year = "";
            try {
                Integer y = opt.getFirstAiredYear();
                if (y != null) {
                    year = y.toString();
                }
            } catch (Exception e) {
                logger.fine("Could not get first aired year: " + e.getMessage());
            }

            String id = safe(opt.getIdString());

            String aliases = "";
            try {
                List<String> aliasNames = opt.getAliasNames();
                if (aliasNames != null && !aliasNames.isEmpty()) {
                    aliases = String.join(", ", aliasNames);
                }
            } catch (Exception e) {
                logger.fine("Could not get alias names: " + e.getMessage());
            }

            // Show score and "Recommended" for top match if score is available
            Double score = scoreMap.get(opt.getIdString());
            if (score != null && firstRow && score >= 0.70) {
                name = name + " \u2605 Recommended (" + String.format("%.0f%%", score * 100) + ")";
            } else if (score != null && score >= 0.50) {
                name = name + " (" + String.format("%.0f%%", score * 100) + ")";
            }

            item.setText(new String[] { name, year, id, aliases });
            firstRow = false;
        }

        for (TableColumn c : rightCandidatesTable.getColumns()) {
            c.pack();
        }

        // Re-apply a max width for Aliases after pack(), since aliases can be very long.
        // Column order: Name, Year, ID, Aliases
        if (rightCandidatesTable.getColumnCount() >= 4) {
            TableColumn aliasesCol = rightCandidatesTable.getColumn(3);
            int maxAliasesWidth = 260;
            if (aliasesCol.getWidth() > maxAliasesWidth) {
                aliasesCol.setWidth(maxAliasesWidth);
            }
        }
    }

    private void selectCandidateById(final String id) {
        if (id == null) {
            return;
        }
        for (int i = 0; i < rightCandidatesTable.getItemCount(); i++) {
            TableItem ti = rightCandidatesTable.getItem(i);
            Object data = ti.getData();
            if (data instanceof ShowOption) {
                ShowOption opt = (ShowOption) data;
                if (id.equals(opt.getIdString())) {
                    rightCandidatesTable.setSelection(i);
                    ti.setChecked(true);
                    // Ensure others are unchecked.
                    for (TableItem other : rightCandidatesTable.getItems()) {
                        if (other != ti) {
                            other.setChecked(false);
                        }
                    }
                    return;
                }
            }
        }
    }

    private void applyCurrentSelectionAndAdvance() {
        if (!applyCurrentSelectionOnly()) {
            return;
        }

        int leftIdx = leftTable.getSelectionIndex();
        if (leftIdx < 0) {
            return;
        }

        // Move to next unresolved row if any
        int next = findNextUnresolvedIndex(leftIdx + 1);
        if (next < 0) {
            next = findNextUnresolvedIndex(0);
        }
        if (next >= 0) {
            leftTable.setSelection(next);
            onLeftSelectionChanged();
        }

        updateOkEnabled();
    }

    private boolean applyCurrentSelectionOnly() {
        int leftIdx = leftTable.getSelectionIndex();
        if (leftIdx < 0) {
            return false;
        }
        TableItem leftItem = leftTable.getItem(leftIdx);
        Object data = leftItem.getData();
        if (!(data instanceof String)) {
            return false;
        }
        String queryString = (String) data;

        // Prefer the checked row, if any.
        TableItem checked = null;
        for (TableItem ti : rightCandidatesTable.getItems()) {
            if (ti.getChecked()) {
                checked = ti;
                break;
            }
        }

        // If nothing is checked, fall back to the currently highlighted row (keyboard-only flow),
        // but do not implicitly "select" it unless the user confirms (OK/DefaultSelection).
        TableItem candItem = checked;
        if (candItem == null) {
            int candIdx = rightCandidatesTable.getSelectionIndex();
            if (candIdx < 0) {
                return false;
            }
            candItem = rightCandidatesTable.getItem(candIdx);
        }

        Object candData = candItem.getData();
        if (!(candData instanceof ShowOption)) {
            return false;
        }
        ShowOption chosen = (ShowOption) candData;

        // If we got here via fallback, ensure the chosen row is checked to make state explicit.
        candItem.setChecked(true);
        for (TableItem other : rightCandidatesTable.getItems()) {
            if (other != candItem) {
                other.setChecked(false);
            }
        }

        selections.put(queryString, chosen.getIdString());

        // Update left status cell
        leftItem.setText(2, "Selected");

        updateOkEnabled();
        return true;
    }

    private int findNextUnresolvedIndex(int start) {
        for (int i = start; i < leftTable.getItemCount(); i++) {
            TableItem item = leftTable.getItem(i);
            Object data = item.getData();
            if (data instanceof String) {
                String queryString = (String) data;
                if (!selections.containsKey(queryString)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean hasAnySelections() {
        for (Map.Entry<String, String> e : selections.entrySet()) {
            String queryString = e.getKey();
            String chosenId = e.getValue();
            if (queryString == null || queryString.isBlank()) {
                continue;
            }
            if (chosenId == null || chosenId.isBlank()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void updateOkEnabled() {
        if (okButton == null || okButton.isDisposed()) {
            return;
        }
        okButton.setEnabled(hasAnySelections());
    }

    /**
     * Enable/disable the animated "Downloading" title state.
     * Caller is responsible for stopping it when discovery completes.
     */
    public void setDownloading(final boolean downloading) {
        this.downloading = downloading;
        if (dialogShell == null || dialogShell.isDisposed()) {
            return;
        }

        if (!downloading) {
            dialogShell.setText(TITLE_BASE);
            return;
        }

        // Use a lightweight self-rescheduling runnable to animate the quadrants spinner.
        Display display = dialogShell.getDisplay();
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!BatchShowDisambiguationDialog.this.downloading) {
                    return;
                }
                if (dialogShell == null || dialogShell.isDisposed()) {
                    return;
                }
                char frame = DOWNLOADING_FRAMES[downloadingFrameIdx];
                downloadingFrameIdx =
                    (downloadingFrameIdx + 1) % DOWNLOADING_FRAMES.length;

                dialogShell.setText(
                    TITLE_DOWNLOADING_PREFIX + frame + TITLE_DOWNLOADING_SUFFIX
                );

                display.timerExec(200, this);
            }
        };

        display.timerExec(0, tick);
    }

    /**
     * Ensures at most one checkbox is checked in the right candidates table.
     * Unchecks every item except the given one.
     */
    private void ensureOnlyOneChecked(TableItem checked) {
        for (TableItem ti : rightCandidatesTable.getItems()) {
            if (ti != checked) {
                ti.setChecked(false);
            }
        }
    }

    private static String safe(final String s) {
        return (s == null) ? "" : s;
    }
}
