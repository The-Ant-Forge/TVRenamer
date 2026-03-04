package org.tvrenamer.controller;

import static org.tvrenamer.controller.util.XPathUtilities.nodeListValue;
import static org.tvrenamer.controller.util.XPathUtilities.nodeTextValue;
import static org.tvrenamer.model.util.Constants.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import org.tvrenamer.controller.util.StringUtils;
import org.tvrenamer.model.DiscontinuedApiException;
import org.tvrenamer.model.EpisodeInfo;
import org.tvrenamer.model.Series;
import org.tvrenamer.model.ShowName;
import org.tvrenamer.model.TVRenamerIOException;
import org.tvrenamer.model.UserPreferences;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class TheTVDBProvider {

    private static final Logger logger = Logger.getLogger(
        TheTVDBProvider.class.getName()
    );

    // The unique API key for our application
    private static final String API_KEY = "4A9560FF0B2670B2";

    // Whether or not we should try making v1 API calls
    private static volatile boolean apiIsDeprecated = false;

    // The base information for the provider
    private static final String DEFAULT_SITE_URL = "https://thetvdb.com/";
    private static final String API_URL = DEFAULT_SITE_URL + "api/";

    // The URL to get, to receive options for a given series search string.
    // Note, does not take API key.
    private static final String BASE_SEARCH_URL =
        API_URL + "GetSeries.php?seriesname=";

    // These are the tags that we use to extract the relevant information from the show document.
    private static final String XPATH_SERIES = "/Data/Series";
    private static final String XPATH_SERIES_ID = "seriesid";
    private static final String XPATH_NAME = "SeriesName";
    private static final String XPATH_FIRST_AIRED = "FirstAired";
    private static final String XPATH_ALIAS_NAMES = "AliasNames";
    private static final String SERIES_NOT_PERMITTED =
        "** 403: Series Not Permitted **";

    // The URL to get, to receive listings for a specific given series.
    private static final String BASE_LIST_URL = API_URL + API_KEY + "/series/";
    private static final String BASE_LIST_FILENAME =
        "/all/" + DEFAULT_LANGUAGE + XML_SUFFIX;

    // These are the tags that we use to extract the episode information
    // from the listings document.
    private static final String XPATH_EPISODE_LIST = "/Data/Episode";
    private static final String XPATH_EPISODE_ID = "id";
    private static final String XPATH_SEASON_NUM = "SeasonNumber";
    private static final String XPATH_EPISODE_NUM = "EpisodeNumber";
    private static final String XPATH_EPISODE_NAME = "EpisodeName";
    private static final String XPATH_AIRDATE = "FirstAired";
    private static final String XPATH_DVD_SEASON_NUM = "DVD_season";
    private static final String XPATH_DVD_EPISODE_NUM = "DVD_episodenumber";

    private static String getShowSearchXml(final String queryString)
        throws TVRenamerIOException, DiscontinuedApiException {
        if (apiIsDeprecated) {
            throw new DiscontinuedApiException();
        }

        String searchURL =
            BASE_SEARCH_URL + StringUtils.encodeUrlQueryParam(queryString);

        logger.log(Level.FINE, () -> "About to download search results from " + searchURL);

        String content = new HttpConnectionHandler().downloadUrl(searchURL);

        // Do not post-process the XML payload. Any string "encoding" here risks corrupting
        // the provider response (e.g., changing entity handling or whitespace).
        return content;
    }

    private static String getSeriesListingXml(final Series series)
        throws TVRenamerIOException, DiscontinuedApiException {
        if (apiIsDeprecated) {
            throw new DiscontinuedApiException();
        }

        int seriesId = series.getId();
        String seriesURL = BASE_LIST_URL + seriesId + BASE_LIST_FILENAME;

        logger.log(Level.FINE, () -> "Downloading episode listing from " + seriesURL);

        String content = new HttpConnectionHandler().downloadUrl(seriesURL);

        // Do not post-process the XML payload. Any string "encoding" here risks corrupting
        // the provider response (e.g., changing entity handling or whitespace).
        return content;
    }

    private static void collectShowOptions(
        final NodeList shows,
        final ShowName showName
    ) throws XPathExpressionException {
        for (int i = 0; i < shows.getLength(); i++) {
            Node eNode = shows.item(i);
            String seriesName = nodeTextValue(XPATH_NAME, eNode);
            String tvdbId = nodeTextValue(XPATH_SERIES_ID, eNode);

            String firstAired = nodeTextValue(XPATH_FIRST_AIRED, eNode);
            Integer firstAiredYear = null;
            if (firstAired != null && firstAired.length() >= 4) {
                try {
                    firstAiredYear = Integer.parseInt(
                        firstAired.substring(0, 4)
                    );
                } catch (NumberFormatException e) {
                    logger.log(Level.FINE, () -> "Could not parse year from firstAired: " + firstAired);
                }
            }

            String aliasNamesRaw = nodeTextValue(XPATH_ALIAS_NAMES, eNode);
            List<String> aliasNames = Collections.emptyList();
            if (aliasNamesRaw != null) {
                String trimmed = aliasNamesRaw.trim();
                if (!trimmed.isEmpty()) {
                    // TVDB v1 often returns pipe-delimited alias lists like: |Alias A|Alias B|
                    String[] parts = trimmed.split("\\|");
                    ArrayList<String> aliases = new ArrayList<>();
                    for (String p : parts) {
                        String a = p.trim();
                        if (!a.isEmpty()) {
                            aliases.add(a);
                        }
                    }
                    if (!aliases.isEmpty()) {
                        aliasNames = Collections.unmodifiableList(aliases);
                    }
                }
            }

            if (SERIES_NOT_PERMITTED.equals(seriesName)) {
                logger.warning(
                    "ignoring unpermitted option for " +
                        showName.getExampleFilename()
                );
            } else {
                showName.addShowOption(
                    tvdbId,
                    seriesName,
                    firstAiredYear,
                    aliasNames
                );
            }
        }
    }

    private static void readShowsFromInputSource(
        final DocumentBuilder bld,
        final InputSource searchXmlSource,
        final ShowName showName
    ) throws TVRenamerIOException {
        try {
            Document doc = bld.parse(searchXmlSource);
            NodeList shows = nodeListValue(XPATH_SERIES, doc);
            collectShowOptions(shows, showName);
        } catch (
            SAXException
            | XPathExpressionException
            | DOMException
            | IOException e
        ) {
            logger.log(Level.WARNING, ERROR_PARSING_XML, e);
            throw new TVRenamerIOException(ERROR_PARSING_XML, e);
        }
    }

    /**
     * Create a DocumentBuilder with XXE (external entity) processing disabled.
     * Since the XML comes from an external network source, this is a security
     * precaution against XXE attacks.
     */
    private static DocumentBuilder createDocumentBuilder()
        throws TVRenamerIOException {
        try {
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
        } catch (ParserConfigurationException e) {
            logger.log(
                Level.WARNING,
                "could not create DocumentBuilder: " + e.getMessage(),
                e
            );
            throw new TVRenamerIOException(ERROR_PARSING_XML, e);
        }
    }

    private static synchronized boolean isApiDiscontinuedError(Throwable e) {
        if (apiIsDeprecated) {
            return true;
        }
        while (e != null) {
            if (e instanceof FileNotFoundException) {
                apiIsDeprecated = true;
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    /**
     * Fetch the show options from the provider, for the given show name.
     *
     * @param showName
     *   the show name to fetch the options for
     * @throws DiscontinuedApiException if it appears that the API we are using
     *   is no longer supported
     * @throws TVRenamerIOException if anything else goes wrong; this could
     *   include network difficulties or difficulty parsing the XML.
     */
    public static void getShowOptions(final ShowName showName)
        throws TVRenamerIOException, DiscontinuedApiException {
        // Reset accumulated options so repeated lookups within the same session
        // (e.g., add/remove cycles) don't append duplicates into the resolve dialog.
        showName.clearShowOptions();

        DocumentBuilder bld = createDocumentBuilder();

        String searchXml = "";
        try {
            searchXml = getShowSearchXml(showName.getQueryString());
            InputSource source = new InputSource(new StringReader(searchXml));
            readShowsFromInputSource(bld, source, showName);
        } catch (TVRenamerIOException tve) {
            String msg =
                "error parsing XML from " +
                searchXml +
                " for series " +
                showName.getExampleFilename();
            if (isApiDiscontinuedError(tve)) {
                throw new DiscontinuedApiException();
            } else {
                logger.log(Level.WARNING, msg, tve);
            }
            throw new TVRenamerIOException(msg, tve);
        }
    }

    private static EpisodeInfo createEpisodeInfo(final Node eNode) {
        try {
            return new EpisodeInfo.Builder()
                .episodeId(nodeTextValue(XPATH_EPISODE_ID, eNode))
                .seasonNumber(nodeTextValue(XPATH_SEASON_NUM, eNode))
                .episodeNumber(nodeTextValue(XPATH_EPISODE_NUM, eNode))
                .episodeName(nodeTextValue(XPATH_EPISODE_NAME, eNode))
                .firstAired(nodeTextValue(XPATH_AIRDATE, eNode))
                .dvdSeason(nodeTextValue(XPATH_DVD_SEASON_NUM, eNode))
                .dvdEpisodeNumber(nodeTextValue(XPATH_DVD_EPISODE_NUM, eNode))
                .build();
        } catch (Exception e) {
            logger.log(Level.WARNING, "exception parsing episode", e);
        }
        return null;
    }

    private static NodeList getEpisodeList(final Series series)
        throws TVRenamerIOException {
        NodeList episodeList;

        DocumentBuilder dbf = createDocumentBuilder();

        try {
            String listingsXml = getSeriesListingXml(series);
            InputSource listingsXmlSource = new InputSource(
                new StringReader(listingsXml)
            );
            Document doc = dbf.parse(listingsXmlSource);
            episodeList = nodeListValue(XPATH_EPISODE_LIST, doc);
        } catch (XPathExpressionException | SAXException | DOMException e) {
            logger.log(
                Level.WARNING,
                "exception parsing episodes for " +
                    series +
                    ": " +
                    e.getMessage(),
                e
            );
            throw new TVRenamerIOException(ERROR_PARSING_XML, e);
        } catch (NumberFormatException nfe) {
            logger.log(Level.WARNING, nfe.getMessage(), nfe);
            throw new TVRenamerIOException(ERROR_PARSING_NUMBERS, nfe);
        } catch (IOException ioe) {
            logger.log(Level.WARNING, ioe.getMessage(), ioe);
            throw new TVRenamerIOException(DOWNLOADING_FAILED_MESSAGE, ioe);
        }
        return episodeList;
    }

    /**
     * Fetch the episode listings from the provider, for the given Series.
     *
     * @param series
     *   the Series to fetch the episode listings for
     * @throws TVRenamerIOException if anything goes wrong; this could include
     *   network difficulties, difficulty parsing the XML, or problems parsing
     *   data expected to be numeric.
     */
    public static void getSeriesListing(final Series series)
        throws TVRenamerIOException {
        try {
            NodeList episodes = getEpisodeList(series);
            int episodeCount = episodes.getLength();

            EpisodeInfo[] episodeInfos = new EpisodeInfo[episodeCount];
            for (int i = 0; i < episodeCount; i++) {
                episodeInfos[i] = createEpisodeInfo(episodes.item(i));
            }

            // Apply user preference: prefer DVD ordering/titles when available, otherwise fall back to aired.
            series.setPreferDvd(
                UserPreferences.getInstance().isPreferDvdOrderIfPresent()
            );

            series.addEpisodeInfos(episodeInfos);
            series.listingsSucceeded();
        } catch (DOMException dom) {
            logger.log(Level.WARNING, dom.getMessage(), dom);
            throw new TVRenamerIOException(ERROR_PARSING_XML, dom);
        } catch (NumberFormatException nfe) {
            logger.log(Level.WARNING, nfe.getMessage(), nfe);
            throw new TVRenamerIOException(ERROR_PARSING_NUMBERS, nfe);
        } catch (IOException ioe) {
            logger.log(Level.WARNING, ioe.getMessage(), ioe);
            throw new TVRenamerIOException(DOWNLOADING_FAILED_MESSAGE, ioe);
        }
    }
}
