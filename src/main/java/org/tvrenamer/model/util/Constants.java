package org.tvrenamer.model.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.tvrenamer.controller.util.StringUtils;

/**
 * Constants.java -- the most important reason for this class to exist is for pieces of
 *    information that are shared throughout the program, so that if they should ever
 *    have to change, they can be changed in one place.
 *
 * Even for values that are used only once, though, it can be advantageous to give them
 * symbolic names rather than inlining the values directly into the code.  And it can
 * be nicer to consolidate all such values in one place, rather than cluttering up the
 * top of every file with its own values.
 *
 * Putting them in one place also allows us to see all the values "at once."  If this were
 * an application being developed for a private company, we might have some non-developer
 * reviewing all the words we present to the user (as well as all the files we might read
 * or create, etc.)  With an open-source project... well, we can still review them,
 * ourselves.  :-)
 *
 * But there's another reason for moving stuff here, and that's as a midway point to some
 * non-code solution.  Particularly if we ever want to localize the application, having
 * strings hard-coded into methods makes it pretty much impossible.  We'd want to get the
 * strings from resource files.
 *
 * It is not, of course, necessary to have this intermediate step; we could go straight from
 * inlined strings to resource files.  This just makes it easier to do it incrementally.
 *
 */
public class Constants {

    // ---- Application identity ----

    public static final String APPLICATION_NAME = "TVRenamer";
    public static final String APPLICATION_DISPLAY_NAME = "TVRenamer (Forked)";
    public static final String ABOUT_LABEL =
        "About " + APPLICATION_DISPLAY_NAME;
    public static final String TVRENAMER_DESCRIPTION =
        APPLICATION_NAME +
        " is a Java GUI utility to rename TV episodes from TV listings";

    public static final String VERSION_NUMBER = Environment.readVersionNumber();
    public static final String VERSION_LABEL = "Version: " + VERSION_NUMBER;

    // ---- URLs ----

    // Fork URLs (GitHub)
    public static final String TVRENAMER_PROJECT_URL =
        "https://github.com/The-Ant-Forge/TVRenamer";
    public static final String TVRENAMER_DOWNLOAD_URL =
        "https://github.com/The-Ant-Forge/TVRenamer/releases";
    public static final String TVRENAMER_ISSUES_URL =
        "https://github.com/The-Ant-Forge/TVRenamer/issues";

    // UpdateChecker should use GitHub Releases rather than tvrenamer.org.
    // Kept as a constant so the implementation can be swapped without UI churn.
    public static final String TVRENAMER_VERSION_URL =
        "https://api.github.com/repos/The-Ant-Forge/TVRenamer/releases/latest";

    public static final String TVRENAMER_REPOSITORY_URL =
        "https://github.com/The-Ant-Forge/TVRenamer";
    public static final String TVRENAMER_LICENSE_URL =
        "https://www.gnu.org/licenses/gpl-2.0.html";

    // Original project links (attribution)
    public static final String ORIGINAL_PROJECT_REPOSITORY_URL =
        "https://github.com/tvrenamer/tvrenamer";
    public static final String ORIGINAL_PROJECT_WEBSITE_URL =
        "https://www.tvrenamer.org/";

    public static final String LICENSE_TEXT_1 = "Licensed under the ";
    public static final String LICENSE_TEXT_2 = "GNU General Public License v2";
    public static final String PROJECT_PAGE = "Project Page";
    public static final String ISSUE_TRACKER = "Issue Tracker";
    public static final String SOURCE_CODE_LINK = "Source Code";

    // ---- Resource paths ----

    public static final String XML_SUFFIX = ".xml";
    // Filesystem fallback path for icons (works in dev; not from fat JAR)
    public static final String ICON_PARENT_DIRECTORY = "src/main/resources";
    public static final String APPLICATION_ICON_PATH = "/icons/tvrenamer.png";
    public static final String SUBLINK_PATH = "/icons/SweetieLegacy/";
    public static final String LOGGING_PROPERTIES = "/logging.properties";

    @SuppressWarnings("SameParameterValue")
    private static String charsToSpaceString(final Set<Character> chars) {
        StringBuilder str = new StringBuilder(2 * chars.size());
        for (Character c : chars) {
            str.append(' ');
            str.append(c);
        }
        return str.toString();
    }

    // ---- Button / dialog labels ----

