package org.tvrenamer.view;

import static org.tvrenamer.model.util.Constants.*;

import java.util.logging.Logger;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Dialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.tvrenamer.controller.UpdateChecker;
import org.tvrenamer.controller.UrlLauncher;
import org.tvrenamer.model.util.Environment;

/**
 * The About Dialog box.
 */
final class AboutDialog extends Dialog {

    private static final Logger logger = Logger.getLogger(
        AboutDialog.class.getName()
    );

    private final UIStarter ui;
    private Shell aboutShell;
    private Font titleFont;
    private Font versionFont;
    private org.eclipse.swt.graphics.Image aboutIcon;

    /**
     * Static inner class to check if there's an update available
     */
    private class UpdateNotifier extends SelectionAdapter {

        /**
         * The link has been clicked.
         *
         * @param arg0
         *    the event object itself; not used
         */
        @Override
        public void widgetSelected(SelectionEvent arg0) {
            UpdateChecker.notifyOfUpdate(updateIsAvailable -> {
                if (updateIsAvailable) {
                    logger.fine(NEW_VERSION_AVAILABLE);
                    ui.showMessageBox(
                        SWTMessageBoxType.DLG_OK,
                        NEW_VERSION_TITLE,
                        NEW_VERSION_AVAILABLE
                    );
                } else {
                    ui.showMessageBox(
                        SWTMessageBoxType.DLG_WARN,
                        NO_NEW_VERSION_TITLE,
                        NO_NEW_VERSION_AVAILABLE
                    );
                }
            });
        }
    }

