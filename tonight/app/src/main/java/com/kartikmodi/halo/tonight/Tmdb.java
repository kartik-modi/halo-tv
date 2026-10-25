package com.kartikmodi.halo.tonight;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TMDB, live: one page of titles per request, only what streams on the configured services (Services) in the
 * configured country.
 * Nothing is saved; the activity keeps what it fetched in memory while it is open.
 */
final class Tmdb {
    private Tmdb() {}

    static final String REGION = Services.REGION;   // set by region= in tonight/secrets.properties
    /** Every configured service's TMDB provider ids. */
    static final String ALL_SERVICES = Services.allProviders();

    /** The wheel's genres. Film and TV genres have different ids on TMDB; 0 means there is no such genre for that kind. */
    static final String[] GENRES = {"Everything", "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary",
            "Drama", "Family", "Fantasy", "History", "Horror", "Kids", "Music", "Mystery", "Reality", "Romance", "Sci-Fi",
            "Thriller", "War", "Western"};
    /**
     * How a wheel genre narrows discover, for films and for series. TMDB gives a title several genres and
     * with_genres matches any of them, so on its own a Comedy row is mostly cartoons and a Sci-Fi row has time-travel
     * romances. So each row has three parts:
     * - a query: the genre, or for what TV has no genre of its own a TMDB keyword tag (horror 315058; thriller
     *   316362, psychological thriller 12565; romance 9840; historical 15126, period drama 15060, biography 5565,
     *   historical drama 192772; music 283297, musical 4344). TV's one "Sci-Fi & Fantasy" genre also needs a keyword
     *   (sci-fi: science fiction 281358, space 9882, time travel 4379, dystopia 4565, alien 9951, cyberpunk 12190,
     *   space opera 161176, post-apocalyptic future 4458, robot 14544; fantasy: fantasy world 170362, magic 2343,
     *   sword and sorcery 234213, dragon 12554, high fantasy 211227, vampire 3133, werewolf 12564), and so does
     *   Adventure, which TV folds into "Action & Adventure" (survival 10349, quest 207372, expedition 1963, treasure
     *   hunt 6956, pirate 12988, jungle 10787, exploration 4759, adventurer 175428, journey 189092, road trip 7312,
     *   swashbuckler 157186);
     * - what to leave out: cartoons and family films, kids' shows, soaps and talk shows have rows of their own, and
     *   romance stays out of the action, thriller, horror, mystery, fantasy and sci-fi rows;
     * - a lead genre: TMDB lists a title's main genre first, so the row's genre must be among its first two (three for
     *   Animation, Family, History, Music, War and Western, which are usually added after the main one). 0: no check.
     * Films have no Reality genre (its row shows documentaries) and no Kids genre (family films rated G or PG in the US,
     * the one set of ratings TMDB has for nearly every film, wherever the viewer is).
     */
    private static final class Rule {
        final String query;
        final int lead, within;

        Rule(String query, int lead, int within) {
            this.query = query;
            this.lead = lead;
            this.within = within;
        }
    }