    public static final String QUIT_LABEL = "Quit";
    public static final String CANCEL_LABEL = "Cancel";
    public static final String SAVE_LABEL = "Save";
    public static final String ERROR_LABEL = "Error";
    public static final String PARSE_SUMMARY_TITLE = "Parse Summary";
    public static final String EXIT_LABEL = "Exit";
    public static final String OK_LABEL = "OK";
    public static final String PREFERENCES_LABEL = "Preferences";
    public static final String FILE_MOVE_THREAD_LABEL = "MoveRunnerThread";
    public static final String RENAME_LABEL = "Rename Checked";
    public static final String JUST_MOVE_LABEL = "Move Checked";
    public static final String RENAME_AND_MOVE = "Rename && Move Checked";
    public static final String CHECKBOX_HEADER = String.valueOf((char) 0x2705);
    public static final String SOURCE_HEADER = "Current File";
    public static final String MOVE_HEADER = "Proposed File Path";
    public static final String RENAME_HEADER = "Proposed File Name";
    public static final String STATUS_HEADER = "Status";
    public static final String REPLACEMENT_OPTIONS_LIST_ENTRY_REGEX =
        "(.*) :.*";
    public static final String IGNORE_WORDS_SPLIT_REGEX = "\\s*,\\s*";

    // ---- Preferences dialog labels and tooltips ----

