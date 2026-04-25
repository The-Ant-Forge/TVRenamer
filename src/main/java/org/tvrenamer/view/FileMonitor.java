package org.tvrenamer.view;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TableItem;
import org.tvrenamer.model.FileEpisode;
import org.tvrenamer.model.MoveObserver;
import org.tvrenamer.model.RowPhase;

public class FileMonitor implements MoveObserver {

    /**
     * Result record containing both the progress label and its table editor.
     * Both must be disposed when the progress display is finished.
     */
    public record ProgressLabelResult(Label label, TableEditor editor) {}

    private final NumberFormat format = NumberFormat.getPercentInstance();

    private final ResultsTable ui;
    private final TableItem item;
    private final Display display;

    // Aggregate copy progress (shared across a move batch).
    // This is best-effort and only intended to drive the bottom progress bar smoothly.
    private final AggregateCopyProgress aggregate;

    private Label label = null;
    private TableEditor editor = null;
    private long maximum = 0;

    /**
     * Creates the monitor, with the label and the display.
     *
     * @param ui - the ResultsTable instance
     * @param item - the TableItem to monitor
     */
    public FileMonitor(ResultsTable ui, TableItem item) {
        this(ui, item, null);
    }

    /**
     * Creates the monitor with optional aggregate progress tracker for the overall progress bar.
     *
     * @param ui - the ResultsTable instance
     * @param item - the TableItem to monitor
     * @param aggregate - aggregate progress tracker (may be null)
     */
    public FileMonitor(
        ResultsTable ui,
        TableItem item,
        AggregateCopyProgress aggregate
    ) {
        this.ui = ui;
        this.item = item;
        this.display = ui.getDisplay();
        this.aggregate = aggregate;
        format.setMaximumFractionDigits(1);
    }

    /**
     * Set the maximum value.
     *
     * @param max the new maximum value
     */
    @Override
    public void initializeProgress(final long max) {
        if (display == null || display.isDisposed()) {
            return;
        }
        if (item == null || item.isDisposed()) {
            return;
        }
        display.syncExec(() -> {
            if (item.isDisposed()) {
                return;
            }
            ProgressLabelResult result = ui.createProgressLabel(item);
            if (result != null) {
                label = result.label();
                editor = result.editor();
            }
        });
        maximum = Math.max(0, max);

        if (aggregate != null) {
            aggregate.registerItem(item, maximum);
        }

        setProgressValue(0);
    }

    /**
     * Update the progress value.
     *
     * @param value the new value
     */
    @Override
    public void setProgressValue(final long value) {
        if (display == null || display.isDisposed()) {
            return;
        }
        if (maximum <= 0) {
            return;
        }

        if (aggregate != null) {
            aggregate.updateItem(item, value);
        }

        if (label == null || label.isDisposed()) {
            return;
        }

        // copyWithUpdates is throttled (~1 MiB), so updating the per-row label on each callback
        // avoids the "stuck at 0%" symptom without spamming the UI thread.
        display.asyncExec(() -> {
            if (display.isDisposed()) {
                return;
            }
            if (label == null || label.isDisposed()) {
                return;
            }
            label.setText(format.format((double) value / maximum));
        });
    }

    /**
     * Update the row's status icon to reflect a sub-phase transition.
     * The percentage label (created during a copy) overlays the status
     * cell, so phase icons are most visible when no copy progress is
     * being shown — but we update the underlying icon either way so the
     * correct icon appears as soon as the percentage label disposes.
     *
     * @param phase the new phase the row has entered
     */
    @Override
    public void onPhaseChange(final RowPhase phase) {
        if (phase == null || display == null || display.isDisposed()) {
            return;
        }
        if (item == null || item.isDisposed()) {
            return;
        }
        final ItemState target = switch (phase) {
            case MOVING -> ItemState.MOVING;
            case TAGGING -> ItemState.TAGGING;
        };
        display.asyncExec(() -> {
            if (display.isDisposed() || item.isDisposed()) {
                return;
            }
            Fields.STATUS_FIELD.setCellImage(item, target);
        });
    }