    private static final String CARTOONS = "16|10751", CARTOONS_ROMANCE = "16|10751|10749",
            KIDS_SOAPS = "16|10762|10766|10767";
    private static final Rule[] MOVIE_RULES = {
            new Rule("", 0, 0),
            new Rule(g(28) + wo(CARTOONS_ROMANCE), 28, 2),                                   // Action
            new Rule(g(12) + wo(CARTOONS_ROMANCE), 12, 2),                                   // Adventure
            new Rule(g(16), 16, 3),                                                          // Animation
            new Rule(g(35) + wo(CARTOONS), 35, 2),                                           // Comedy
            new Rule(g(80) + wo(CARTOONS), 80, 2),                                           // Crime
            new Rule(g(99), 99, 2),                                                          // Documentary
            new Rule(g(18) + wo(CARTOONS), 18, 2),                                           // Drama
            new Rule(g(10751) + wo("16"), 10751, 3),                                         // Family: live action
            new Rule(g(14) + wo(CARTOONS_ROMANCE), 14, 2),                                   // Fantasy
            new Rule(g(36) + wo(CARTOONS), 36, 3),                                           // History
            new Rule(g(27) + wo(CARTOONS_ROMANCE), 27, 2),                                   // Horror
            new Rule(g(10751) + "&certification_country=US&certification.lte=PG", 0, 0),    // Kids
            new Rule(g(10402) + wo(CARTOONS), 10402, 3),                                     // Music
            new Rule(g(9648) + wo(CARTOONS_ROMANCE), 9648, 2),                               // Mystery
            new Rule(g(99), 99, 2),                                                          // Reality
            new Rule(g(10749) + wo(CARTOONS), 10749, 2),                                     // Romance
            new Rule(g(878) + wo(CARTOONS_ROMANCE), 878, 2),                                 // Sci-Fi
            new Rule(g(53) + wo(CARTOONS_ROMANCE), 53, 2),                                   // Thriller
            new Rule(g(10752), 10752, 3),                                                    // War
            new Rule(g(37), 37, 3)};                                                         // Western
    private static final Rule[] TV_RULES = {
            new Rule("", 0, 0),
            new Rule(g(10759) + wo(KIDS_SOAPS), 10759, 2),                                   // Action
            new Rule(g(10759) + k("10349|207372|1963|6956|12988|10787|4759|175428|189092|7312|157186")
                    + wo(KIDS_SOAPS), 0, 0),                                                 // Adventure
            new Rule(g(16) + wo("10762"), 16, 3),                                            // Animation
            new Rule(g(35) + wo(KIDS_SOAPS), 35, 2),                                         // Comedy
            new Rule(g(80) + wo(KIDS_SOAPS), 80, 2),                                         // Crime
            new Rule(g(99), 99, 2),                                                          // Documentary
            new Rule(g(18) + wo(KIDS_SOAPS), 18, 2),                                         // Drama
            new Rule(g(10751) + wo("10766"), 0, 0),                                          // Family
            new Rule(g(10765) + k("170362|2343|234213|12554|211227|3133|12564") + wo(KIDS_SOAPS), 10765, 2),   // Fantasy
            new Rule(k("15126|15060|5565|192772") + "&without_keywords=9840" + wo(KIDS_SOAPS), 0, 0),          // History
            new Rule(k("315058") + wo(KIDS_SOAPS), 0, 0),                                    // Horror
            new Rule(g(10762), 0, 0),                                                        // Kids
            new Rule(k("283297|4344") + wo("10762"), 0, 0),                                  // Music
            new Rule(g(9648) + wo(KIDS_SOAPS), 9648, 2),                                     // Mystery
            new Rule(g(10764), 0, 0),                                                        // Reality
            new Rule(k("9840") + wo(KIDS_SOAPS + "|80"), 0, 0),                              // Romance
            new Rule(g(10765) + k("281358|9882|4379|4565|9951|12190|161176|4458|14544") + "&without_keywords=9840"
                    + wo(KIDS_SOAPS), 10765, 2),                                             // Sci-Fi
            new Rule(k("316362|12565") + wo(KIDS_SOAPS), 0, 0),                              // Thriller
            new Rule(g(10768) + wo(KIDS_SOAPS), 10768, 2),                                   // War
            new Rule(g(37), 37, 3)};                                                         // Western

    private static String wo(String ids) {
        return "&without_genres=" + ids.replace("|", "%7C");
    }

    private static String g(int id) {
        return "&with_genres=" + id;
    }

    private static String k(String ids) {
        return "&with_keywords=" + ids.replace("|", "%7C");
    }

    private static final Map<Integer, String> MOVIE_NAMES = new HashMap<>(), TV_NAMES = new HashMap<>();
    static {
        Object[][] m = {{28, "Action"}, {12, "Adventure"}, {16, "Animation"}, {35, "Comedy"}, {80, "Crime"}, {99, "Documentary"},
                {18, "Drama"}, {10751, "Family"}, {14, "Fantasy"}, {36, "History"}, {27, "Horror"}, {10402, "Music"},
                {9648, "Mystery"}, {10749, "Romance"}, {878, "Sci-Fi"}, {10770, "TV Movie"}, {53, "Thriller"}, {10752, "War"}, {37, "Western"}};
        Object[][] t = {{10759, "Action"}, {16, "Animation"}, {35, "Comedy"}, {80, "Crime"}, {99, "Documentary"}, {18, "Drama"},
                {10751, "Family"}, {10762, "Kids"}, {9648, "Mystery"}, {10763, "News"}, {10764, "Reality"}, {10765, "Sci-Fi"},
                {10766, "Soap"}, {10767, "Talk"}, {10768, "War"}, {37, "Western"}};
        for (Object[] o : m) MOVIE_NAMES.put((Integer) o[0], (String) o[1]);
        for (Object[] o : t) TV_NAMES.put((Integer) o[0], (String) o[1]);
    }

    /** The query that narrows discover to a wheel genre, for films or series ("" for Everything). */
    static String filter(int wheelIndex, boolean series) {
        return rule(wheelIndex, series).query;
    }

    private static Rule rule(int wheelIndex, boolean series) {
        Rule[] r = series ? TV_RULES : MOVIE_RULES;
        return r[Math.max(0, Math.min(r.length - 1, wheelIndex))];
    }