    public static final String GENERAL_LABEL = "General";
    public static final String RENAMING_LABEL = "Renaming";
    public static final String MOVE_SELECTED_TEXT = "Move Enabled [?]";
    public static final String RENAME_SELECTED_TEXT = "Rename Enabled [?]";
    public static final String DEST_DIR_TEXT = "TV Directory [?]";
    public static final String DEST_DIR_BUTTON_TEXT = "Select directory";
    public static final String DIR_DIALOG_TEXT =
        "Please select a directory and click OK";
    public static final String SEASON_PREFIX_TEXT = "Season Prefix [?]";
    public static final String SEASON_PREFIX_ZERO_TEXT =
        "Season Prefix Leading Zero [?]";
    public static final String IGNORE_LABEL_TEXT =
        "Ignore files containing [?]";
    public static final String RECURSE_FOLDERS_TEXT =
        "Recursively add shows in subdirectories [?]";
    public static final String RECURSE_FOLDERS_TOOLTIP =
        "If unchecked, do not look into subfolders " + "for shows to add";
    public static final String REMOVE_EMPTIED_TEXT =
        "Remove emptied directories [?]";
    public static final String REMOVE_EMPTIED_TOOLTIP =
        "When selected, directories which become empty due to file\n" +
        "movement will be deleted.";
    public static final String CHECK_UPDATES_TEXT =
        "Check for Updates at startup [?]";
    public static final String CHECK_UPDATES_TOOLTIP =
        "If checked, will automatically check " + APPLICATION_NAME + "\n" +
        "website for new versions at startup, and offer to update if found.";
    public static final String THEME_MODE_TEXT = "Theme [?]";
    public static final String THEME_MODE_TOOLTIP =
        "Choose Light, Dark, or Auto (uses the operating system theme).\n" +
        "Changes take effect after restart.";
    public static final String THEME_MODE_RESTART_NOTE =
        "Theme changes apply next time TVRenamer starts.";
    public static final String PREFER_DVD_ORDER_TEXT =
        "Prefer DVD episode order if present [?]";
    public static final String PREFER_DVD_ORDER_TOOLTIP =
        "If checked, TVRenamer will prefer DVD ordering/titles\n" +
        "when available. If DVD ordering is not present for a show,\n" +
        "TVRenamer will fall back to aired order.";
    public static final String DELETE_ROWS_TEXT = "Clear completed rows [?]";
    public static final String DELETE_ROWS_TOOLTIP =
        "If checked, after a file has been successfully moved/renamed,\n" +
        "clear the completed row from the table.";
    public static final String OVERWRITE_DEST_TEXT = "Always overwrite destination [?]";
    public static final String OVERWRITE_DEST_TOOLTIP =
        "If checked, overwrite existing destination files instead of\n" +
        "creating versioned filenames like (1), (2). Use with caution.";
    public static final String CLEANUP_DUPLICATES_TEXT =
        "Delete duplicate video files [?]";
    public static final String CLEANUP_DUPLICATES_TOOLTIP =
        "After moving a file, delete other video files that\n" +
        "represent the same episode (same base name or same\n" +
        "season/episode). Only video files are deleted, not subtitles.";
    public static final String TAG_VIDEO_METADATA_TEXT =
        "Tag video files with episode metadata [?]";
    public static final String TAG_VIDEO_METADATA_TOOLTIP =
        "If checked, TVRenamer will write TV show metadata\n" +
        "(show name, season, episode, title) to supported video\n" +
        "files after moving. This enables media players to display\n" +
        "episode information.\n\n" +
        "Supported formats:\n" +
        "• MP4/M4V/MOV - Requires AtomicParsley or ffmpeg\n" +
        "• MKV/WebM - Requires MKVToolNix (mkvpropedit)\n\n" +
        "If the required tool is not installed, those files\n" +
        "are silently skipped.";
    public static final String RENAME_TOKEN_TEXT = "Rename Tokens [?]";
    public static final String RENAME_FORMAT_TEXT = "Rename Format [?]";
    public static final String RENAME_SELECTED_TOOLTIP =
        "Whether the 'rename' functionality is enabled.\n" +
        "You can move a file into a folder based on its show\n" +
        "without actually renaming the file";
    public static final String HELP_TOOLTIP =
        "Hover mouse over [?] to get help";
    public static final String GENERAL_TOOLTIP =
        " - TVRenamer will automatically move the files to your\n" +
        "   'TV' folder if you want it to.\n" +
        " - It will move the file to:\n" +
        "   <tv directory>/<series name>/<season prefix> #/\n" +
        " - Once enabled, set the location below.";
    public static final String MOVE_SELECTED_TOOLTIP =
        "Whether the " + "'move to TV location' functionality is enabled";
    public static final String DEST_DIR_TOOLTIP =
        "The location of your 'TV' folder";
    public static final String PREFIX_TOOLTIP =
        " - The prefix of the season when renaming and moving\n" +
        "   the file. It is usually \"Season \" or \"s\".\n" +
        " - If no value is entered (or \"\"), the season folder\n" +
        "   will not be created, putting all files in the series\n" +
        "   name folder.\n" +
        " - The \" will not be included, just displayed here to\n" +
        "   show whitespace.";
    public static final String SEASON_PREFIX_ZERO_TOOLTIP =
        "Whether to have a leading zero " + "in the season prefix";
    public static final String IGNORE_LABEL_TOOLTIP =
        "Comma-separated list of keywords. Files containing any of these\n" +
        "words (case-insensitive) will be skipped and not renamed.\n" +
        "Example: sample,RARBG";
    public static final String RENAME_TOKEN_TOOLTIP =
        " - These are the possible tokens to make up the\n" +
        "   'Rename Format' below.\n" +
        " - You can drag and drop tokens to the 'Rename Format'\n" +
        "   text box below.";
    public static final String RENAME_FORMAT_TOOLTIP =
        "The result of the rename, with the " +
        "tokens being replaced by the meaning above";
    public static final String CANT_CREATE_DEST =
        "Unable to create the destination directory";
    public static final String MOVE_NOT_POSSIBLE =
        "You will not be able to actually move files until this is\n" +
        "corrected. Open the Preferences dialog to correct it.\n\n" +
        "Hint: verify the path exists (or can be created) and that\n" +
        "you have write permission; for network shares, confirm the\n" +
        "share is online and accessible.";
    public static final String MOVE_FAILURE_MSG_1 = "Some files were not moved";
    public static final String MOVE_FAILURE_PARTIAL_MSG = ".  These include";
    public static final String NEWLINE_BULLET = "\n\u2022 ";
    public static final String MOVE_INTRO = "Clicking this button will ";
    public static final String AND_RENAME = "rename and ";
    public static final String INTRO_MOVE_DIR =
        "move the checked files to the directory " +
        "set in preferences (currently ";
    public static final String FINISH_MOVE_DIR = ").";
    public static final String RENAME_TOOLTIP =
        "Clicking this button will rename the checked " +
        "files but leave them where they are.";
    public static final String NO_ACTION_TOOLTIP =
        "You have selected not to move files, and not to rename\n" +
        "them, either. Therefore, there's no action to be taken.\n\n" +
        "Open the Preferences dialog and enable \"Move\", \"Rename\",\n" +
        "or both, in order to take some action.";
    public static final String UNKNOWN_EXCEPTION =
        "An error occurred, please check " +
        "the console output to see any errors:";
    private static final String ILLEGAL_CHARS_INTRO =
        "The following characters cannot be " + "used in file paths:";
    public static final String ILLEGAL_CHARACTERS_WARNING =
        ILLEGAL_CHARS_INTRO +
        charsToSpaceString(StringUtils.ILLEGAL_CHARACTERS);
    public static final String NO_TEXT_BEFORE_OPENING_QUOTE =
        "Cannot insert text before " + "the opening double quote";
    public static final String NO_TEXT_AFTER_CLOSING_QUOTE =
        "Cannot insert text after " + "the closing double quote";

