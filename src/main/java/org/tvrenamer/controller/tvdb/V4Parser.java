package org.tvrenamer.controller.tvdb;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.tvrenamer.model.EpisodeInfo;

/** Pure JSON -&gt; model parsing for v4 responses. No I/O. */
public final class V4Parser {

    private static final Gson GSON = new Gson();

    private V4Parser() {}

    public record V4SeriesResult(String tvdbId, String name, Integer year, List<String> aliases) {}

    public record V4EpisodesPage(List<EpisodeInfo> episodes, boolean hasNext) {}

    public static List<V4SeriesResult> parseSearchSeries(String json) {
        List<V4SeriesResult> out = new ArrayList<>();
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) {
            return out;
        }
        for (JsonElement el : root.getAsJsonArray("data")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "tvdb_id");
            String name = str(o, "name");
            if (id == null || name == null) {
                continue;
            }
            Integer year = parseYear(str(o, "year"));
            List<String> aliases = new ArrayList<>();
            if (o.has("aliases") && o.get("aliases").isJsonArray()) {
                for (JsonElement a : o.getAsJsonArray("aliases")) {
                    if (a.isJsonPrimitive()) {
                        aliases.add(a.getAsString());
                    }
                }
            }
            out.add(new V4SeriesResult(id, name, year, aliases));
        }
        return out;
    }

    /**
     * Parse a v4 /series/{id} response. Unlike search results, the id is a
     * number and each alias is an object with a {@code name}.
     *
     * @return the series, or null if the body has no usable id and name
     */
    public static V4SeriesResult parseSeries(String json) {
        JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
        if (root == null || !root.has("data") || !root.get("data").isJsonObject()) {
            return null;
        }
        JsonObject o = root.getAsJsonObject("data");
        String id = str(o, "id");
        String name = str(o, "name");
        if (id == null || name == null) {
            return null;
        }
        List<String> aliases = new ArrayList<>();
        if (o.has("aliases") && o.get("aliases").isJsonArray()) {
            for (JsonElement a : o.getAsJsonArray("aliases")) {
                String alias = a.isJsonObject() ? str(a.getAsJsonObject(), "name") : null;
                if (alias != null) {
                    aliases.add(alias);
                }
            }
        }
        return new V4SeriesResult(id, name, parseYear(str(o, "year")), aliases);
    }

    public static V4EpisodesPage parseEpisodes(String json) {
        List<EpisodeInfo> episodes = new ArrayList<>();
        boolean hasNext = false;
        JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
        } catch (JsonSyntaxException e) {
            // Malformed body (e.g. HTTP 200 with garbage): treat as an empty
            // page so the provider's dvd->default / language fallbacks proceed
            // instead of the whole listing failing.
            return new V4EpisodesPage(episodes, false);
        }
        if (root != null && root.has("data") && root.get("data").isJsonObject()) {
            JsonObject data = root.getAsJsonObject("data");
            if (data.has("episodes") && data.get("episodes").isJsonArray()) {
                for (JsonElement el : data.getAsJsonArray("episodes")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject e = el.getAsJsonObject();
                    String season = str(e, "seasonNumber");
                    String number = str(e, "number");
                    if (season == null || number == null) {
                        continue;
                    }
                    episodes.add(new EpisodeInfo.Builder()
                        .episodeId(str(e, "id"))
                        .seasonNumber(season)
                        .episodeNumber(number)
                        .episodeName(str(e, "name"))
                        .firstAired(str(e, "aired"))
                        .build());
                }
            }
        }
        if (root != null && root.has("links") && root.get("links").isJsonObject()) {
            JsonElement next = root.getAsJsonObject("links").get("next");
            hasNext = next != null && !next.isJsonNull();
        }
        return new V4EpisodesPage(episodes, hasNext);
    }

    /** Parse the translated series name from a v4 /series/{id}/translations/{lang} response. */
    public static String parseTranslationName(String json) {
        JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
        } catch (JsonSyntaxException e) {
            // Malformed body: no translation -> caller clears the override and
            // keeps the original series name.
            return null;
        }
        if (root == null || !root.has("data") || !root.get("data").isJsonObject()) {
            return null;
        }
        return str(root.getAsJsonObject("data"), "name");
    }

    /** Leading four-digit year of {@code yr}, or null if absent or not numeric. */
    private static Integer parseYear(String yr) {
        if (yr == null || yr.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(yr.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Return a trimmed string for key, or null if absent/JSON-null/blank. */
    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive()) {
            return null;
        }
        String s = el.getAsString();
        return (s == null || s.isEmpty()) ? null : s;
    }
}
