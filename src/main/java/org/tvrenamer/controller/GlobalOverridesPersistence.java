package org.tvrenamer.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import org.tvrenamer.controller.util.StringUtils;
import org.tvrenamer.controller.util.XmlUtilities;
import org.tvrenamer.model.GlobalOverrides;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

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
                xml.append("      <string>").append(StringUtils.escapeXml(entry.getKey())).append("</string>\n");
                xml.append("      <string>").append(StringUtils.escapeXml(entry.getValue())).append("</string>\n");
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
            Files.writeString(path, xml.toString(), java.nio.charset.StandardCharsets.UTF_8);
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
            DocumentBuilder builder = XmlUtilities.createDocumentBuilder();
            Document doc = builder.parse(in);

            Element root = doc.getDocumentElement();

            // Parse showNames map
            Map<String, String> showNames = XmlUtilities.parseStringMap(root, "showNames");

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

}
