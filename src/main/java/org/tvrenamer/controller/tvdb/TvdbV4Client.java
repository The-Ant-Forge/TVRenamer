package org.tvrenamer.controller.tvdb;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.tvrenamer.controller.tvdb.TvdbV4Transport.TvdbHttpResponse;
import org.tvrenamer.model.TVRenamerIOException;

/** Handles v4 auth (bearer token, reactive re-login) and raw JSON fetches. */
public class TvdbV4Client {

    static final String BASE_URL = "https://api4.thetvdb.com/v4";

    private static final Gson GSON = new Gson();

    private final TvdbV4Transport transport;
    private final Supplier<String> apiKeySupplier;
    private final Object loginLock = new Object();
    private volatile String token;

    public TvdbV4Client(TvdbV4Transport transport, Supplier<String> apiKeySupplier) {
        this.transport = transport;
        this.apiKeySupplier = apiKeySupplier;
    }

    public String searchSeriesJson(String query) throws TVRenamerIOException {
        String enc = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        return authedGet("/search?query=" + enc + "&type=series");
    }

    public String seriesJson(int seriesId) throws TVRenamerIOException {
        return authedGet("/series/" + seriesId);
    }

    public String episodesJson(int seriesId, String seasonType, String lang, int page)
        throws TVRenamerIOException {
        String base = "/series/" + seriesId + "/episodes/" + seasonType;
        if (lang != null && !lang.isBlank()) {
            base = base + "/" + lang;
        }
        return authedGet(base + "?page=" + page);
    }

    public String seriesTranslationJson(int seriesId, String lang)
        throws TVRenamerIOException {
        return authedGet("/series/" + seriesId + "/translations/" + lang);
    }

    /** Logs in unconditionally. Callers must hold {@link #loginLock}. */
    private void login() throws TVRenamerIOException {
        String key = apiKeySupplier.get();
        if (key == null || key.trim().isEmpty()) {
            throw new TVRenamerIOException("TheTVDB v4 API key not configured");
        }
        Map<String, String> headers = jsonHeaders();
        String body = GSON.toJson(Map.of("apikey", key.trim()));
        try {
            TvdbHttpResponse resp = transport.post(BASE_URL + "/login", body, headers);
            if (resp.status() != 200) {
                throw new TVRenamerIOException(
                    "TheTVDB v4 login failed (HTTP " + resp.status() + ")");
            }
            JsonObject o = GSON.fromJson(resp.body(), JsonObject.class);
            String t = null;
            if (o != null && o.has("data") && o.get("data").isJsonObject()) {
                JsonObject data = o.getAsJsonObject("data");
                if (data.has("token") && data.get("token").isJsonPrimitive()) {
                    t = data.get("token").getAsString();
                }
            }
            if (t == null || t.isEmpty()) {
                throw new TVRenamerIOException("TheTVDB v4 login returned no token");
            }
            token = t;
        } catch (TVRenamerIOException tve) {
            // Already a domain exception (e.g. non-200 status, missing token):
            // propagate as-is so its message isn't re-wrapped by the IOException
            // handler below (TVRenamerIOException extends IOException).
            throw tve;
        } catch (IOException e) {
            throw new TVRenamerIOException("TheTVDB v4 login error: " + e.getMessage(), e);
        }
    }

    /**
     * Logs in only if no token has been obtained yet. Double-checks under
     * {@link #loginLock} so a pool of threads racing on the first call don't
     * each fire a redundant login.
     */
    private void ensureLoggedIn() throws TVRenamerIOException {
        if (token == null) {
            synchronized (loginLock) {
                if (token == null) {
                    login();
                }
            }
        }
    }

    /**
     * Re-logs in only if {@code token} is still the stale value that produced
     * a 401. If another thread already refreshed it while this one was
     * waiting on the lock, that refreshed token is reused instead of firing a
     * second redundant login.
     */
    private void reloginIfStillStale(String staleToken) throws TVRenamerIOException {
        synchronized (loginLock) {
            if (Objects.equals(token, staleToken)) {
                login();
            }
        }
    }

    private String authedGet(String path) throws TVRenamerIOException {
        ensureLoggedIn();
        try {
            String tokenForRequest = token;
            TvdbHttpResponse resp = transport.get(BASE_URL + path, authHeaders(tokenForRequest));
            if (resp.status() == 401) {
                // token expired (or about to be): re-login once, unless
                // another thread already refreshed it, then retry once.
                reloginIfStillStale(tokenForRequest);
                resp = transport.get(BASE_URL + path, authHeaders(token));
            }
            if (resp.status() != 200) {
                throw new TVRenamerIOException(
                    "TheTVDB v4 request failed (HTTP " + resp.status() + "): " + path);
            }
            return resp.body();
        } catch (TVRenamerIOException tve) {
            // Already a domain exception (non-200 status, or thrown by a nested
            // re-login): propagate as-is so its message isn't re-wrapped by the
            // IOException handler below (TVRenamerIOException extends IOException).
            throw tve;
        } catch (IOException e) {
            throw new TVRenamerIOException("TheTVDB v4 request error: " + e.getMessage(), e);
        }
    }

    private Map<String, String> jsonHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        return h;
    }

    private Map<String, String> authHeaders(String bearerToken) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Authorization", "Bearer " + bearerToken);
        return h;
    }
}
