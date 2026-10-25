package com.kartikmodi.halo.tonight;

import android.util.Log;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Claude Haiku 5.5, the cheapest current Claude model, does two jobs on the taste graph:
 * reads newly opened titles into themes and moods and rewrites the profile, and picks "For You" titles from
 * a live pool of what is on your services, with a one-line reason each. Each call is a few thousand tokens.
 */
final class Taste {
    private static final String TAG = "TonightTaste";
    private static final String MODEL = "claude-haiku-5-5";
    private static AnthropicClient client;

    private Taste() {}

    static boolean available() {
        return !BuildConfig.ANTHROPIC_KEY.isEmpty();
    }

    private static synchronized AnthropicClient client() throws Throwable {
        if (client == null) client = AnthropicOkHttpClient.builder().apiKey(BuildConfig.ANTHROPIC_KEY).build();
        return client;
    }

    /** Blocking. Themes and moods for unread titles, then a fresh profile. Returns false if the call failed. */
    static boolean grow(TasteGraph graph) {
        List<JSONObject> fresh = graph.unthemed();
        if (fresh.isEmpty() && graph.profile() != null) return true;
        StringBuilder titles = new StringBuilder();
        for (JSONObject h : fresh) {
            titles.append("- key=").append(h.optString("key")).append(" | ").append(h.optString("name")).append(" (")
                    .append(h.optBoolean("series") ? "series" : "film").append(", ").append(h.optString("year"))
                    .append(") genres: ").append(h.optJSONArray("genres")).append('\n');
        }
        String system = "You keep a viewer's taste graph for a TV app. Answer with one JSON object and nothing else.";
        String prompt = "Here is the viewer's taste graph so far:\n" + graph.describe(40)
                + "\nNewly opened titles to read:\n" + (titles.length() == 0 ? "(none)\n" : titles)
                + "\nReturn JSON:\n{\"titles\": [{\"key\": \"<key as given>\", \"themes\": [2-4 short themes, e.g. \"time loops\", \"found family\"], "
                + "\"moods\": [1-3 moods, e.g. \"tense\", \"cosy\"]}], "
                + "\"profile\": {\"summary\": \"one warm sentence about what this viewer loves, under 20 words\", "
                + "\"genres\": [{\"name\": \"<one of: " + String.join(", ", wheelGenres()) + ">\", \"weight\": 0.0-1.0}], "
                + "\"themes\": [up to 6 themes they keep coming back to], \"avoid\": [genres or themes they seem to skip]}}\n"
                + "Include every new title in \"titles\". Base the profile on the whole graph, recent titles weighing more.";
        JSONObject out = ask(system, prompt, 3000);
        if (out == null) return false;
        JSONArray ts = out.optJSONArray("titles");
        if (ts != null) for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            graph.addThemes(t.optString("key"), strings(t.optJSONArray("themes")), strings(t.optJSONArray("moods")));
        }
        JSONObject profile = out.optJSONObject("profile");
        if (profile != null) graph.setProfile(profile);
        else graph.save();
        return true;
    }

    /** A pick for "For You": a candidate key and why it fits. */
    static final class Pick {
        final String key;
        final String why;

        Pick(String key, String why) {
            this.key = key;
            this.why = why;
        }
    }

    /** Blocking. Claude ranks the live candidates against the graph; best first, at most 'max'. */
    static List<Pick> rank(TasteGraph graph, List<Title> candidates, int max) {
        StringBuilder cs = new StringBuilder();
        for (Title t : candidates) {
            String ov = t.overview.length() > 180 ? t.overview.substring(0, 180) + "…" : t.overview;
            List<String> g = new ArrayList<>();
            for (int id : t.genreIds) { String n = Tmdb.genreName(id, t.series); if (!n.isEmpty()) g.add(n); }
            cs.append("- key=").append(t.key()).append(" | ").append(t.name).append(" (").append(t.series ? "series" : "film")
                    .append(", ").append(t.year).append(") [").append(String.join(", ", g)).append("] ").append(ov).append('\n');
        }
        String system = "You pick what a viewer should watch tonight from their taste graph. Answer with one JSON object and nothing else.";
        String prompt = "Taste graph:\n" + graph.describe(30)
                + "\nProfile: " + (graph.profile() == null ? "{}" : graph.profile().toString())
                + "\n\nCandidates (all streaming on the viewer's services in the UK right now):\n" + cs
                + "\nChoose up to " + max + " the viewer will most likely love, best first. Prefer variety; never pick something "
                + "already in the graph. For each, give a reason under 12 words that names the trait or a title it connects to, "
                + "like \"Because you loved Dark: twisty time puzzles\".\n"
                + "Return JSON: {\"picks\": [{\"key\": \"<key as given>\", \"why\": \"...\"}]}";
        JSONObject out = ask(system, prompt, 300L + 60L * max);
        List<Pick> picks = new ArrayList<>();
        JSONArray ps = out == null ? null : out.optJSONArray("picks");
        if (ps != null) for (int i = 0; i < ps.length() && picks.size() < max; i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && !p.optString("key").isEmpty()) picks.add(new Pick(p.optString("key"), p.optString("why", "")));
        }
        return picks;
    }

    /**
     * Blocking. Turns a spoken request ("a Marvel movie", "something scary from the 80s with Jamie Lee Curtis")
     * into filters TONIGHT. can look up on TMDB. Null when Claude is unavailable.
     */
    static JSONObject interpret(String request) {
        String system = "You turn a spoken request for something to watch into search filters. Answer with one JSON object and nothing else.";
        String prompt = "Request: \"" + request + "\"\n\nReturn JSON:\n{\"label\": \"1-2 words naming what was asked, for a dial, e.g. Marvel, 80s Horror, Tom Cruise\", "
                + "\"kind\": \"film\" | \"series\" | \"any\", "
                + "\"genres\": [zero or more of: " + String.join(", ", wheelGenres()) + "], "
                + "\"companies\": [production companies or studios named or clearly meant, e.g. \"Marvel Studios\", \"Pixar\", \"Studio Ghibli\"], "
                + "\"keywords\": [TMDB-style topic keywords, e.g. \"superhero\", \"time travel\", \"heist\"], "
                + "\"people\": [actors or directors named], "
                + "\"year_from\": number or null, \"year_to\": number or null}\n"
                + "Use only what the request says or clearly implies; leave the rest empty. \"movie\" means film; \"show\" means series.";
        return ask(system, prompt, 800);
    }

    private static JSONObject ask(String system, String prompt, long maxTokens) {
        if (!available()) return null;
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(maxTokens)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .system(system)
                    .addUserMessage(prompt)
                    .build();
            Message m = client().messages().create(params);
            if (m.stopReason().isPresent() && StopReason.REFUSAL.equals(m.stopReason().get())) {
                Log.w(TAG, "Claude declined the request");
                return null;
            }
            StringBuilder text = new StringBuilder();
            m.content().forEach(b -> b.text().ifPresent(t -> text.append(t.text())));
            String s = text.toString();
            int a = s.indexOf('{'), z = s.lastIndexOf('}');
            if (a < 0 || z <= a) { Log.w(TAG, "no JSON in Claude's answer"); return null; }
            Log.i(TAG, "Claude: " + m.usage().inputTokens() + " in, " + m.usage().outputTokens() + " out");
            return new JSONObject(s.substring(a, z + 1));
        } catch (Throwable e) {
            // never take the app down over a taste update; the next opening tries again
            Log.w(TAG, "Claude call failed", e);
            return null;
        }
    }

    private static List<String> wheelGenres() {
        List<String> g = new ArrayList<>();
        for (int i = 1; i < Tmdb.GENRES.length; i++) g.add(Tmdb.GENRES[i]);
        return g;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) if (!a.optString(i).isEmpty()) out.add(a.optString(i));
        return out;
    }
}
