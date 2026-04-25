package org.tvrenamer.controller.subtitle;

import java.nio.file.Path;
import java.util.EnumSet;

/**
 * One paired subtitle file ready to be muxed.
 *
 * @param file        the absolute path of the subtitle file
 * @param langCode3   3-letter ISO 639-2 B-form language code (never blank)
 * @param trackName   the human-readable track name to embed in the container
 * @param descriptors any track flavours parsed from the filename
 */
public record SubtitleEntry(
    Path file,
    String langCode3,
    String trackName,
    EnumSet<Descriptor> descriptors
) { }
