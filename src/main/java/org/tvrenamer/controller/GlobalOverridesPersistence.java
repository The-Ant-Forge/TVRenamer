package org.tvrenamer.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.tvrenamer.model.GlobalOverrides;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class GlobalOverridesPersistence {

    private static final Logger logger = Logger.getLogger(
        GlobalOverridesPersistence.class.getName()
    );

    /**
     * Save the overrides object to the file.
     *
     * @param overrides the overrides object to save
     * @param path the path to save it to
     */
    @SuppressWarnings("SameParameterValue")
    public static void persist(GlobalOverrides overrides, Path path) {
        Map<String, String> showNames = overrides.getShowNames();

        StringBuilder xml = new StringBuilder();
        xml.append("<overrides>\n");

        if (showNames != null && !showNames.isEmpty()) {
            xml.append("  <showNames>\n");
            for (Map.Entry<String, String> entry : showNames.entrySet()) {
                xml.append("    <entry>\n");
                xml.append("      <string>").append(escapeXml(entry.getKey())).append("</string>\n");
                xml.append("      <string>").append(escapeXml(entry.getValue())).append("</string>\n");
                xml.append("    </entry>\n");
            }
            xml.append("  </showNames>\n");
        }

        xml.append("</overrides>");

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
                "Exception occurred when writing overrides file '" +
                    path.toAbsolutePath() +
                    "'",
                e
            );
        }
    }

    /**
     * Load the overrides from path.
     *
     * @param path the path to read
     * @return the populated overrides object
     */
    @SuppressWarnings("SameParameterValue")
    public static GlobalOverrides retrieve(Path path) {
        if (Files.notExists(path)) {
            // If file doesn't exist, assume defaults
            logger.fine(
                "Overrides file '" +
                    path.toAbsolutePath() +
                    "' does not exist - assuming no overrides"
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

            // Parse showNames map
            Map<String, String> showNames = parseStringMap(root, "showNames");

            return GlobalOverrides.fromParsedXml(showNames);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            logger.log(
                Level.SEVERE,
                "Exception reading overrides file '" +
                    path.toAbsolutePath() +
                    "'",
                e
            );
            logger.info("assuming no overrides");
            return null;
        } catch (Exception e) {
            // Catches ParserConfigurationException, SAXException
            logger.log(
                Level.SEVERE,
                "Exception parsing overrides XML from '" +
                    path.toAbsolutePath() +
                    "'",
                e
            );
            logger.info("assuming no overrides");
            return null;
        }
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
