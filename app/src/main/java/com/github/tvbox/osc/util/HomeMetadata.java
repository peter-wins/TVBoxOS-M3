package com.github.tvbox.osc.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/** Description-only parsing: never resolves playback URLs or starts a player. */
public final class HomeMetadata {
    public final String description;

    private HomeMetadata(String description) {
        this.description = clean(description);
    }

    public static HomeMetadata fromSource(String body, boolean xml, String expectedId, String expectedName) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            if (xml) {
                Document document = Jsoup.parse(body, "", Parser.xmlParser());
                for (Element video : document.select("list > video")) {
                    if (matches(text(video, "id"), text(video, "name"), expectedId, expectedName)) {
                        return nonEmpty(text(video, "des"));
                    }
                }
            } else {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonArray list = root.getAsJsonArray("list");
                if (list == null) return null;
                for (JsonElement entry : list) {
                    if (!entry.isJsonObject()) continue;
                    JsonObject video = entry.getAsJsonObject();
                    if (matches(value(video, "vod_id"), value(video, "vod_name"), expectedId, expectedName)) {
                        return nonEmpty(value(video, "vod_content"));
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static HomeMetadata fromDescription(String description) {
        return nonEmpty(description);
    }

    public static boolean sameTitle(String first, String second) {
        String a = clean(first).replaceAll("\\s+", "");
        String b = clean(second).replaceAll("\\s+", "");
        return !a.isEmpty() && a.equalsIgnoreCase(b);
    }

    /** Ambiguous remakes are deliberately not chosen just for having the same title. */
    public static String findExactSourceId(String body, boolean xml, String name, int year) {
        String matched = null;
        try {
            if (xml) {
                for (Element video : Jsoup.parse(body, "", Parser.xmlParser()).select("list > video")) {
                    String id = text(video, "id");
                    if (!id.isEmpty() && sameTitle(text(video, "name"), name) && sameYear(text(video, "year"), year)) {
                        if (matched != null && !matched.equals(id)) return null;
                        matched = id;
                    }
                }
            } else {
                JsonArray list = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("list");
                if (list == null) return null;
                for (JsonElement entry : list) {
                    if (!entry.isJsonObject()) continue;
                    JsonObject video = entry.getAsJsonObject();
                    String id = value(video, "vod_id");
                    if (!id.isEmpty() && sameTitle(value(video, "vod_name"), name) && sameYear(value(video, "vod_year"), year)) {
                        if (matched != null && !matched.equals(id)) return null;
                        matched = id;
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return matched;
    }

    private static boolean sameYear(String actual, int expected) {
        if (expected <= 0 || actual.isEmpty()) return true;
        try { return Integer.parseInt(actual.trim()) == expected; }
        catch (NumberFormatException ignored) { return true; }
    }

    public static HomeMetadata fromDouban(String html, String subjectId) {
        if (html == null || subjectId == null || !subjectId.matches("[0-9]+")) return null;
        Document document = Jsoup.parse(html);
        Element canonical = document.selectFirst("link[rel=canonical]");
        if (canonical != null && !canonical.attr("href").matches("https?://movie\\.douban\\.com/subject/" + subjectId + "/?")) {
            return null;
        }
        Element summary = document.selectFirst("#link-report .all.hidden");
        if (summary == null) summary = document.selectFirst("[property=v:summary]");
        return summary == null ? null : nonEmpty(summary.text());
    }

    private static boolean matches(String id, String name, String expectedId, String expectedName) {
        // Never attach a different result's plot just because it was first in the response.
        if (!id.isEmpty()) return id.equals(expectedId);
        return !clean(name).isEmpty() && clean(name).equals(clean(expectedName));
    }

    private static String value(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static String text(Element element, String selector) {
        Element child = element.selectFirst(selector);
        return child == null ? "" : child.text();
    }

    private static HomeMetadata nonEmpty(String text) {
        HomeMetadata data = new HomeMetadata(text);
        return data.description.isEmpty() ? null : data;
    }

    public static String clean(String html) {
        if (html == null) return "";
        Document document = Jsoup.parse(html);
        document.select("script,style").remove();
        return document.text().replace('\u00a0', ' ').trim();
    }
}
