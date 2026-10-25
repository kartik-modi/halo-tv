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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * "For You" without the wait: Claude ranks a big batch of picks once, the batch is kept on the TV, and every
 * open or shuffle deals a fresh mix from it at once. A new batch is fetched in the background when the deck runs
 * low, is a day old, or the taste graph has grown since it was made.
 */
final class ForYou {
    private static final String TAG = "TonightForYou";
    private static final int BATCH = 40, POOL = 90, DEAL = 16;
    private static final long STALE_MS = 24 * 3600 * 1000L;

    private static final class Pick {
        final Title title;
        final String why;

        Pick(Title title, String why) {
            this.title = title;
            this.why = why;
        }
    }

    private final File file;
    private final List<Pick> deck = new ArrayList<>();
    /** Picks already dealt since the deck was last made; a shuffle prefers the ones not shown yet. */
    private final Set<String> dealt = new HashSet<>();
    private long madeAt;
    private int graphSize;
    private boolean refilling;
    private final Object firstFill = new Object();

    ForYou(Context ctx) {
        file = new File(ctx.getFilesDir(), "foryou.json");
        load();
    }

    /**
     * Blocking only when there is nothing kept yet (the first time); otherwise returns at once and refreshes in
     * the background through 'background' when the deck needs it.
     */
    void deal(TasteGraph graph, Random random, List<Title>[] got, Map<String, String> reasons,
              java.util.concurrent.Executor background) throws Exception {
        synchronized (firstFill) {   // a second caller waits for the first deck instead of asking Claude again
            boolean empty;
            synchronized (this) { empty = deck.isEmpty(); }
            if (empty) refill(graph, random);
        }
        synchronized (this) {
            List<Pick> fresh = new ArrayList<>(), seen = new ArrayList<>();
            for (Pick p : deck) {
                if (graph.watched(p.title.key())) continue;
                (dealt.contains(p.title.key()) ? seen : fresh).add(p);
            }
            Collections.shuffle(fresh, random);
            Collections.shuffle(seen, random);
            fresh.addAll(seen);
            int n = 0;
            for (Pick p : fresh) {
                if (n >= DEAL) break;
                got[p.title.series ? 0 : 1].add(p.title);
                reasons.put(p.title.key(), p.why);
                dealt.add(p.title.key());
                n++;
            }
            boolean low = deck.size() - dealt.size() < DEAL;
            boolean old = System.currentTimeMillis() - madeAt > STALE_MS || graph.size() != graphSize;
            if ((low || old) && !refilling) {
                refilling = true;
                background.execute(() -> {
                    try {
                        refill(graph, new Random());
                    } catch (Exception e) {
                        Log.w(TAG, "background refill", e);
                    } finally {
                        synchronized (ForYou.this) { refilling = false; }
                    }
                });
            }
        }
    }

    /** Blocking: a live pool from the genres the graph leans to, ranked by Claude into a new deck. */
    private void refill(TasteGraph graph, Random random) throws Exception {
        long started = System.currentTimeMillis();
        List<String> genres = graph.topGenres(3);
        if (genres.isEmpty()) genres.add("Everything");
        List<Title> pool = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String g : genres) {
            int w = Tmdb.wheelIndexOf(g);
            for (int k = 0; k < 2; k++) {
                for (int page : new int[]{1 + random.nextInt(2), 3 + random.nextInt(3)}) {
                    for (Title t : Tmdb.genre(k == 0, w, Tmdb.ALL_SERVICES, page, 20).titles)
                        if (seen.add(t.key()) && !graph.watched(t.key())) pool.add(t);
                }
            }
        }
        Collections.shuffle(pool, random);
        if (pool.size() > POOL) pool = pool.subList(0, POOL);
        Map<String, Title> byKey = new HashMap<>();
        for (Title t : pool) byKey.put(t.key(), t);
        List<Pick> made = new ArrayList<>();
        for (Taste.Pick p : Taste.rank(graph, pool, BATCH)) {
            Title t = byKey.get(p.key);
            if (t != null) made.add(new Pick(t, p.why));
        }
        if (made.isEmpty()) {   // Claude unreachable: the pool by popularity, still only your genres
            pool.sort((a, b) -> Double.compare(b.popularity, a.popularity));
            for (Title t : pool) if (made.size() < BATCH) made.add(new Pick(t, ""));
            if (made.isEmpty()) return;
            synchronized (this) { if (!deck.isEmpty()) return; }   // keep Claude's older picks over these
        }
        synchronized (this) {
            deck.clear();
            deck.addAll(made);
            dealt.clear();
            madeAt = System.currentTimeMillis();
            graphSize = graph.size();
        }
        save();
        Log.i(TAG, "new deck of " + made.size() + " in " + (System.currentTimeMillis() - started) + " ms");
    }

    private synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : deck) {
                Title t = p.title;
                JSONArray g = new JSONArray();
                for (int id : t.genreIds) g.put(id);
                a.put(new JSONObject().put("id", t.id).put("s", t.series).put("n", t.name).put("y", t.year)
                        .put("o", t.overview).put("p", t.posterPath).put("b", t.backdropPath).put("g", g)
                        .put("pop", t.popularity).put("why", p.why));
            }
            JSONObject o = new JSONObject().put("at", madeAt).put("graph", graphSize).put("picks", a);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "save", e);
        }
    }

    private synchronized void load() {
        if (!file.isFile()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            madeAt = o.optLong("at");
            graphSize = o.optInt("graph");
            JSONArray a = o.getJSONArray("picks");
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                List<Integer> g = new ArrayList<>();
                JSONArray ga = p.optJSONArray("g");
                if (ga != null) for (int k = 0; k < ga.length(); k++) g.add(ga.getInt(k));
                Title t = new Title(p.getInt("id"), p.optBoolean("s"), p.optString("n"), p.optString("y"), p.optString("o"),
                        p.optString("p"), p.optString("b"), g, p.optDouble("pop"));
                deck.add(new Pick(t, p.optString("why")));
            }
        } catch (Exception e) {
            Log.w(TAG, "load", e);
            deck.clear();
        }
    }
}
