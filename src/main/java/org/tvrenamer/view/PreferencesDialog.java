package org.tvrenamer.view;

import static org.tvrenamer.model.ReplacementToken.*;
import static org.tvrenamer.model.util.Constants.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DragSourceListener;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.DropTargetListener;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Dialog;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.tvrenamer.controller.TheTVDBProvider;
import org.tvrenamer.controller.util.FileUtilities;
import org.tvrenamer.controller.util.StringUtils;
import org.tvrenamer.model.ReplacementToken;
import org.tvrenamer.model.ShowName;
import org.tvrenamer.model.ShowOption;
import org.tvrenamer.model.ShowSelectionEvaluator;
import org.tvrenamer.model.ThemeMode;
import org.tvrenamer.model.UserPreferences;

class PreferencesDialog extends Dialog {

    private static final Logger logger = Logger.getLogger(
        PreferencesDialog.class.getName()
    );
    private static final UserPreferences prefs = UserPreferences.getInstance();

    private static final int DND_OPERATIONS = DND.DROP_MOVE;
    private static final char DOUBLE_QUOTE = '"';

    private static class PreferencesDropTargetListener
        implements DropTargetListener
    {

        private final Text targetText;

        public PreferencesDropTargetListener(Text targetText) {
            this.targetText = targetText;
        }

        @Override
        public void drop(DropTargetEvent event) {
            String data = (String) event.data;
            if (data == null) {
                return;
            }

            // Insert at caret/selection position (not just append).
            // This makes drag/drop behave like a normal text editor insertion.
            int selectionStart;
            int selectionEnd;
            try {
                org.eclipse.swt.graphics.Point sel = targetText.getSelection();
                selectionStart = sel.x;
                selectionEnd = sel.y;
            } catch (Exception ignored) {
                // Best-effort fallback: append if we can't read selection.
                targetText.append(data);
                return;
            }

            String current = targetText.getText();
            if (current == null) {
                current = "";
            }

            // Clamp defensively
            if (selectionStart < 0) {
                selectionStart = 0;
            }
            if (selectionEnd < selectionStart) {
                selectionEnd = selectionStart;
            }
            if (selectionStart > current.length()) {
                selectionStart = current.length();
            }
            if (selectionEnd > current.length()) {
                selectionEnd = current.length();
            }

            String before = current.substring(0, selectionStart);
            String after = current.substring(selectionEnd);

            String newValue = before + data + after;
            targetText.setText(newValue);

            // Move caret to just after inserted text
            int newCaret = selectionStart + data.length();
            targetText.setSelection(newCaret);
        }

        @Override
        public void dragEnter(DropTargetEvent event) {
            // no-op
        }

        @Override
        public void dragLeave(DropTargetEvent event) {
            // no-op
        }

        @Override
        public void dragOperationChanged(DropTargetEvent event) {
            // no-op
        }

        @Override
        public void dragOver(DropTargetEvent event) {
            // Move caret to track mouse position during drag, showing where drop will occur.
            // Convert display coordinates to widget-relative coordinates.
            org.eclipse.swt.graphics.Point displayPoint =
                new org.eclipse.swt.graphics.Point(event.x, event.y);
            org.eclipse.swt.graphics.Point widgetPoint =
                targetText.toControl(displayPoint);

            // Use getCaretOffsetAtLocation to find text position under mouse.
            // If the point is outside the text bounds, this may throw or return -1.
            try {
                int offset = getTextOffsetAtLocation(targetText, widgetPoint);
                if (offset >= 0) {
                    targetText.setFocus();
                    targetText.setSelection(offset);
                }
            } catch (Exception ignored) {
                // Best-effort: if we can't determine position, don't update caret
            }
        }

        /**
         * Calculate text offset at a given widget-relative point.
         * SWT Text doesn't have getCaretOffsetAtLocation, so we approximate.
         */
        private int getTextOffsetAtLocation(Text text, org.eclipse.swt.graphics.Point point) {
            String content = text.getText();
            if (content == null || content.isEmpty()) {
                return 0;
            }

            // Use text extent to estimate character position.
            // This is an approximation assuming monospace-ish font.
            org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC(text);
            try {
                int avgCharWidth = (int) gc.getFontMetrics().getAverageCharacterWidth();
                if (avgCharWidth <= 0) {
                    avgCharWidth = 8; // fallback
                }

                // Account for text widget internal padding (approximately 4 pixels on each side)
                int adjustedX = point.x - 4;
                if (adjustedX < 0) {
                    adjustedX = 0;
                }

                int estimatedOffset = adjustedX / avgCharWidth;
                // Clamp to valid range
                if (estimatedOffset < 0) {
                    estimatedOffset = 0;
                }
                if (estimatedOffset > content.length()) {
                    estimatedOffset = content.length();
                }
                return estimatedOffset;
            } finally {
                gc.dispose();
            }
        }

        @Override
        public void dropAccept(DropTargetEvent event) {
            // no-op
        }
    }

    // Preview label for rename format
    private Label renameFormatPreviewLabel;

    // The controls to save
    private Button moveSelectedCheckbox;
    private Button renameSelectedCheckbox;
    private Text destDirText;
    private Button destDirButton;
    private Text seasonPrefixText;
    private Button seasonPrefixLeadingZeroCheckbox;
    private Text replacementStringText;
    private Text ignoreWordsText;
    private Button checkForUpdatesCheckbox;
    private Button recurseFoldersCheckbox;
    private Button rmdirEmptyCheckbox;
    private Button deleteRowsCheckbox;
    private Button overwriteDestinationCheckbox;
    private Button cleanupDuplicatesCheckbox;
    private Button tagVideoMetadataCheckbox;

    // If checked, set moved/renamed files' modification time to "now".
    // If unchecked (default), preserve original modification time.
    private Button setMtimeToNowCheckbox;
    private Combo themeModeCombo;
    private Button preferDvdOrderCheckbox;
    private ThemePalette themePalette;

    // Matching (Overrides + Disambiguations)
    // Overrides (Extracted show -> replacement show text)
    private Text overridesFromText;
    private Text overridesToText;
    private Table overridesTable;

    // Disambiguations (query string -> series id)
    private Text disambiguationsQueryText;
    private Text disambiguationsIdText;
    private Table disambiguationsTable;

    // Matching hover "tooltip" labels (SWT TableItem has no setToolTipText()).
    // We show validation messages near the relevant table so users don't miss them.
    private Label overridesHoverTipLabel;
    private Label disambiguationsHoverTipLabel;

    // Matching validation / dirty tracking
    private static final String MATCHING_STATUS_INCOMPLETE = "Incomplete";
    private static final String MATCHING_DIRTY_KEY = "tvrenamer.matching.dirty";

    // Status icons (reuse the same assets as the main results table).
    private static final org.eclipse.swt.graphics.Image MATCHING_ICON_OK =
        ItemState.SUCCESS.getIcon();
    private static final org.eclipse.swt.graphics.Image MATCHING_ICON_ERROR =
        ItemState.FAIL.getIcon();
    private static final org.eclipse.swt.graphics.Image MATCHING_ICON_VALIDATING =
        ItemState.DOWNLOADING.getIcon();

    // Save gating: disable Save when any dirty row is invalid/incomplete/validating
    private Button saveButton;

    // Matching async validation
    private static final String MATCHING_VALIDATE_TOKEN_KEY =
        "tvrenamer.matching.validateToken";
    private volatile long matchingValidationSeq = 0L;

    private TabFolder tabFolder;
    private Shell preferencesShell;

    // Optional initial state applied when opening with a specific tab and pre-filled text.
    private int initialTabIndex = -1;
    private String initialOverrideFromText = null;

    private final Shell parent;
    private final StatusLabel statusLabel;

    private String seasonPrefixString;

    private void createContents() {
        GridLayout shellGridLayout = new GridLayout(4, false);
        preferencesShell.setLayout(shellGridLayout);

        Label helpLabel = new Label(preferencesShell, SWT.NONE);
        helpLabel.setText(HELP_TOOLTIP);
        helpLabel.setLayoutData(
            new GridData(
                SWT.END,
                SWT.CENTER,
                true,
                true,
                shellGridLayout.numColumns,
                1
            )
        );

        tabFolder = new TabFolder(preferencesShell, getStyle());

        tabFolder.setLayoutData(
            new GridData(
                SWT.END,
                SWT.CENTER,
                true,
                true,
                shellGridLayout.numColumns,
                1
            )
        );

        // TabFolder and its TabItems can remain OS/native themed on some platforms,
        // but we should still ensure the contained controls inherit our palette.
        ThemeManager.applyPalette(tabFolder, themePalette);

        createGeneralTab();
        createRenameTab();
        createOverridesTab();

        // Best-effort: re-apply after tab creation so children created inside tab composites
        // are definitely themed (checkbox text, text fields, combos, lists, etc).
        ThemeManager.applyPalette(tabFolder, themePalette);

        statusLabel.open(preferencesShell, shellGridLayout.numColumns);

        createActionButtonGroup();
    }

    /**
     * Toggle whether the or not the listed {@link Control}s are enabled, based off the of
     * the given state value.
     *
     * @param state the boolean to set the other controls to
     * @param controls the list of controls to update
     */
    private void toggleEnableControls(boolean state, Control... controls) {
        for (Control control : controls) {
            control.setEnabled(state);
        }
        preferencesShell.redraw();
    }

