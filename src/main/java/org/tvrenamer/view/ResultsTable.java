package org.tvrenamer.view;

import static org.tvrenamer.model.util.Constants.*;
import static org.tvrenamer.view.Fields.*;
import static org.tvrenamer.view.ItemState.*;

import java.text.Collator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.TaskBar;
import org.eclipse.swt.widgets.TaskItem;
import org.tvrenamer.controller.AddEpisodeListener;
import org.tvrenamer.controller.FilenameParser;
import org.tvrenamer.controller.FileMover;
import org.tvrenamer.controller.MoveRunner;
import org.tvrenamer.controller.ShowInformationListener;
import org.tvrenamer.controller.ShowListingsListener;
import org.tvrenamer.controller.UpdateChecker;
import org.tvrenamer.controller.UrlLauncher;
import org.tvrenamer.controller.util.StringUtils;
import org.tvrenamer.model.EpisodeDb;
import org.tvrenamer.model.FailedShow;
import org.tvrenamer.model.FileEpisode;
import org.tvrenamer.model.Series;
import org.tvrenamer.model.Show;
import org.tvrenamer.model.ShowName;
import org.tvrenamer.model.ShowStore;
import org.tvrenamer.model.UserPreference;
import org.tvrenamer.model.UserPreferences;

public final class ResultsTable
    implements java.beans.PropertyChangeListener, AddEpisodeListener
{

    private static final Logger logger = Logger.getLogger(
        ResultsTable.class.getName()
    );
    // load preferences
    private static final UserPreferences prefs = UserPreferences.getInstance();
    private static final Collator COLLATOR = Collator.getInstance(
        Locale.getDefault()
    );

    private static final int ITEM_NOT_IN_TABLE = -1;

    private static final int WIDTH_CHECKED = 30;
    private static final int WIDTH_CURRENT_FILE = 550;
    private static final int WIDTH_NEW_FILENAME = 550;
    private static final int WIDTH_STATUS = 60;

    private static final int DEFAULT_MAX_FAILURES_TO_LIST = 3;

    // Status text used when a row is blocked on ambiguous show selection.
    private static final String STATUS_SELECT_SHOW = "Select Show...";

    // Per-row flag stored on TableItem to keep "Select Show..." clickable even if other
    // code recomputes the proposed destination text.
    private static final String SELECT_SHOW_PENDING_KEY =
        "tvrenamer.selectShowPending";

    // Per-row reference to the FileEpisode, stored via setData(String, Object)
    // so it is independent of the unnamed setData(Object) used for Combo/Link widgets.
    private static final String EPISODE_DATA_KEY = "tvrenamer.episode";

    private final UIStarter ui;
    private final Shell shell;
    private final Display display;
    private final ThemePalette themePalette;
    private final Table swtTable;
    private final EpisodeDb episodeMap = new EpisodeDb();

    // "Select Shows..." button: disabled until we first detect any ambiguity,
    // then kept enabled for the remainder of the session for easy access.
    private Button selectShowsButton;
    private volatile boolean selectShowsButtonEverEnabled = false;

    private Button actionButton;
    private ProgressBar totalProgressBar;
    private TaskItem taskItem = null;

    // Guard flag to prevent Combo ModifyListener re-entry during chain propagation.
    private boolean propagatingChain = false;

    // Session-only override for "move selected" (does not persist to preferences).
    // null means "no override; follow preferences".
    private Boolean sessionMoveSelectedOverride = null;

    // The move-selected value from preferences at app startup.
    // We use this so the session checkbox can temporarily override move behavior
    // and still revert to the saved preference on restart.
    private final boolean initialMoveSelected = prefs.isMoveSelected();

    // Bottom-bar "Move [ ]" control (session-only).
    private Button sessionMoveCheckbox;

    // Aggregate overall copy progress for copy+delete operations only.
    // This drives the bottom progress bar smoothly (byte-based) instead of file-count chunks.
    private FileMonitor.AggregateCopyProgress aggregateCopyProgress = null;

    // Overall copy totals computed at the start of a move batch (copy+delete only).
    private volatile long overallCopyTotalBytes = 0L;

    private final Queue<FileEpisode> currentFailures =
        new ConcurrentLinkedQueue<>();

    // Track the currently running move operation so we can cancel it on shutdown.
    private volatile MoveRunner activeMover = null;

    private boolean apiDeprecated = false;

    private synchronized void checkDestinationDirectory() {
        boolean success = prefs.ensureDestDir();
        if (!success) {
            logger.warning(CANT_CREATE_DEST);
            ui.showMessageBox(
                SWTMessageBoxType.DLG_ERR,
                ERROR_LABEL,
                CANT_CREATE_DEST +
                    ": '" +
                    prefs.getDestinationDirectoryName() +
                    "'. " +
                    MOVE_NOT_POSSIBLE
            );
        }
    }

    void ready() {
        logger.fine(
            "ResultsTable.ready(): registering property change listener"
        );
        prefs.addPropertyChangeListener(this);

        logger.fine("ResultsTable.ready(): setting focus to table");
        swtTable.setFocus();

        logger.fine("ResultsTable.ready(): validating destination directory");
        checkDestinationDirectory();

        logger.fine(
            "ResultsTable.ready(): subscribing to episode map and requesting preload"
        );
        // Load the preload folder into the episode map, which will call

        // us back with the list of files once they've been loaded.

        episodeMap.subscribe(this);

        logger.fine("ResultsTable.ready(): preload requested");
        episodeMap.preload();
    }

    private void updateClearCompletedButtonEnabled() {
        // Button only exists after setupTopButtons runs; best-effort lookup.
        Object btnObj = shell.getData("tvrenamer.clearCompletedButton");
        if (!(btnObj instanceof Button)) {
            return;
        }
        Button btn = (Button) btnObj;
        if (btn.isDisposed()) {
            return;
        }

        // If auto-clear is enabled, the button is not meaningful.
        if (prefs.isDeleteRowAfterMove()) {
            btn.setEnabled(false);
            return;
        }

        boolean anyCompleted = false;
        for (final TableItem item : swtTable.getItems()) {
            Object completed = item.getData("tvrenamer.moveCompleted");
            if (Boolean.TRUE.equals(completed)) {
                anyCompleted = true;
                break;
            }
        }
        btn.setEnabled(anyCompleted);
    }

    Display getDisplay() {
        return display;
    }

    ProgressBar getProgressBar() {
        return totalProgressBar;
    }

    /**
     * Whether aggregate (byte-based) copy progress tracking is active for the current batch.
     *
     * When active, the bottom progress bar is updated directly from per-file MoveObserver callbacks
     * (copy+delete operations only), and coarse file-count updates should not overwrite it.
     *
     * @return true if aggregate copy progress is active, false otherwise
     */
    public boolean isAggregateCopyProgressActive() {
        return aggregateCopyProgress != null;
    }

    /**
     * Set the overall copy total for the current batch (copy+delete only).
     *
     * This is computed up-front from the queued moves so the overall progress bar reflects
     * a stable denominator across the entire batch.
     *
     * @param totalBytes total bytes expected to be copied in this batch
     */
    public void setOverallCopyTotalBytes(final long totalBytes) {
        this.overallCopyTotalBytes = Math.max(0L, totalBytes);
    }

    /**
     * Update overall copy progress for the bottom progress bar.
     *
     * This is intended for copy+delete operations only (cross-filesystem moves).
     * Renames are excluded by design because they are effectively instant.
     *
     * @param totalBytes total bytes to be copied in the current batch (<= 0 disables the bar)
     * @param copiedBytes bytes copied so far (clamped to [0,totalBytes])
     */
    public void updateOverallCopyProgress(
        final long totalBytes,
        final long copiedBytes
    ) {
        if (display == null || display.isDisposed()) {
            return;
        }

        // Prefer the batch-computed total (stable denominator) if available.
        final long resolvedTotal = (overallCopyTotalBytes > 0L)
            ? overallCopyTotalBytes
            : totalBytes;

        display.asyncExec(() -> {
            if (shell == null || shell.isDisposed()) {
                return;
            }
            if (totalProgressBar == null || totalProgressBar.isDisposed()) {
                return;
            }

            if (resolvedTotal <= 0) {
                totalProgressBar.setSelection(0);
                return;
            }

            int max = totalProgressBar.getMaximum();
            if (max <= 0) {
                max = 1000;
                totalProgressBar.setMaximum(max);
            }

            long safeCopied = Math.max(
                0L,
                Math.min(copiedBytes, resolvedTotal)
            );
            double ratio = (double) safeCopied / (double) resolvedTotal;
            int sel = (int) Math.round(ratio * max);

            totalProgressBar.setSelection(Math.max(0, Math.min(sel, max)));
        });
    }

    TaskItem getTaskItem() {
        return taskItem;
    }

    private Combo newComboBox() {
        if (swtTable.isDisposed()) {
            return null;
        }
        return new Combo(swtTable, SWT.DROP_DOWN | SWT.READ_ONLY);
    }

    private TableItem newTableItem() {
        return new TableItem(swtTable, SWT.NONE);
    }

    private void setComboBoxProposedDest(
        final TableItem item,
        final FileEpisode ep
    ) {
        if (swtTable.isDisposed() || item.isDisposed()) {
            return;
        }
        final List<String> options = ep.getReplacementOptions();
        final int chosen = ep.getChosenEpisode();
        final String defaultOption = options.get(chosen);
        NEW_FILENAME_FIELD.setCellText(item, defaultOption);

        final Combo combo = newComboBox();
        if (combo == null) {
            return;
        }
        options.forEach(combo::add);
        combo.setText(defaultOption);

        // Fuzzy pre-selection: if the filename contains title text that matches
        // one option significantly better than the other, pre-select it and
        // cascade through the chain.
        if (ep.optionCount() == 2) {
            int fuzzyPick = ep.fuzzyPreSelectEpisode();
            if (fuzzyPick >= 0 && fuzzyPick != ep.getChosenEpisode()) {
                ep.setChosenEpisode(fuzzyPick);
                combo.select(fuzzyPick);

                String title = ep.getEpisodeTitle(fuzzyPick);
                if (title != null) {
                    propagatingChain = true;
                    try {
                        Set<FileEpisode> visited = new HashSet<>();
                        visited.add(ep);
                        propagateEpisodeChain(ep, title, visited);
                    } finally {
                        propagatingChain = false;
                    }
                }
            }
        }

        combo.addModifyListener(e -> {
            int idx = combo.getSelectionIndex();
            ep.setChosenEpisode(idx);
            if (!propagatingChain) {
                String title = ep.getEpisodeTitle(idx);
                if (title != null && ep.optionCount() == 2) {
                    propagatingChain = true;
                    try {
                        Set<FileEpisode> visited = new HashSet<>();
                        visited.add(ep);
                        propagateEpisodeChain(ep, title, visited);
                    } finally {
                        propagatingChain = false;
                    }
                }
            }
        });
        item.setData(combo);

        final TableEditor editor = new TableEditor(swtTable);
        editor.grabHorizontal = true;
        NEW_FILENAME_FIELD.setEditor(item, editor, combo);
    }

    /**
     * When the user selects an episode title from a 2-option Combo, propagate
     * the constraint to other episodes of the same show that share the selected
     * title.  See docs/Episode Chain Spec.md.
     */
    private void propagateEpisodeChain(
        final FileEpisode sourceEp,
        final String selectedTitle,
        final Set<FileEpisode> visited
    ) {
        if (swtTable.isDisposed()) {
            return;
        }
        for (final TableItem item : swtTable.getItems()) {
            if (item.isDisposed()) {
                continue;
            }
            final Object data = item.getData(EPISODE_DATA_KEY);
            if (!(data instanceof FileEpisode otherEp)) {
                continue;
            }
            if (otherEp == sourceEp || visited.contains(otherEp)) {
                continue;
            }
            if (otherEp.optionCount() != 2) {
                continue;
            }
            if (otherEp.getActualShow() != sourceEp.getActualShow()) {
                continue;
            }

            int titleIndex = otherEp.indexOfEpisodeTitle(selectedTitle);
            if (titleIndex < 0) {
                continue;
            }

            int otherIndex = 1 - titleIndex;
            if (otherEp.getChosenEpisode() == otherIndex) {
                continue;
            }

            otherEp.setChosenEpisode(otherIndex);
            visited.add(otherEp);

            // Update the Combo widget to reflect the new selection.
            final Object widgetData = item.getData();
            if (widgetData instanceof Combo combo && !combo.isDisposed()) {
                combo.select(otherIndex);
            }

            // Cascade: the newly selected title may chain to yet another episode.
            String newTitle = otherEp.getEpisodeTitle(otherIndex);
            if (newTitle != null) {
                propagateEpisodeChain(otherEp, newTitle, visited);
            }
        }
    }

    private void deleteItemControl(final TableItem item) {
        final Object itemData = item.getData();
        if (itemData != null) {
            final Control oldControl = (Control) itemData;
            if (!oldControl.isDisposed()) {
                oldControl.dispose();
            }
        }
    }

    /**
     * Embeds a clickable Link widget in the "Proposed File Path" cell for rows
     * where the show was not found. Clicking the link opens Preferences with the
     * Matching tab selected and the extracted show name pre-filled.
     */
    private void setUnfoundShowLink(
        final TableItem item,
        final FileEpisode ep,
        final String displayText
    ) {
        if (swtTable.isDisposed() || item.isDisposed()) {
            return;
        }
        final Link link = new Link(swtTable, SWT.NONE);
        link.setText(displayText + " \u2014 <a>click to set Hint</a>");
        link.setToolTipText(
            "Opens Preferences \u2192 Matching to add a show name override"
        );

        link.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                String extractedShow = ep.getExtractedFilenameShow();
                PreferencesDialog preferencesDialog =
                    new PreferencesDialog(shell);
                preferencesDialog.open(2, extractedShow);
            }
        });

        item.setData(link);

        final TableEditor editor = new TableEditor(swtTable);
        editor.grabHorizontal = true;
        NEW_FILENAME_FIELD.setEditor(item, editor, link);
    }

    /**
     * Fill in the value for the "Proposed File" column of the given row, with the
     * text
     * we get from the given episode. This is the only method that should ever set
     * this text, to ensure that the text of each row is ALWAYS the value returned
     * by
     * getReplacementText() on the associated episode.
     *
     * @param item
     *             the row in the table to set the text of the "Proposed File"
     *             column
     * @param ep
     *             the FileEpisode to use to obtain the text
     */
    private void setProposedDestColumn(
        final TableItem item,
        final FileEpisode ep
    ) {
        if (swtTable.isDisposed() || item.isDisposed()) {
            return;
        }
        deleteItemControl(item);

        int nOptions = ep.optionCount();
        if (nOptions > 1) {
            setComboBoxProposedDest(item, ep);
        } else if (nOptions == 1) {
            NEW_FILENAME_FIELD.setCellText(item, ep.getReplacementText());
        } else {
            String displayText = ep.getReplacementText();
            NEW_FILENAME_FIELD.setCellText(item, displayText);
            item.setChecked(false);

            if (ep.isShowUnfound()) {
                setUnfoundShowLink(item, ep, displayText);
            }
        }
    }

    private void failTableItem(final TableItem item) {
        STATUS_FIELD.setCellImage(item, FAIL);
        item.setChecked(false);
    }

    /**
     * Shows a summary dialog after batch parsing if there were failures.
     * This is non-blocking and displayed after all files have been processed.
     */
    private void showParseSummary(
        final int totalAdded,
        final int failureCount,
        final Map<FilenameParser.ParseFailureReason, Integer> failureBreakdown
    ) {
        if (failureCount == 0) {
            return;
        }

        StringBuilder message = new StringBuilder();
        int successCount = totalAdded - failureCount;
        if (successCount > 0) {
            message.append("Added ").append(successCount).append(" file(s) successfully.\n");
        }
        message.append(failureCount).append(" file(s) could not be parsed:\n\n");

        for (Map.Entry<FilenameParser.ParseFailureReason, Integer> entry : failureBreakdown.entrySet()) {
            message.append("  \u2022 ").append(entry.getKey().getUserMessage())
                   .append(": ").append(entry.getValue()).append("\n");
        }

        message.append("\nFiles that failed to parse are marked with a red X icon.");

        ui.showMessageBox(
            SWTMessageBoxType.DLG_WARN,
            PARSE_SUMMARY_TITLE,
            message.toString()
        );
    }

    private void setTableItemStatus(final TableItem item, final int epsFound) {
        if (epsFound > 1) {
            STATUS_FIELD.setCellImage(item, OPTIONS);
            item.setChecked(true);
        } else if (epsFound == 1) {
            STATUS_FIELD.setCellImage(item, SUCCESS);
            item.setChecked(true);
        } else {
            failTableItem(item);
        }
    }

    private int getTableItemIndex(final TableItem item) {
        try {
            return swtTable.indexOf(item);
        } catch (IllegalArgumentException | SWTException ignored) {
            // We'll just fall through and return the sentinel.
        }
        return ITEM_NOT_IN_TABLE;
    }

    private boolean tableContainsTableItem(final TableItem item) {
        return (ITEM_NOT_IN_TABLE != getTableItemIndex(item));
    }

    private void listingsDownloaded(
        final TableItem item,
        final FileEpisode episode
    ) {
        int epsFound = episode.listingsComplete();
        display.asyncExec(() -> {
            if (tableContainsTableItem(item)) {
                setProposedDestColumn(item, episode);
                setTableItemStatus(item, epsFound);
            }
        });
    }

    private void listingsFailed(
        final TableItem item,
        final FileEpisode episode,
        final Exception err
    ) {
        episode.listingsFailed(err);
        display.asyncExec(() -> {
            if (tableContainsTableItem(item)) {
                setProposedDestColumn(item, episode);
                failTableItem(item);
            }
        });
    }

    private void getSeriesListings(
        final Series series,
        final TableItem item,
        final FileEpisode episode
    ) {
        series.addListingsListener(
            new ShowListingsListener() {
                @Override
                public void listingsDownloadComplete() {
                    listingsDownloaded(item, episode);
                }

                @Override
                public void listingsDownloadFailed(Exception err) {
                    listingsFailed(item, episode, err);
                }
            }
        );
    }

    private void tableItemFailed(
        final TableItem item,
        final FileEpisode episode
    ) {
        display.asyncExec(() -> {
            if (tableContainsTableItem(item)) {
                setProposedDestColumn(item, episode);
                failTableItem(item);
            }
        });
    }

    private synchronized void noteApiFailure() {
        boolean showDialogBox = !apiDeprecated;
        apiDeprecated = true;
        if (showDialogBox) {
            boolean updateIsAvailable = UpdateChecker.isUpdateAvailable();
            ui.showMessageBox(
                SWTMessageBoxType.DLG_ERR,
                ERROR_LABEL,
                updateIsAvailable ? GET_UPDATE_MESSAGE : NEED_UPDATE
            );
        }
    }

    private TableItem createTableItem(final FileEpisode episode) {
        TableItem item = newTableItem();

        // Initially we add items to the table unchecked. When we successfully obtain
        // enough
        // information about the episode to determine how to rename it, the check box
        // will
        // automatically be activated.
        item.setChecked(false);
        item.setData(EPISODE_DATA_KEY, episode);
        CURRENT_FILE_FIELD.setCellText(item, episode.getFilepath());
        setProposedDestColumn(item, episode);
        STATUS_FIELD.setCellImage(item, DOWNLOADING);
        return item;
    }

    private void setSelectShowPending(final TableItem item) {
        if (item == null || item.isDisposed()) {
            return;
        }
        // Durable "action required" state: store a per-row flag so the row remains clickable
        // even if other code later recomputes the proposed destination text.
        item.setData(SELECT_SHOW_PENDING_KEY, Boolean.TRUE);

        // Use a dedicated "action required" icon, but keep the required action visible in the
        // Proposed File Path column.
        STATUS_FIELD.setCellImage(item, ItemState.ACTION_REQUIRED.getIcon());
        NEW_FILENAME_FIELD.setCellText(item, STATUS_SELECT_SHOW);
    }

    @Override
    public void addEpisodes(final Queue<FileEpisode> episodes) {
        // Track parse failures for summary dialog
        int totalAdded = 0;
        int parseFailures = 0;
        Map<FilenameParser.ParseFailureReason, Integer> failureBreakdown = new LinkedHashMap<>();

        for (final FileEpisode episode : episodes) {
            totalAdded++;
            final TableItem item = createTableItem(episode);
            if (!episode.wasParsed()) {
                failTableItem(item);
                parseFailures++;
                // Track failure reason for summary
                FilenameParser.ParseFailureReason reason = episode.getParseFailureReason();
                if (reason != null) {
                    failureBreakdown.merge(reason, 1, Integer::sum);
                }
                continue;
            }
            synchronized (this) {
                if (apiDeprecated) {
                    tableItemFailed(item, episode);
                    continue;
                }
            }

            final String showName = episode.getFilenameShow();
            if (StringUtils.isBlank(showName)) {
                logger.fine("no show name found for " + episode);
                continue;
            }

            ShowStore.mapStringToShow(
                showName,
                new ShowInformationListener() {
                    @Override
                    public void downloadSucceeded(Show show) {
                        episode.setEpisodeShow(show);
                        display.asyncExec(() -> {
                            if (tableContainsTableItem(item)) {
                                setProposedDestColumn(item, episode);
                                STATUS_FIELD.setCellImage(item, ADDED);
                                item.setText(
                                    STATUS_FIELD.column.id,
                                    EMPTY_STRING
                                );
                            }
                        });
                        if (show.isValidSeries()) {
                            getSeriesListings(show.asSeries(), item, episode);
                        }
                    }

                    @Override
                    public void downloadFailed(FailedShow failedShow) {
                        episode.setFailedShow(failedShow);

                        // If the failure is due to pending show disambiguation, do not surface it
                        // as an immediate user-visible "unable to find show" error. We'll prompt
                        // the user in the batch disambiguation dialog and then re-run lookup.
                        String msg = null;
                        try {
                            msg = failedShow.toString();
                        } catch (Exception e) {
                            logger.fine("Could not get failure message: " + e.getMessage());
                        }
                        if (
                            msg != null &&
                            msg
                                .toLowerCase(Locale.ROOT)
                                .contains("show selection required")
                        ) {
                            display.asyncExec(() -> {
                                if (tableContainsTableItem(item)) {
                                    setSelectShowPending(item);
                                }
                            });
                            return;
                        }

                        tableItemFailed(item, episode);
                    }

                    @Override
                    public void apiHasBeenDeprecated() {
                        noteApiFailure();
                        episode.setApiDiscontinued();
                        tableItemFailed(item, episode);
                    }
                }
            );
        }

        // Parse failures are shown in-table (unchecked, with reason in status column)
        // rather than as a modal popup, so non-media files like subtitles or docs
        // don't trigger disruptive error dialogs.
        if (parseFailures > 0) {
            logger.info("Added " + totalAdded + " file(s); "
                        + parseFailures + " could not be parsed");
        }

        // Refresh any existing "Select Show..." rows in case adding files (or other table refresh
        // paths) recomputed the Proposed File Path column and wiped the label.
        markAllSelectShowPending();

        // No timer-based polling: ShowStore notifies us when pending disambiguations are enqueued.
        // We do nothing here; the listener will open the batch dialog when needed.
    }

    // Guard against re-entrancy / multiple dialogs opening at once.
    private volatile boolean batchDisambiguationDialogOpen = false;

    // Debounce reopening the batch dialog after the user cancels/closes it.
    // Pending ambiguities may still exist (by design), and ShowStore may notify again.
    // We treat the window close button as cancel, so both paths should cool down.
    private volatile long batchDisambiguationReopenNotBeforeMs = 0L;

    // Small cooldown to prevent immediate re-open loops if pending ambiguities remain.
    private static final int BATCH_DISAMBIGUATION_REOPEN_COOLDOWN_MS = 1500;

    private void markAllSelectShowPending() {
        // Mark only rows that are actually blocked on show disambiguation.
        // We do this by matching the row's provider query string against the current pending disambiguations.
        Map<String, ShowStore.PendingDisambiguation> pending =
            ShowStore.getPendingDisambiguations();
        if (pending == null || pending.isEmpty()) {
            return;
        }

        HashSet<String> pendingQueryStrings = new HashSet<>();
        for (String q : pending.keySet()) {
            if (q != null && !q.isBlank()) {
                pendingQueryStrings.add(q);
            }
        }
        if (pendingQueryStrings.isEmpty()) {
            return;
        }

        for (final TableItem item : swtTable.getItems()) {
            if (item == null || item.isDisposed()) {
                continue;
            }

            String fileNameKey = CURRENT_FILE_FIELD.getCellText(item);
            FileEpisode episode = episodeMap.get(fileNameKey);
            if (episode == null || !episode.wasParsed()) {
                // Clear any stale flag
                item.setData(SELECT_SHOW_PENDING_KEY, null);
                continue;
            }

            String extractedShow = episode.getExtractedFilenameShow();
            if (StringUtils.isBlank(extractedShow)) {
                item.setData(SELECT_SHOW_PENDING_KEY, null);
                continue;
            }

            String queryString = StringUtils.makeQueryString(extractedShow);
            if (!pendingQueryStrings.contains(queryString)) {
                // No longer pending: clear flag and allow normal proposed-dest rendering.
                item.setData(SELECT_SHOW_PENDING_KEY, null);
                continue;
            }

            setSelectShowPending(item);
        }
    }

    // (removed duplicate declaration)

    /**
     * Show a single batch dialog for any queued show disambiguations, persist the user's
     * selections (query string -> series id), then re-trigger lookup for each affected
     * extracted show name so the table updates.
     *
     * @return true if a dialog was shown (or attempted), false if there was nothing to show
     */
    // Keep a reference so we can stream new pending items into an already-open dialog.
    private BatchShowDisambiguationDialog batchDisambiguationDialog = null;

    // Only auto-open the batch dialog once per "add files" operation: when pending transitions
    // from empty -> non-empty. Further pending items should stream into the open dialog.
    // After the user closes the dialog, we do not auto-open again; remaining pending items can
    // be opened explicitly via the "Select Shows..." button.
    private volatile int lastKnownPendingDisambiguationCount = 0;

    private boolean showBatchDisambiguationDialogIfNeeded() {
        logger.info(
            "Batch show disambiguation: checking for pending ambiguities..."
        );

        // Debounce: if the user just cancelled/closed the dialog, don't immediately reopen it.
        long now = System.currentTimeMillis();
        if (now < batchDisambiguationReopenNotBeforeMs) {
            logger.info(
                "Batch show disambiguation: within cancel/close cooldown; not reopening yet."
            );
            return false;
        }

        // If the table is empty, there is nothing to resolve. Clear any stale pending
        // disambiguations so we don't show a "Resolve ambiguous shows" dialog later.
        if (swtTable.getItemCount() == 0) {
            logger.info(
                "Batch show disambiguation: results table is empty; clearing stale pending ambiguities."
            );
            ShowStore.clearPendingDisambiguations();
            return false;
        }

        // Snapshot pending disambiguations from the lookup layer
        Map<String, ShowStore.PendingDisambiguation> pending =
            ShowStore.getPendingDisambiguations();

        if (pending == null || pending.isEmpty()) {
            logger.info(
                "Batch show disambiguation: no pending ambiguities found (nothing to show)."
            );
            return false;
        }

        // Enable the "Select Shows..." button once we know ambiguities exist.
        // Keep it enabled for the session even after resolving, so the user can reopen if needed.
        if (!selectShowsButtonEverEnabled) {
            selectShowsButtonEverEnabled = true;
            if (selectShowsButton != null && !selectShowsButton.isDisposed()) {
                selectShowsButton.setEnabled(true);
            }
        }

        logger.info(
            "Batch show disambiguation: found " +
                pending.size() +
                " pending ambiguity(ies); preparing dialog"
        );

        // Enrich pending entries with an example filename if missing.
        // We only have access to episodes here, so we pick the first matching file for each extracted show.
        Map<String, ShowStore.PendingDisambiguation> enriched =
            new LinkedHashMap<>();

        for (Map.Entry<
            String,
            ShowStore.PendingDisambiguation
        > entry : pending.entrySet()) {
            String queryString = entry.getKey();
            ShowStore.PendingDisambiguation pd = entry.getValue();
            if (pd == null) {
                continue;
            }

            String exampleFileName = pd.exampleFileName;
            if (exampleFileName == null || exampleFileName.isBlank()) {
                // Find a representative file name from the table/model
                String extracted = pd.extractedShowName;
                if (extracted != null) {
                    for (TableItem item : swtTable.getItems()) {
                        String fileNameKey = CURRENT_FILE_FIELD.getCellText(
                            item
                        );
                        FileEpisode ep = episodeMap.get(fileNameKey);
                        if (ep == null) {
                            continue;
                        }
                        if (extracted.equals(ep.getExtractedFilenameShow())) {
                            exampleFileName = ep.getFileName();
                            break;
                        }
                    }
                }
            }

            enriched.put(
                queryString,
                new ShowStore.PendingDisambiguation(
                    pd.queryString,
                    pd.extractedShowName,
                    (exampleFileName == null) ? "" : exampleFileName,
                    pd.options,
                    pd.scoredOptions
                )
            );
        }

        // Show the batch dialog on the UI thread (we're already on UI thread here).
        BatchShowDisambiguationDialog dialog =
            new BatchShowDisambiguationDialog(shell, enriched);

        // IMPORTANT: set the live dialog reference and open-flag before entering dialog.open().
        // Otherwise, pending-disambiguation notifications that arrive during dialog startup can
        // observe "dialogOpen==true but dialog==null" and won't stream into the already-open dialog.
        batchDisambiguationDialog = dialog;
        batchDisambiguationDialogOpen = true;

        // Track resolved queryStrings so we can remove only those from the pending queue.
        final HashSet<String> resolvedQueryStrings = new HashSet<>();

        try {
            logger.info("Batch show disambiguation: opening dialog...");
            Map<String, String> selections = dialog.open();
            if (selections == null) {
                logger.info(
                    "Batch show disambiguation: user cancelled/closed dialog; leaving pending ambiguities queued."
                );

                // Debounce reopening to avoid immediate re-open loops when pending ambiguities remain.
                batchDisambiguationReopenNotBeforeMs =
                    System.currentTimeMillis() +
                    BATCH_DISAMBIGUATION_REOPEN_COOLDOWN_MS;

                // User cancelled: keep the pending queue, but make the UI state explicit.
                // Any rows that are blocked on show disambiguation should show "Select Show..."
                // so users don't think it's still downloading.
                markAllSelectShowPending();
                return true;
            }
            logger.info(
                "Batch show disambiguation: dialog completed with " +
                    selections.size() +
                    " selection(s)"
            );

            // Apply selections (persist + update prefs). We intentionally allow partial resolution.
            for (Map.Entry<String, String> sel : selections.entrySet()) {
                String queryString = sel.getKey();
                String chosenId = sel.getValue();
                if (queryString == null || queryString.isBlank()) {
                    continue;
                }
                if (chosenId == null || chosenId.isBlank()) {
                    continue;
                }
                ShowStore.applyShowDisambiguationSelection(
                    queryString,
                    chosenId
                );
                resolvedQueryStrings.add(queryString);
            }

            // Remove only resolved items; keep unresolved pending so the user can reopen via the button later.
            if (!resolvedQueryStrings.isEmpty()) {
                ShowStore.removePendingDisambiguations(resolvedQueryStrings);
            }

            // Re-trigger lookup for each affected item based on provider query string rather than
            // the extracted show name. This avoids mismatches where the extracted name varies
            // (punctuation/case/overrides) but the underlying query string is what we actually
            // disambiguate and persist.
            HashSet<String> affectedQueryStrings = new HashSet<>(
                resolvedQueryStrings
            );
            if (affectedQueryStrings.isEmpty()) {
                return true;
            }

            for (final TableItem item : swtTable.getItems()) {
                String fileNameKey = CURRENT_FILE_FIELD.getCellText(item);
                final FileEpisode episode = episodeMap.get(fileNameKey);
                if (episode == null) {
                    continue;
                }
                if (!episode.wasParsed()) {
                    continue;
                }

                final String extractedShow = episode.getExtractedFilenameShow();
                if (StringUtils.isBlank(extractedShow)) {
                    continue;
                }

                // Compute the provider query string the same way ShowName/ShowStore does.
                final String queryString = StringUtils.makeQueryString(
                    extractedShow
                );
                if (!affectedQueryStrings.contains(queryString)) {
                    continue;
                }

                // Reset to "downloading" while we re-resolve show
                STATUS_FIELD.setCellImage(item, DOWNLOADING);

                ShowStore.mapStringToShow(
                    extractedShow,
                    new ShowInformationListener() {
                        @Override
                        public void downloadSucceeded(Show show) {
                            episode.setEpisodeShow(show);
                            display.asyncExec(() -> {
                                if (tableContainsTableItem(item)) {
                                    setProposedDestColumn(item, episode);
                                    STATUS_FIELD.setCellImage(item, ADDED);
                                }
                            });
                            if (show.isValidSeries()) {
                                getSeriesListings(
                                    show.asSeries(),
                                    item,
                                    episode
                                );
                            }
                        }

                        @Override
                        public void downloadFailed(FailedShow failedShow) {
                            episode.setFailedShow(failedShow);

                            // Same logic as above: suppress "show selection required" failures
                            // because they'll be resolved via the batch disambiguation dialog.
                            String msg = null;
                            try {
                                msg = failedShow.toString();
                            } catch (Exception e) {
                                logger.fine("Could not get failure message: " + e.getMessage());
                            }
                            if (
                                msg != null &&
                                msg
                                    .toLowerCase(Locale.ROOT)
                                    .contains("show selection required")
                            ) {
                                display.asyncExec(() -> {
                                    if (tableContainsTableItem(item)) {
                                        STATUS_FIELD.setCellImage(
                                            item,
                                            DOWNLOADING
                                        );
                                    }
                                });
                                return;
                            }

                            tableItemFailed(item, episode);
                        }

                        @Override
                        public void apiHasBeenDeprecated() {
                            noteApiFailure();
                            episode.setApiDiscontinued();
                            tableItemFailed(item, episode);
                        }
                    }
                );
            }

            return true;
        } finally {
            // Ensure any "Downloading" title animation is stopped when the dialog closes.
            if (batchDisambiguationDialog != null) {
                try {
                    batchDisambiguationDialog.setDownloading(false);
                } catch (RuntimeException ignored) {
                    // best-effort; dialog may already be disposed
                }
            }
            batchDisambiguationDialogOpen = false;
            batchDisambiguationDialog = null;
        }

        // (Selection apply + queue clear + re-trigger lookups are handled above within the try block.)
    }

    /**
     * Creates a progress label for the given item, used to display progress
     * while the item's file is being copied. (We don't actually support "copying"
     * the file, only moving it, but when the user chooses to "move" it across
     * filesystems, that becomes a copy-and-delete operation.)
     *
     * @param item
     *             the item to create a progress label for
     * @return
     *         a ProgressLabelResult containing both the Label and TableEditor,
     *         which must both be disposed when progress is complete
     */
    public FileMonitor.ProgressLabelResult createProgressLabel(final TableItem item) {
        Label progressLabel = new Label(swtTable, SWT.SHADOW_NONE | SWT.CENTER);
        TableEditor editor = new TableEditor(swtTable);
        editor.grabHorizontal = true;
        STATUS_FIELD.setEditor(item, editor, progressLabel);

        return new FileMonitor.ProgressLabelResult(progressLabel, editor);
    }

    private void renameFiles() {
        // Ensure items are sorted by the current sort column so moves
        // are processed in the visual order displayed to the user.
        ensureTableSorted();

        final List<FileMover> pendingMoves = new LinkedList<>();
        for (final TableItem item : swtTable.getItems()) {
            if (item.getChecked()) {
                String fileName = CURRENT_FILE_FIELD.getCellText(item);
                final FileEpisode episode = episodeMap.get(fileName);
                // Skip files not successfully downloaded and ready to be moved
                if (episode.optionCount() == 0) {
                    logger.fine(
                        "checked but not ready: " + episode.getFilepath()
                    );
                    continue;
                }
                // Skip rows that have already been successfully processed (moved/renamed)
                Object moveCompleted = item.getData("tvrenamer.moveCompleted");
                if (Boolean.TRUE.equals(moveCompleted)) {
                    logger.fine(
                        "checked but already completed: " + episode.getFilepath()
                    );
                    continue;
                }
                // Track whether this row has been successfully processed so "Clear Completed"
                // can remove it later when auto-clear is disabled.
                item.setData("tvrenamer.moveCompleted", Boolean.FALSE);

                FileMover pendingMove = new FileMover(episode);

                // Lazily create an aggregate tracker for overall copy progress.
                // This will only be meaningfully driven for copy+delete operations
                // (cross-filesystem moves) where FileMover invokes initializeProgress/setProgressValue.
                if (aggregateCopyProgress == null) {
                    aggregateCopyProgress =
                        new FileMonitor.AggregateCopyProgress(this);
                }

                pendingMove.addObserver(
                    new FileMonitor(this, item, aggregateCopyProgress)
                );
                pendingMoves.add(pendingMove);
            }
        }

        // If there is nothing to do, re-enable the action button and return.
        if (pendingMoves.isEmpty()) {
            actionButton.setEnabled(true);
            return;
        }

        try {
            // Compute overall copy total up-front (copy+delete only).
            //
            // IMPORTANT: whether a move will use rename vs copy+delete depends on whether the source and the
            // *actual destination directory for that file* are on the same filesystem. We therefore mirror the
            // disk-check logic here and sum sizes only for those that will require copy+delete.
            long totalCopyBytes = 0L;
            for (final FileMover m : pendingMoves) {
                if (m == null) {
                    continue;
                }
                try {
                    // Mirror FileMover's logic: copy+delete when source and destination are not on the same disk.
                    final boolean sameDisk =
                        org.tvrenamer.controller.util.FileUtilities.areSameDisk(
                            m.getCurrentPath(),
                            m.getMoveToDirectory()
                        );
                    if (!sameDisk) {
                        totalCopyBytes += m.getFileSize();
                    }
                } catch (Exception e) {
                    logger.fine("Could not determine disk for progress: " + e.getMessage());
                }
            }
            setOverallCopyTotalBytes(totalCopyBytes);

            MoveRunner mover = new MoveRunner(pendingMoves);
            mover.setUpdater(new ProgressBarUpdater(this));
            activeMover = mover;
            mover.runThread();
        } catch (RuntimeException e) {
            // If we fail to start the move thread, restore UI state.
            activeMover = null;
            logger.log(Level.WARNING, "Failed to start move operation", e);
            actionButton.setEnabled(true);
            throw e;
        }
    }

    private void executeActionButton() {
        currentFailures.clear();

        // Session override: treat "move selected" as disabled when the session checkbox is OFF.
        // Note: moveEnabled reflects destination validation; renameSelected reflects preferences.
        if (
            (!prefs.isMoveEnabled() || !isMoveSelectedForSession()) &&
            !prefs.isRenameSelected()
        ) {
            logger.fine("move and rename both disabled, nothing to be done.");
            return;
        }

        actionButton.setEnabled(false);
        try {
            renameFiles();
        } catch (RuntimeException e) {
            // Defensive: ensure the UI doesn’t get stuck disabled if startup fails.
            actionButton.setEnabled(true);
            throw e;
        }
        swtTable.setFocus();
    }

    /**
     * Insert a copy of the row at the given position, and then delete the original
     * row.
     * Note that insertion does not overwrite the row that is already there. It
     * pushes
     * the row, and every row below it, down one slot.
     *
     * @param oldItem
     *                         the TableItem to copy
     * @param positionToInsert
     *                         the position where we should insert the row
     */
    private void setSortedItem(
        final TableItem oldItem,
        final int positionToInsert
    ) {
        boolean wasChecked = oldItem.getChecked();

        TableItem item = new TableItem(swtTable, SWT.NONE, positionToInsert);
        item.setChecked(wasChecked);
        CURRENT_FILE_FIELD.setCellText(
            item,
            CURRENT_FILE_FIELD.getCellText(oldItem)
        );
        NEW_FILENAME_FIELD.setCellText(
            item,
            NEW_FILENAME_FIELD.getCellText(oldItem)
        );
        STATUS_FIELD.setCellImage(item, STATUS_FIELD.getCellImage(oldItem));

        final Object itemData = oldItem.getData();

        // Although the name suggests dispose() is primarily about reclaiming system
        // resources, it also deletes the item from the Table.
        oldItem.dispose();
        if (itemData != null) {
            final TableEditor newEditor = new TableEditor(swtTable);
            newEditor.grabHorizontal = true;
            NEW_FILENAME_FIELD.setEditor(item, newEditor, (Control) itemData);
            item.setData(itemData);
        }
    }

    /**
     * Sort the table by the given column in the given direction.
     *
     * @param column
     *                      the Column to sort by
     * @param sortDirection
     *                      the direction to sort by; SWT.UP means sort A-Z, while
     *                      SWT.DOWN is Z-A
     */
    void sortTable(final Column column, final int sortDirection) {
        if (swtTable.isDisposed()) {
            return;
        }

        // Sorting can temporarily mis-paint TableEditor controls (notably Combos used
        // when there are multiple "Proposed File Name" options). Reduce visible
        // glitches by suspending redraw during row shuffling and forcing a refresh
        // once done.
        swtTable.setRedraw(false);
        try {
            Field field = column.field;

            // Get the items
            TableItem[] items = swtTable.getItems();

            // Go through the item list and bubble rows up to the top as appropriate
            for (int i = 1; i < items.length; i++) {
                String value1 = field.getItemTextValue(items[i]);
                for (int j = 0; j < i; j++) {
                    String value2 = field.getItemTextValue(items[j]);
                    // Compare the two values and order accordingly
                    int comparison = COLLATOR.compare(value1, value2);
                    if (
                        ((comparison < 0) && (sortDirection == SWT.UP)) ||
                        ((comparison > 0) && (sortDirection == SWT.DOWN))
                    ) {
                        // Insert a copy of row i at position j, and then delete
                        // row i. Then fetch the list of items anew, since we
                        // just modified it.
                        setSortedItem(items[i], j);
                        items = swtTable.getItems();
                        break;
                    }
                }
            }
            swtTable.setSortDirection(sortDirection);
            swtTable.setSortColumn(column.swtColumn);

            // Rebuild any Combo/TableEditor controls after sorting.
            // During sorting we reattach existing Combo controls to new TableItems, and SWT can
            // leave one cell visually stale until the next focus/selection event. Recreating the
            // editors deterministically prevents the "first row wrong until click" glitch.
            for (TableItem item : swtTable.getItems()) {
                if (item == null || item.isDisposed()) {
                    continue;
                }

                // Dispose any existing combo editor control (if present)
                deleteItemControl(item);
                item.setData(null);

                // Recompute proposed destination/editor from the model
                String fileName = CURRENT_FILE_FIELD.getCellText(item);
                String newFileName = episodeMap.currentLocationOf(fileName);
                if (newFileName == null) {
                    // Defensive: if the file moved out from under us, remove the row.
                    deleteTableItem(item);
                    continue;
                }
                FileEpisode episode = episodeMap.get(newFileName);
                if (episode != null) {
                    setProposedDestColumn(item, episode);
                }
            }
        } finally {
            // Force a post-sort layout/paint pass so any embedded editors (Combo/TableEditor)
            // recompute bounds and the UI doesn't appear "half sorted" until the next click.
            swtTable.setRedraw(true);
            swtTable.layout(true, true);
            swtTable.redraw();
            swtTable.update();
        }
    }

    /**
     * Refreshes the "destination" and "status" field of all items in the table.
     *
     * This is intended to be called after something happens which changes what the
     * proposed destination would be. The destination is determined partly by how
     * we parse the filename, of course, but also based on numerous fields that the
     * user sets in the Preferences Dialog. When the user closes the dialog and
     * saves the changes, we want to immediately update the table for the new
     * choices
     * specified. This method iterates over each item, makes sure the model is
     * updated ({@link FileEpisode}), and then updates the relevant fields.
     *
     * (Doesn't bother updating other fields, because we know nothing in the
     * Preferences Dialog can cause them to need to be changed.)
     */
    public void refreshDestinations() {
        logger.fine("Refreshing destinations");
        for (TableItem item : swtTable.getItems()) {
            String fileName = CURRENT_FILE_FIELD.getCellText(item);
            String newFileName = episodeMap.currentLocationOf(fileName);
            if (newFileName == null) {
                // Not expected, but could happen, primarily if some other,
                // unrelated program moves the file out from under us.
                deleteTableItem(item);
                continue;
            }
            FileEpisode episode = episodeMap.get(newFileName);
            episode.refreshReplacement();
            setProposedDestColumn(item, episode);
            setTableItemStatus(item, episode.optionCount());
        }
    }

    private boolean isMoveSelectedForSession() {
        // If the user has toggled the session checkbox, prefer that.
        // Otherwise, follow the saved preference (initial value at startup).
        return (sessionMoveSelectedOverride != null)
            ? sessionMoveSelectedOverride.booleanValue()
            : initialMoveSelected;
    }

    private void refreshSessionMoveToggleUi() {
        if (sessionMoveCheckbox == null || sessionMoveCheckbox.isDisposed()) {
            return;
        }

        final boolean effectiveMoveSelected = isMoveSelectedForSession();

        // Checkbox label is stable; selection reflects effective state for session.
        sessionMoveCheckbox.setText("Move");
        sessionMoveCheckbox.setSelection(effectiveMoveSelected);

        // If user has set "move selected" but move is not possible (dest invalid),
        // communicate that in tooltip.
        String tooltip;
        if (effectiveMoveSelected) {
            if (prefs.isMoveEnabled()) {
                tooltip =
                    "Session toggle: Move is ON.\n\n" +
                    "Files will be moved to:\n" +
                    prefs.getDestinationDirectoryName() +
                    "\n\nThis toggle is temporary and resets when you restart TVRenamer.";
            } else {
                tooltip =
                    "Session toggle: Move is ON, but move is currently not possible.\n\n" +
                    "Destination folder cannot be used:\n" +
                    prefs.getDestinationDirectoryName() +
                    "\n\nThis toggle is temporary and resets when you restart TVRenamer.";
            }
        } else {
            tooltip =
                "Session toggle: Move is OFF.\n\n" +
                "Files will not be moved (rename only if enabled).\n\n" +
                "This toggle is temporary and resets when you restart TVRenamer.";
        }
        sessionMoveCheckbox.setToolTipText(tooltip);

        sessionMoveCheckbox.requestLayout();
        shell.layout(false, true);
    }

    private void setActionButtonText(final Button b) {
        final boolean doRename = prefs.isRenameSelected();
        final boolean doMove = isMoveSelectedForSession();

        // Action button has four states:
        // - Dry Run (neither rename nor move)
        // - Rename (rename only)
        // - Move (move only)
        // - Full (rename + move)
        String label;
        if (!doRename && !doMove) {
            label = "Dry Run";
        } else if (doRename && !doMove) {
            label = "Rename";
        } else if (!doRename && doMove) {
            label = "Move";
        } else {
            label = "Full";
        }
        b.setText(label);

        // Enable the button by default; we may disable it if the chosen action is impossible.
        b.setEnabled(true);

        String tooltip;
        if (!doRename && !doMove) {
            tooltip =
                "Dry Run: no rename and no move will be performed.\n\n" +
                "Enable Rename in Preferences and/or enable Move using the session checkbox.";
        } else if (doRename && !doMove) {
            tooltip = RENAME_TOOLTIP;
        } else if (!doRename && doMove) {
            if (prefs.isMoveEnabled()) {
                tooltip =
                    MOVE_INTRO +
                    INTRO_MOVE_DIR +
                    prefs.getDestinationDirectoryName() +
                    FINISH_MOVE_DIR;
            } else {
                b.setEnabled(false);
                tooltip = CANT_CREATE_DEST + ". " + MOVE_NOT_POSSIBLE;
            }
        } else {
            // Full (rename + move)
            if (prefs.isMoveEnabled()) {
                tooltip =
                    MOVE_INTRO +
                    AND_RENAME +
                    INTRO_MOVE_DIR +
                    prefs.getDestinationDirectoryName() +
                    FINISH_MOVE_DIR;
            } else {
                b.setEnabled(false);
                tooltip = CANT_CREATE_DEST + ". " + MOVE_NOT_POSSIBLE;
            }
        }

        b.setToolTipText(tooltip);

        // Keep the session checkbox in sync with any preference changes and action state.
        refreshSessionMoveToggleUi();

        b.requestLayout();
        shell.layout(false, true);
    }

    private void setColumnDestText() {
        final TableColumn destinationColumn =
            NEW_FILENAME_FIELD.getTableColumn();
        if (destinationColumn == null) {
            logger.warning("could not get destination column");
        } else if (isMoveSelectedForSession()) {
            destinationColumn.setText(MOVE_HEADER);
        } else {
            destinationColumn.setText(RENAME_HEADER);
        }
    }

    private void deleteTableItem(final TableItem item) {
        deleteItemControl(item);
        episodeMap.remove(CURRENT_FILE_FIELD.getCellText(item));
        item.dispose();

        // Guard rail: if the last row was removed via any code path, clear pending
        // disambiguations so we never show a stale "Select Shows" dialog later.
        if (swtTable.getItemCount() == 0) {
            ShowStore.clearPendingDisambiguations();
        }

        updateClearCompletedButtonEnabled();
    }

    private void deleteSelectedTableItems() {
        for (final TableItem item : swtTable.getSelection()) {
            int index = getTableItemIndex(item);
            deleteTableItem(item);

            if (ITEM_NOT_IN_TABLE == index) {
                logger.info("error: somehow selected item not found in table");
            }
        }
        swtTable.deselectAll();

        // Note: clearing pending disambiguations on last-row removal is handled in deleteTableItem(...)
        // so it applies consistently across all delete paths.
    }

    /**
     * Re-lookup unfound shows whose effective show name changed due to an
     * override update.  Called when the SHOW_NAME_OVERRIDES preference fires.
     */
    private void retryUnfoundShowsAfterOverrideChange() {
        if (swtTable.isDisposed()) {
            return;
        }
        for (final TableItem item : swtTable.getItems()) {
            if (item.isDisposed()) {
                continue;
            }
            final Object data = item.getData(EPISODE_DATA_KEY);
            if (!(data instanceof FileEpisode ep)) {
                continue;
            }
            if (!ep.isShowUnfound()) {
                continue;
            }

            String extractedName = ep.getExtractedFilenameShow();
            String newName = prefs.resolveShowName(extractedName);
            if (newName.equals(ep.getFilenameShow())) {
                // Override didn't change this episode's lookup name.
                continue;
            }

            ep.setFilenameShow(newName);
            ShowName.mapShowName(newName);
            STATUS_FIELD.setCellImage(item, DOWNLOADING);

            ShowStore.mapStringToShow(
                newName,
                new ShowInformationListener() {
                    @Override
                    public void downloadSucceeded(Show show) {
                        ep.setEpisodeShow(show);
                        display.asyncExec(() -> {
                            if (tableContainsTableItem(item)) {
                                setProposedDestColumn(item, ep);
                                STATUS_FIELD.setCellImage(item, ADDED);
                                item.setText(
                                    STATUS_FIELD.column.id,
                                    EMPTY_STRING
                                );
                            }
                        });
                        if (show.isValidSeries()) {
                            getSeriesListings(
                                show.asSeries(), item, ep
                            );
                        }
                    }

                    @Override
                    public void downloadFailed(FailedShow failedShow) {
                        ep.setFailedShow(failedShow);
                        tableItemFailed(item, ep);
                    }

                    @Override
                    public void apiHasBeenDeprecated() {
                        noteApiFailure();
                        ep.setApiDiscontinued();
                        tableItemFailed(item, ep);
                    }
                }
            );
        }
    }

    private void updateUserPreferences(final UserPreference userPref) {
        logger.fine("Preference change event: " + userPref);

        switch (userPref) {
            case RENAME_SELECTED:
            case MOVE_SELECTED:
            case DEST_DIR:
                checkDestinationDirectory();
                setColumnDestText();
                // Keep session toggle label/tooltip synced when prefs change underneath.
                refreshSessionMoveToggleUi();
                setActionButtonText(actionButton);
            // Note: NO break! We WANT to fall through.
            case REPLACEMENT_MASK:
            case SEASON_PREFIX:
            case LEADING_ZERO:
                refreshDestinations();
                break;
            case THEME_MODE:
                Fields.setThemeMode(prefs.getThemeMode());
                break;
            case SHOW_NAME_OVERRIDES:
                retryUnfoundShowsAfterOverrideChange();
                break;
            case IGNORE_REGEX:
            case PRELOAD_FOLDER:
            case ADD_SUBDIRS:
            case REMOVE_EMPTY:
            case DELETE_ROWS:
            case UPDATE_CHECK:
            case PREFER_DVD_ORDER:
            case FILE_MTIME_POLICY:
            case OVERWRITE_DESTINATION:
            case CLEANUP_DUPLICATES:
            case TAG_VIDEO_METADATA:
                // These changes don't require an immediate table update here
                break;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see java.beans.PropertyChangeListener#propertyChange(java.beans.
     * PropertyChangeEvent)
     */
    @Override
    public void propertyChange(java.beans.PropertyChangeEvent evt) {
        if (
            "preference".equals(evt.getPropertyName()) &&
            (evt.getNewValue() instanceof UserPreference)
        ) {
            updateUserPreferences((UserPreference) evt.getNewValue());
        }
    }

    private void informUserOfFailures() {
        int nFailures = currentFailures.size();
        int toInclude = nFailures;
        StringBuilder failureMessage = new StringBuilder(MOVE_FAILURE_MSG_1);
        if (nFailures > DEFAULT_MAX_FAILURES_TO_LIST) {
            failureMessage.append(MOVE_FAILURE_PARTIAL_MSG);
            toInclude = DEFAULT_MAX_FAILURES_TO_LIST;
        }
        failureMessage.append(':');
        while (toInclude > 0) {
            toInclude--;
            failureMessage.append(NEWLINE_BULLET);
            failureMessage.append(currentFailures.poll().getFileName());
        }
        ui.showMessageBox(
            SWTMessageBoxType.DLG_ERR,
            ERROR_LABEL,
            failureMessage.toString()
        );
    }

    void finishAllMoves() {
        // Capture duplicates before clearing the mover reference.
        java.util.List<java.nio.file.Path> foundDuplicates = java.util.Collections.emptyList();
        if (activeMover != null) {
            foundDuplicates = activeMover.getFoundDuplicates();
        }

        // The current move batch (if any) is done.
        activeMover = null;

        // Reset overall copy progress bar state after each batch.
        if (aggregateCopyProgress != null) {
            aggregateCopyProgress.reset();
        }
        aggregateCopyProgress = null;
        overallCopyTotalBytes = 0L;

        ui.setAppIcon();
        if (currentFailures.size() > 0) {
            informUserOfFailures();
        }
        currentFailures.clear();
        actionButton.setEnabled(true);

        // Show duplicate cleanup dialog if duplicates were found.
        if (!foundDuplicates.isEmpty()) {
            showDuplicateCleanupDialog(foundDuplicates);
        }
    }

    /**
     * Shows a dialog allowing the user to select which duplicate files to delete.
     *
     * @param duplicates list of duplicate file paths found after moves
     */
    private void showDuplicateCleanupDialog(java.util.List<java.nio.file.Path> duplicates) {
        if (duplicates == null || duplicates.isEmpty()) {
            return;
        }
        logger.fine("Showing duplicate cleanup dialog with " + duplicates.size() + " file(s)");
        DuplicateCleanupDialog dialog = new DuplicateCleanupDialog(shell, duplicates);
        java.util.List<java.nio.file.Path> toDelete = dialog.open();
        if (toDelete != null && !toDelete.isEmpty()) {
            int deleted = org.tvrenamer.controller.util.FileUtilities.deleteFiles(toDelete);
            logger.fine("Deleted " + deleted + " of " + toDelete.size() + " duplicate file(s)");
        }
    }

    /*
     * The table displays various data; a lot of it changes during the course of the
     * program. As we get information from the provider, we automatically update the
     * status, the proposed destination, even whether the row is checked or not.
     *
     * The one thing we don't automatically update is the location. That's something
     * that doesn't change, no matter how much information comes flowing in.
     * EXCEPT...
     * that's kind of the whole point of the program, to move files. So when we
     * actually
     * do move a file, we need to update things in some way.
     *
     * The program now has the "deleteRowAfterMove" option, which I recommend. But
     * if
     * we do not delete the row, then we need to update it.
     *
     * We also need to update the internal model we have of which files we're
     * working with.
     *
     * So, here's what we do:
     * 1) find the text that is CURRENTLY being displayed as the file's location
     * 2) ask EpisodeDb to look up that file, figure out where it now resides,
     * update its
     * own internal model, and then return to us the current location
     * 3) assuming the file was found, check to see if it was really moved
     * 4) if it actually was moved, update the row with the most current information
     *
     * We do all this only after checking the row is still valid, and then we do it
     * with the item locked, so it can't change out from under us.
     *
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private void updateTableItemAfterMove(final TableItem item) {
        synchronized (item) {
            if (item.isDisposed()) {
                return;
            }
            String fileName = CURRENT_FILE_FIELD.getCellText(item);
            String newLocation = episodeMap.currentLocationOf(fileName);
            if (newLocation == null) {
                // Not expected, but could happen, primarily if some other,
                // unrelated program moves the file out from under us.
                deleteTableItem(item);
                return;
            }
            if (!fileName.equals(newLocation)) {
                CURRENT_FILE_FIELD.setCellText(item, newLocation);
            }
        }
    }

    /**
     * A callback that indicates that the {@link FileMover} has finished trying
     * to move a file, the one displayed in the given item. We want to take
     * an action when the move has been finished.
     *
     * The specific action depends on the user preference, "deleteRowAfterMove".
     * As its name suggests, when it's true, and we successfully move the file,
     * we delete the TableItem from the table.
     *
     * If "deleteRowAfterMove" is false, then the moved file remains in the
     * table. There's no reason why its proposed destination should change;
     * nothing that is used to create the proposed destination has changed.
     * But one thing that has changed is the file's current location. We call
     * helper method updateTableItemAfterMove to update the table.
     *
     * In a case where, for example, we can't create files in the destination
     * directory, we may have a lot of failures. We don't want to give
     * multiple dialog boxes to the user. So all we do here is add the failure
     * to a queue of failures; when the entire move operation is finished, we'll
     * inform the user of any failures that occurred.
     *
     * @param item
     *                the item representing the file that we've just finished trying
     *                to move
     * @param episode
     *                the related episode
     */
    /** Delay (ms) before auto-clearing a completed row, allowing user to see the checkmark. */
    private static final int COMPLETED_DISPLAY_DELAY_MS = 500;

    public void finishMove(final TableItem item, final FileEpisode episode) {
        if (episode.isSuccess()) {
            // Increment processed counter once per successful file operation (rename and/or move).
            prefs.incrementProcessedFileCount(1);
            UserPreferences.store(prefs);

            // Best-effort: refresh the processed label if present.
            // (We avoid hard dependency by looking it up via shell data.)
            Object labelObj = shell.getData("tvrenamer.processedLabel");
            if (labelObj instanceof Label label && !label.isDisposed()) {
                label.setText("Processed: " + prefs.getProcessedFileCount());
                label.getParent().layout(true, true);
            }

            // Mark as completed for "Clear Completed" behavior when auto-clear is disabled.
            // The SUCCESS icon (ready to rename) is already visible; no need to change it.
            // For copy operations, the progress bar overlay was disposed in FileMonitor.finishProgress,
            // revealing the underlying SUCCESS icon.
            if (item != null && !item.isDisposed()) {
                item.setData("tvrenamer.moveCompleted", Boolean.TRUE);
            }

            if (prefs.isDeleteRowAfterMove()) {
                // Brief delay so user sees the completed checkmark before row disappears.
                display.timerExec(COMPLETED_DISPLAY_DELAY_MS, () -> {
                    if (!item.isDisposed()) {
                        deleteTableItem(item);
                    }
                });
            } else {
                updateTableItemAfterMove(item);
            }

            // Refresh the Clear Completed button enabled state (best-effort).
            updateClearCompletedButtonEnabled();
        } else {
            currentFailures.add(episode);
            logger.fine("failed to move item: " + episode);
        }
    }

    private void setupUpdateStuff(final Composite parentComposite) {
        Link updatesAvailableLink = new Link(parentComposite, SWT.VERTICAL);
        // updatesAvailableLink.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true,
        // true));
        updatesAvailableLink.setVisible(false);
        updatesAvailableLink.setText(UPDATE_AVAILABLE);
        updatesAvailableLink.addSelectionListener(
            new UrlLauncher(TVRENAMER_DOWNLOAD_URL)
        );

        // Show the label if updates are available (in a new thread)
        UpdateChecker.notifyOfUpdate(updateIsAvailable -> {
            if (updateIsAvailable) {
                display.asyncExec(() -> updatesAvailableLink.setVisible(true));
            }
        });
    }

    private void setupTopButtons() {
        // Use GridLayout so we can keep the "Processed: X" label on the same row as the buttons.
        final Composite topRowComposite = new Composite(shell, SWT.NONE);
        topRowComposite.setLayout(new GridLayout(2, false));
        topRowComposite.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1)
        );
        ThemeManager.applyPalette(topRowComposite, themePalette);

        final Composite topButtonsComposite = new Composite(
            topRowComposite,
            SWT.NONE
        );
        topButtonsComposite.setLayout(new RowLayout());
        ThemeManager.applyPalette(topButtonsComposite, themePalette);

        final Label processedLabel = new Label(topRowComposite, SWT.NONE);
        processedLabel.setLayoutData(
            new GridData(SWT.END, SWT.CENTER, true, false, 1, 1)
        );
        processedLabel.setText("Processed: " + prefs.getProcessedFileCount());
        shell.setData("tvrenamer.processedLabel", processedLabel);

        final FileDialog fd = new FileDialog(shell, SWT.MULTI);
        final Button addFilesButton = new Button(topButtonsComposite, SWT.PUSH);
        addFilesButton.setText("Add files");
        addFilesButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    String pathPrefix = fd.open();
                    if (pathPrefix != null) {
                        episodeMap.addFilesToQueue(
                            pathPrefix,
                            fd.getFileNames()
                        );
                    }
                }
            }
        );

        final DirectoryDialog dd = new DirectoryDialog(shell, SWT.SINGLE);
        final Button addFolderButton = new Button(
            topButtonsComposite,
            SWT.PUSH
        );
        addFolderButton.setText("Add Folder");
        addFolderButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    String directory = dd.open();
                    if (directory != null) {
                        // load all of the files in the dir
                        episodeMap.addFolderToQueue(directory);
                    }
                }
            }
        );

        selectShowsButton = new Button(topButtonsComposite, SWT.PUSH);
        selectShowsButton.setText("Select Shows...");
        selectShowsButton.setToolTipText(
            "Open the show selection dialog for any pending ambiguous show matches."
        );
        // Disabled until we first detect any ambiguity; once enabled it stays enabled for the session.
        selectShowsButton.setEnabled(selectShowsButtonEverEnabled);
        selectShowsButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    // If the user clicks the button, treat it as an explicit request and
                    // bypass the cancel/close cooldown (which exists to prevent auto-reopen loops).
                    batchDisambiguationReopenNotBeforeMs = 0L;

                    // Explicit open: allow open even if pending count didn't transition from 0.
                    lastKnownPendingDisambiguationCount = 0;

                    showBatchDisambiguationDialogIfNeeded();
                }
            }
        );

        final Button clearFilesButton = new Button(
            topButtonsComposite,
            SWT.PUSH
        );
        clearFilesButton.setText("Clear List");
        clearFilesButton.addSelectionListener(
            new SelectionAdapter() {
                public void widgetSelected(SelectionEvent e) {
                    for (final TableItem item : swtTable.getItems()) {
                        deleteTableItem(item);
                    }

                    // Clearing the list leaves no rows that could be resolved, so clear any
                    // pending show disambiguations to avoid stale "Resolve ambiguous shows" prompts.
                    ShowStore.clearPendingDisambiguations();

                    updateClearCompletedButtonEnabled();
                }
            }
        );

        final Button clearCompletedButton = new Button(
            topButtonsComposite,
            SWT.PUSH
        );
        clearCompletedButton.setText("Clear Completed");
        clearCompletedButton.setToolTipText(
            "Remove rows that were successfully processed (when auto-clear is disabled)."
        );
        clearCompletedButton.setEnabled(false);
        clearCompletedButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    // Only relevant when auto-clear is disabled.
                    if (prefs.isDeleteRowAfterMove()) {
                        return;
                    }

                    // Batch removals so TableEditor-hosted Combos reposition
                    // in a single repaint instead of showing stale positions.
                    swtTable.setRedraw(false);
                    try {
                        for (final TableItem item : swtTable.getItems()) {
                            Object completed = item.getData(
                                "tvrenamer.moveCompleted"
                            );
                            if (Boolean.TRUE.equals(completed)) {
                                deleteTableItem(item);
                            }
                        }
                    } finally {
                        swtTable.setRedraw(true);
                    }

                    updateClearCompletedButtonEnabled();
                }
            }
        );

        // Store button for later enable/disable refresh.
        shell.setData("tvrenamer.clearCompletedButton", clearCompletedButton);

        setupUpdateStuff(topButtonsComposite);
    }

    private void setupBottomComposite() {
        Composite bottomButtonsComposite = new Composite(shell, SWT.FILL);
        bottomButtonsComposite.setLayout(new GridLayout(3, false));
        ThemeManager.applyPalette(bottomButtonsComposite, themePalette);

        GridData bottomButtonsCompositeGridData = new GridData(
            SWT.FILL,
            SWT.CENTER,
            true,
            false,
            3,
            1
        );
        bottomButtonsComposite.setLayoutData(bottomButtonsCompositeGridData);

        totalProgressBar = new ProgressBar(bottomButtonsComposite, SWT.SMOOTH);
        totalProgressBar.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, true)
        );

        // Session-only Move checkbox (does not persist to preferences).
        sessionMoveCheckbox = new Button(bottomButtonsComposite, SWT.CHECK);
        GridData sessionMoveCheckboxGridData = new GridData(
            GridData.BEGINNING,
            GridData.CENTER,
            false,
            false
        );
        sessionMoveCheckboxGridData.minimumWidth = 70;
        sessionMoveCheckbox.setLayoutData(sessionMoveCheckboxGridData);

        // On startup, reflect the preference setting (no override yet).
        refreshSessionMoveToggleUi();

        sessionMoveCheckbox.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    // Checkbox state is the session override value; lasts only for this run.
                    final boolean selected = sessionMoveCheckbox.getSelection();
                    sessionMoveSelectedOverride = Boolean.valueOf(selected);

                    // Option 1: make the session toggle affect real behavior by temporarily
                    // updating the in-memory UserPreferences. We do NOT persist preferences
                    // from here; the Preferences dialog Save is what persists to disk.
                    prefs.setMoveSelected(selected);

                    // Refresh UI that depends on move-selected.
                    setColumnDestText();
                    setActionButtonText(actionButton);
                    refreshDestinations();
                }
            }
        );

        actionButton = new Button(bottomButtonsComposite, SWT.PUSH);
        GridData actionButtonGridData = new GridData(
            GridData.END,
            GridData.CENTER,
            false,
            false
        );
        actionButton.setLayoutData(actionButtonGridData);
        setActionButtonText(actionButton);
        actionButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    executeActionButton();
                }
            }
        );
    }

    private void setupTableDragDrop() {
        DropTarget dt = new DropTarget(
            swtTable,
            DND.DROP_DEFAULT | DND.DROP_MOVE
        );
        dt.setTransfer(new Transfer[] { FileTransfer.getInstance() });
        dt.addDropListener(
            new DropTargetAdapter() {
                @Override
                public void drop(DropTargetEvent e) {
                    FileTransfer ft = FileTransfer.getInstance();
                    if (ft.isSupportedType(e.currentDataType)) {
                        String[] fileList = (String[]) e.data;
                        episodeMap.addArrayOfStringsToQueue(fileList);
                    }
                }
            }
        );
    }

    private void setupSelectionListener() {
        swtTable.addListener(SWT.Selection, event -> {
            if (event.detail == SWT.CHECK) {
                TableItem eventItem = (TableItem) event.item;
                // This assumes that the current status of the TableItem
                // already reflects its toggled state, which appears to
                // be the case.
                boolean checked = eventItem.getChecked();
                boolean isSelected = false;

                for (final TableItem item : swtTable.getSelection()) {
                    if (item == eventItem) {
                        isSelected = true;
                        break;
                    }
                }
                if (isSelected) {
                    for (final TableItem item : swtTable.getSelection()) {
                        item.setChecked(checked);
                    }
                } else {
                    swtTable.deselectAll();
                }
            }
            // else, it's a SELECTED event. If the row is in "Select Show..." pending state,
            // open the batch disambiguation dialog so the user can resolve ambiguities.
            else {
                try {
                    TableItem eventItem = (TableItem) event.item;
                    if (eventItem != null && !eventItem.isDisposed()) {
                        // If this row is flagged as pending show selection, clicking it should reopen
                        // the batch disambiguation dialog even if the proposed text was recomputed.
                        Object pending = eventItem.getData(
                            SELECT_SHOW_PENDING_KEY
                        );
                        if (Boolean.TRUE.equals(pending)) {
                            showBatchDisambiguationDialogIfNeeded();
                        }
                    }
                } catch (Exception e) {
                    logger.fine("Selection event handling error: " + e.getMessage());
                }
            }
        });
    }

    private synchronized void createColumns() {
        CHECKBOX_FIELD.createColumn(this, swtTable, WIDTH_CHECKED);
        CURRENT_FILE_FIELD.createColumn(this, swtTable, WIDTH_CURRENT_FILE);
        NEW_FILENAME_FIELD.createColumn(this, swtTable, WIDTH_NEW_FILENAME);
        STATUS_FIELD.createColumn(this, swtTable, WIDTH_STATUS);
    }

    private void setSortColumn() {
        TableColumn sortColumn = CURRENT_FILE_FIELD.getTableColumn();
        if (sortColumn == null) {
            logger.warning("could not find preferred sort column");
        } else {
            swtTable.setSortColumn(sortColumn);
            swtTable.setSortDirection(SWT.UP);
        }
    }

    /**
     * Ensures the table items are sorted by the current sort column.
     * This is called before processing moves to ensure items are processed
     * in the visual order displayed to the user.
     */
    private void ensureTableSorted() {
        TableColumn sortColumn = swtTable.getSortColumn();
        int sortDirection = swtTable.getSortDirection();
        if (sortColumn == null || sortDirection == SWT.NONE) {
            return;
        }
        // Find the Column that matches the current sort column.
        Column matchingColumn = null;
        for (Field f : new Field[] {
            CHECKBOX_FIELD,
            CURRENT_FILE_FIELD,
            NEW_FILENAME_FIELD,
            STATUS_FIELD
        }) {
            if (f.getTableColumn() == sortColumn) {
                matchingColumn = f.column;
                break;
            }
        }
        if (matchingColumn != null) {
            sortTable(matchingColumn, sortDirection);
        }
    }

    private void setupResultsTable() {
        swtTable.setHeaderVisible(true);
        swtTable.setLinesVisible(false);
        GridData gridData = new GridData(GridData.FILL_BOTH);
        // gridData.widthHint = 780;
        gridData.heightHint = 350;
        gridData.horizontalSpan = 3;
        swtTable.setLayoutData(gridData);

        createColumns();
        setColumnDestText();
        setSortColumn();

        // Allow deleting of elements
        swtTable.addKeyListener(
            new KeyAdapter() {
                @Override
                public void keyReleased(KeyEvent e) {
                    super.keyReleased(e);

                    // Select All: MOD1+A (Ctrl+A on Windows/Linux, Cmd+A on macOS)
                    if (
                        ((e.stateMask & SWT.MOD1) != 0) &&
                        (e.keyCode == 'a' || e.keyCode == 'A')
                    ) {
                        if (swtTable.getItemCount() > 0) {
                            swtTable.selectAll();
                            swtTable.showSelection();
                        }
                        return;
                    }

                    // Clear selected rows
                    if (
                        (e.keyCode == '\u0008') || // backspace
                        (e.keyCode == '\u007F') // delete
                    ) {
                        deleteSelectedTableItems();
                    }
                }
            }
        );

        // editable table
        final TableEditor editor = new TableEditor(swtTable);
        editor.horizontalAlignment = SWT.CENTER;
        editor.grabHorizontal = true;

        setupSelectionListener();

        // When provider lookups enqueue ambiguous shows, ShowStore notifies listeners.
        // Behavior:
        // - If the batch dialog is already open, stream new items into it.
        // - Otherwise, auto-open ONLY when pending transitions from 0 -> >0.
        // - After the user closes the dialog, do not auto-open again; remaining pending can be opened
        //   explicitly via the "Select Shows..." button.
        ShowStore.addPendingDisambiguationsListener(() -> {
            if (shell == null || shell.isDisposed()) {
                return;
            }

            display.asyncExec(() -> {
                if (shell == null || shell.isDisposed()) {
                    return;
                }

                Map<String, ShowStore.PendingDisambiguation> snapshot =
                    ShowStore.getPendingDisambiguations();
                int pendingCount = (snapshot == null) ? 0 : snapshot.size();

                if (batchDisambiguationDialogOpen) {
                    // Dialog is open: stream in new items.
                    if (batchDisambiguationDialog != null) {
                        try {
                            batchDisambiguationDialog.mergePending(snapshot);
                        } catch (RuntimeException ex) {
                            logger.warning(
                                "Batch show disambiguation: mergePending failed: " +
                                    ex
                            );
                        }
                    }

                    // While open, keep lastKnown in sync so we don't re-open on close.
                    lastKnownPendingDisambiguationCount = pendingCount;
                    return;
                }

                // Dialog is not open: auto-open only on 0 -> >0 transition.
                boolean shouldAutoOpen =
                    (lastKnownPendingDisambiguationCount == 0) &&
                    (pendingCount > 0);

                // Always update lastKnown to current snapshot count.
                lastKnownPendingDisambiguationCount = pendingCount;

                if (!shouldAutoOpen) {
                    return;
                }

                showBatchDisambiguationDialogIfNeeded();
            });
        });
    }

    private void setupMainWindow() {
        setupResultsTable();
        setupTableDragDrop();
        setupBottomComposite();

        TaskBar taskBar = display.getSystemTaskBar();
        if (taskBar != null) {
            taskItem = taskBar.getItem(shell);
            if (taskItem == null) {
                taskItem = taskBar.getItem(null);
            }
        }
    }

    ResultsTable(final UIStarter ui) {
        logger.fine("=== ResultsTable constructor begin ===");
        this.ui = ui;

        shell = ui.shell;

        display = ui.display;
        themePalette = ThemeManager.createPalette(display);
        Fields.setThemeMode(themePalette.getMode());

        // If the UI is disposed while a move is in progress, cancel pending moves.
        shell.addListener(SWT.Dispose, e -> {
            MoveRunner mover = activeMover;
            if (mover != null) {
                mover.requestShutdown();
            }
        });

        logger.fine("Wiring SWT components from UIStarter.");
        setupTopButtons();

        logger.fine("Creating ResultsTable...");
        swtTable = new Table(shell, SWT.CHECK | SWT.FULL_SELECTION | SWT.MULTI);
        swtTable.setBackground(themePalette.getControlBackground());
        swtTable.setForeground(themePalette.getControlForeground());
        swtTable.setHeaderBackground(themePalette.getTableHeaderBackground());
        swtTable.setHeaderForeground(themePalette.getTableHeaderForeground());

        setupMainWindow();

        logger.fine("ResultsTable constructor complete.");
    }
}
