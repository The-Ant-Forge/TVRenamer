package org.tvrenamer.controller.util;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Shared XML utilities for parsing and building XML documents.
 */
public class XmlUtilities {

    /**
     * Create a DocumentBuilder with XXE (external entity) processing disabled.
     * This is a security precaution when parsing XML from any source.
     *
     * @return a hardened DocumentBuilder
     * @throws ParserConfigurationException if the factory cannot be configured
     */
    public static DocumentBuilder createDocumentBuilder()
        throws ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(
            "http://apache.org/xml/features/disallow-doctype-decl", true
        );
        dbf.setFeature(
            "http://xml.org/sax/features/external-general-entities", false
        );
        dbf.setFeature(
            "http://xml.org/sax/features/external-parameter-entities", false
        );
        return dbf.newDocumentBuilder();
    }

    /**
     * Parse a map of strings from a container element.
     * Expects: {@code <containerTag><entry><string>key</string><string>value</string></entry></containerTag>}
     *
     * @param root the parent element to search within
     * @param containerTag the tag name of the container element
     * @return the parsed map, or null if the container is missing or empty
     */
    public static Map<String, String> parseStringMap(Element root, String containerTag) {
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
}