    private void createLabel(
        final String label,
        final String tooltip,
        final Composite group
    ) {
        final Label labelObj = new Label(group, SWT.NONE);
        labelObj.setText(label);
        labelObj.setToolTipText(tooltip);

        // we don't need to return the object
    }

    private Text createText(
        final String text,
        final Composite group,
        boolean setSize
    ) {
        final Text textObj = new Text(group, SWT.BORDER);
        textObj.setText(text);
        textObj.setTextLimit(99);
        GridData layout;
        if (setSize) {
            layout = new GridData(
                GridData.FILL,
                GridData.CENTER,
                true,
                true,
                2,
                1
            );
        } else {
            layout = new GridData(GridData.FILL, GridData.CENTER, true, true);
        }
        textObj.setLayoutData(layout);

        return textObj;
    }

    private Button createCheckbox(
        final String text,
        final String tooltip,
        final boolean isChecked,
        final Composite group,
        final int alignment,
        final int span
    ) {
        final Button box = new Button(group, SWT.CHECK);
        box.setText(text);
        box.setSelection(isChecked);
        box.setLayoutData(
            new GridData(alignment, GridData.CENTER, true, true, span, 1)
        );
        box.setToolTipText(tooltip);

        return box;
    }

    private Button createDestDirButton(Composite group) {
        final Button button = new Button(group, SWT.PUSH);
        button.setText(DEST_DIR_BUTTON_TEXT);
        button.addListener(SWT.Selection, event -> {
            DirectoryDialog directoryDialog = new DirectoryDialog(
                preferencesShell
            );

            directoryDialog.setFilterPath(prefs.getDestinationDirectoryName());
            directoryDialog.setText(DIR_DIALOG_TEXT);

            String dir = directoryDialog.open();
            if (dir != null) {
                destDirText.setText(dir);
            }
        });

        return button;
    }

    /*
     * Return true if the parameters indicate a double-quote character
     * is being inserted as the first or last character of the text.
     *
     * The purpose of this method is that the double-quote is an illegal
     * character in file paths, but it is allowed in the text box where
     * the user enters the prefix -- but only as surrounding characters,
     * to show the limits of the text being entered (i.e., to help display
     * whitespace).
     *
     * Note this test is not sufficient on its own.  If the text is quoted
     * already, and then the user tries to add a double-quote in front of
     * the existing quote, that should not be allowed.  It's assumed that
     * situation is caught by other code; this method just detects if it's
     * the first or last character.
     */
    private boolean quoteAtBeginningOrEnd(
        final char c,
        final int pos,
        final int start,
        final int end,
        final int originalLength,
        final int insertLength
    ) {
        // The user has entered a character that is not a double quote.
        if (c != DOUBLE_QUOTE) {
            return false;
        }
        // If start is 0, that means we're inserting at the beginning of the text box;
        // but this may be the result of a paste, so we may be inserting multiple
        // characters.  Checking (pos == 0) makes sure we're looking at the first
        // character of the text that's being inserted.
        if ((start == 0) && (pos == 0)) {
            return true;
        }
        // This is the same idea.  "end == originalLength" means we're inserting at
        // the end of the text box, but we only want to allow the double quote if
        // it's the LAST character of the text being inserted.
        if ((end == originalLength) && (pos == (insertLength - 1))) {
            return true;
        }
        // The user has tried to insert a double quote somewhere other than the first
        // or last character.
        return false;
    }

    /*
     * A sub-method to be called once it's been determined that the user has tried to insert
     * text into the "season prefix" text box, in a legal position (i.e., not before the
     * opening quote, and not after the closing quote.)  Not all edits are insertions; some
     * just delete text.
     *
     * Constructs a string with any illegal characters removed.  If the text is the same
     * length as what we got from the event, then all characters were legal.  If the new text
     * is zero length, then all characters were illegal, and we reject the insertion.  If the
     * length is neither zero, nor the full length of the inserted text, then the user has
     * pasted in some mix of legal and illegal characters.  We strip away the illegal ones,
     * and insert the legal ones, with a warning given to the user.
     */
    private void filterEnteredSeasonPrefixText(
        VerifyEvent e,
        final int previousTextLength
    ) {
        String textToInsert = e.text;
        int insertLength = textToInsert.length();
        StringBuilder acceptedText = new StringBuilder(insertLength);
        for (int i = 0; i < insertLength; i++) {
            char c = textToInsert.charAt(i);
            boolean isLegal =
                StringUtils.isLegalFilenameCharacter(c) ||
                quoteAtBeginningOrEnd(
                    c,
                    i,
                    e.start,
                    e.end,
                    previousTextLength,
                    insertLength
                );
            if (isLegal) {
                acceptedText.append(c);
            }
        }
        if (acceptedText.length() == insertLength) {
            statusLabel.clear(ILLEGAL_CHARACTERS_WARNING);
        } else {
            statusLabel.add(ILLEGAL_CHARACTERS_WARNING);
            if (acceptedText.length() == 0) {
                e.doit = false;
            } else {
                e.text = acceptedText.toString();
            }
        }
    }

    /*
     * The main verifier method for the "season prefix" text box.  The basic idea is
     * that we want to prohibit characters that are illegal in filenames.  But we
     * added a complication by deciding to display (and accept) the text with or without
     * surrounding double quotes.
     *
     * Double quotes are, of course, illegal in filenames (on the filesystems we care
     * about, anyway).  So they are generally prohibited.  And we put the surrounding
     * quotes up by default, so the user never needs to type them.  But it's very possible
     * they might delete the quotes, and then try to re-enter them, and that should be
     * supported.  And we also support them deleting the quotes and NOT reinstating them.
     *
     * A really stringent application might insist the quotes be either absent or balanced.
     * But that makes it impossible to delete a quote, unless you delete the entire text.
     * That's very annoying.  So we allow them to be unbalanced.  The StringUtils method
     * unquoteString will remove the quote from the front and from the back, whether they
     * are balanced or not.
     *
     * In order to avoid having the illegal quote character in the middle of the text, we
     * cannot allow the user to insert any text before the opening quote, or any text after
     * the closing quote.  Doing so would change them from delimiters to part of the text.
     *
     * Edits might not be inserting text at all.  They could be deleting text.  This method
     * checks that the user is trying to insert text, and that it's not before the opening
     * quote or after the closing quote.  If that's the case, it calls the next method,
     * filterEnteredSeasonPrefixText, to ensure no illegal characters are being entered.
     */
    private void verifySeasonPrefixText(VerifyEvent e) {
        if (e.text.length() > 0) {
            String previousText = seasonPrefixText.getText();
            int originalLength = previousText.length();

            if (
                (e.end < (originalLength - 1)) &&
                (previousText.charAt(e.end) == DOUBLE_QUOTE)
            ) {
                statusLabel.add(NO_TEXT_BEFORE_OPENING_QUOTE);
                e.doit = false;
            } else if (
                (e.start > 1) &&
                (previousText.charAt(e.start - 1) == DOUBLE_QUOTE)
            ) {
                statusLabel.add(NO_TEXT_AFTER_CLOSING_QUOTE);
                e.doit = false;
            } else {
                filterEnteredSeasonPrefixText(e, originalLength);
            }
        }
    }

    /*
     * Makes sure the text entered as season prefix is valid in a pathname.
     */
    private void ensureValidPrefixText() {
        String prefixText = seasonPrefixText.getText();

        // Remove the surrounding double quotes, if present;
        // any other double quotes should not be removed.
        String unquoted = StringUtils.unquoteString(prefixText);
        // The verifier should have prevented any illegal characters from
        // being entered.  This is just to check.
        seasonPrefixString = StringUtils.replaceIllegalCharacters(unquoted);

        if (!seasonPrefixString.equals(unquoted)) {
            // Somehow, illegal characters got through.
            logger.severe("Illegal characters recognized in season prefix");
            logger.severe(
                "Instead of \"" +
                    unquoted +
                    "\", will use \"" +
                    seasonPrefixString +
                    "\""
            );
        }
    }

    /*
     * Create the controls that regard the naming of the season prefix folder.
     * The text box gets both a verify listener and a modify listener.
     */
    private void createSeasonPrefixControls(final Composite generalGroup) {
        createLabel(SEASON_PREFIX_TEXT, PREFIX_TOOLTIP, generalGroup);
        seasonPrefixString = prefs.getSeasonPrefix();
        seasonPrefixText = createText(
            StringUtils.makeQuotedString(seasonPrefixString),
            generalGroup,
            true
        );
        seasonPrefixText.addVerifyListener(e -> {
            statusLabel.clear(NO_TEXT_BEFORE_OPENING_QUOTE);
            statusLabel.clear(NO_TEXT_AFTER_CLOSING_QUOTE);
            verifySeasonPrefixText(e);
        });
        seasonPrefixText.addModifyListener(e -> ensureValidPrefixText());
        seasonPrefixLeadingZeroCheckbox = createCheckbox(
            SEASON_PREFIX_ZERO_TEXT,
            SEASON_PREFIX_ZERO_TOOLTIP,
            prefs.isSeasonPrefixLeadingZero(),
            generalGroup,
            GridData.BEGINNING,
            3
        );
    }