    // ---- Update checker messages ----

    public static final String UPDATE_TEXT = "Check for Updates...";
    private static final String TO_DOWNLOAD =
        "Please visit " +
        TVRENAMER_PROJECT_URL +
        " to download the new version.";
    public static final String NEW_VERSION_TITLE = "New Version Available!";
    public static final String NEW_VERSION_AVAILABLE =
        "There is a new version available!\n\n" +
        "You are currently running " +
        VERSION_NUMBER +
        ", but there is an update available\n\n" +
        TO_DOWNLOAD;
    public static final String UPDATE_AVAILABLE =
        "There is an update available. <a href=\"" +
        TVRENAMER_DOWNLOAD_URL +
        "\">Click here to download</a>";
    public static final String NO_NEW_VERSION_TITLE =
        "No New Version Available";
    public static final String NO_NEW_VERSION_AVAILABLE =
        "There is no new version available\n\n" +
        "Please check the website (" +
        TVRENAMER_PROJECT_URL +
        ") for any news or check back later.";
    public static final String GET_UPDATE_MESSAGE =
        "This version of TVRenamer is no longer " +
        "functional.  There is a new version available, which should work. " +
        TO_DOWNLOAD;
    public static final String NEED_UPDATE =
        "This version of TVRenamer is no longer " +
        "functional.  There is a not currently a new version available, but please " +
        "check\n" +
        TVRENAMER_PROJECT_URL +
        "\nto see when one becomes available.";

    // ---- Provider / parsing error messages ----

    public static final String ERROR_PARSING_XML = "Error parsing XML";
    public static final String ERROR_PARSING_NUMBERS =
        ERROR_PARSING_XML + ": a field expected to be a number was not";
    public static final String ADDED_PLACEHOLDER_FILENAME = "Downloading ...";
    public static final String EPISODE_NOT_FOUND =
        "Could not get episode for show";
    public static final String DOWNLOADING_FAILED =
        "Downloading show listings failed";
    public static final String TIMEOUT_DOWNLOADING =
        "Timed out trying to look up";
    public static final String BAD_PARSE_MESSAGE =
        "Did not extract show name from filename";
    public static final String DOWNLOADING_FAILED_MESSAGE =
        DOWNLOADING_FAILED + ".  Check internet connection";
    public static final String FILE_EPISODE_NEEDS_PATH =
        "cannot create FileEpisode with no path";

    // ---- Default values ----

    public static final String DEFAULT_REPLACEMENT_MASK = "%S [%sx%0e] %t";
    public static final String DEFAULT_SEASON_PREFIX = "Season ";
    public static final String DEFAULT_IGNORED_KEYWORD = "sample";
    public static final String DUPLICATES_DIRECTORY = "versions";
    public static final String DEFAULT_LANGUAGE = "en";

    // ---- File system paths ----

    private static final String CONFIGURATION_DIRECTORY_NAME = ".tvrenamer";
    private static final String PREFERENCES_FILENAME = "prefs.xml";
    private static final String OVERRIDES_FILENAME = "overrides.xml";

    public static final Path TMP_DIR = Paths.get(Environment.TMP_DIR_NAME);

    private static final Path USER_HOME_DIR = Paths.get(Environment.USER_HOME);
    public static final Path DEFAULT_DESTINATION_DIRECTORY =
        USER_HOME_DIR.resolve("TV");
    public static final Path CONFIGURATION_DIRECTORY = USER_HOME_DIR.resolve(
        CONFIGURATION_DIRECTORY_NAME
    );
    public static final Path PREFERENCES_FILE = CONFIGURATION_DIRECTORY.resolve(
        PREFERENCES_FILENAME
    );
    public static final Path OVERRIDES_FILE = CONFIGURATION_DIRECTORY.resolve(
        OVERRIDES_FILENAME
    );

    public static final Path PREFERENCES_FILE_LEGACY = USER_HOME_DIR.resolve(
        "tvrenamer.preferences"
    );
    public static final String EMPTY_STRING = "";

    // ---- FileEpisode constants ----
    /** Sentinel value indicating file size is unknown or unavailable. */
    public static final long NO_FILE_SIZE = -1L;

    /**
     * Maximum length for episode titles in replacement filenames.
     * Long enough for titles like:
     * "The One With The Thanksgiving Flashbacks (a.k.a. The One With All The Thanksgivings)"
     */
    public static final int MAX_TITLE_LENGTH = 85;
}
