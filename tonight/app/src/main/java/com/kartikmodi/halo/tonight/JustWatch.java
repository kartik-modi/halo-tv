package com.kartikmodi.halo.tonight;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Opens a title straight in its app. JustWatch (where TMDB's "available on" data comes from) knows each
 * service's own address for a title. Netflix plays a watch link; every other service gets its title link sent to
 * its own app (Disney+ and Prime Video open the title page, tested on a Fire TV on 22 Oct 2026). When there is no
 * link, a service with a search link opens it with the title typed in, and any other app (Netflix included) simply
 * opens, so the viewer is one search away.
 */
final class JustWatch {
    private JustWatch() {}

    private static final String QUERY = "query S($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter) {"
            + " popularTitles(country: $country, first: $first, filter: $filter) { edges { node {"
            + " objectType content(country: $country, language: $language) { externalIds { tmdbId } }"
            + " offers(country: $country, platform: WEB) { monetizationType package { technicalName } standardWebURL }"
            + " } } } }";

    /** The intent that opens this title in its service's app. Blocking: call off the main thread. */
    static Intent intentFor(Context c, Title t) {
        Services.Service svc = Services.of(t.service);
        if (svc == null && !Services.ALL.isEmpty()) svc = Services.ALL.get(0);
        if (svc == null) return new Intent(Intent.ACTION_MAIN);
        String url = null;
        try {
            url = lookup(t, svc);
        } catch (Exception ignored) {
            // the search fallback below still works
        }
        String pkg = svc.installedPackage(c);
        if (svc.netflix()) {
            String id = url == null ? null : url.replaceAll(".*netflix\\.com/title/(\\d+).*", "$1");
            Intent i = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(pkg, pkg + ".MainActivity"));
            if (id != null && id.matches("\\d+")) i.setData(Uri.parse("http://www.netflix.com/watch/" + id)).putExtra("source", "30");
            else i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, pkg + ".MainActivity"));
            return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (url == null && svc.search != null) url = String.format(svc.search, Uri.encode(t.name));
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | (svc.fresh ? Intent.FLAG_ACTIVITY_CLEAR_TASK : 0);
        PackageManager pm = c.getPackageManager();
        if (url != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(flags);
            if (pkg != null) i.setPackage(pkg);
            if (pkg == null || i.resolveActivity(pm) != null) return i;
        }
        // the app doesn't take links (or there is none): just open it
        Intent launch = pkg == null ? null : pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null && pkg != null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch != null) return launch.addFlags(flags);
        // not installed: an intent that fails, so the caller says so
        return new Intent(Intent.ACTION_MAIN).setPackage(pkg == null ? "none.installed" : pkg).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
    }

    /** JustWatch's address for this title on its service in the country, matched by TMDB id; null if none. */
    private static String lookup(Title t, Services.Service svc) throws Exception {
        JSONObject vars = new JSONObject().put("country", Tmdb.REGION).put("language", "en").put("first", 5)
                .put("filter", new JSONObject().put("searchQuery", t.name));
        String body = new JSONObject().put("query", QUERY).put("variables", vars).toString();
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        JSONObject d = new JSONObject(new String(Net.post("https://apis.justwatch.com/graphql", h, body), StandardCharsets.UTF_8));
        JSONArray edges = d.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
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
                if (pkg.startsWith(svc.justWatch)) return o.optString("standardWebURL", null);
            }
            return null;
        }
        return null;
    }
}
