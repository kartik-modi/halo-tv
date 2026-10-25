package com.kartikmodi.halo.tonight;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * You, as a graph, kept in the app's private storage on the TV (taste.json, a few KB).
 * Nodes: every title opened from TONIGHT., and the genres, lead actors, services, themes and moods they
 * carry. Edges join a title to each of those, weighted. Genres, people and services are linked here as soon
 * as a title is opened; themes and moods are added by Claude (Taste). A node's weight is the sum of its
 * edges, with older openings counting a little less, so the graph drifts with what you watch now.
 */
final class TasteGraph {
    private static final String TAG = "TonightTaste";
    private static final int MAX_HISTORY = 120;
    private final File file;
    private JSONObject root;

    TasteGraph(Context ctx) {
        file = new File(ctx.getFilesDir(), "taste.json");
        try {
            root = file.isFile() ? new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : null;
        } catch (Exception e) {
            Log.w(TAG, "taste.json unreadable, starting over", e);
        }
        if (root == null) root = new JSONObject();
        try {
            if (!root.has("history")) root.put("history", new JSONArray());
            if (!root.has("nodes")) root.put("nodes", new JSONObject());
            if (!root.has("edges")) root.put("edges", new JSONArray());
        } catch (Exception ignored) {
        }
    }

    /** Records an opened title and links it to its genres, lead and service. */
    synchronized void opened(Title t) {
        try {
            JSONObject h = new JSONObject().put("key", t.key()).put("name", t.name).put("series", t.series)
                    .put("year", t.year).put("genres", new JSONArray(t.genreNames)).put("lead", t.leadName == null ? "" : t.leadName)
                    .put("service", t.service == null ? "" : t.service).put("at", System.currentTimeMillis()).put("themed", false);
            JSONArray hist = root.getJSONArray("history");
            JSONArray kept = new JSONArray();
            kept.put(h);
            for (int i = 0; i < hist.length() && kept.length() < MAX_HISTORY; i++)
                if (!t.key().equals(hist.getJSONObject(i).optString("key"))) kept.put(hist.getJSONObject(i));
            root.put("history", kept);
            String title = "title:" + t.key();
            node(title, "title", t.name);
            for (String g : t.genreNames) link(title, node("genre:" + g, "genre", g), 1.0);
            if (t.leadName != null) link(title, node("person:" + t.leadName, "person", t.leadName), 0.6);
            if (t.service != null) link(title, node("service:" + t.service, "service", t.service), 0.3);
            save();
        } catch (Exception e) {
            Log.w(TAG, "could not record " + t.name, e);
        }
    }

