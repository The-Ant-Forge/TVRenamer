package org.tvrenamer.model;

import static org.tvrenamer.model.util.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.tvrenamer.controller.UserPreferencesPersistence;
import org.tvrenamer.controller.util.FileUtilities;

public class UserPreferences {

    private static final Logger logger = Logger.getLogger(
        UserPreferences.class.getName()
    );

    private static final UserPreferences INSTANCE = load();

    private final java.beans.PropertyChangeSupport pcs =
        new java.beans.PropertyChangeSupport(this);

    public void addPropertyChangeListener(
        java.beans.PropertyChangeListener listener
    ) {
        pcs.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(
        java.beans.PropertyChangeListener listener
    ) {
        pcs.removePropertyChangeListener(listener);
    }

    private final String preloadFolder;
    private transient Path destDirPath;
    private String destDir;
    private String seasonPrefix;
    private boolean seasonPrefixLeadingZero;
    private boolean moveSelected;
    private boolean renameSelected;
    private boolean removeEmptiedDirectories;
    private boolean deleteRowAfterMove;
    private String renameReplacementMask;
    private boolean checkForUpdates;
    private ThemeMode themeMode;
    private boolean recursivelyAddFolders;

    // If true (default), preserve the original file modification time when moving/renaming.
    // If false, the mover may set the destination file's modification time to "now".
    private boolean preserveFileModificationTime = true;

    // Prefer DVD ordering/titles if present; fall back to aired ordering otherwise.
    private boolean preferDvdOrderIfPresent = false;

    // If true, overwrite existing destination files instead of creating versioned suffixes (1), (2).
    // Default is false (safe behavior).
    private boolean alwaysOverwriteDestination = false;

    // If true, after a successful move, scan destination for duplicate video files
    // (same base name, different extension) and move them to a duplicates folder.
    private boolean cleanupDuplicateVideoFiles = false;

    // If true, after a successful move, write TV metadata (show, season, episode, title)
    // to supported video files (MP4/M4V).
    private boolean tagVideoMetadata = false;

    // Fun metric: count of files successfully processed (renamed and/or moved).
    // Persisted in prefs.xml. This should only be incremented once per successfully
    // processed TableItem to avoid double-counting rename+move operations.
    private long processedFileCount = 0L;

    // For the ignore keywords, we do some processing. So we also preserve exactly
    // what the user specified.
    private transient String specifiedIgnoreKeywords;
    private final List<String> ignoreKeywords;

    // Show name overrides: exact match with case-insensitive comparison at lookup time.
    // Stored in prefs.xml to avoid a separate overrides.xml file.
    // Uses ConcurrentHashMap for safe iteration while other threads may update.
    private final Map<String, String> showNameOverrides =
        new java.util.concurrent.ConcurrentHashMap<>();

    // Show disambiguation overrides: map a provider query string (normalized) to a chosen
    // provider series ID (e.g., TVDB seriesid). This allows us to persist user selections
    // when a provider search returns multiple close matches (e.g., "Star" vs "Star (2024)").
    // Stored in prefs.xml alongside other preferences.
    // Uses ConcurrentHashMap for safe iteration while other threads may update.
    private final Map<String, String> showDisambiguationOverrides =
        new java.util.concurrent.ConcurrentHashMap<>();

    private transient boolean destDirProblem = false;

    /**
     * UserPreferences constructor which uses the defaults from
     * {@link org.tvrenamer.model.util.Constants}
     */
    private UserPreferences() {
        preloadFolder = null;
        destDirPath = DEFAULT_DESTINATION_DIRECTORY;
        destDir = destDirPath.toString();
        seasonPrefix = DEFAULT_SEASON_PREFIX;
        seasonPrefixLeadingZero = false;
        moveSelected = false;
        renameSelected = true;
        removeEmptiedDirectories = true;
        deleteRowAfterMove = false;
        renameReplacementMask = DEFAULT_REPLACEMENT_MASK;
        checkForUpdates = true;
        themeMode = ThemeMode.LIGHT;
        recursivelyAddFolders = true;

        // Default: preserving timestamps is less surprising since the file contents
        // are unchanged by a rename/move.
        preserveFileModificationTime = true;

        preferDvdOrderIfPresent = false;
        processedFileCount = 0L;
        ignoreKeywords = new ArrayList<>();
        ignoreKeywords.add(DEFAULT_IGNORED_KEYWORD);
        buildIgnoredKeywordsString();
        destDirProblem = false;

        // no default show name overrides
    }

    /**
     * @return the singleton UserPreferences instance for this application
     */
    public static UserPreferences getInstance() {
        return INSTANCE;
    }

    /**
     * Create a UserPreferences instance from parsed XML data.
     * Fields not present in the map retain their default values.
     * This is used by UserPreferencesPersistence to construct an instance
     * without firing PropertyChangeSupport events.
     *
     * @param scalars map of field names to string values
     * @param keywords list of ignore keywords (null = use defaults)
     * @param nameOverrides show name overrides map (null = empty)
     * @param disambigOverrides show disambiguation overrides map (null = empty)
     * @return a populated UserPreferences instance
     */
    public static UserPreferences fromParsedXml(
        Map<String, String> scalars,
        List<String> keywords,
        Map<String, String> nameOverrides,
        Map<String, String> disambigOverrides
    ) {
        UserPreferences p = new UserPreferences();

        if (scalars == null) {
            return p;
        }

        String val;

        val = scalars.get("destDir");
        if (val != null) {
            p.destDir = val;
            p.destDirPath = Paths.get(val);
        }

        val = scalars.get("seasonPrefix");
        if (val != null) {
            p.seasonPrefix = val;
        }

        val = scalars.get("seasonPrefixLeadingZero");
        if (val != null) {
            p.seasonPrefixLeadingZero = Boolean.parseBoolean(val);
        }

        // Handle legacy alias: moveEnabled -> moveSelected
        val = scalars.get("moveSelected");
        if (val == null) {
            val = scalars.get("moveEnabled");
        }
        if (val != null) {
            p.moveSelected = Boolean.parseBoolean(val);
        }

        // Handle legacy alias: renameEnabled -> renameSelected
        val = scalars.get("renameSelected");
        if (val == null) {
            val = scalars.get("renameEnabled");
        }
        if (val != null) {
            p.renameSelected = Boolean.parseBoolean(val);
        }

        val = scalars.get("removeEmptiedDirectories");
        if (val != null) {
            p.removeEmptiedDirectories = Boolean.parseBoolean(val);
        }

        val = scalars.get("deleteRowAfterMove");
        if (val != null) {
            p.deleteRowAfterMove = Boolean.parseBoolean(val);
        }

        val = scalars.get("renameReplacementMask");
        if (val != null) {
            p.renameReplacementMask = val;
        }

        val = scalars.get("checkForUpdates");
        if (val != null) {
            p.checkForUpdates = Boolean.parseBoolean(val);
        }

        // Handle legacy alias: theme -> themeMode
        val = scalars.get("themeMode");
        if (val == null) {
            val = scalars.get("theme");
        }
        if (val != null) {
            try {
                p.themeMode = ThemeMode.valueOf(val);
            } catch (IllegalArgumentException e) {
                logger.warning("Unknown theme mode: " + val + "; defaulting to LIGHT");
                p.themeMode = ThemeMode.LIGHT;
            }
        }

        val = scalars.get("recursivelyAddFolders");
        if (val != null) {
            p.recursivelyAddFolders = Boolean.parseBoolean(val);
        }

        val = scalars.get("preserveFileModificationTime");
        if (val != null) {
            p.preserveFileModificationTime = Boolean.parseBoolean(val);
        }

        val = scalars.get("preferDvdOrderIfPresent");
        if (val != null) {
            p.preferDvdOrderIfPresent = Boolean.parseBoolean(val);
        }

        val = scalars.get("alwaysOverwriteDestination");
        if (val != null) {
            p.alwaysOverwriteDestination = Boolean.parseBoolean(val);
        }

        val = scalars.get("cleanupDuplicateVideoFiles");
        if (val != null) {
            p.cleanupDuplicateVideoFiles = Boolean.parseBoolean(val);
        }

        val = scalars.get("tagVideoMetadata");
        if (val != null) {
            p.tagVideoMetadata = Boolean.parseBoolean(val);
        }

        val = scalars.get("processedFileCount");
        if (val != null) {
            try {
                p.processedFileCount = Long.parseLong(val);
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }

        if (keywords != null && !keywords.isEmpty()) {
            p.ignoreKeywords.clear();
            p.ignoreKeywords.addAll(keywords);
        }
        p.buildIgnoredKeywordsString();

        if (nameOverrides != null) {
            p.showNameOverrides.putAll(nameOverrides);
        }

        if (disambigOverrides != null) {
            p.showDisambiguationOverrides.putAll(disambigOverrides);
        }

        return p;
    }

    /**
     * Resolve a show name by applying user-defined overrides.
     * Matching is case-insensitive exact match.
     *
     * @param extractedShowName show name extracted from the filename
     * @return overridden show name if configured, otherwise the original input
     */
    public String resolveShowName(final String extractedShowName) {
        if (extractedShowName == null) {
            return null;
        }

        for (Map.Entry<String, String> entry : showNameOverrides.entrySet()) {
            String from = entry.getKey();
            if (from != null && from.equalsIgnoreCase(extractedShowName)) {
                String to = entry.getValue();
                if (to != null && !to.isBlank()) {
                    return to;
                }
            }
        }

        return extractedShowName;
    }

    /**
     * Replace the current set of show name overrides.
     *
     * @param overrides mapping of extracted name -> corrected name
     */
    public void setShowNameOverrides(final Map<String, String> overrides) {
        showNameOverrides.clear();
        if (overrides != null) {
            showNameOverrides.putAll(overrides);
        }
        preferenceChanged(UserPreference.SHOW_NAME_OVERRIDES);
    }

    /**
     * Return the live map of show name overrides.
     *
     * @return mapping of extracted name -> corrected name
     */
    public Map<String, String> getShowNameOverrides() {
        return showNameOverrides;
    }

    /**
     * Replace the current set of show disambiguation overrides.
     *
     * @param overrides mapping of normalized provider query string -> chosen provider series id
     */
    public void setShowDisambiguationOverrides(
        final Map<String, String> overrides
    ) {
        showDisambiguationOverrides.clear();
        if (overrides != null) {
            showDisambiguationOverrides.putAll(overrides);
        }
        // No dedicated preference enum yet; treat as show override change for refresh purposes.
        preferenceChanged(UserPreference.SHOW_NAME_OVERRIDES);
    }

    /**
     * Return the live map of show disambiguation overrides.
     *
     * @return mapping of normalized provider query string -> chosen provider series id
     */
    public Map<String, String> getShowDisambiguationOverrides() {
        return showDisambiguationOverrides;
    }

    /**
     * Try to resolve a provider query string to a chosen provider series id using
     * the persisted disambiguation map.
     *
     * @param providerQueryString normalized query string (e.g., ShowName.getQueryString())
     * @return the chosen provider series id, or null if not configured
     */
    public String resolveDisambiguatedSeriesId(
        final String providerQueryString
    ) {
        if (providerQueryString == null) {
            return null;
        }
        String key = providerQueryString.trim();
        if (key.isEmpty()) {
            return null;
        }

        // Query strings are already normalized, but we keep this case-insensitive
        // to be robust across future normalization changes.
        for (Map.Entry<
            String,
            String
        > entry : showDisambiguationOverrides.entrySet()) {
            String from = entry.getKey();
            if (from != null && from.equalsIgnoreCase(key)) {
                String to = entry.getValue();
                if (to != null && !to.isBlank()) {
                    return to;
                }
            }
        }
        return null;
    }

    /**
     * Whether to preserve the original file modification time when moving/renaming.
     *
     * Default: true (preserve), since the file contents are not changed by a rename/move.
     */
    public boolean isPreserveFileModificationTime() {
        return preserveFileModificationTime;
    }

    /**
     * Set whether to preserve the original file modification time when moving/renaming.
     *
     * @param preserve true to preserve original mtime; false to set mtime to "now"
     */
    public void setPreserveFileModificationTime(boolean preserve) {
        if (valuesAreDifferent(this.preserveFileModificationTime, preserve)) {
            this.preserveFileModificationTime = preserve;
            preferenceChanged(UserPreference.FILE_MTIME_POLICY);
        }
    }

    /**
     * Whether to prefer DVD ordering/titles when available (falls back to aired ordering otherwise).
     *
     * This applies to future lookups only; existing table entries are not re-queried.
     */
    public boolean isPreferDvdOrderIfPresent() {
        return preferDvdOrderIfPresent;
    }

    /**
     * @return total number of files successfully processed (renamed and/or moved)
     */
    public long getProcessedFileCount() {
        return processedFileCount;
    }

    /**
     * Increment the processed file counter.
     *
     * @param delta number of successfully processed files to add (ignored if <= 0)
     */
    public void incrementProcessedFileCount(long delta) {
        if (delta <= 0) {
            return;
        }
        processedFileCount += delta;
    }

    /**
     * Set whether to prefer DVD ordering/titles when available (falls back to aired ordering otherwise).
     *
     * This applies to future lookups only; existing table entries are not re-queried.
     */
    public void setPreferDvdOrderIfPresent(boolean prefer) {
        if (valuesAreDifferent(this.preferDvdOrderIfPresent, prefer)) {
            this.preferDvdOrderIfPresent = prefer;
            preferenceChanged(UserPreference.PREFER_DVD_ORDER);
        }
    }

    /**
     * @return true if existing destination files should be overwritten instead
     *         of creating versioned suffixes like (1), (2)
     */
    public boolean isAlwaysOverwriteDestination() {
        return alwaysOverwriteDestination;
    }

    /**
     * @param overwrite true to overwrite existing destination files,
     *                  false to create versioned suffixes
     */
    public void setAlwaysOverwriteDestination(boolean overwrite) {
        if (valuesAreDifferent(this.alwaysOverwriteDestination, overwrite)) {
            this.alwaysOverwriteDestination = overwrite;
            preferenceChanged(UserPreference.OVERWRITE_DESTINATION);
        }
    }

    /**
     * @return true if duplicate video files (same base name, different extension)
     *         should be moved to a duplicates folder after move
     */
    public boolean isCleanupDuplicateVideoFiles() {
        return cleanupDuplicateVideoFiles;
    }

    /**
     * @param cleanup true to enable duplicate cleanup, false to disable
     */
    public void setCleanupDuplicateVideoFiles(boolean cleanup) {
        if (valuesAreDifferent(this.cleanupDuplicateVideoFiles, cleanup)) {
            this.cleanupDuplicateVideoFiles = cleanup;
            preferenceChanged(UserPreference.CLEANUP_DUPLICATES);
        }
    }

    /**
     * @return true if video files should be tagged with TV metadata (show, season,
     *         episode, title) after move
     */
    public boolean isTagVideoMetadata() {
        return tagVideoMetadata;
    }

    /**
     * @param tag true to enable metadata tagging, false to disable
     */
    public void setTagVideoMetadata(boolean tag) {
        if (valuesAreDifferent(this.tagVideoMetadata, tag)) {
            this.tagVideoMetadata = tag;
            preferenceChanged(UserPreference.TAG_VIDEO_METADATA);
        }
    }

    /**
     * Return the current theme preference.
     *
     * @return selected ThemeMode
     */
    public ThemeMode getThemeMode() {
        return themeMode;
    }

    /**
     * Set the theme preference. Null inputs default to LIGHT.
     *
     * @param mode the desired ThemeMode
     */
    public void setThemeMode(ThemeMode mode) {
        ThemeMode resolved = (mode == null) ? ThemeMode.LIGHT : mode;
        if (valuesAreDifferent(this.themeMode, resolved)) {
            this.themeMode = resolved;
            preferenceChanged(UserPreference.THEME_MODE);
        }
    }

    // Overrides used to be stored in a separate overrides.xml file.
    // This fork stores overrides directly in prefs.xml (UserPreferences) instead.
    // The legacy setup/migration logic is intentionally removed to avoid creating
    // or maintaining overrides.xml going forward.

    /**
     * Save preferences to xml file
     *
     * @param prefs the instance to export to XML
     */
    @SuppressWarnings("SameParameterValue")
    public static void store(UserPreferences prefs) {
        UserPreferencesPersistence.persist(prefs, PREFERENCES_FILE);
        logger.fine("Successfully saved/updated preferences");
    }

    /**
     * Deal with legacy files and set up
     */
    private static void initialize() {
        logger.log(Level.FINE,
            () -> "configuration directory = " +
                CONFIGURATION_DIRECTORY.toAbsolutePath()
        );

        // Ensure configuration directory exists and is writable.
        if (!FileUtilities.ensureWritableDirectory(CONFIGURATION_DIRECTORY)) {
            throw new RuntimeException(
                "Could not create configuration directory: " +
                    CONFIGURATION_DIRECTORY
            );
        }

        // If CONFIGURATION_DIRECTORY exists but is not a directory, relocate it (legacy behavior).
        if (
            Files.exists(CONFIGURATION_DIRECTORY) &&
            !Files.isDirectory(CONFIGURATION_DIRECTORY)
        ) {
            try {
                Path tempDir = Files.createTempDirectory(APPLICATION_NAME);
                // Replace temp directory with the legacy file contents
                Files.deleteIfExists(tempDir);
                Files.move(CONFIGURATION_DIRECTORY, tempDir);

                // Legacy behavior appears to treat this as an old prefs file; move it into place.
                if (Files.notExists(PREFERENCES_FILE)) {
                    Files.move(tempDir, PREFERENCES_FILE);
                } else {
                    logger.warning(
                        "Legacy configuration file found but preferences file already exists; leaving legacy file at: " +
                            tempDir
                    );
                }
            } catch (IOException | SecurityException e) {
                throw new RuntimeException(
                    "Could not relocate legacy configuration file at " +
                        CONFIGURATION_DIRECTORY,
                    e
                );
            }
        }

        // Legacy preferences file -> new preferences file
        if (Files.exists(PREFERENCES_FILE_LEGACY)) {
            if (Files.exists(PREFERENCES_FILE)) {
                throw new RuntimeException(
                    "Found two legacy preferences files!!"
                );
            }
            try {
                Path parent = PREFERENCES_FILE.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.move(PREFERENCES_FILE_LEGACY, PREFERENCES_FILE);
            } catch (IOException | SecurityException e) {
                logger.log(
                    Level.WARNING,
                    "Could not migrate legacy preferences file from " +
                        PREFERENCES_FILE_LEGACY +
                        " to " +
                        PREFERENCES_FILE,
                    e
                );
            }
        }

        // No legacy overrides.xml setup; overrides are stored in prefs.xml.
    }

    /**
     * Load preferences from xml file
     *
     * @return an instance of UserPreferences, expected to be used as the singleton
     *         instance
     *         for the class
     */
    private static UserPreferences load() {
        initialize();

        // retrieve from file and update in-memory copy
        UserPreferences prefs = UserPreferencesPersistence.retrieve(
            PREFERENCES_FILE
        );

        if (prefs != null) {
            logger.finer(
                "Successfully read preferences from: " +
                    PREFERENCES_FILE.toAbsolutePath()
            );
            final UserPreferences logPrefs = prefs;
            logger.log(Level.FINE, () -> "Successfully read preferences: " + logPrefs.toString());
        } else {
            prefs = new UserPreferences();
        }

        return prefs;
    }

    /**
     * A private helper method we call for each preference that gets change.
     * When any attribute of this object changes, the object itself has changed.
     * Set the flag, notify the observers, and then clear the flag.
     *
     * @param preference the user preference that has changed
     */
    private void preferenceChanged(UserPreference preference) {
        pcs.firePropertyChange("preference", null, preference);
    }

    /**
     * Simply the complement of equals(), but with the specific purpose of detecting
     * if the value of a preference has been changed.
     *
     * @param originalValue the value of the UserPreference before the dialog was
     *                      opened
     * @param newValue      the value of the UserPreference as set in the dialog
     * @return true if the values are different
     */
    private boolean valuesAreDifferent(Object originalValue, Object newValue) {
        return !originalValue.equals(newValue);
    }

    /**
     * Gets the name of the directory to preload into the table.
     *
     * @return String naming the directory.
     */
    public String getPreloadFolder() {
        return preloadFolder;
    }

    /**
     * Create the directory if it doesn't exist and we need it.
     *
     * @return true if the destination directory exists -- at the time this method
     *         returns. That is, it's true whether the directory was already
     *         there, or if we successfully created it. Returns false if the
     *         directory does not exist and could not be created.
     */
    public boolean ensureDestDir() {
        if (!moveSelected) {
            // It doesn't matter if the directory exists or not if move is not selected.
            return true;
        }

        boolean canCreate = FileUtilities.checkForCreatableDirectory(
            destDirPath
        );
        destDirProblem = !canCreate;

        if (destDirProblem) {
            logger.warning(CANT_CREATE_DEST + " " + destDir);
        }

        return canCreate;
    }

    /**
     * Sets the directory to move renamed files to. Must be an absolute path, and
     * the entire path
     * will be created if it doesn't exist.
     *
     * @param dir the path to the directory
     */
    public void setDestinationDirectory(String dir) {
        // Note: The javadoc says this must be an absolute path, but enforcement/normalization
        // (including UNC/SMB edge cases) is tracked for future cleanup in docs/todo.md.
        if (valuesAreDifferent(destDir, dir)) {
            destDir = dir;
            destDirPath = Paths.get(destDir);

            preferenceChanged(UserPreference.DEST_DIR);
        }
    }

    /**
     * Gets the directory the user last chose to move renamed files to.
     *
     * Note that this returns a directory name even if "move" is disabled.
     * Therefore, this is NOT necessarily "where files should be moved to".
     * Callers need to check isMoveSelected() separately.
     *
     * @return name of the directory.
     */
    public String getDestinationDirectoryName() {
        // This method is called by the preferences dialog, to fill in the
        // field of the dialog. If "move" is disabled, the dialog should
        // show this text greyed out, but it still needs to know what it
        // is, in order to disable it.
        return destDir;
    }

    /**
     * Gets the directory that files should be moved into; if "move" is
     * disabled, returns null.
     *
     * @return the directory if move is enabled, null if not.
     */
    public Path getDestinationDirectory() {
        if (moveSelected) {
            return destDirPath;
        } else {
            return null;
        }
    }

    /**
     * Sets whether or not we want the FileMover to move files to a destination
     * directory
     *
     * @param moveSelected whether or not we want the FileMover to move files to a
     *                     destination directory
     */
    public void setMoveSelected(boolean moveSelected) {
        if (valuesAreDifferent(this.moveSelected, moveSelected)) {
            this.moveSelected = moveSelected;
            ensureDestDir();
            preferenceChanged(UserPreference.MOVE_SELECTED);
        }
    }

    /**
     * Get whether or not the user has requested that the FileMover move files to
     * a destination directory. This can be true even if the destination directory
     * is invalid.
     *
     * @return true if the user requested that the FileMover move files to a
     *         destination directory
     */
    public boolean isMoveSelected() {
        return moveSelected;
    }

    /**
     * Get whether or the FileMover should try to move files to a destination
     * directory.
     * For this to be true, the following BOTH must be true:
     * - the user has requested we move files
     * - the user has supplied a valid place to move them to.
     *
     * @return true if the FileMover should try to move files to a destination
     *         directory.
     */
    public boolean isMoveEnabled() {
        return moveSelected && !destDirProblem;
    }

    /**
     * Sets whether or not we want the FileMover to rename files based on the show,
     * season, and episode we find.
     *
     * @param renameSelected whether or not we want the FileMover to rename files
     */
    public void setRenameSelected(boolean renameSelected) {
        if (valuesAreDifferent(this.renameSelected, renameSelected)) {
            this.renameSelected = renameSelected;

            preferenceChanged(UserPreference.RENAME_SELECTED);
        }
    }

    /**
     * Get whether or not we want the FileMover to rename files based on the show,
     * season, and episode we find.
     *
     * @return true if we want the FileMover to rename files
     */
    public boolean isRenameSelected() {
        return renameSelected;
    }

    /**
     * Sets whether or not we want the FileMover to delete directories when their
     * last
     * remaining contents have been moved away.
     *
     * @param removeEmptiedDirectories whether or not we want the FileMover to
     *                                 delete
     *                                 directories when their last remaining
     *                                 contents have been moved away.
     */
    public void setRemoveEmptiedDirectories(boolean removeEmptiedDirectories) {
        if (
            valuesAreDifferent(
                this.removeEmptiedDirectories,
                removeEmptiedDirectories
            )
        ) {
            this.removeEmptiedDirectories = removeEmptiedDirectories;

            preferenceChanged(UserPreference.REMOVE_EMPTY);
        }
    }

    /**
     * Get whether or not we want the FileMover to delete directories when their
     * last
     * remaining contents have been moved away.
     *
     * @return true if we want the FileMover to delete directories when their last
     *         remaining contents have been moved away.
     */
    public boolean isRemoveEmptiedDirectories() {
        return removeEmptiedDirectories;
    }

    /**
     * Sets whether or not we want the UI to automatically delete rows after the
     * files have been successfully moved/renamed.
     *
     * @param deleteRowAfterMove whether or not we want the UI to automatically
     *                           delete rows after the files have been successfully
     *                           moved/renamed.
     */
    public void setDeleteRowAfterMove(boolean deleteRowAfterMove) {
        if (valuesAreDifferent(this.deleteRowAfterMove, deleteRowAfterMove)) {
            this.deleteRowAfterMove = deleteRowAfterMove;

            preferenceChanged(UserPreference.DELETE_ROWS);
        }
    }

    /**
     * Get whether or not we want the UI to automatically delete rows after the
     * files have been successfully moved/renamed.
     *
     * @return true if we want the UI to automatically delete rows after the
     *         files have been successfully moved/renamed.
     */
    public boolean isDeleteRowAfterMove() {
        return deleteRowAfterMove;
    }

    /**
     * Sets whether or not we want "Add Folder" to descend into subdirectories.
     *
     * @param recursivelyAddFolders whether or not we want "Add Folder" to descend
     *                              into subdirectories.
     */
    public void setRecursivelyAddFolders(boolean recursivelyAddFolders) {
        if (
            valuesAreDifferent(
                this.recursivelyAddFolders,
                recursivelyAddFolders
            )
        ) {
            this.recursivelyAddFolders = recursivelyAddFolders;

            preferenceChanged(UserPreference.ADD_SUBDIRS);
        }
    }

    /**
     * Get the status of recursively adding files within a directory
     *
     * @return true if we want "Add Folder" to descend into subdirectories,
     *         false if we want it to just consider the files at the top level of
     *         the folder
     */
    public boolean isRecursivelyAddFolders() {
        return recursivelyAddFolders;
    }

    /**
     * @return a list of strings that indicate that the presence of that string in
     *         a filename means that we should ignore that file
     */
    public List<String> getIgnoreKeywords() {
        return ignoreKeywords;
    }

    /**
     * @return a string containing the list of ignored keywords, separated by commas
     */
    public String getIgnoredKeywordsString() {
        return specifiedIgnoreKeywords;
    }

    /**
     * Turn the "ignore keywords" list into a String. This is only necessary when we
     * are restoring
     * the user preferences from XML. When the keywords are modified by the user via
     * the preferences
     * dialog, we maintain the actual string the user entered.
     *
     */
    private void buildIgnoredKeywordsString() {
        StringBuilder ignoreWords = new StringBuilder();
        String sep = "";
        for (String s : ignoreKeywords) {
            ignoreWords.append(sep);
            ignoreWords.append(s);
            sep = ",";
        }
        specifiedIgnoreKeywords = ignoreWords.toString();
    }

    /**
     * Sets the ignore keywords, given a string
     *
     * @param ignoreWordsString a string which, when parsed, indicate the files
     *                          that should be ignored. To be acceptable as an
     *                          "ignore keyword",
     *                          a string must be at least two characters long.
     */
    public void setIgnoreKeywords(String ignoreWordsString) {
        if (valuesAreDifferent(specifiedIgnoreKeywords, ignoreWordsString)) {
            specifiedIgnoreKeywords = ignoreWordsString;
            ignoreKeywords.clear();
            String[] ignoreWords = ignoreWordsString.split(
                IGNORE_WORDS_SPLIT_REGEX
            );
            for (String ignorable : ignoreWords) {
                // Be careful not to allow empty string as a "keyword."
                if (ignorable.length() > 1) {
                    // Note: further normalization (e.g., commas-to-pipes for regex, punctuation handling)
                    // is tracked for future work in docs/todo.md.
                    ignoreKeywords.add(ignorable);
                } else {
                    logger.warning(
                        "keywords to ignore must be at least two characters."
                    );
                    logger.warning("not adding \"" + ignorable + "\"");
                }
            }

            // Technically, we could end up with an identical array of strings despite the
            // fact that the input was not precisely identical to the previous input. But
            // not worth it to check.
            preferenceChanged(UserPreference.IGNORE_REGEX);
        }
    }

    /**
     * Sets the season prefix
     *
     * @param prefix the prefix for subfolders we would create to hold individual
     *               seasons of a show
     */
    public void setSeasonPrefix(String prefix) {
        if (valuesAreDifferent(seasonPrefix, prefix)) {
            seasonPrefix = prefix;

            preferenceChanged(UserPreference.SEASON_PREFIX);
        }
    }

    /**
     * @return the prefix for subfolders we would create to hold individual
     *         seasons of a show
     */
    public String getSeasonPrefix() {
        return seasonPrefix;
    }

    /**
     * Get whether or not we want the season subfolder to be numbered with a
     * leading zero.
     *
     * @return true if we want want the season subfolder to be numbered with
     *         a leading zero
     */
    public boolean isSeasonPrefixLeadingZero() {
        return seasonPrefixLeadingZero;
    }

    /**
     * Sets whether or not we want the season subfolder to be numbered with a
     * leading zero.
     *
     * @param seasonPrefixLeadingZero whether or not we want the season subfolder
     *                                to be numbered with a leading zero
     */
    public void setSeasonPrefixLeadingZero(boolean seasonPrefixLeadingZero) {
        if (
            valuesAreDifferent(
                this.seasonPrefixLeadingZero,
                seasonPrefixLeadingZero
            )
        ) {
            this.seasonPrefixLeadingZero = seasonPrefixLeadingZero;

            preferenceChanged(UserPreference.LEADING_ZERO);
        }
    }

    /**
     * Sets the rename replacement mask
     *
     * @param renameReplacementMask the rename replacement mask
     */
    public void setRenameReplacementString(String renameReplacementMask) {
        if (
            valuesAreDifferent(
                this.renameReplacementMask,
                renameReplacementMask
            )
        ) {
            this.renameReplacementMask = renameReplacementMask;

            preferenceChanged(UserPreference.REPLACEMENT_MASK);
        }
    }

    /**
     * @return the rename replacement mask
     */
    public String getRenameReplacementString() {
        return renameReplacementMask;
    }

    /**
     * @return the checkForUpdates
     */
    public boolean checkForUpdates() {
        return checkForUpdates;
    }

    /**
     * @param checkForUpdates the checkForUpdates to set
     */
    public void setCheckForUpdates(boolean checkForUpdates) {
        if (valuesAreDifferent(this.checkForUpdates, checkForUpdates)) {
            this.checkForUpdates = checkForUpdates;

            preferenceChanged(UserPreference.UPDATE_CHECK);
        }
    }

    /**
     * @return a string displaying attributes of this object
     */
    @Override
    public String toString() {
        return (
            "UserPreferences\n [destDir=" +
            destDir +
            ",\n  seasonPrefix=" +
            seasonPrefix +
            ",\n  moveSelected=" +
            moveSelected +
            ",\n  renameSelected=" +
            renameSelected +
            ",\n  renameReplacementMask=" +
            renameReplacementMask +
            ",\n  checkForUpdates=" +
            checkForUpdates +
            ",\n  preferDvdOrderIfPresent=" +
            preferDvdOrderIfPresent +
            ",\n  alwaysOverwriteDestination=" +
            alwaysOverwriteDestination +
            ",\n  cleanupDuplicateVideoFiles=" +
            cleanupDuplicateVideoFiles +
            ",\n  tagVideoMetadata=" +
            tagVideoMetadata +
            ",\n  deleteRowAfterMove=" +
            deleteRowAfterMove +
            ",\n  setRecursivelyAddFolders=" +
            recursivelyAddFolders +
            "]"
        );
    }
}