    private void populateGeneralTab(final Composite generalGroup) {
        moveSelectedCheckbox = createCheckbox(
            MOVE_SELECTED_TEXT,
            MOVE_SELECTED_TOOLTIP,
            true,
            generalGroup,
            GridData.BEGINNING,
            2
        );
        renameSelectedCheckbox = createCheckbox(
            RENAME_SELECTED_TEXT,
            RENAME_SELECTED_TOOLTIP,
            true,
            generalGroup,
            GridData.END,
            1
        );

        createLabel(DEST_DIR_TEXT, DEST_DIR_TOOLTIP, generalGroup);
        destDirText = createText(
            prefs.getDestinationDirectoryName(),
            generalGroup,
            false
        );
        destDirButton = createDestDirButton(generalGroup);

        createSeasonPrefixControls(generalGroup);

        createLabel(IGNORE_LABEL_TEXT, IGNORE_LABEL_TOOLTIP, generalGroup);
        ignoreWordsText = createText(
            prefs.getIgnoredKeywordsString(),
            generalGroup,
            false
        );

        recurseFoldersCheckbox = createCheckbox(
            RECURSE_FOLDERS_TEXT,
            RECURSE_FOLDERS_TOOLTIP,
            prefs.isRecursivelyAddFolders(),
            generalGroup,
            GridData.BEGINNING,
            3
        );
        rmdirEmptyCheckbox = createCheckbox(
            REMOVE_EMPTIED_TEXT,
            REMOVE_EMPTIED_TOOLTIP,
            prefs.isRemoveEmptiedDirectories(),
            generalGroup,
            GridData.BEGINNING,
            3
        );
        deleteRowsCheckbox = createCheckbox(
            DELETE_ROWS_TEXT,
            DELETE_ROWS_TOOLTIP,
            prefs.isDeleteRowAfterMove(),
            generalGroup,
            GridData.BEGINNING,
            3
        );
        overwriteDestinationCheckbox = createCheckbox(
            OVERWRITE_DEST_TEXT,
            OVERWRITE_DEST_TOOLTIP,
            prefs.isAlwaysOverwriteDestination(),
            generalGroup,
            GridData.BEGINNING,
            3
        );
        cleanupDuplicatesCheckbox = createCheckbox(
            CLEANUP_DUPLICATES_TEXT,
            CLEANUP_DUPLICATES_TOOLTIP,
            prefs.isCleanupDuplicateVideoFiles(),
            generalGroup,
            GridData.BEGINNING,
            3
        );
        tagVideoMetadataCheckbox = createCheckbox(
            TAG_VIDEO_METADATA_TEXT,
            TAG_VIDEO_METADATA_TOOLTIP,
            prefs.isTagVideoMetadata(),
            generalGroup,
            GridData.BEGINNING,
            3
        );
        checkForUpdatesCheckbox = createCheckbox(
            CHECK_UPDATES_TEXT,
            CHECK_UPDATES_TOOLTIP,
            prefs.checkForUpdates(),
            generalGroup,
            GridData.BEGINNING,
            3
        );

        setMtimeToNowCheckbox = createCheckbox(
            "Set file modification time to now after move/rename",
            "If checked, TVRenamer will set the destination file's modification time to the current time after moving/renaming.\nIf unchecked (default), TVRenamer will preserve the original modification time.",
            !prefs.isPreserveFileModificationTime(),
            generalGroup,
            GridData.BEGINNING,
            3
        );

        createLabel(THEME_MODE_TEXT, THEME_MODE_TOOLTIP, generalGroup);
        themeModeCombo = new Combo(generalGroup, SWT.DROP_DOWN | SWT.READ_ONLY);
        themeModeCombo.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1)
        );
        themeModeCombo.setToolTipText(THEME_MODE_TOOLTIP);
        for (ThemeMode mode : ThemeMode.values()) {
            themeModeCombo.add(mode.toString());
        }

        Label themeRestartLabel = new Label(generalGroup, SWT.NONE);
        themeRestartLabel.setText(THEME_MODE_RESTART_NOTE);
        themeRestartLabel.setLayoutData(
            new GridData(SWT.BEGINNING, SWT.CENTER, true, false, 3, 1)
        );

        preferDvdOrderCheckbox = createCheckbox(
            PREFER_DVD_ORDER_TEXT,
            PREFER_DVD_ORDER_TOOLTIP,
            prefs.isPreferDvdOrderIfPresent(),
            generalGroup,
            GridData.BEGINNING,
            3
        );
    }

    private void initializeGeneralControls() {
        final boolean moveIsSelected = prefs.isMoveSelected();
        moveSelectedCheckbox.setSelection(moveIsSelected);
        toggleEnableControls(
            moveIsSelected,
            destDirText,
            destDirButton,
            seasonPrefixText,
            seasonPrefixLeadingZeroCheckbox
        );
        moveSelectedCheckbox.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    toggleEnableControls(
                        moveSelectedCheckbox.getSelection(),
                        destDirText,
                        destDirButton,
                        seasonPrefixText,
                        seasonPrefixLeadingZeroCheckbox
                    );
                }
            }
        );

        boolean renameIsSelected = prefs.isRenameSelected();
        renameSelectedCheckbox.setSelection(renameIsSelected);

        ThemeMode selectedTheme = prefs.getThemeMode();
        if (selectedTheme == null) {
            selectedTheme = ThemeMode.LIGHT;
        }
        int themeIndex = themeModeCombo.indexOf(selectedTheme.toString());
        if (themeIndex >= 0) {
            themeModeCombo.select(themeIndex);
        } else if (themeModeCombo.getItemCount() > 0) {
            themeModeCombo.select(0);
        }
    }

    private void createGeneralTab() {
        final TabItem item = new TabItem(tabFolder, SWT.NULL);
        item.setText(GENERAL_LABEL);

        final Composite generalGroup = new Composite(tabFolder, SWT.NONE);

        generalGroup.setLayout(new GridLayout(3, false));

        generalGroup.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, true, 3, 1)
        );

        generalGroup.setToolTipText(GENERAL_TOOLTIP);
        ThemeManager.applyPalette(generalGroup, themePalette);

        populateGeneralTab(generalGroup);
        initializeGeneralControls();

        item.setControl(generalGroup);
    }

    // Light blue color for token pills (works in both light and dark modes)
    private static final int PILL_BG_RED = 200;
    private static final int PILL_BG_GREEN = 220;
    private static final int PILL_BG_BLUE = 255;

    // Darker blue for active/dragging border
    private static final int PILL_ACTIVE_RED = 100;
    private static final int PILL_ACTIVE_GREEN = 150;
    private static final int PILL_ACTIVE_BLUE = 220;

    private void createRenameTab() {
        TabItem item = new TabItem(tabFolder, SWT.NULL);
        item.setText(RENAMING_LABEL);

        Composite replacementGroup = new Composite(tabFolder, SWT.NONE);

        // Use 1-column layout so title is above tokens, format row below
        replacementGroup.setLayout(new GridLayout(1, false));

        replacementGroup.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, true, 1, 1)
        );
        ThemeManager.applyPalette(replacementGroup, themePalette);

        // Title label above tokens
        Label renameTokensLabel = new Label(replacementGroup, SWT.NONE);
        renameTokensLabel.setText(RENAME_TOKEN_TEXT);
        renameTokensLabel.setToolTipText(RENAME_TOKEN_TOOLTIP);
        ThemeManager.applyPalette(renameTokensLabel, themePalette);

        // Container for pill-styled tokens (vertical stack, one per line)
        Composite tokensContainer = new Composite(replacementGroup, SWT.NONE);
        org.eclipse.swt.layout.RowLayout tokenLayout =
            new org.eclipse.swt.layout.RowLayout(SWT.VERTICAL);
        tokenLayout.spacing = 4;
        tokenLayout.marginTop = 4;
        tokenLayout.marginBottom = 8;
        tokensContainer.setLayout(tokenLayout);
        tokensContainer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ThemeManager.applyPalette(tokensContainer, themePalette);

        // Create pill-styled draggable token labels
        ReplacementToken[] tokens = {
            SHOW_NAME,
            SEASON_NUM,
            SEASON_NUM_LEADING_ZERO,
            EPISODE_NUM,
            EPISODE_NUM_LEADING_ZERO,
            EPISODE_TITLE,
            EPISODE_TITLE_NO_SPACES,
            EPISODE_RESOLUTION,
            DATE_DAY_NUM,
            DATE_DAY_NUMLZ,
            DATE_MONTH_NUM,
            DATE_MONTH_NUMLZ,
            DATE_YEAR_MIN,
            DATE_YEAR_FULL
        };

        for (ReplacementToken token : tokens) {
            createTokenPill(tokensContainer, token);
        }

        // Rename Format row (label + text field side by side)
        Composite formatRow = new Composite(replacementGroup, SWT.NONE);
        formatRow.setLayout(new GridLayout(2, false));
        formatRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ThemeManager.applyPalette(formatRow, themePalette);

        Label episodeTitleLabel = new Label(formatRow, SWT.NONE);
        episodeTitleLabel.setText(RENAME_FORMAT_TEXT);
        episodeTitleLabel.setToolTipText(RENAME_FORMAT_TOOLTIP);
        ThemeManager.applyPalette(episodeTitleLabel, themePalette);

        replacementStringText = createText(
            prefs.getRenameReplacementString(),
            formatRow,
            true
        );

        createDropTarget(replacementStringText);

        // Preview row (label + preview text side by side)
        Composite previewRow = new Composite(replacementGroup, SWT.NONE);
        previewRow.setLayout(new GridLayout(2, false));
        previewRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ThemeManager.applyPalette(previewRow, themePalette);

        Label previewLabel = new Label(previewRow, SWT.NONE);
        previewLabel.setText("Preview:");
        ThemeManager.applyPalette(previewLabel, themePalette);

        renameFormatPreviewLabel = new Label(previewRow, SWT.NONE);
        renameFormatPreviewLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ThemeManager.applyPalette(renameFormatPreviewLabel, themePalette);

        // Update preview when format changes
        replacementStringText.addModifyListener(e -> updateRenameFormatPreview());

        // Initial preview
        updateRenameFormatPreview();

        item.setControl(replacementGroup);
    }

    // Rounded corner radius for token pills
    private static final int PILL_CORNER_RADIUS = 6; // 3px radius = 6px arc diameter
    private static final int PILL_PADDING_X = 8;
    private static final int PILL_PADDING_Y = 4;

    // Key for storing dragging state in widget data
    private static final String DRAGGING_KEY = "dragging";

    // Sample data for rename format preview
    private static final String PREVIEW_SHOW_NAME = "Rover";
    private static final int PREVIEW_SEASON = 2;
    private static final int PREVIEW_EPISODE = 5;
    private static final String PREVIEW_EPISODE_TITLE = "The Squirrels Episode";
    private static final String PREVIEW_RESOLUTION = "720p";
    private static final int PREVIEW_YEAR = 2009;
    private static final int PREVIEW_MONTH = 4;
    private static final int PREVIEW_DAY = 26;

    /**
     * Update the rename format preview label with sample data.
     */
    private void updateRenameFormatPreview() {
        if (renameFormatPreviewLabel == null || renameFormatPreviewLabel.isDisposed()) {
            return;
        }
        if (replacementStringText == null || replacementStringText.isDisposed()) {
            return;
        }

        String template = replacementStringText.getText();
        if (template == null) {
            template = "";
        }

        // Replace tokens with sample data (same order as EpisodeReplacementFormatter)
        String preview = template
            .replace(SEASON_NUM.getToken(), String.valueOf(PREVIEW_SEASON))
            .replace(SEASON_NUM_LEADING_ZERO.getToken(), String.format("%02d", PREVIEW_SEASON))
            .replace(EPISODE_NUM.getToken(), String.valueOf(PREVIEW_EPISODE))
            .replace(EPISODE_NUM_LEADING_ZERO.getToken(), String.format("%02d", PREVIEW_EPISODE))
            .replace(SHOW_NAME.getToken(), PREVIEW_SHOW_NAME)
            .replace(EPISODE_TITLE.getToken(), PREVIEW_EPISODE_TITLE)
            .replace(EPISODE_TITLE_NO_SPACES.getToken(), PREVIEW_EPISODE_TITLE.replace(' ', '.'))
            .replace(EPISODE_RESOLUTION.getToken(), PREVIEW_RESOLUTION)
            .replace(DATE_DAY_NUM.getToken(), String.valueOf(PREVIEW_DAY))
            .replace(DATE_DAY_NUMLZ.getToken(), String.format("%02d", PREVIEW_DAY))
            .replace(DATE_MONTH_NUM.getToken(), String.valueOf(PREVIEW_MONTH))
            .replace(DATE_MONTH_NUMLZ.getToken(), String.format("%02d", PREVIEW_MONTH))
            .replace(DATE_YEAR_FULL.getToken(), String.valueOf(PREVIEW_YEAR))
            .replace(DATE_YEAR_MIN.getToken(), String.valueOf(PREVIEW_YEAR % 100));

        // Sanitize for display (same as EpisodeReplacementFormatter does)
        preview = StringUtils.sanitiseTitle(preview);

        renameFormatPreviewLabel.setText(preview);

        // Request layout to accommodate changed text width
        Composite parent = renameFormatPreviewLabel.getParent();
        if (parent != null && !parent.isDisposed()) {
            parent.layout(true);
        }
    }

    /**
     * Create a pill-styled token label that can be dragged or clicked to insert.
     * Uses Canvas with custom painting for reliable background color on Windows.
     */
    private void createTokenPill(Composite parent, ReplacementToken token) {
        String text = token.toString();
        String tokenString = token.getToken();

        // Use Canvas for complete control over painting
        org.eclipse.swt.widgets.Canvas pill =
            new org.eclipse.swt.widgets.Canvas(parent, SWT.DOUBLE_BUFFERED);

        // Light blue color for background
        org.eclipse.swt.graphics.Color pillBg = new org.eclipse.swt.graphics.Color(
            parent.getDisplay(), PILL_BG_RED, PILL_BG_GREEN, PILL_BG_BLUE
        );
        // Darker blue for active/dragging border
        org.eclipse.swt.graphics.Color pillActive = new org.eclipse.swt.graphics.Color(
            parent.getDisplay(), PILL_ACTIVE_RED, PILL_ACTIVE_GREEN, PILL_ACTIVE_BLUE
        );
        pill.addDisposeListener(e -> {
            pillBg.dispose();
            pillActive.dispose();
        });

        // Initialize dragging state
        pill.setData(DRAGGING_KEY, Boolean.FALSE);

        // Calculate size based on text extent
        org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC(pill);
        org.eclipse.swt.graphics.Point textSize = gc.textExtent(text);
        gc.dispose();

        int width = textSize.x + (PILL_PADDING_X * 2);
        int height = textSize.y + (PILL_PADDING_Y * 2);

        // Set size via RowData for RowLayout
        pill.setLayoutData(new org.eclipse.swt.layout.RowData(width, height));

        // Custom paint: rounded rectangle background + text
        pill.addPaintListener(e -> {
            org.eclipse.swt.graphics.Rectangle bounds = pill.getClientArea();
            e.gc.setAntialias(SWT.ON);

            boolean isDragging = Boolean.TRUE.equals(pill.getData(DRAGGING_KEY));

            // Fill rounded rectangle with light blue
            e.gc.setBackground(pillBg);
            e.gc.fillRoundRectangle(0, 0, bounds.width, bounds.height,
                PILL_CORNER_RADIUS, PILL_CORNER_RADIUS);

            // Draw border - darker blue when dragging, same as bg otherwise
            e.gc.setForeground(isDragging ? pillActive : pillBg);
            e.gc.drawRoundRectangle(0, 0, bounds.width - 1, bounds.height - 1,
                PILL_CORNER_RADIUS, PILL_CORNER_RADIUS);
            if (isDragging) {
                // Draw a second border for thickness
                e.gc.drawRoundRectangle(1, 1, bounds.width - 3, bounds.height - 3,
                    PILL_CORNER_RADIUS, PILL_CORNER_RADIUS);
            }

            // Draw text centered
            e.gc.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_BLACK));
            org.eclipse.swt.graphics.Point ts = e.gc.textExtent(text);
            int tx = (bounds.width - ts.x) / 2;
            int ty = (bounds.height - ts.y) / 2;
            e.gc.drawText(text, tx, ty, true);
        });

        pill.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        // Click to insert at caret position
        pill.addListener(SWT.MouseUp, event -> {
            if (replacementStringText != null && !replacementStringText.isDisposed()) {
                insertTokenAtCaret(tokenString);
            }
        });

        // Set up drag source for this pill with visual feedback
        createTokenDragSource(pill, tokenString);
    }

    /**
     * Insert token text at current caret position in replacementStringText.
     */
    private void insertTokenAtCaret(String tokenText) {
        replacementStringText.setFocus();
        org.eclipse.swt.graphics.Point sel = replacementStringText.getSelection();
        String current = replacementStringText.getText();
        if (current == null) {
            current = "";
        }

        int start = sel.x;
        int end = sel.y;
        if (start < 0) start = 0;
        if (end < start) end = start;
        if (start > current.length()) start = current.length();
        if (end > current.length()) end = current.length();

        String newValue = current.substring(0, start) + tokenText + current.substring(end);
        replacementStringText.setText(newValue);
        replacementStringText.setSelection(start + tokenText.length());
    }

    /**
     * Create a drag source for a single token pill/label with visual feedback.
     */
    private void createTokenDragSource(Control control, String tokenText) {
        Transfer[] types = new Transfer[] { TextTransfer.getInstance() };
        DragSource dragSource = new DragSource(control, DND_OPERATIONS);
        dragSource.setTransfer(types);
        dragSource.addDragListener(new DragSourceListener() {
            @Override
            public void dragStart(DragSourceEvent event) {
                event.doit = true;
                // Set dragging state and redraw for visual feedback
                control.setData(DRAGGING_KEY, Boolean.TRUE);
                control.redraw();
            }

            @Override
            public void dragSetData(DragSourceEvent event) {
                if (TextTransfer.getInstance().isSupportedType(event.dataType)) {
                    event.data = tokenText;
                }
            }

            @Override
            public void dragFinished(DragSourceEvent event) {
                // Clear dragging state and redraw
                control.setData(DRAGGING_KEY, Boolean.FALSE);
                control.redraw();
            }
        });
    }

    private void createOverridesTab() {
        TabItem item = new TabItem(tabFolder, SWT.NULL);
        item.setText("Matching");

        Composite overridesGroup = new Composite(tabFolder, SWT.NONE);
        overridesGroup.setLayout(new GridLayout(3, false));
        overridesGroup.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, true, 3, 1)
        );
        ThemeManager.applyPalette(overridesGroup, themePalette);

        // --- Overrides section (Extracted show -> Replacement text) ---
        Label overridesHeader = new Label(overridesGroup, SWT.NONE);
        overridesHeader.setText(
            "Overrides (Extracted show \u2192 Replacement text)"
        );
        overridesHeader.setLayoutData(
            new GridData(SWT.BEGINNING, SWT.CENTER, true, false, 3, 1)
        );

        Label fromLabel = new Label(overridesGroup, SWT.NONE);
        fromLabel.setText("Extracted show");
        fromLabel.setToolTipText(
            "Exact match (case-insensitive). Example: Stars"
        );

        overridesFromText = createText("", overridesGroup, false);
        new Label(overridesGroup, SWT.NONE); // spacer

        Label toLabel = new Label(overridesGroup, SWT.NONE);
        toLabel.setText("Replace with");
        toLabel.setToolTipText(
            "Replacement name used for TVDB search. Example: Stars (2024)"
        );

        overridesToText = createText("", overridesGroup, false);
        new Label(overridesGroup, SWT.NONE); // spacer

        // Force relayout/redraw when focus moves between fields; some SWT layouts
        // can temporarily miscompute sizes, causing the button row to appear hidden
        // until the next paint event (e.g., mouse hover).
        overridesFromText.addListener(SWT.FocusOut, e ->
            overridesGroup.layout(true, true)
        );
        overridesToText.addListener(SWT.FocusOut, e ->
            overridesGroup.layout(true, true)
        );

        Composite overrideButtons = new Composite(overridesGroup, SWT.NONE);
        overrideButtons.setLayout(new GridLayout(3, true));
        GridData overrideButtonsGridData = new GridData(
            SWT.FILL,
            SWT.CENTER,
            true,
            false,
            3,
            1
        );
        overrideButtonsGridData.minimumHeight = 35;
        overrideButtons.setLayoutData(overrideButtonsGridData);

        Button overrideAddButton = new Button(overrideButtons, SWT.PUSH);
        overrideAddButton.setText("Add / Update");
        GridData overrideAddGridData = new GridData(
            SWT.FILL,
            SWT.CENTER,
            true,
            false
        );
        overrideAddGridData.minimumWidth = 110;
        overrideAddGridData.heightHint = 30;
        overrideAddButton.setLayoutData(overrideAddGridData);
        overrideAddButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    String from = overridesFromText.getText().trim();
                    String to = overridesToText.getText().trim();
                    if (from.isEmpty() || to.isEmpty()) {
                        return;
                    }
                    upsertOverride(from, to);
                    overridesFromText.setText("");
                    overridesToText.setText("");

                    // Validate the selected row (or the upserted row) asynchronously.
                    int idx = overridesTable.getSelectionIndex();
                    if (idx < 0) {
                        idx = overridesTable.getItemCount() - 1;
                    }
                    validateMatchingRowOnline(
                        overridesTable,
                        idx,
                        MatchingRowType.OVERRIDE
                    );
                }
            }
        );

        Button overrideRemoveButton = new Button(overrideButtons, SWT.PUSH);
        overrideRemoveButton.setText("Remove");
        GridData overrideRemoveGridData = new GridData(
            SWT.FILL,
            SWT.CENTER,
            true,
            false
        );
        overrideRemoveGridData.minimumWidth = 90;
        overrideRemoveGridData.heightHint = 30;
        overrideRemoveButton.setLayoutData(overrideRemoveGridData);
        overrideRemoveButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    int idx = overridesTable.getSelectionIndex();
                    if (idx >= 0) {
                        overridesTable.remove(idx);
                        updateSaveEnabledFromMatchingValidation();
                    }
                }
            }
        );

        Button overrideClearButton = new Button(overrideButtons, SWT.PUSH);
        overrideClearButton.setText("Clear All");
        GridData overrideClearGridData = new GridData(
            SWT.FILL,
            SWT.CENTER,
            true,
            false
        );
        overrideClearGridData.minimumWidth = 90;
        overrideClearGridData.heightHint = 30;
        overrideClearButton.setLayoutData(overrideClearGridData);
        overrideClearButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    MessageBox box = new MessageBox(
                        preferencesShell,
                        SWT.ICON_WARNING | SWT.OK | SWT.CANCEL
                    );
                    box.setText("Clear All");
                    box.setMessage(
                        "Are you sure?\r\nThis will remove all entries."
                    );
                    if (box.open() == SWT.OK) {
                        overridesTable.removeAll();
                        updateSaveEnabledFromMatchingValidation();
                    }
                }
            }
        );

        overridesTable = new Table(
            overridesGroup,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE
        );
        overridesTable.setHeaderVisible(true);
        overridesTable.setLinesVisible(true);
        GridData overridesTableData = new GridData(
            SWT.FILL,
            SWT.FILL,
            true,
            true,
            3,
            1
        );
        overridesTableData.minimumHeight = 110;
        overridesTable.setLayoutData(overridesTableData);

        TableColumn oColStatus = new TableColumn(overridesTable, SWT.CENTER);
        oColStatus.setText("");

        TableColumn oColFrom = new TableColumn(overridesTable, SWT.LEFT);
        oColFrom.setText("Extracted show");
        TableColumn oColTo = new TableColumn(overridesTable, SWT.LEFT);
        oColTo.setText("Replace with");

        // Populate table from prefs (not dirty; treat as OK by default).
        for (Map.Entry<String, String> e : prefs
            .getShowNameOverrides()
            .entrySet()) {
            TableItem ti = new TableItem(overridesTable, SWT.NONE);
            ti.setText(new String[] { "", e.getKey(), e.getValue() });
            ti.setImage(0, MATCHING_ICON_OK);
            ti.setData(MATCHING_DIRTY_KEY, Boolean.FALSE);
        }
        // Column sizing:
        // - Col 0 (status icon): fixed-ish width
        // - Cols 1 & 2: split the remaining width evenly so the table fills its bounds neatly
        final int statusColWidth = 28;
        oColStatus.setWidth(statusColWidth);

        overridesTable.addListener(SWT.Resize, e -> {
            if (overridesTable == null || overridesTable.isDisposed()) {
                return;
            }
            TableColumn[] cols = overridesTable.getColumns();
            if (cols == null || cols.length < 3) {
                return;
            }

            int clientWidth = overridesTable.getClientArea().width;
            if (clientWidth <= 0) {
                return;
            }

            cols[0].setWidth(statusColWidth);

            int remaining = clientWidth - cols[0].getWidth();
            if (remaining < 0) {
                remaining = 0;
            }

            int w = remaining / 2;
            cols[1].setWidth(w);
            cols[2].setWidth(remaining - w);
        });

        // Trigger an initial layout pass after population.
        overridesTable.notifyListeners(SWT.Resize, new Event());

        // Ensure the Save button enabled state is correct after initial table population.
        // (Matching validation can disable Save; we must recompute once the tables exist.)
        updateSaveEnabledFromMatchingValidation();

        // Validation message display for Overrides table (shown near this table).
        overridesHoverTipLabel = new Label(overridesGroup, SWT.WRAP);
        overridesHoverTipLabel.setText("");
        overridesHoverTipLabel.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1)
        );

        // Clicking a row loads it into the edit fields for easy update
        // Note: column 0 is the status icon column; values are in columns 1 and 2.
        overridesTable.addListener(SWT.Selection, e -> {
            int idx = overridesTable.getSelectionIndex();
            if (idx >= 0) {
                TableItem ti = overridesTable.getItem(idx);
                overridesFromText.setText(ti.getText(1));
                overridesToText.setText(ti.getText(2));
            }
        });

        // Hover: show stored validation message near the Overrides table (best-effort).
        overridesTable.addListener(SWT.MouseMove, e -> {
            if (
                overridesHoverTipLabel == null ||
                overridesHoverTipLabel.isDisposed()
            ) {
                return;
            }
            // Hit-test the hovered row and show its validation message (best-effort).
            TableItem ti = overridesTable.getItem(
                new org.eclipse.swt.graphics.Point(e.x, e.y)
            );
            Object msgObj = (ti == null)
                ? null
                : ti.getData("tvrenamer.matching.validationMessage");
            String next = (msgObj == null) ? "" : msgObj.toString();

            // Avoid forcing layouts on every mouse move; only update when text actually changes.
            String current = overridesHoverTipLabel.getText();
            if (current == null) {
                current = "";
            }
            if (!current.equals(next)) {
                overridesHoverTipLabel.setText(next);
            }
        });

        // --- Disambiguations section (Query string -> Series ID) ---
        Label spacer = new Label(overridesGroup, SWT.NONE);
        spacer.setLayoutData(
            new GridData(SWT.BEGINNING, SWT.CENTER, true, false, 3, 1)
        );

        Label disambiguationsHeader = new Label(overridesGroup, SWT.NONE);
        disambiguationsHeader.setText(
            "Disambiguations (Query string \u2192 Series ID)"
        );
        disambiguationsHeader.setLayoutData(
            new GridData(SWT.BEGINNING, SWT.CENTER, true, false, 3, 1)
        );

        Label queryLabel = new Label(overridesGroup, SWT.NONE);
        queryLabel.setText("Query string");
        queryLabel.setToolTipText(
            "Provider query string (normalized). Example: the rookie"
        );

        disambiguationsQueryText = createText("", overridesGroup, false);
        new Label(overridesGroup, SWT.NONE); // spacer

        Label idLabel = new Label(overridesGroup, SWT.NONE);
        idLabel.setText("Series ID");
        idLabel.setToolTipText(
            "Provider series id (e.g., TVDB seriesid). Example: 361753"
        );

        disambiguationsIdText = createText("", overridesGroup, false);
        new Label(overridesGroup, SWT.NONE); // spacer

        disambiguationsQueryText.addListener(SWT.FocusOut, e ->
            overridesGroup.layout(true, true)
        );
        disambiguationsIdText.addListener(SWT.FocusOut, e ->
            overridesGroup.layout(true, true)
        );

        Composite disambiguationButtons = new Composite(
            overridesGroup,
            SWT.NONE
        );
        disambiguationButtons.setLayout(new GridLayout(3, true));
        GridData disambButtonsGridData = new GridData(
            SWT.FILL,
            SWT.CENTER,
            true,
            false,
            3,
            1
        );
        disambButtonsGridData.minimumHeight = 35;
        disambiguationButtons.setLayoutData(disambButtonsGridData);

        Button disambAddButton = new Button(disambiguationButtons, SWT.PUSH);
        disambAddButton.setText("Add / Update");
        GridData disambAddGridData = new GridData(
            SWT.FILL,
            SWT.CENTER,
            true,
            false
        );
        disambAddGridData.minimumWidth = 110;
        disambAddGridData.heightHint = 30;
        disambAddButton.setLayoutData(disambAddGridData);
        disambAddButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    String q = disambiguationsQueryText.getText().trim();
                    String id = disambiguationsIdText.getText().trim();
                    if (q.isEmpty() || id.isEmpty()) {
                        return;
                    }
                    upsertDisambiguation(q, id);
                    disambiguationsQueryText.setText("");
                    disambiguationsIdText.setText("");

                    // Validate the selected row (or the upserted row) asynchronously.
                    int idx = disambiguationsTable.getSelectionIndex();
                    if (idx < 0) {
                        idx = disambiguationsTable.getItemCount() - 1;
                    }
                    validateMatchingRowOnline(
                        disambiguationsTable,
                        idx,
                        MatchingRowType.DISAMBIGUATION
                    );
                }
            }
        );

        Button disambRemoveButton = new Button(disambiguationButtons, SWT.PUSH);
        disambRemoveButton.setText("Remove");
        GridData disambRemoveGridData = new GridData(
            SWT.FILL,
            SWT.CENTER,
            true,
            false
        );
        disambRemoveGridData.minimumWidth = 90;
        disambRemoveGridData.heightHint = 30;
        disambRemoveButton.setLayoutData(disambRemoveGridData);
        disambRemoveButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    int idx = disambiguationsTable.getSelectionIndex();
                    if (idx >= 0) {
                        disambiguationsTable.remove(idx);
                        updateSaveEnabledFromMatchingValidation();
                    }
                }
            }
        );

        Button disambClearButton = new Button(disambiguationButtons, SWT.PUSH);
        disambClearButton.setText("Clear All");
        GridData disambClearGridData = new GridData(
            SWT.FILL,
            SWT.CENTER,
            true,
            false
        );
        disambClearGridData.minimumWidth = 90;
        disambClearGridData.heightHint = 30;
        disambClearButton.setLayoutData(disambClearGridData);
        disambClearButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    MessageBox box = new MessageBox(
                        preferencesShell,
                        SWT.ICON_WARNING | SWT.OK | SWT.CANCEL
                    );
                    box.setText("Clear All");
                    box.setMessage(
                        "Are you sure?\r\nThis will remove all entries."
                    );
                    if (box.open() == SWT.OK) {
                        disambiguationsTable.removeAll();
                        updateSaveEnabledFromMatchingValidation();
                    }
                }
            }
        );

        disambiguationsTable = new Table(
            overridesGroup,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE
        );
        disambiguationsTable.setHeaderVisible(true);
        disambiguationsTable.setLinesVisible(true);
        GridData disambiguationsTableData = new GridData(
            SWT.FILL,
            SWT.FILL,
            true,
            true,
            3,
            1
        );
        disambiguationsTableData.minimumHeight = 110;
        disambiguationsTable.setLayoutData(disambiguationsTableData);

        TableColumn dColStatus = new TableColumn(
            disambiguationsTable,
            SWT.CENTER
        );
        dColStatus.setText("");

        TableColumn dColQuery = new TableColumn(disambiguationsTable, SWT.LEFT);
        dColQuery.setText("Query string");
        TableColumn dColId = new TableColumn(disambiguationsTable, SWT.LEFT);
        dColId.setText("Series ID");

        // Populate table from prefs (not dirty; treat as OK by default).
        for (Map.Entry<String, String> e : prefs
            .getShowDisambiguationOverrides()
            .entrySet()) {
            TableItem ti = new TableItem(disambiguationsTable, SWT.NONE);
            ti.setText(new String[] { "", e.getKey(), e.getValue() });
            ti.setImage(0, MATCHING_ICON_OK);
            ti.setData(MATCHING_DIRTY_KEY, Boolean.FALSE);
        }
        // Column sizing:
        // - Col 0 (status icon): fixed-ish width
        // - Cols 1 & 2: split the remaining width evenly so the table fills its bounds neatly
        final int statusColWidth2 = 28;
        dColStatus.setWidth(statusColWidth2);

        disambiguationsTable.addListener(SWT.Resize, e -> {
            if (
                disambiguationsTable == null ||
                disambiguationsTable.isDisposed()
            ) {
                return;
            }
            TableColumn[] cols = disambiguationsTable.getColumns();
            if (cols == null || cols.length < 3) {
                return;
            }

            int clientWidth = disambiguationsTable.getClientArea().width;
            if (clientWidth <= 0) {
                return;
            }

            cols[0].setWidth(statusColWidth2);

            int remaining = clientWidth - cols[0].getWidth();
            if (remaining < 0) {
                remaining = 0;
            }

            int w = remaining / 2;
            cols[1].setWidth(w);
            cols[2].setWidth(remaining - w);
        });

        // Trigger an initial layout pass after population.
        disambiguationsTable.notifyListeners(SWT.Resize, new Event());

        // Ensure the Save button enabled state is correct after initial table population.
        updateSaveEnabledFromMatchingValidation();

        // Validation message display for Disambiguations table (shown near this table).
        disambiguationsHoverTipLabel = new Label(overridesGroup, SWT.WRAP);
        disambiguationsHoverTipLabel.setText("");
        disambiguationsHoverTipLabel.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1)
        );

        disambiguationsTable.addListener(SWT.Selection, e -> {
            int idx = disambiguationsTable.getSelectionIndex();
            if (idx >= 0) {
                TableItem ti = disambiguationsTable.getItem(idx);
                // Note: column 0 is the status icon column; values are in columns 1 and 2.
                disambiguationsQueryText.setText(ti.getText(1));
                disambiguationsIdText.setText(ti.getText(2));
            }
        });

        // Hover: show stored validation message near the Disambiguations table (best-effort).
        disambiguationsTable.addListener(SWT.MouseMove, e -> {
            if (
                disambiguationsHoverTipLabel == null ||
                disambiguationsHoverTipLabel.isDisposed()
            ) {
                return;
            }
            // Hit-test the hovered row and show its validation message (best-effort).
            TableItem ti = disambiguationsTable.getItem(
                new org.eclipse.swt.graphics.Point(e.x, e.y)
            );
            Object msgObj = (ti == null)
                ? null
                : ti.getData("tvrenamer.matching.validationMessage");
            String next = (msgObj == null) ? "" : msgObj.toString();

            // Avoid forcing layouts on every mouse move; only update when text actually changes.
            String current = disambiguationsHoverTipLabel.getText();
            if (current == null) {
                current = "";
            }
            if (!current.equals(next)) {
                disambiguationsHoverTipLabel.setText(next);
            }
        });

        item.setControl(overridesGroup);
    }

    private void upsertOverride(String from, String to) {
        // Update selected row if present; otherwise upsert by key (case-insensitive).
        int selected = (overridesTable == null)
            ? -1
            : overridesTable.getSelectionIndex();
        if (selected >= 0) {
            TableItem ti = overridesTable.getItem(selected);
            // Column 0 is the status icon column; values are columns 1 and 2.
            ti.setText(new String[] { "", from, to });
            return;
        }

        int updateIdx = -1;
        if (overridesTable != null) {
            for (int i = 0; i < overridesTable.getItemCount(); i++) {
                TableItem ti = overridesTable.getItem(i);
                if (ti.getText(0).trim().equalsIgnoreCase(from)) {
                    updateIdx = i;
                    break;
                }
            }
            if (updateIdx >= 0) {
                overridesTable
                    .getItem(updateIdx)
                    .setText(new String[] { from, to });
                return;
            }

            TableItem ti = new TableItem(overridesTable, SWT.NONE);
            // Column 0 is the status icon column; values are columns 1 and 2.
            ti.setText(new String[] { "", from, to });
        }
    }

    private void upsertDisambiguation(String queryString, String seriesId) {
        int selected = (disambiguationsTable == null)
            ? -1
            : disambiguationsTable.getSelectionIndex();
        if (selected >= 0) {
            TableItem ti = disambiguationsTable.getItem(selected);
            // Column 0 is the status icon column; values are columns 1 and 2.
            ti.setText(new String[] { "", queryString, seriesId });
            return;
        }

        int updateIdx = -1;
        if (disambiguationsTable != null) {
            for (int i = 0; i < disambiguationsTable.getItemCount(); i++) {
                TableItem ti = disambiguationsTable.getItem(i);
                if (ti.getText(0).trim().equalsIgnoreCase(queryString)) {
                    updateIdx = i;
                    break;
                }
            }
            if (updateIdx >= 0) {
                disambiguationsTable
                    .getItem(updateIdx)
                    .setText(new String[] { queryString, seriesId });
                return;
            }

            TableItem ti = new TableItem(disambiguationsTable, SWT.NONE);
            // Column 0 is the status icon column; values are columns 1 and 2.
            ti.setText(new String[] { "", queryString, seriesId });
        }
    }

    private void createDropTarget(final Text targetText) {
        Transfer[] types = new Transfer[] { TextTransfer.getInstance() };
        DropTarget dropTarget = new DropTarget(targetText, DND_OPERATIONS);
        dropTarget.setTransfer(types);
        dropTarget.addDropListener(
            new PreferencesDropTargetListener(targetText)
        );
    }

    private void createActionButtonGroup() {
        Composite bottomButtonsComposite = new Composite(
            preferencesShell,
            SWT.FILL
        );
        bottomButtonsComposite.setLayout(new GridLayout(2, false));
        ThemeManager.applyPalette(bottomButtonsComposite, themePalette);

        bottomButtonsComposite.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, true, 2, 1)
        );

        Button cancelButton = new Button(bottomButtonsComposite, SWT.PUSH);
        GridData cancelButtonGridData = new GridData(
            GridData.BEGINNING,
            GridData.CENTER,
            false,
            false
        );
        cancelButtonGridData.minimumWidth = 150;
        cancelButtonGridData.widthHint = 150;
        cancelButton.setLayoutData(cancelButtonGridData);
        cancelButton.setText(CANCEL_LABEL);
        cancelButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    preferencesShell.close();
                }
            }
        );

        saveButton = new Button(bottomButtonsComposite, SWT.PUSH);
        GridData saveButtonGridData = new GridData(
            GridData.END,
            GridData.CENTER,
            false,
            false
        );
        saveButtonGridData.minimumWidth = 150;
        saveButtonGridData.widthHint = 150;
        saveButton.setLayoutData(saveButtonGridData);
        saveButton.setText(SAVE_LABEL);
        saveButton.setFocus();
        saveButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    boolean saved = savePreferences();
                    if (saved) {
                        preferencesShell.close();
                    }
                }
            }
        );

        // Set the OK button as the default, so
        // user can press Enter to save
        preferencesShell.setDefaultButton(saveButton);
    }

    /**
     * Save the preferences to the xml file
     */
    private boolean savePreferences() {
        // Block save if there are invalid/incomplete dirty matching rows.
        if (!matchingIsSaveable()) {
            return false;
        }
        // Validate the move destination BEFORE committing it into UserPreferences.
        // This prevents the main window (ResultsTable) from reacting to preference changes
        // and showing a second error popup while the Preferences dialog is still open.
        final boolean moveSelected = moveSelectedCheckbox.getSelection();
        final String destDirTextValue = destDirText.getText();

        if (moveSelected) {
            final Path destPath;
            try {
                destPath = Paths.get(destDirTextValue);
            } catch (RuntimeException ex) {
                MessageBox box = new MessageBox(
                    preferencesShell,
                    SWT.ICON_ERROR | SWT.OK
                );
                box.setText(ERROR_LABEL);
                box.setMessage(
                    CANT_CREATE_DEST +
                        ": '" +
                        destDirTextValue +
                        "'. " +
                        MOVE_NOT_POSSIBLE
                );
                box.open();

                if (destDirText != null && !destDirText.isDisposed()) {
                    destDirText.setFocus();
                    destDirText.selectAll();
                }
                return false;
            }

            if (!FileUtilities.checkForCreatableDirectory(destPath)) {
                MessageBox box = new MessageBox(
                    preferencesShell,
                    SWT.ICON_ERROR | SWT.OK
                );
                box.setText(ERROR_LABEL);
                box.setMessage(
                    CANT_CREATE_DEST +
                        ": '" +
                        destDirTextValue +
                        "'. " +
                        MOVE_NOT_POSSIBLE
                );
                box.open();

                if (destDirText != null && !destDirText.isDisposed()) {
                    destDirText.setFocus();
                    destDirText.selectAll();
                }
                return false;
            }
        }

        // Update the preferences object from the UI control values
        prefs.setSeasonPrefix(seasonPrefixString);
        prefs.setSeasonPrefixLeadingZero(
            seasonPrefixLeadingZeroCheckbox.getSelection()
        );
        prefs.setRenameReplacementString(replacementStringText.getText());
        prefs.setIgnoreKeywords(ignoreWordsText.getText());
        prefs.setCheckForUpdates(checkForUpdatesCheckbox.getSelection());
        prefs.setRecursivelyAddFolders(recurseFoldersCheckbox.getSelection());
        prefs.setRemoveEmptiedDirectories(rmdirEmptyCheckbox.getSelection());
        prefs.setDeleteRowAfterMove(deleteRowsCheckbox.getSelection());
        prefs.setAlwaysOverwriteDestination(
            overwriteDestinationCheckbox.getSelection()
        );
        prefs.setCleanupDuplicateVideoFiles(
            cleanupDuplicatesCheckbox.getSelection()
        );
        prefs.setTagVideoMetadata(
            tagVideoMetadataCheckbox.getSelection()
        );

        // Default is preserve; checkbox is the inverse ("set to now").
        if (setMtimeToNowCheckbox != null) {
            prefs.setPreserveFileModificationTime(
                !setMtimeToNowCheckbox.getSelection()
            );
        }

        // Commit move settings only after validation succeeded
        prefs.setDestinationDirectory(destDirTextValue);
        prefs.setMoveSelected(moveSelected);

        prefs.setRenameSelected(renameSelectedCheckbox.getSelection());

        prefs.setPreferDvdOrderIfPresent(preferDvdOrderCheckbox.getSelection());

        ThemeMode selectedTheme = ThemeMode.fromString(
            themeModeCombo.getText()
        );
        if (selectedTheme == null) {
            selectedTheme = ThemeMode.LIGHT;
        }
        prefs.setThemeMode(selectedTheme);

        // Show name overrides (exact match, case-insensitive)
        // Note: column 0 is the status icon column; values are in columns 1 and 2.
        Map<String, String> overrides = new LinkedHashMap<>();
        if (overridesTable != null && !overridesTable.isDisposed()) {
            for (TableItem ti : overridesTable.getItems()) {
                String from = ti.getText(1).trim();
                String to = ti.getText(2).trim();
                if (!from.isEmpty() && !to.isEmpty()) {
                    overrides.put(from, to);
                }
            }
        }
        prefs.setShowNameOverrides(overrides);

        // Show disambiguations (query string -> series id)
        // Note: column 0 is the status icon column; values are in columns 1 and 2.
        Map<String, String> disambiguations = new LinkedHashMap<>();
        if (
            disambiguationsTable != null && !disambiguationsTable.isDisposed()
        ) {
            for (TableItem ti : disambiguationsTable.getItems()) {
                String from = ti.getText(1).trim();
                String to = ti.getText(2).trim();
                if (!from.isEmpty() && !to.isEmpty()) {
                    disambiguations.put(from, to);
                }
            }
        }
        prefs.setShowDisambiguationOverrides(disambiguations);

        UserPreferences.store(prefs);
        return true;
    }

    private boolean matchingIsSaveable() {
        // Save gating (Matching tab):
        // - Block save if any dirty row is invalid/incomplete/validating
        // - Otherwise allow save (even if there are zero dirty rows), so a user can still
        //   save other preference changes without being blocked by Matching validation.
        //
        // Note: The Save button's enabled state must be recomputed after each validation
        // transition and after add/remove operations.
        return !hasInvalidDirtyMatchingRows();
    }

    private void updateSaveEnabledFromMatchingValidation() {
        if (saveButton == null || saveButton.isDisposed()) {
            return;
        }

        saveButton.setEnabled(matchingIsSaveable());
    }

    private boolean hasInvalidDirtyMatchingRows() {
        return (
            hasInvalidDirtyMatchingRows(overridesTable) ||
            hasInvalidDirtyMatchingRows(disambiguationsTable)
        );
    }

    private boolean hasInvalidDirtyMatchingRows(final Table table) {
        if (table == null || table.isDisposed()) {
            return false;
        }
        for (TableItem ti : table.getItems()) {
            boolean dirty = Boolean.TRUE.equals(ti.getData(MATCHING_DIRTY_KEY));
            if (!dirty) {
                continue;
            }
            String status = safeCell(ti, 0).trim();
            org.eclipse.swt.graphics.Image img = ti.getImage(0);

            // Dirty rows must be validated; block save for blank/incomplete or if still validating/error.
            //
            // Column 0 text is intentionally kept blank for OK/ERROR states (we show an icon instead).
            // Therefore, empty status text is only a blocker if the row is not explicitly OK yet.
            if (MATCHING_STATUS_INCOMPLETE.equals(status)) {
                return true;
            }

            if (img == MATCHING_ICON_VALIDATING) {
                return true;
            }
            if (img == MATCHING_ICON_ERROR) {
                return true;
            }

            if (status.isEmpty() && img != MATCHING_ICON_OK) {
                return true;
            }
        }
        return false;
    }

    private enum MatchingRowType {
        OVERRIDE,
        DISAMBIGUATION,
    }

    private void validateMatchingRowOnline(
        final Table table,
        final int idx,
        final MatchingRowType type
    ) {
        if (table == null || table.isDisposed()) {
            return;
        }
        if (idx < 0 || idx >= table.getItemCount()) {
            return;
        }
        final TableItem ti = table.getItem(idx);

        // Mark dirty + assign token to ignore stale validation results.
        ti.setData(MATCHING_DIRTY_KEY, Boolean.TRUE);
        final long token = ++matchingValidationSeq;
        ti.setData(MATCHING_VALIDATE_TOKEN_KEY, Long.valueOf(token));

        // Column 0 is the status icon column; real key/value are columns 1 and 2.
        final String key = safeCell(ti, 1).trim();
        final String val = safeCell(ti, 2).trim();

        if (key.isEmpty() || val.isEmpty()) {
            ti.setText(0, MATCHING_STATUS_INCOMPLETE);
            ti.setImage(0, MATCHING_ICON_ERROR);
            updateSaveEnabledFromMatchingValidation();
            return;
        }

        // Show validating state immediately and start animation.
        setRowValidating(table, ti, token);
        updateSaveEnabledFromMatchingValidation();

        // Run provider checks off the UI thread (daemon so it won't block shutdown).
        Thread validateThread = new Thread(
            () -> {
                ValidationResult result;
                try {
                    result = validateViaProvider(type, key, val);
                } catch (Exception ex) {
                    logger.log(
                        Level.INFO,
                        "Matching validation failed (exception): type=" +
                            type +
                            ", key=" +
                            key,
                        ex
                    );
                    result = ValidationResult.invalid(
                        "Cannot validate (provider error)"
                    );
                }

                final ValidationResult finalResult = result;

                Display display = (preferencesShell != null)
                    ? preferencesShell.getDisplay()
                    : Display.getDefault();

                if (display == null || display.isDisposed()) {
                    return;
                }

                display.asyncExec(() -> {
                    if (table.isDisposed() || ti.isDisposed()) {
                        return;
                    }
                    Object tokObj = ti.getData(MATCHING_VALIDATE_TOKEN_KEY);
                    if (!(tokObj instanceof Long)) {
                        return;
                    }
                    long currentToken = ((Long) tokObj).longValue();
                    if (currentToken != token) {
                        // stale result; ignore
                        return;
                    }

                    if (finalResult.valid) {
                        ti.setText(0, "");
                        ti.setImage(0, MATCHING_ICON_OK);
                    } else {
                        ti.setText(0, "");
                        ti.setImage(0, MATCHING_ICON_ERROR);
                    }

                    // Store the validation message for tooltip rendering.
                    String msg = finalResult.message;
                    if (msg == null) {
                        msg = "";
                    }
                    ti.setData("tvrenamer.matching.validationMessage", msg);

                    // TableItem does not support tooltips; PreferencesDialog uses a shared tooltip label
                    // that is updated on hover (see Matching tab hover listener).
                    updateSaveEnabledFromMatchingValidation();
                });
            },
            "tvrenamer-matching-validate"
        );
        validateThread.setDaemon(true);
        validateThread.start();
    }

    private static final class ValidationResult {

        final boolean valid;
        final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = (message == null) ? "" : message;
        }

        static ValidationResult ok(String msg) {
            return new ValidationResult(true, msg);
        }

        static ValidationResult invalid(String msg) {
            return new ValidationResult(false, msg);
        }
    }

    private ValidationResult validateViaProvider(
        final MatchingRowType type,
        final String key,
        final String val
    ) {
        if (type == MatchingRowType.DISAMBIGUATION) {
            // key=query string, val=series id
            String queryString = key;
            String seriesId = val;

            ShowName sn = ShowName.mapShowName(queryString);
            try {
                TheTVDBProvider.getShowOptions(sn);
            } catch (Exception e) {
                return ValidationResult.invalid(
                    "Cannot validate (provider unavailable)"
                );
            }

            java.util.List<ShowOption> options = sn.getShowOptions();
            if (options == null || options.isEmpty()) {
                return ValidationResult.invalid("No matches");
            }

            for (ShowOption opt : options) {
                if (opt != null && seriesId.equals(opt.getIdString())) {
                    return ValidationResult.ok("Pinned match is valid");
                }
            }
            return ValidationResult.invalid("ID not found in results");
        }

        // OVERRIDE: key=extracted show, val=replacement text
        String replacementText = val;

        // Pragmatic semantics: treat replacementText as the "extracted name" input to selection.
        // Simulate pipeline: replacementText -> query string -> provider options -> selection decision.
        String queryString = StringUtils.makeQueryString(replacementText);

        ShowName sn = ShowName.mapShowName(replacementText);
        try {
            TheTVDBProvider.getShowOptions(sn);
        } catch (Exception e) {
            return ValidationResult.invalid(
                "Cannot validate (provider unavailable)"
            );
        }

        java.util.List<ShowOption> options = sn.getShowOptions();
        if (options == null || options.isEmpty()) {
            return ValidationResult.invalid("No matches");
        }

        String pinnedId = prefs.resolveDisambiguatedSeriesId(queryString);
        ShowSelectionEvaluator.Decision decision =
            ShowSelectionEvaluator.evaluate(replacementText, options, pinnedId);

        if (decision.isResolved()) {
            String msg = decision.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = "Resolves";
            }
            return ValidationResult.ok(msg);
        }
        if (decision.isNotFound()) {
            return ValidationResult.invalid(decision.getMessage());
        }
        // Ambiguous: runtime would prompt.
        return ValidationResult.invalid(decision.getMessage());
    }

    private void setRowValidating(
        final Table table,
        final TableItem ti,
        final long token
    ) {
        if (table.isDisposed() || ti.isDisposed()) {
            return;
        }
        // initial label
        ti.setText(0, "");
        ti.setImage(0, MATCHING_ICON_VALIDATING);

        Display display = table.getDisplay();
        display.timerExec(
            200,
            new Runnable() {
                @Override
                public void run() {
                    if (table.isDisposed() || ti.isDisposed()) {
                        return;
                    }
                    Object tokObj = ti.getData(MATCHING_VALIDATE_TOKEN_KEY);
                    if (!(tokObj instanceof Long)) {
                        return;
                    }
                    long currentToken = ((Long) tokObj).longValue();
                    if (currentToken != token) {
                        return; // replaced/stale
                    }

                    // Stop animating once validation completes (icon no longer "clock").
                    org.eclipse.swt.graphics.Image img = ti.getImage(0);
                    if (img != MATCHING_ICON_VALIDATING) {
                        return;
                    }

                    // Keep clock icon; re-schedule to continue while validating.
                    display.timerExec(200, this);
                }
            }
        );
    }

    private static String safeCell(final TableItem ti, final int col) {
        if (ti == null) {
            return "";
        }
        try {
            String s = ti.getText(col);
            return (s == null) ? "" : s;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Creates and opens the preferences dialog, and runs the event loop.
     *
     */
    public void open() {
        // Create the dialog window

        preferencesShell = new Shell(parent, getStyle());

        preferencesShell.setText(PREFERENCES_LABEL);

        themePalette = ThemeManager.createPalette(
            preferencesShell.getDisplay()
        );
        ThemeManager.applyPalette(preferencesShell, themePalette);

        // Add the contents of the dialog window

        createContents();

        // Apply initial state if the dialog was opened with pre-filled data.
        if (initialTabIndex >= 0
                && initialTabIndex < tabFolder.getItemCount()) {
            tabFolder.setSelection(initialTabIndex);
        }
        if (initialOverrideFromText != null
                && !initialOverrideFromText.isEmpty()
                && overridesFromText != null) {
            overridesFromText.setText(initialOverrideFromText);
            if (overridesToText != null) {
                overridesToText.setFocus();
            }
        }

        preferencesShell.pack();

        // Center over the parent (main window) so it doesn't appear in an OS-random place.
        // Zero offset for exact centering; clamped to parent's monitor work area.
        DialogPositioning.positionDialog(preferencesShell, parent, 0, 0);

        preferencesShell.open();
        DialogHelper.runModalLoop(preferencesShell);
    }

    /**
     * Opens the preferences dialog, auto-selecting the given tab and
     * optionally pre-filling the "Extracted show" override text field.
     *
     * @param tabIndex
     *     the zero-based tab index to select (0 = General, 1 = Rename, 2 = Matching)
     * @param overrideFromText
     *     if non-null/non-empty, pre-fills the "Extracted show" text field
     *     on the Matching tab
     */
    public void open(final int tabIndex, final String overrideFromText) {
        this.initialTabIndex = tabIndex;
        this.initialOverrideFromText = overrideFromText;
        open();
    }

    /**
     * PreferencesDialog constructor
     *
     * @param parent
     *            the parent {@link Shell}
     */
    public PreferencesDialog(final Shell parent) {
        super(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        this.parent = parent;
        statusLabel = new StatusLabel();
    }
}