    /** Titles whose themes and moods Claude has not read yet. */
    synchronized List<JSONObject> unthemed() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray hist = root.getJSONArray("history");
            for (int i = 0; i < hist.length(); i++) if (!hist.getJSONObject(i).optBoolean("themed")) out.add(hist.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Adds Claude's themes and moods for one title, and marks it read. */
    synchronized void addThemes(String key, List<String> themes, List<String> moods) {
        try {
            String title = "title:" + key;
            for (String th : themes) link(title, node("theme:" + th.toLowerCase(), "theme", th.toLowerCase()), 0.8);
            for (String m : moods) link(title, node("mood:" + m.toLowerCase(), "mood", m.toLowerCase()), 0.6);
            JSONArray hist = root.getJSONArray("history");
            for (int i = 0; i < hist.length(); i++) if (key.equals(hist.getJSONObject(i).optString("key"))) hist.getJSONObject(i).put("themed", true);
        } catch (Exception e) {
            Log.w(TAG, "themes for " + key, e);
        }
    }

    synchronized void setProfile(JSONObject profile) {
        try {
            profile.put("updated", System.currentTimeMillis());
            root.put("profile", profile);
            save();
        } catch (Exception e) {
            Log.w(TAG, "profile", e);
        }
    }

    synchronized JSONObject profile() {
        return root.optJSONObject("profile");
    }

    synchronized int size() {
        return root.optJSONArray("history") == null ? 0 : root.optJSONArray("history").length();
    }

    synchronized boolean watched(String key) {
        return root.optJSONObject("nodes") != null && root.optJSONObject("nodes").has("title:" + key);
    }

    /**
     * The graph as Claude reads it: the strongest traits with their weights and the titles behind each,
     * plus the latest history. Recent openings weigh more (half weight after about 60 days).
     */
    synchronized String describe(int maxTraits) {
        try {
            JSONArray hist = root.getJSONArray("history");
            JSONObject when = new JSONObject();
            for (int i = 0; i < hist.length(); i++) when.put("title:" + hist.getJSONObject(i).optString("key"), hist.getJSONObject(i).optLong("at"));
            long now = System.currentTimeMillis();
            JSONObject weight = new JSONObject(), behind = new JSONObject();
            JSONArray edges = root.getJSONArray("edges");
            JSONObject nodes = root.getJSONObject("nodes");
            for (int i = 0; i < edges.length(); i++) {
                JSONArray e = edges.getJSONArray(i);
                String a = e.getString(0), b = e.getString(1);
                double days = (now - when.optLong(a, now)) / 86_400_000.0;
                double w = e.getDouble(2) * Math.pow(0.5, days / 60.0);
                weight.put(b, weight.optDouble(b, 0) + w);
                JSONArray list = behind.optJSONArray(b);
                if (list == null) behind.put(b, list = new JSONArray());
                if (list.length() < 4) list.put(nodes.getJSONObject(a).optString("label"));
            }
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = weight.keys(); it.hasNext(); ) keys.add(it.next());
            keys.sort((x, y) -> Double.compare(weight.optDouble(y), weight.optDouble(x)));
            StringBuilder sb = new StringBuilder("TRAITS (type: label | weight | titles behind it)\n");
            for (int i = 0; i < keys.size() && i < maxTraits; i++) {
                JSONObject n = nodes.getJSONObject(keys.get(i));
                sb.append(n.optString("type")).append(": ").append(n.optString("label")).append(" | ")
                        .append(String.format(java.util.Locale.UK, "%.2f", weight.optDouble(keys.get(i))))
                        .append(" | ").append(join(behind.getJSONArray(keys.get(i)))).append('\n');
            }
            sb.append("\nRECENTLY OPENED (newest first)\n");
            for (int i = 0; i < hist.length() && i < 25; i++) {
                JSONObject h = hist.getJSONObject(i);
                sb.append("- ").append(h.optString("name")).append(" (").append(h.optBoolean("series") ? "series" : "film")
                        .append(", ").append(h.optString("year")).append(")\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** The wheel genres the graph leans to, strongest first. */
    synchronized List<String> topGenres(int n) {
        List<String> out = new ArrayList<>();
        JSONObject p = profile();
        JSONArray g = p == null ? null : p.optJSONArray("genres");
        if (g != null) for (int i = 0; i < g.length() && out.size() < n; i++) {
            String name = g.optJSONObject(i) == null ? null : g.optJSONObject(i).optString("name");
            if (name != null && Tmdb.wheelIndexOf(name) > 0) out.add(name);
        }
        return out;
    }

    private String node(String id, String type, String label) throws Exception {
        JSONObject nodes = root.getJSONObject("nodes");
        if (!nodes.has(id)) nodes.put(id, new JSONObject().put("type", type).put("label", label));
        return id;
    }

    private void link(String a, String b, double w) throws Exception {
        JSONArray edges = root.getJSONArray("edges");
        for (int i = 0; i < edges.length(); i++) {
            JSONArray e = edges.getJSONArray(i);
            if (e.getString(0).equals(a) && e.getString(1).equals(b)) return;
        }
        edges.put(new JSONArray().put(a).put(b).put(w));
    }

    synchronized void save() {
        try {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file)) Log.w(TAG, "could not save taste.json");
        } catch (Exception e) {
            Log.w(TAG, "save", e);
        }
    }

    private static String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) sb.append(i > 0 ? ", " : "").append(a.optString(i));
        return sb.toString();
    }
}
