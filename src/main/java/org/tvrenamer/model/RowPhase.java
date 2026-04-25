package org.tvrenamer.model;

/**
 * Sub-phases reported per-row during a rename action.
 *
 * <p>The view layer maps these to status icons so the user can see which
 * specific operation is currently running on a given file.  These cover
 * mid-pipeline phases that are otherwise invisible — moving the file
 * bytes, embedding metadata.  The merge phase has its own progress
 * listener.
 */
public enum RowPhase {
    /** File rename or copy in progress. */
    MOVING,
    /** Embedding metadata into the moved file. */
    TAGGING
}