    /** Whether the wheel genre is one of the title's main genres (see Rule), not one tagged on at the end. */
    static boolean fits(Title t, int wheelIndex) {
        Rule r = rule(wheelIndex, t.series);
        if (r.lead == 0) return true;
        int at = t.genreIds.indexOf(r.lead);
        return at >= 0 && at < r.within;
    }

    /**
     * A wheel genre's titles from 'page' on: what fits() keeps, topped up from the next pages (at most three
     * requests) until there are 'want'. totalPages is the genre's own count, for picking a random page next time.
     */
    static Page genre(boolean series, int wheelIndex, String providers, int page, int want) throws Exception {
        Page out = new Page();
        String q = filter(wheelIndex, series);
        for (int n = 0, p = page; n < 3 && out.titles.size() < want; n++, p++) {
            Page got = discover(series, -1, providers, p, q);
            out.totalPages = got.totalPages;
            for (Title t : got.titles) if (fits(t, wheelIndex)) out.titles.add(t);
            if (p >= got.totalPages) break;
        }
        return out;
    }

    static int wheelIndexOf(String name) {
        for (int i = 0; i < GENRES.length; i++) if (GENRES[i].equalsIgnoreCase(name)) return i;
        return -1;
    }

    static String genreName(int id, boolean series) {
        String n = (series ? TV_NAMES : MOVIE_NAMES).get(id);
        return n == null ? "" : n;
    }

    /** One page of films or series. genre: -1 for any; providers: an ALL_SERVICES-style "|" list. */
    static Page discover(boolean series, int genre, String providers, int page, String extra) throws Exception {
        StringBuilder u = new StringBuilder("/discover/").append(series ? "tv" : "movie")
                .append("?watch_region=").append(REGION)
                .append("&with_watch_providers=").append(enc(providers))
                .append("&with_watch_monetization_types=flatrate")
                .append("&sort_by=popularity.desc&include_adult=false&language=").append(Services.language())
                .append("&vote_count.gte=").append(series ? 40 : 80)
                .append("&page=").append(page);
        if (genre > 0) u.append("&with_genres=").append(genre);
        if (extra != null) u.append(extra);
        JSONObject d = api(u.toString());
        Page p = new Page();
        p.totalPages = Math.min(d.optInt("total_pages", 1), 500);
        JSONArray rs = d.optJSONArray("results");
        if (rs != null) for (int i = 0; i < rs.length(); i++) {
            Title t = parse(rs.getJSONObject(i), series);
            if (t != null) p.titles.add(t);
        }
        return p;
    }

    static final class Page {
        final List<Title> titles = new ArrayList<>();
        int totalPages = 1;
    }

    private static Title parse(JSONObject r, boolean series) {
        String poster = r.optString("poster_path", "");
        if (poster.isEmpty() || "null".equals(poster)) return null;   // nothing to show on the wheel
        String date = r.optString(series ? "first_air_date" : "release_date", "");
        List<Integer> g = new ArrayList<>();
        JSONArray ga = r.optJSONArray("genre_ids");
        if (ga != null) for (int i = 0; i < ga.length(); i++) g.add(ga.optInt(i));
        return new Title(r.optInt("id"), series, r.optString(series ? "name" : "title"),
                date.length() >= 4 ? date.substring(0, 4) : "", r.optString("overview", ""), poster,
                r.optString("backdrop_path", ""), g, r.optDouble("popularity", 0));
    }

