package org.tvrenamer.controller.tvmaze;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.tvrenamer.controller.tvdb.JdkHttpTransport;
import org.tvrenamer.controller.tvdb.TvdbV4Transport;
import org.tvrenamer.controller.tvdb.TvdbV4Transport.TvdbHttpResponse;
import org.tvrenamer.model.TVRenamerIOException;

/** Keyless HTTP client for the TVMaze API (api.tvmaze.com). */
public class TvMazeClient {

    static final String BASE_URL = "https://api.tvmaze.com";
    private static final long DEFAULT_RETRY_DELAY_MS = 1500L;

    private final TvdbV4Transport transport;
    private final long retryDelayMs;

    public TvMazeClient() {
        this(new JdkHttpTransport(), DEFAULT_RETRY_DELAY_MS);
    }

    // Test seam: inject transport and a (possibly zero) retry delay.
    public TvMazeClient(TvdbV4Transport transport, long retryDelayMs) {
        this.transport = transport;
        this.retryDelayMs = retryDelayMs;
    }

    public String searchShowsJson(String query) throws TVRenamerIOException {
        String enc = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        return get("/search/shows?q=" + enc);
    }

    public String showJson(int showId) throws TVRenamerIOException {
        return get("/shows/" + showId);
    }

    public String episodesJson(int showId) throws TVRenamerIOException {
        return get("/shows/" + showId + "/episodes");
    }

    private String get(String path) throws TVRenamerIOException {
        Map<String, String> headers = Map.of("Accept", "application/json");
        try {
            TvdbHttpResponse resp = transport.get(BASE_URL + path, headers);
            if (resp.status() == 429) {
                // Rate limited: back off briefly and retry once.
                if (retryDelayMs > 0) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new TVRenamerIOException("TVMaze retry interrupted", ie);
                    }
                }
                resp = transport.get(BASE_URL + path, headers);
            }
            if (resp.status() != 200) {
                throw new TVRenamerIOException(
                    "TVMaze request failed (HTTP " + resp.status() + "): " + path);
            }
            return resp.body();
        } catch (TVRenamerIOException tve) {
            throw tve;
        } catch (IOException e) {
            throw new TVRenamerIOException("TVMaze request error: " + e.getMessage(), e);
        }
    }
}
