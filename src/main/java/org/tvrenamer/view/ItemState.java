package org.tvrenamer.view;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.swt.graphics.Image;
import org.tvrenamer.model.util.Constants;

public class ItemState {

    private final Image image;
    private final String ordering;

    private ItemState(String ordering, String imageFilename) {
        this.ordering = ordering;
        this.image = UIStarter.readImageFromPath(
            Constants.SUBLINK_PATH + imageFilename
        );
    }

    /**
     * Generic "validated / OK" indicator (still used by the Preferences
     * matching tab to mark a valid override row).  In the file pipeline
     * this is no longer used as the "ready to rename" state — see
     * {@link #READY} below.
     */
    public static final ItemState SUCCESS = new ItemState(
        "a",
        "16-em-check.png"
    );

    /**
     * Pre-pipeline "ready / approved" indicator: the row has been parsed,
     * matched, and is staged to be processed, but no work has started on
     * it yet.  Distinct from {@link #COMPLETED} (post-pipeline) so the
     * user can tell at a glance which rows are still waiting versus which
     * have been fully renamed/tagged/merged.  Solid green circle = "go".
     */
    public static final ItemState READY = new ItemState(
        "a2",
        "16-circle-green.png"
    );

    public static final ItemState OPTIONS = new ItemState(
        "b",
        "16-circle-green-add.png"
    );
    public static final ItemState ADDED = new ItemState(
        "c",
        "16-circle-blue.png"
    );
    public static final ItemState DOWNLOADING = new ItemState(
        "d",
        "16-clock.png"
    );

    // Dedicated icon for "action required" (e.g., user must select a show).
    public static final ItemState ACTION_REQUIRED = new ItemState(
        "d2",
        "16-comment-question.png"
    );

    public static final ItemState RENAMING = new ItemState(
        "e",
        "16-em-pencil.png"
    );

    // Mid-pipeline phase: file move/copy in progress (post-rename).
    public static final ItemState MOVING = new ItemState(
        "e2",
        "16-arrow-right.png"
    );

    // Mid-pipeline phase: embedding metadata into the moved file.
    public static final ItemState TAGGING = new ItemState(
        "e3",
        "16-tag-pencil.png"
    );

    // Mid-pipeline phase: muxing subtitle tracks into the moved file.
    public static final ItemState MERGING = new ItemState(
        "e4",
        "16-video-rect.png"
    );

    // Solid red circle — pairs visually with READY's solid green circle so
    // ready/error read as opposite states at a glance ("go" vs. "stop").
    public static final ItemState FAIL = new ItemState("f", "16-circle-red.png");

    // Indicates a file was successfully moved/renamed.
    public static final ItemState COMPLETED = new ItemState(
        "g",
        "16-circle-green-check.png"
    );

    private static final ItemState[] STANDARD_STATUSES = {
        SUCCESS,
        READY,
        OPTIONS,
        ADDED,
        DOWNLOADING,
        ACTION_REQUIRED,
        RENAMING,
        MOVING,
        TAGGING,
        MERGING,
        FAIL,
        COMPLETED,
    };

    private static final Map<Image, ItemState> IMAGES =
        new ConcurrentHashMap<>();

    static {
        for (ItemState state : STANDARD_STATUSES) {
            IMAGES.put(state.image, state);
        }
    }

    /**
     * Gets the Image associated with this ItemState
     *
     * @return
     *    the Image to display for this ItemState
     */
    public Image getIcon() {
        return image;
    }

    /**
     * Returns a "prioritized" string that the given Image is mapped to.
     *
     * This is used for sorting.  If the user clicks the column header to sort
     * by "Status", we want to sort the table in a meaningful way.  Specifically,
     * assuming sort direction is "up", we want the "most resolved" files at
     * the top, and the "least resolved" at the bottom.
     *
     * Therefore, we associate each status with a String, which is meaningless
     * except that it is lexicographically appropriate relative to the Strings
     * of the other statuses.
     *
     * But in the case of sorting the table, we don't really even have the status!
     * All we can easily retrieve is the actual Image object that is being displayed
     * in the cell.  So, we maintain a mapping from Image objects to priority
     * Strings, as well, and use that mapping here.
     *
     * If we cannot find the given Image in the mapping, we return null, which might
     * well cause a null pointer exception in the caller.  It's up to the caller to
     * deal with that, but if things are so confused that we have an unrecognized
     * Image in the cell, maybe it's best for the program to just exit...
     *
     * @param img
     *   the Image that we want mapped to a priority String
     * @return
     *   a priority String if the Image is found, or null if it isn't
     */
    public static String getImagePriority(final Image img) {
        ItemState state = IMAGES.get(img);
        if (state == null) {
            return null;
        }
        return state.ordering;
    }

    /**
     * Register all static ItemState images for disposal when the Display is shut down.
     * Must be called once after the Display is created.
     *
     * @param display the SWT Display to register with
     */
    public static void registerDisposal(org.eclipse.swt.widgets.Display display) {
        display.disposeExec(() -> {
            for (ItemState state : STANDARD_STATUSES) {
                if (state.image != null && !state.image.isDisposed()) {
                    state.image.dispose();
                }
            }
        });
    }
}