    /**
     * Update the status label.
     *
     * @param status the new status label
     */
    @Override
    public void setProgressStatus(final String status) {
        if (display == null || display.isDisposed()) {
            return;
        }
        if (label == null || label.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (display.isDisposed()) {
                return;
            }
            if (label == null || label.isDisposed()) {
                return;
            }
            label.setToolTipText(status);
        });
    }

    /**
     * Dispose of the label and editor. We need to do this whether they were used or not.
     * Disposing both ensures the underlying cell content (status icon) is visible.
     */
    @Override
    public void finishProgress(final FileEpisode episode) {
        if (display == null || display.isDisposed()) {
            return;
        }
        if (aggregate != null) {
            aggregate.completeItem(item);
        }
        display.asyncExec(() -> {
            if (display.isDisposed()) {
                return;
            }
            // Dispose both the label and the editor so the cell's underlying
            // status icon (e.g., the completion checkmark) is visible.
            if (label != null && !label.isDisposed()) {
                label.dispose();
            }
            if (editor != null) {
                editor.dispose();
            }
            if (item == null || item.isDisposed()) {
                return;
            }
            ui.finishMove(item, episode);
        });
    }

    /**
     * Aggregate tracker that updates the bottom progress bar in a thread-safe way.
     *
     * Tracks per-item maxima and latest values so overall progress reflects total bytes copied
     * across all files. The UI is updated on the SWT thread via ResultsTable.
     */
    public static final class AggregateCopyProgress {

        private final ResultsTable ui;

        private long totalBytes = 0L;
        private long copiedBytes = 0L;

        // Track per-item maxima and latest values so we can sum accurately.
        private final Map<TableItem, Long> itemMaxBytes = new HashMap<>();
        private final Map<TableItem, Long> itemCopiedBytes = new HashMap<>();

        public AggregateCopyProgress(final ResultsTable ui) {
            this.ui = ui;
        }

        public synchronized void registerItem(
            final TableItem item,
            final long maxBytes
        ) {
            if (item == null) {
                return;
            }
            long safeMax = Math.max(0L, maxBytes);

            // If we already registered this item, adjust totals by the difference.
            Long prevMax = itemMaxBytes.get(item);
            if (prevMax != null) {
                totalBytes = Math.max(0L, totalBytes - prevMax);
            }

            itemMaxBytes.put(item, safeMax);
            totalBytes += safeMax;

            // Ensure we have a tracking slot for current copied bytes.
            itemCopiedBytes.putIfAbsent(item, 0L);

            pushUi();
        }

        public synchronized void updateItem(
            final TableItem item,
            final long valueBytes
        ) {
            if (item == null) {
                return;
            }

            Long max = itemMaxBytes.get(item);
            if (max == null || max <= 0L) {
                return;
            }

            long safeValue = Math.max(0L, Math.min(valueBytes, max));
            itemCopiedBytes.put(item, safeValue);

            // Recompute aggregate copied bytes to avoid snap-back and any delta accounting drift.
            long sum = 0L;
            for (Long v : itemCopiedBytes.values()) {
                if (v != null && v > 0L) {
                    sum += v;
                }
            }
            copiedBytes = Math.max(0L, Math.min(totalBytes, sum));

            pushUi();
        }

        public synchronized void completeItem(final TableItem item) {
            if (item == null) {
                return;
            }
            Long max = itemMaxBytes.get(item);
            if (max == null || max <= 0L) {
                return;
            }

            // Treat completion as fully copied if we didn't already reach max.
            updateItem(item, max);
        }

        public synchronized void reset() {
            totalBytes = 0L;
            copiedBytes = 0L;
            itemMaxBytes.clear();
            itemCopiedBytes.clear();
            if (ui != null) {
                ui.updateOverallCopyProgress(0L, 0L);
            }
        }

        private void pushUi() {
            if (ui != null) {
                ui.updateOverallCopyProgress(totalBytes, copiedBytes);
            }
        }
    }
}
