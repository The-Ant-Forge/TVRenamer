package org.tvrenamer.model;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.tvrenamer.controller.AddEpisodeListener;
import org.tvrenamer.controller.util.FileUtilities;

public class EpisodeDb implements java.beans.PropertyChangeListener {

    private static final Logger logger = Logger.getLogger(
        EpisodeDb.class.getName()
    );
    private static final UserPreferences prefs = UserPreferences.getInstance();

    private final Map<String, FileEpisode> episodes = new ConcurrentHashMap<>(
        1000
    );
    private List<String> ignoreKeywords = prefs.getIgnoreKeywords();

    public EpisodeDb() {
        prefs.addPropertyChangeListener(this);
    }

    private String ignorableReason(String fileName) {
        String fileNameLower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String ignoreKeyword : ignoreKeywords) {
            if (fileNameLower.contains(ignoreKeyword.toLowerCase(java.util.Locale.ROOT))) {
                return ignoreKeyword;
            }
        }
        return null;
    }

    private FileEpisode add(final String pathname) {
        Path path = Paths.get(pathname);
        final FileEpisode episode = new FileEpisode(path);
        episode.setIgnoreReason(ignorableReason(pathname));
        if (!episode.wasParsed()) {
            // Note: unparsed files are still inserted so users can see what failed.
            // Follow-up UX improvements are tracked in docs/todo.md ("Improve handling of \"unparsed\" files").
            logger.warning("Couldn't parse file: " + pathname);
        }
        episodes.put(pathname, episode);
        return episode;
    }

    /**
     * Remove the given key from the Episode database
     *
     * This is called when the user removes a row from the table. It's possible
     * (even if unlikely) that the user might delete the entry, only to re-add
     * it later. And this works fine. But it does cause us to recreate the
     * FileEpisode from scratch. It might be nice to put removed episodes
     * "aside" somewhere that we could still find them, but just know they're
     * not actively in the table.
     *
     * @param key
     *            the key to remove from the Episode database
     */
    public void remove(String key) {
        episodes.remove(key);
    }

    public FileEpisode get(String key) {
        return episodes.get(key);
    }

    private boolean fileIsVisible(Path path) {
        boolean isVisible = false;
        try {
            if (Files.exists(path)) {
                if (Files.isHidden(path)) {
                    logger.finer("ignoring hidden file " + path);
                } else {
                    isVisible = true;
                }
            }
        } catch (IOException | SecurityException e) {
            logger.finer("could not access file; treating as hidden: " + path);
        }
        return isVisible;
    }

    /**
     * Get the current location -- and, therefore, the database key -- for the
     * file that has been referred to by the given key.
     *
     * That is, we know where the file USED TO be. It may still be there; it may
     * have moved. Tell the caller where it is now, which is also how to retrieve
     * its FileEpisode object.
     *
     * @param key
     *            a String, representing a path to the last known location of the
     *            file,
     *            to look up and check
     * @return the current location, if the file still exists; null if the file
     *         is no longer valid
     *
     *         The method might change the internal database, if it detects the file
     *         has
     *         been moved. That means, the key given will no longer be valid when
     *         the
     *         method returns. The return value does not explicitly give an
     *         indication
     *         of whether or not that's true. Callers must simply use the returned
     *         value
     *         as the key after this function returns, or must do a comparison with
     *         the
     *         previous key to see if it's still valid.
     *
     */
    public String currentLocationOf(final String key) {
        if (key == null) {
            return null;
        }

        // Always work with canonical keys so we keep the EpisodeDb mapping stable.
        String canonicalKey = canonicalizeKey(Paths.get(key));

        FileEpisode ep = episodes.get(canonicalKey);
        if (ep == null) {
            // Backward compatibility: older entries may have been stored under a non-canonical key.
            ep = episodes.get(key);
            if (ep != null) {
                episodes.remove(key);
                episodes.put(canonicalKey, ep);
            } else {
                return null;
            }
        }

        Path currentLocation = ep.getPath();
        if (
            fileIsVisible(currentLocation) &&
            Files.isRegularFile(currentLocation)
        ) {
            // OK, the file is good! But that could be true even if
            // it were moved. Now try to see if it's been moved, or if
            // it's still where we think it is.
            String canonicalDirect = canonicalizeKey(currentLocation);

            // If the canonical keys match, we're done.
            if (canonicalKey.equals(canonicalDirect)) {
                return canonicalKey;
            }

            // If both paths refer to the same file, normalize the key so the map stays consistent.
            // (We avoid toRealPath-based canonicalization; see canonicalizeKey(...) for rationale.)
            Path keyPath = Paths.get(canonicalKey);
            if (FileUtilities.isSameFile(currentLocation, keyPath)) {
                // Ensure we are stored under the canonical key.
                if (!episodes.containsKey(canonicalKey)) {
                    episodes.put(canonicalKey, ep);
                }
                return canonicalKey;
            }

            // The file has been moved. Update our database key to the canonical path and return it.
            episodes.remove(canonicalKey);
            episodes.put(canonicalDirect, ep);
            return canonicalDirect;
        } else {
            // The file has disappeared out from under us (or, bizarrely, been replaced
            // by a directory? Anything is possible...). Remove it from the db and let
            // the caller know by returning null.
            episodes.remove(canonicalKey);
            return null;
        }
    }

    private void addFileToQueue(
        final Queue<FileEpisode> contents,
        final Path path
    ) {
        if (!Files.isRegularFile(path)) {
            logger.fine("skipping non-regular file: " + path);
            return;
        }
        final String key = canonicalizeKey(path);
        if (episodes.containsKey(key)) {
            logger.info("already in table: " + key);
        } else {
            FileEpisode ep = add(key);
            contents.add(ep);
        }
    }

    private void addFileIfVisible(
        final Queue<FileEpisode> contents,
        final Path path
    ) {
        if (fileIsVisible(path) && Files.isRegularFile(path)) {
            addFileToQueue(contents, path);
        }
    }

    private void addFilesRecursively(
        final Queue<FileEpisode> contents,
        final Path parent,
        final Path filename
    ) {
        if (parent == null) {
            logger.warning("cannot add files; parent is null");
            return;
        }
        if (filename == null) {
            logger.warning("cannot add files; filename is null");
            return;
        }
        final Path fullpath = parent.resolve(filename);
        if (fileIsVisible(fullpath)) {
            if (Files.isDirectory(fullpath)) {
                try (
                    DirectoryStream<Path> files = Files.newDirectoryStream(
                        fullpath
                    )
                ) {
                    if (files != null) {
                        // recursive call
                        files.forEach(pth ->
                            addFilesRecursively(
                                contents,
                                fullpath,
                                pth.getFileName()
                            )
                        );
                    }
                } catch (IOException ioe) {
                    logger.warning("IO Exception descending " + fullpath);
                }
            } else {
                addFileToQueue(contents, fullpath);
            }
        }
    }

    /**
     * Add the given folder to the queue. This is intended to support the
     * "Add Folder" functionality. This method itself does only sanity
     * checking, and if everything's in order, calls addFilesRecursively()
     * to do the actual work.
     *
     * @param pathname the name of a folder
     */
    public void addFolderToQueue(final String pathname) {
        if (!prefs.isRecursivelyAddFolders()) {
            logger.warning(
                "cannot add folder when preference \"add files recursively\" is off"
            );
            return;
        }

        if (pathname == null) {
            logger.warning("cannot add files; pathname is null");
            return;
        }

        Queue<FileEpisode> contents = new ConcurrentLinkedQueue<>();
        final Path path = Paths.get(pathname);
        addFilesRecursively(contents, path.getParent(), path.getFileName());
        publish(contents);
    }

    /**
     * Add the given array of filename Strings, each of which are expected to be
     * found within the directory given by the pathPrefix, to the queue.
     * This is intended to support the "Add Files" functionality.
     *
     * @param pathPrefix the directory where the fileNames are found
     * @param fileNames  an array of Strings presumed to represent filenames
     */
    public void addFilesToQueue(final String pathPrefix, String[] fileNames) {
        Queue<FileEpisode> contents = new ConcurrentLinkedQueue<>();
        if (pathPrefix != null) {
            Path path = Paths.get(pathPrefix);
            Path parent = path.getParent();

            for (String fileName : fileNames) {
                path = parent.resolve(fileName);
                addFileIfVisible(contents, path);
            }
            publish(contents);
        }
    }

    /**
     * Add the given array of filename Strings to the queue. This is intended
     * to support Drag and Drop.
     *
     * @param fileNames an array of Strings presumed to represent filenames
     */
    public void addArrayOfStringsToQueue(final String[] fileNames) {
        Queue<FileEpisode> contents = new ConcurrentLinkedQueue<>();
        boolean descend = prefs.isRecursivelyAddFolders();
        for (final String fileName : fileNames) {
            final Path path = Paths.get(fileName);
            if (descend) {
                addFilesRecursively(
                    contents,
                    path.getParent(),
                    path.getFileName()
                );
            } else {
                addFileIfVisible(contents, path);
            }
        }
        publish(contents);
    }

    /**
     * Add the contents of the preload folder to the queue.
     *
     * This can involve scanning a large directory tree, so it runs on a background
     * thread to avoid blocking the UI thread.
     *
     */
    public void preload() {
        if (!prefs.isRecursivelyAddFolders()) {
            return;
        }

        final String preload = prefs.getPreloadFolder();
        if (preload == null) {
            return;
        }

        final Thread t = new Thread(
            () -> {
                try {
                    addFolderToQueue(preload);
                } catch (Exception e) {
                    logger.log(
                        Level.WARNING,
                        "Exception while preloading folder: " + preload,
                        e
                    );
                }
            },
            "EpisodeDbPreload"
        );
        t.setDaemon(true);
        t.start();
    }

    /**
     * Canonicalize file keys so the same file is less likely to appear multiple times
     * due to path string differences (relative vs absolute, ".." segments, etc.).
     *
     * We intentionally avoid calling {@code Path#toRealPath()} here because it can
     * be slow or fail on network shares/UNC paths. This is a "string-level"
     * canonicalization only.
     */
    private static String canonicalizeKey(final Path path) {
        if (path == null) {
            return "";
        }
        try {
            return path.toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            // Best-effort fallback
            return path.toAbsolutePath().toString();
        }
    }

    @Override
    public void propertyChange(java.beans.PropertyChangeEvent evt) {
        if (
            "preference".equals(evt.getPropertyName()) &&
            (evt.getNewValue() == UserPreference.IGNORE_REGEX)
        ) {
            ignoreKeywords = prefs.getIgnoreKeywords();
            for (FileEpisode ep : episodes.values()) {
                ep.setIgnoreReason(ignorableReason(ep.getFilepath()));
            }
            listeners.forEach(AddEpisodeListener::refreshDestinations);
        }
    }

    /**
     * Standard object method to represent this EpisodeDb as a string.
     *
     * @return string version of this; just says how many episodes are in the map.
     */
    @Override
    public String toString() {
        return "{EpisodeDb with " + episodes.size() + " files}";
    }

    private final Queue<AddEpisodeListener> listeners =
        new ConcurrentLinkedQueue<>();

    /**
     * Register interest in files and folders that are added to the queue.
     *
     * @param listener
     *                 the AddEpisodeListener that should be called when we have
     *                 finished processing
     *                 a folder or array of files
     */
    public void subscribe(AddEpisodeListener listener) {
        listeners.add(listener);
    }

    /**
     * Notify registered interested parties that we've finished adding a folder or
     * array of files to the queue, and pass the queue to each listener.
     *
     * @param episodes
     *                 the queue of FileEpisode objects we've created since the last
     *                 time we
     *                 published
     */
    private void publish(Queue<FileEpisode> episodes) {
        for (AddEpisodeListener listener : listeners) {
            listener.addEpisodes(episodes);
        }
    }
}