    /**
     * AboutDialog constructor
     *
     * @param ui
     *            the parent {@link UIStarter}
     */
    public AboutDialog(final UIStarter ui) {
        super(ui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        this.ui = ui;
    }

    public void open() {
        // Create the dialog window
        aboutShell = new Shell(getParent(), getStyle());
        aboutShell.setText(ABOUT_LABEL);
        aboutIcon = UIStarter.readImageFromPath(APPLICATION_ICON_PATH);
        aboutShell.setImage(aboutIcon);

        // Add the contents of the dialog window
        createContents();

        aboutShell.addListener(SWT.Dispose, e -> {
            if (titleFont != null && !titleFont.isDisposed()) {
                titleFont.dispose();
            }
            if (versionFont != null && !versionFont.isDisposed()) {
                versionFont.dispose();
            }
            if (aboutIcon != null && !aboutIcon.isDisposed()) {
                aboutIcon.dispose();
            }
        });

        aboutShell.pack();

        // Position relative to the parent (main window) so it doesn't appear in an OS-random place.
        DialogPositioning.positionDialog(aboutShell, getParent());

        aboutShell.open();
        DialogHelper.runModalLoop(aboutShell);
    }

    /**
     * Creates the grid layout
     *
     */
    private void createGridLayout() {
        GridLayout shellGridLayout = new GridLayout();
        shellGridLayout.numColumns = 2;
        shellGridLayout.marginRight = 15;
        shellGridLayout.marginBottom = 5;
        aboutShell.setLayout(shellGridLayout);
    }

    /**
     * Creates the labels
     *
     */
    private void createLabels() {
        Label iconLabel = new Label(aboutShell, SWT.NONE);
        GridData iconGridData = new GridData();
        iconGridData.verticalAlignment = GridData.FILL;
        iconGridData.horizontalAlignment = GridData.FILL;
        // Force the icon to take up the whole of the right column
        iconGridData.verticalSpan = 10;
        iconGridData.grabExcessVerticalSpace = false;
        iconGridData.grabExcessHorizontalSpace = false;
        iconLabel.setLayoutData(iconGridData);
        iconLabel.setImage(aboutIcon);

        Label applicationLabel = new Label(aboutShell, SWT.NONE);
        FontData defaultFont = ui.getDefaultSystemFont();
        titleFont = new Font(
            aboutShell.getDisplay(),
            defaultFont.getName(),
            defaultFont.getHeight() + 4,
            SWT.BOLD
        );
        applicationLabel.setFont(titleFont);
        applicationLabel.setText(APPLICATION_DISPLAY_NAME);
        applicationLabel.setLayoutData(
            new GridData(GridData.BEGINNING, GridData.CENTER, true, true)
        );

        Label versionLabel = new Label(aboutShell, SWT.NONE);
        versionFont = new Font(
            aboutShell.getDisplay(),
            defaultFont.getName(),
            defaultFont.getHeight() + 2,
            SWT.BOLD
        );
        versionLabel.setFont(versionFont);

        versionLabel.setText(VERSION_LABEL);
        versionLabel.setLayoutData(
            new GridData(GridData.BEGINNING, GridData.CENTER, true, true)
        );

        String builtOn = Environment.readBuildDateYYMMDD();
        if (builtOn != null && !builtOn.isBlank()) {
            Label builtOnLabel = new Label(aboutShell, SWT.NONE);
            builtOnLabel.setLayoutData(
                new GridData(GridData.BEGINNING, GridData.CENTER, true, true)
            );
            builtOnLabel.setText("Built on: " + builtOn);
        }

        Label descriptionLabel = new Label(aboutShell, SWT.NONE);
        descriptionLabel.setLayoutData(
            new GridData(GridData.BEGINNING, GridData.CENTER, true, true)
        );
        descriptionLabel.setText(TVRENAMER_DESCRIPTION);
    }

    /**
     * Utility method for creating a URL link.
     *
     * SWT allows very generic links, that could do any arbitrary action when clicked,
     * but we just one basic ones that have a URL and open it when clicked.
     *
     * @param intro
     *          text to place before the link; can be empty, but not null
     * @param url
     *          the URL to link to
     * @param label
     *          the text to use for the link
     */
    private void createUrlLink(String intro, String url, String label) {
        final Link link = new Link(aboutShell, SWT.NONE);
        link.setText(intro + "<a href=\"" + url + "\">" + label + "</a>");
        link.setLayoutData(
            new GridData(GridData.BEGINNING, GridData.CENTER, true, true)
        );
        link.addSelectionListener(new UrlLauncher(url));
    }

    private void createPipeSeparatedLinkGroup(
        final String label1,
        final String url1,
        final String label2,
        final String url2
    ) {
        createPipeSeparatedLinkGroup(label1, url1, label2, url2, null, null);
    }

    private void createPipeSeparatedLinkGroup(
        final String label1,
        final String url1,
        final String label2,
        final String url2,
        final String label3,
        final String url3
    ) {
        final Link link = new Link(aboutShell, SWT.NONE);

        String text =
            "<a href=\"" +
            url1 +
            "\">" +
            label1 +
            "</a> | <a href=\"" +
            url2 +
            "\">" +
            label2 +
            "</a>";

        if (label3 != null && url3 != null) {
            text = text + " | <a href=\"" + url3 + "\">" + label3 + "</a>";
        }

        link.setText(text);
        link.setLayoutData(
            new GridData(GridData.BEGINNING, GridData.CENTER, true, true)
        );
        link.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    if (e == null || e.text == null) {
                        return;
                    }
                    String href = e.text;
                    if (
                        href.startsWith("http://") ||
                        href.startsWith("https://")
                    ) {
                        new UrlLauncher(href).widgetSelected(e);
                    }
                }
            }
        );
    }

    /**
     * Creates the links
     *
     */
    private void createLinks() {
        createUrlLink(LICENSE_TEXT_1, TVRENAMER_LICENSE_URL, LICENSE_TEXT_2);

        createPipeSeparatedLinkGroup(
            PROJECT_PAGE,
            TVRENAMER_PROJECT_URL,
            ISSUE_TRACKER,
            TVRENAMER_ISSUES_URL,
            SOURCE_CODE_LINK,
            TVRENAMER_REPOSITORY_URL
        );

        createPipeSeparatedLinkGroup(
            "Original Project Page",
            ORIGINAL_PROJECT_REPOSITORY_URL,
            "Original Web Site",
            ORIGINAL_PROJECT_WEBSITE_URL
        );
    }

    /**
     * Creates the buttons
     *
     */
    private void createButtons() {
        Button updateCheckButton = new Button(aboutShell, SWT.PUSH);
        updateCheckButton.setText(UPDATE_TEXT);
        GridData gridDataUpdateCheck = new GridData();
        gridDataUpdateCheck.widthHint = 160;
        gridDataUpdateCheck.horizontalAlignment = GridData.END;
        updateCheckButton.setLayoutData(gridDataUpdateCheck);
        updateCheckButton.addSelectionListener(new UpdateNotifier());

        Button okButton = new Button(aboutShell, SWT.PUSH);
        okButton.setText(OK_LABEL);
        GridData gridDataOK = new GridData();
        gridDataOK.widthHint = 160;
        gridDataOK.horizontalAlignment = GridData.END;
        okButton.setLayoutData(gridDataOK);
        okButton.setFocus();

        okButton.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    aboutShell.close();
                }
            }
        );

        // Set the OK button as the default, so
        // user can press Enter to dismiss
        aboutShell.setDefaultButton(okButton);
    }

    /**
     * Creates the dialog's contents.
     *
     */
    private void createContents() {
        createGridLayout();
        createLabels();
        createLinks();
        createButtons();
    }
}
