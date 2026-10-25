package com.kartikmodi.halo.tonight;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Opens a title straight in its app. JustWatch (where TMDB's "available on" data comes from) knows each
 * service's own address for a title; these are the forms that work on this Fire TV, tested on 22 Oct 2026:
 * Netflix plays a watch link, Disney+ and Prime Video open the title page. When JustWatch has no address,
 * Disney+ and Prime Video open their search with the title typed in, and Netflix (which has no search link)
 * simply opens.
 */
final class JustWatch {
    private JustWatch() {}

    static final String NETFLIX_PKG = "com.netflix.ninja", DISNEY_PKG = "com.disney.disneyplus", PRIME_PKG = "com.amazon.firebat";

    private static final String QUERY = "query S($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter) {"
            + " popularTitles(country: $country, first: $first, filter: $filter) { edges { node {"
            + " objectType content(country: $country, language: $language) { externalIds { tmdbId } }"
            + " offers(country: $country, platform: WEB) { monetizationType package { technicalName } standardWebURL }"
            + " } } } }";

    /** The intent that opens this title in its service's app. Blocking: call off the main thread. */
    static Intent intentFor(Title t) {
        String url = null;
        try {
            url = lookup(t);
        } catch (Exception ignored) {
            // the search fallback below still works
        }
        String s = t.service == null ? "N" : t.service;
        String q = Uri.encode(t.name);
        switch (s) {
            case "N": {
                String id = url == null ? null : url.replaceAll(".*netflix\\.com/title/(\\d+).*", "$1");
                Intent i = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(NETFLIX_PKG, NETFLIX_PKG + ".MainActivity"));
                if (id != null && id.matches("\\d+")) i.setData(Uri.parse("http://www.netflix.com/watch/" + id)).putExtra("source", "30");
                else i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(NETFLIX_PKG, NETFLIX_PKG + ".MainActivity"));
                return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            case "D":
                // Disney+ is one singleTask screen that ignores a link while it is already showing something
                // (a paused film, say), so start it afresh: the link is then read as it opens.
                return new Intent(Intent.ACTION_VIEW, Uri.parse(url != null ? url : "https://www.disneyplus.com/search?q=" + q))
                        .setPackage(DISNEY_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            default:
                return new Intent(Intent.ACTION_VIEW, Uri.parse(url != null ? url : "https://app.primevideo.com/search?phrase=" + q))
                        .setPackage(PRIME_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
    }

    /** JustWatch's address for this title on its service in the UK, matched by TMDB id; null if none. */
    private static String lookup(Title t) throws Exception {
        JSONObject vars = new JSONObject().put("country", Tmdb.REGION).put("language", "en").put("first", 5)
                .put("filter", new JSONObject().put("searchQuery", t.name));
        String body = new JSONObject().put("query", QUERY).put("variables", vars).toString();
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        JSONObject d = new JSONObject(new String(Net.post("https://apis.justwatch.com/graphql", h, body), StandardCharsets.UTF_8));
        JSONArray edges = d.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        String want = t.service == null ? "" : t.service;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            String type = n.optString("objectType");
            if (t.series != "SHOW".equals(type)) continue;
            String tmdb = n.getJSONObject("content").getJSONObject("externalIds").optString("tmdbId");
            if (!String.valueOf(t.id).equals(tmdb)) continue;
            JSONArray offers = n.optJSONArray("offers");
            if (offers == null) return null;
            for (int k = 0; k < offers.length(); k++) {
                JSONObject o = offers.getJSONObject(k);
                if (!"FLATRATE".equals(o.optString("monetizationType"))) continue;
                String pkg = o.getJSONObject("package").optString("technicalName");
                boolean match = ("N".equals(want) && pkg.startsWith("netflix"))
                        || ("D".equals(want) && pkg.equals("disneyplus"))
                        || ("P".equals(want) && pkg.startsWith("amazonprime"));
                if (match) return o.optString("standardWebURL", null);
            }
            return null;
        }
        return null;
    }
}
