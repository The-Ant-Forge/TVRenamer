package org.tvrenamer.controller;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.tvrenamer.model.TVRenamerIOException;

class HttpConnectionHandler {

    private static final Logger logger = Logger.getLogger(
        HttpConnectionHandler.class.getName()
    );

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /**
     * Try to keep {@link #downloadUrl} as clean as possible by not doing error handling
     * there.  On any kind of failure, we'll get here.  We may need to re-discover something
     * that was already known, but it's really not an issue, especially since this only
     * happens in the error case.
     *
     * @param statusCode
     *   the HTTP status code from the response, or -1 if no response was received
     * @param url
     *   the URL we tried to download, as a String
     * @param cause
     *   an exception that may give some indication of what went wrong
     * @return
     *   does not actually return anything; always throws an exception
     * @throws TVRenamerIOException in all cases; the fact of this method being called
     *   means something went wrong; creates it from the given arguments
     */
    private String downloadUrlFailed(
        final int statusCode,
        final String url,
        final Exception cause
    ) throws TVRenamerIOException {
        String msg;
        if (cause == null) {
            String category = switch (statusCode / 100) {
                case 4 -> " (client error)";
                case 5 -> " (server error)";
                default -> "";
            };
            msg = "HTTP " + statusCode + category
                + " downloading " + url;
        } else {
            msg = "exception downloading " + url;
        }
        logger.log(Level.WARNING, msg, cause);
        throw new TVRenamerIOException(msg, cause);
    }

    /**
     * Download the URL and return as a String
     *
     * @param urlString the URL as a String
     * @return String of the contents
     * @throws TVRenamerIOException when there is an error connecting or reading the URL
     */
    public String downloadUrl(String urlString) throws TVRenamerIOException {
        logger.fine("Downloading URL " + urlString);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(urlString))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                String downloaded = response.body();
                logger.fine("Downloaded " + urlString + " (" + statusCode + ")");
                if (logger.isLoggable(Level.FINEST)) {
                    logger.log(
                        Level.FINEST,
                        "Url stream:\n{0}",
                        downloaded
                    );
                }
                return downloaded;
            } else if (statusCode == 404) {
                throw new FileNotFoundException(urlString);
            }

            // Preserve response details for diagnostics.
            return downloadUrlFailed(statusCode, urlString, null);
        } catch (IOException ioe) {
            return downloadUrlFailed(-1, urlString, ioe);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return downloadUrlFailed(-1, urlString, ie);
        }
    }
}
