package com.kartikmodi.halo.home;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Headlines for Jarvis from Google News' public RSS feeds: free, no key. A category (world, UK,
 * business, technology, science, health, sport, entertainment) uses its section; anything else is a
 * news search; nothing gives the top stories. UK edition.
 */
final class News {

    private static final String EDITION = "hl=en-GB&gl=GB&ceid=GB:en";

    private News() {
    }

    /** Up to {@code max} headlines: {title, source}. */
    static JSONArray headlines(String topic, int max) throws Exception {
        String url;
        String section = section(topic);
        if (topic == null || topic.trim().isEmpty() || topic.matches("(?i)\\s*(top|latest|today'?s|the|all|general|main)?\\s*(news|headlines|stories)?\\s*")) {
            url = "https://news.google.com/rss?" + EDITION;
        } else if (section != null) {
            url = "https://news.google.com/rss/headlines/section/topic/" + section + "?" + EDITION;
        } else {
            url = "https://news.google.com/rss/search?q=" + URLEncoder.encode(topic.trim() + " when:2d", "UTF-8") + "&" + EDITION;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        JSONArray out = new JSONArray();
        try (InputStream in = c.getInputStream()) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            boolean inItem = false;
            String title = null, source = null;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT && out.length() < max; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    if (p.getName().equals("item")) { inItem = true; title = null; source = null; }
                    else if (inItem && p.getName().equals("title")) title = p.nextText();
                    else if (inItem && p.getName().equals("source")) source = p.nextText();
                } else if (e == XmlPullParser.END_TAG && p.getName().equals("item")) {
                    inItem = false;
                    if (title != null) {
                        // Google appends " - Source" to each title.
                        if (source != null && title.endsWith(" - " + source)) title = title.substring(0, title.length() - source.length() - 3);
                        out.put(new JSONObject().put("title", title.trim()).put("source", source == null ? "" : source));
                    }
                }
            }
        }
        return out;
    }

    private static String section(String topic) {
        if (topic == null) return null;
        String t = topic.toLowerCase(Locale.UK);
        if (t.matches(".*\\b(world|international|global)\\b.*")) return "WORLD";
        if (t.matches(".*\\b(uk|britain|british|national|local)\\b.*")) return "NATION";
        if (t.matches(".*\\b(business|money|markets?|finance|economy|stocks?)\\b.*")) return "BUSINESS";
        if (t.matches(".*\\b(tech|technology|gadgets?|ai)\\b.*")) return "TECHNOLOGY";
        if (t.matches(".*\\b(science|space)\\b.*")) return "SCIENCE";
        if (t.matches(".*\\b(health|medical)\\b.*")) return "HEALTH";
        if (t.matches(".*\\b(sports?|football|cricket)\\b.*") && !t.matches(".*\\b(arsenal|chelsea|liverpool|united|city|india|england)\\b.*")) return "SPORTS";
        if (t.matches(".*\\b(entertainment|celebrity|celebrities|films?|movies?|music|showbiz)\\b.*")) return "ENTERTAINMENT";
        return null;
    }
}
