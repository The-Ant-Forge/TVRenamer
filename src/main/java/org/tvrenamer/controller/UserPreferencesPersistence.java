package org.tvrenamer.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.tvrenamer.model.UserPreferences;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class UserPreferencesPersistence {

    private static final Logger logger = Logger.getLogger(
        UserPreferencesPersistence.class.getName()
    );

    /** Scalar field names that map directly to simple string values. */
    private static final String[] SCALAR_FIELDS = {
        "destDir",
        "seasonPrefix",
        "seasonPrefixLeadingZero",
        "moveSelected",
        "moveEnabled",        // legacy alias
        "renameSelected",
        "renameEnabled",      // legacy alias
        "removeEmptiedDirectories",
        "deleteRowAfterMove",
        "renameReplacementMask",
        "checkForUpdates",
        "themeMode",
        "theme",              // legacy alias
        "recursivelyAddFolders",
        "preserveFileModificationTime",
        "preferDvdOrderIfPresent",
        "alwaysOverwriteDestination",
        "cleanupDuplicateVideoFiles",
        "tagVideoMetadata",
        "processedFileCount",
    };

    /**
     * Save the preferences object to the path.
     *
     * @param prefs the preferences object to save
     * @param path  the path to save it to
     */
    @SuppressWarnings("SameParameterValue")
    public static void persist(UserPreferences prefs, Path path) {
        StringBuilder xml = new StringBuilder();
        xml.append("<preferences>\n");

        appendElement(xml, "destDir", prefs.getDestinationDirectoryName());
        appendElement(xml, "seasonPrefix", prefs.getSeasonPrefix());
        appendElement(xml, "seasonPrefixLeadingZero", prefs.isSeasonPrefixLeadingZero());
        appendElement(xml, "moveSelected", prefs.isMoveSelected());
        appendElement(xml, "renameSelected", prefs.isRenameSelected());
        appendElement(xml, "removeEmptiedDirectories", prefs.isRemoveEmptiedDirectories());
        appendElement(xml, "deleteRowAfterMove", prefs.isDeleteRowAfterMove());
        appendElement(xml, "renameReplacementMask", prefs.getRenameReplacementString());
        appendElement(xml, "checkForUpdates", prefs.checkForUpdates());
        appendElement(xml, "themeMode", prefs.getThemeMode().name());
        appendElement(xml, "recursivelyAddFolders", prefs.isRecursivelyAddFolders());
        appendElement(xml, "preserveFileModificationTime", prefs.isPreserveFileModificationTime());
        appendElement(xml, "preferDvdOrderIfPresent", prefs.isPreferDvdOrderIfPresent());
        appendElement(xml, "alwaysOverwriteDestination", prefs.isAlwaysOverwriteDestination());
        appendElement(xml, "cleanupDuplicateVideoFiles", prefs.isCleanupDuplicateVideoFiles());
        appendElement(xml, "tagVideoMetadata", prefs.isTagVideoMetadata());
        appendElement(xml, "processedFileCount", prefs.getProcessedFileCount());

        // ignoreKeywords list
        List<String> keywords = prefs.getIgnoreKeywords();
        if (keywords != null && !keywords.isEmpty()) {
            xml.append("  <ignoreKeywords>\n");
            for (String kw : keywords) {
                appendElement(xml, "string", kw, 4);
            }
            xml.append("  </ignoreKeywords>\n");
        }

        // showNameOverrides map
        appendMap(xml, "showNameOverrides", prefs.getShowNameOverrides());

        // showDisambiguationOverrides map
        appendMap(xml, "showDisambiguationOverrides", prefs.getShowDisambiguationOverrides());

        xml.append("</preferences>");

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // Overwrite any existing file
            Files.writeString(path, xml.toString());
        } catch (
            IOException
            | UnsupportedOperationException
            | SecurityException e
        ) {
            logger.log(
                Level.SEVERE,
                "Exception occurred when writing preferences file '" +
                    path.toAbsolutePath() +
                    "'",
                e
            );
        }
    }

    /**
     * Load the preferences from path.
     *
     * @param path the path to read
     * @return the populated preferences object
     */
    @SuppressWarnings("SameParameterValue")
    public static UserPreferences retrieve(Path path) {
        if (Files.notExists(path)) {
            // If file doesn't exist, assume defaults
            logger.fine(
                "Preferences file '" +
                    path.toAbsolutePath() +
                    "' does not exist - assuming defaults"
            );
            return null;
        }

        try (InputStream in = Files.newInputStream(path)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Harden against XXE
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);

            Element root = doc.getDocumentElement();

            // Parse scalar fields
            Map<String, String> scalars = new LinkedHashMap<>();
            for (String field : SCALAR_FIELDS) {
                String value = getElementText(root, field);
                if (value != null) {
                    scalars.put(field, value);
                }
            }

            // Parse ignoreKeywords list
            List<String> keywords = parseStringList(root, "ignoreKeywords");

            // Parse map fields
            Map<String, String> nameOverrides = parseStringMap(root, "showNameOverrides");
            Map<String, String> disambigOverrides = parseStringMap(root, "showDisambiguationOverrides");

            return UserPreferences.fromParsedXml(
                scalars, keywords, nameOverrides, disambigOverrides
            );
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            logger.log(
                Level.SEVERE,
                "Exception reading preferences file '" +
                    path.toAbsolutePath() +
                    "'",
                e
            );
            logger.info("assuming default preferences");
            return null;
        } catch (Exception e) {
            // Catches ParserConfigurationException, SAXException
            logger.log(
                Level.SEVERE,
                "Exception parsing preferences XML from '" +
                    path.toAbsolutePath() +
                    "'",
                e
            );
            logger.info("assuming default preferences");
            return null;
        }
    }

    /**
     * Get the text content of the first direct child element with the given tag name.
     * Returns null if no such element exists.
     */
    private static String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        // Use the first match that is a direct child of parent
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getParentNode() == parent) {
                String text = node.getTextContent();
                return (text != null) ? text.trim() : null;
            }
        }
        return null;
    }

    /**
     * Parse a list of strings from a container element.
     * Expects: {@code <containerTag><string>val1</string><string>val2</string></containerTag>}
     */
    private static List<String> parseStringList(Element root, String containerTag) {
        NodeList containers = root.getElementsByTagName(containerTag);
        if (containers.getLength() == 0) {
            return null;
        }
        Element container = (Element) containers.item(0);
        NodeList items = container.getElementsByTagName("string");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            String text = items.item(i).getTextContent();
            if (text != null) {
                result.add(text.trim());
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Parse a map of strings from a container element.
     * Expects: {@code <containerTag><entry><string>key</string><string>value</string></entry></containerTag>}
     */
    private static Map<String, String> parseStringMap(Element root, String containerTag) {
        NodeList containers = root.getElementsByTagName(containerTag);
        if (containers.getLength() == 0) {
            return null;
        }
        Element container = (Element) containers.item(0);
        NodeList entries = container.getElementsByTagName("entry");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            NodeList strings = entry.getElementsByTagName("string");
            if (strings.getLength() >= 2) {
                String key = strings.item(0).getTextContent();
                String value = strings.item(1).getTextContent();
                if (key != null && value != null) {
                    result.put(key.trim(), value.trim());
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static void appendElement(StringBuilder sb, String name, String value) {
        appendElement(sb, name, value, 2);
    }

    private static void appendElement(StringBuilder sb, String name, String value, int indent) {
        String spaces = " ".repeat(indent);
        sb.append(spaces).append('<').append(name).append('>');
        sb.append(escapeXml(value));
        sb.append("</").append(name).append(">\n");
    }

    private static void appendElement(StringBuilder sb, String name, boolean value) {
        appendElement(sb, name, String.valueOf(value));
    }

    private static void appendElement(StringBuilder sb, String name, long value) {
        appendElement(sb, name, String.valueOf(value));
    }

    private static void appendMap(StringBuilder sb, String name, Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        sb.append("  <").append(name).append(">\n");
        for (Map.Entry<String, String> entry : map.entrySet()) {
            sb.append("    <entry>\n");
            appendElement(sb, "string", entry.getKey(), 6);
            appendElement(sb, "string", entry.getValue(), 6);
            sb.append("    </entry>\n");
        }
        sb.append("  </").append(name).append(">\n");
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