    /** Fills in the details for the focused title: runtime or seasons, genres, the lead, the textless poster and the service. */
    static void details(Title t) throws Exception {
        if (t.detailed) return;
        String append = t.series ? "aggregate_credits,images,watch/providers" : "credits,images,watch/providers";
        JSONObject d = api("/" + (t.series ? "tv" : "movie") + "/" + t.id + "?language=" + Services.language() + "&append_to_response=" + append
                + "&include_image_language=null,en");
        // runtime / seasons
        if (t.series) {
            int s = d.optInt("number_of_seasons", 0);
            t.meta = t.year + (s > 0 ? " · " + s + (s == 1 ? " season" : " seasons") : "");
        } else {
            int m = d.optInt("runtime", 0);
            t.meta = t.year + (m > 0 ? " · " + (m / 60 > 0 ? (m / 60) + "h " : "") + (m % 60) + "m" : "");
        }
        JSONArray gs = d.optJSONArray("genres");
        List<String> names = new ArrayList<>();
        if (gs != null) for (int i = 0; i < gs.length() && i < 3; i++) {
            String n = genreName(gs.getJSONObject(i).optInt("id"), t.series);
            names.add(n.isEmpty() ? gs.getJSONObject(i).optString("name") : n);
        }
        t.genreNames.clear();
        t.genreNames.addAll(names);
        t.genresText = String.join(" / ", names);
        // the lead: first billed across the whole run (series) or the film
        JSONObject credits = d.optJSONObject(t.series ? "aggregate_credits" : "credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        if (cast != null && cast.length() > 0) {
            JSONObject c = cast.getJSONObject(0);
            t.leadName = c.optString("name", null);
            t.leadPersonId = c.optInt("id");
            String p = c.optString("profile_path", "");
            t.leadProfile = p.isEmpty() || "null".equals(p) ? null : p;
            if (t.series) {
                JSONArray roles = c.optJSONArray("roles");
                if (roles != null && roles.length() > 0) t.leadCharacter = roles.getJSONObject(0).optString("character", null);
            } else {
                t.leadCharacter = c.optString("character", null);
            }
            if (t.leadCharacter != null && t.leadCharacter.trim().isEmpty()) t.leadCharacter = null;
        }
        // a poster without text, best rated first: the cleanest picture of the cast
        JSONObject images = d.optJSONObject("images");
        JSONArray posters = images == null ? null : images.optJSONArray("posters");
        if (posters != null) {
            double best = -1;
            for (int i = 0; i < posters.length(); i++) {
                JSONObject p = posters.getJSONObject(i);
                if (!p.isNull("iso_639_1")) continue;
                double v = p.optDouble("vote_average", 0) + p.optInt("vote_count", 0) * 0.01;
                if (v > best) { best = v; t.textlessPoster = p.optString("file_path"); }
            }
        }
        // the title logo, for a card that ends up with no figure
        JSONArray logos = images == null ? null : images.optJSONArray("logos");
        if (logos != null) {
            double best = -1;
            for (int i = 0; i < logos.length(); i++) {
                JSONObject l = logos.getJSONObject(i);
                if (!l.isNull("iso_639_1") && !"en".equals(l.optString("iso_639_1"))) continue;
                double v = l.optDouble("vote_average", 0) + l.optInt("vote_count", 0) * 0.01 + ("en".equals(l.optString("iso_639_1")) ? 1 : 0);
                if (v > best) { best = v; t.logoPath = l.optString("file_path"); }
            }
        }
        // which of the configured services streams it in the country; the first one TMDB lists wins
        JSONObject wp = d.optJSONObject("watch/providers");
        JSONObject gb = wp == null ? null : wp.optJSONObject("results");
        gb = gb == null ? null : gb.optJSONObject(REGION);
        JSONArray flat = gb == null ? null : gb.optJSONArray("flatrate");
        if (flat != null) for (int i = 0; i < flat.length() && t.service == null; i++) {
            Services.Service s = Services.forProvider(flat.getJSONObject(i).optInt("provider_id"));
            if (s != null) t.service = String.valueOf(s.code);
        }
        t.detailed = true;
    }

    /**
     * Which of the configured services stream a title in the country right now, as codes in the configured order
     * ("NPD" for all three of the defaults, "" for none). Blocking.
     */
    static String services(boolean series, int id) throws Exception {
        JSONObject d = api("/" + (series ? "tv" : "movie") + "/" + id + "/watch/providers");
        JSONObject gb = d.optJSONObject("results");
        gb = gb == null ? null : gb.optJSONObject(REGION);
        StringBuilder got = new StringBuilder();
        for (String kind : new String[]{"flatrate", "ads"}) {
            JSONArray a = gb == null ? null : gb.optJSONArray(kind);
            if (a != null) for (int i = 0; i < a.length(); i++) {
                Services.Service s = Services.forProvider(a.getJSONObject(i).optInt("provider_id"));
                if (s != null) got.append(s.code);
            }
        }
        return Services.order(got.toString());
    }

    /** The TMDB id for a company, keyword or person name ("company", "keyword", "person"); 0 if none. */
    static int searchId(String type, String name) {
        try {
            JSONObject d = api("/search/" + type + "?query=" + enc(name));
            JSONArray rs = d.optJSONArray("results");
            if (rs == null || rs.length() == 0) return 0;
            // an exact name first, then the most popular
            for (int i = 0; i < rs.length(); i++) if (rs.getJSONObject(i).optString("name").equalsIgnoreCase(name)) return rs.getJSONObject(i).optInt("id");
            return rs.getJSONObject(0).optInt("id");
        } catch (Exception e) {
            return 0;
        }
    }

    static String image(String path, String size) {
        return "https://image.tmdb.org/t/p/" + size + path;
    }

    private static JSONObject api(String path) throws IOException, org.json.JSONException {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + BuildConfig.TMDB_TOKEN);
        h.put("Accept", "application/json");
        return new JSONObject(new String(Net.get("https://api.themoviedb.org/3" + path, h), StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
