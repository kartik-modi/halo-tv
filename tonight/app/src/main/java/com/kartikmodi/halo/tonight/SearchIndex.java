package com.kartikmodi.halo.tonight;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Every film and series streaming in the UK on Netflix, Prime Video and Disney+, kept on the TV and searched in
 * memory, so results change with every letter without going online.
 *
 * Built from TMDB's discover lists (films and series, per service, 20 a page, at most 500 pages a list, so a
 * longer list is split by release year): about 2,200 calls, once a week (IndexJob). Each day a quick refresh
 * reads the first pages of every list, where new arrivals show up. The file is a gzipped table, one title a line,
 * most popular first.
 */
final class SearchIndex {
    private static final String TAG = "TonightIndex";
    private static final String[][] SERVICES = {{"N", "8"}, {"P", "9|2100"}, {"D", "337"}};
    private static final int MAX_PAGES = 500, THREADS = 8, REFRESH_PAGES = 3, CHUNK = 50;

    static final class Hit {
        final int id;
        final boolean tv;
        final String name, year, poster;
        String services;
        final double pop;
        final String[] words;
        final String squash;

        Hit(int id, boolean tv, String name, String year, String poster, String services, double pop) {
            this(id, tv, name, year, poster, services, pop, String.join(" ", fold(name).split("[^a-z0-9]+")).trim());
        }

        /** folded: the name as search sees it (lower case, no accents, words split by single spaces), kept in the file. */
        Hit(int id, boolean tv, String name, String year, String poster, String services, double pop, String folded) {
            this.id = id;
            this.tv = tv;
            this.name = name;
            this.year = year;
            this.poster = poster;
            this.services = services;
            this.pop = pop;
            this.folded = folded;
            this.words = folded.split(" ");
            this.squash = folded.replace(" ", "");
        }

        final String folded;

        String key() {
            return (tv ? "tv:" : "movie:") + id;
        }
    }

    private static SearchIndex instance;

    static synchronized SearchIndex get(Context c) {
        if (instance == null) instance = new SearchIndex(c.getApplicationContext());
        return instance;
    }

    private final File file, meta;
    private volatile List<Hit> titles;
    private volatile boolean loaded;
    /** The file's time when it was read: the weekly job builds in its own process, and a newer file is read again. */
    private long loadedAt;
    /** While building: pages fetched and pages to fetch; both 0 otherwise. */
    final AtomicInteger done = new AtomicInteger(), total = new AtomicInteger();
    private volatile boolean building;
    private final Object buildLock = new Object();

    private SearchIndex(Context c) {
        file = new File(c.getFilesDir(), "search-index.tsv.gz");
        meta = new File(c.getFilesDir(), "search-index.json");
    }

    boolean building() {
        return building;
    }

    /** Built at least once: there is something to search. */
    boolean exists() {
        return file.isFile();
    }

    long builtAt() {
        try {
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8)).optLong("full");
        } catch (Exception e) {
            return 0;
        }
    }

    int size() {
        List<Hit> t = titles;
        return t == null ? 0 : t.size();
    }

    // ------------------------------------------------------------------ searching

    /** Reads the index into memory ahead of the first search; call off the main thread. */
    void preload() {
        load();
    }

    /** Blocking: loads the file the first time (about a second). Most popular first, then those starting with the words. */
    List<Hit> query(String q, int limit) {
        List<Hit> all = load();
        List<Hit> out = new ArrayList<>();
        String[] want = fold(q).trim().split("[^a-z0-9]+");
        List<String> ws = new ArrayList<>();
        for (String w : want) if (!w.isEmpty()) ws.add(w);
        if (ws.isEmpty() || all == null) return out;
        String squash = String.join("", ws), head = String.join(" ", ws);
        for (Hit h : all) {
            if (matches(h, ws) || h.squash.startsWith(squash)) {
                out.add(h);
                if (out.size() >= 200) break;
            }
        }
        // stable: popularity order kept within each score
        Collections.sort(out, (a, b) -> Integer.compare(score(a, head, ws.get(0)), score(b, head, ws.get(0))));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static boolean matches(Hit h, List<String> ws) {
        for (String w : ws) {
            boolean any = false;
            for (String t : h.words) if (t.startsWith(w)) { any = true; break; }
            if (!any) return false;
        }
        return true;
    }

    private static int score(Hit h, String head, String first) {
        String t = h.folded;
        if (t.equals(head)) return 0;
        if (t.startsWith(head)) return 1;
        for (String w : h.words) if (w.equals(first)) return 2;
        return 3;
    }

    private List<Hit> load() {
        if (loaded && file.lastModified() == loadedAt) return titles;
        synchronized (this) {
            if (loaded && file.lastModified() == loadedAt) return titles;
            if (file.isFile()) {
                loadedAt = file.lastModified();
                long t0 = System.currentTimeMillis();
                try {
                    titles = read();
                    Log.i(TAG, "loaded " + titles.size() + " titles in " + (System.currentTimeMillis() - t0) + " ms");
                } catch (Exception e) {
                    Log.w(TAG, "load", e);
                }
            }
            loaded = titles != null;
            return titles;
        }
    }

    private List<Hit> read() throws Exception {
        return readTable(file);
    }

    private static List<Hit> readTable(File from) throws Exception {
        List<Hit> out = new ArrayList<>(48000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(from), 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\t", 8);
                if (f.length < 7) continue;
                String poster = f[4].isEmpty() ? null : f[4];
                out.add(f.length == 8
                        ? new Hit(Integer.parseInt(f[0]), "1".equals(f[1]), f[6], f[2], poster, f[3], Double.parseDouble(f[5]), f[7])
                        : new Hit(Integer.parseInt(f[0]), "1".equals(f[1]), f[6], f[2], poster, f[3], Double.parseDouble(f[5])));
            }
        }
        return out;
    }

    private void write(List<Hit> all, boolean full) throws Exception {
        File tmp = new File(file.getPath() + ".tmp");
        writeTable(tmp, all);
        if (!tmp.renameTo(file)) throw new java.io.IOException("could not replace the index");
        JSONObject m = new JSONObject();
        long old = builtAt();
        m.put("full", full ? System.currentTimeMillis() : old).put("refresh", System.currentTimeMillis()).put("titles", all.size());
        try (FileOutputStream o = new FileOutputStream(meta)) {
            o.write(m.toString().getBytes(StandardCharsets.UTF_8));
        }
        titles = all;
        loadedAt = file.lastModified();
        loaded = true;
    }

    private static void writeTable(File tmp, List<Hit> all) throws Exception {
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(tmp), 1 << 16), StandardCharsets.UTF_8)) {
            StringBuilder b = new StringBuilder();
            for (Hit h : all) {
                b.setLength(0);
                b.append(h.id).append('\t').append(h.tv ? '1' : '0').append('\t').append(h.year).append('\t').append(h.services)
                        .append('\t').append(h.poster == null ? "" : h.poster).append('\t').append(h.pop).append('\t')
                        .append(h.name.replace('\t', ' ').replace('\n', ' ')).append('\t').append(h.folded).append('\n');
                w.write(b.toString());
            }
        }
    }

    // ------------------------------------------------------------------ building

    /** Blocking. full: every page of every list (weekly); otherwise the first pages, merged into what is there (daily). */
    void build(boolean full) {
        synchronized (buildLock) {
            if (!full && !exists()) full = true;
            building = true;
            done.set(0);
            total.set(0);
            long t0 = System.currentTimeMillis();
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                List<Hit> all = full ? buildFull(pool) : refresh(pool);
                Collections.sort(all, (a, b) -> Double.compare(b.pop, a.pop));
                if (full && all.size() < 1000) throw new IllegalStateException("only " + all.size() + " titles: keeping the old index");
                write(all, full);
                if (full) wipe(work());
                Log.i(TAG, (full ? "built " : "refreshed ") + all.size() + " titles in " + (System.currentTimeMillis() - t0) + " ms");
            } catch (Exception e) {
                Log.w(TAG, "build", e);
            } finally {
                pool.shutdownNow();
                building = false;
                done.set(0);
                total.set(0);
            }
        }
    }

    /** Where a full build keeps its finished chunks, so a build the TV stops part-way carries on where it was. */
    private File work() {
        return new File(file.getParentFile(), "index-build");
    }

    /**
     * Every page of every list, in chunks of CHUNK pages; each finished chunk is saved at once. The TV kills
     * background apps while it plays video, and the job then starts again later and skips the saved chunks.
     */
    private List<Hit> buildFull(ExecutorService pool) throws Exception {
        File dir = work(), plan = new File(dir, "plan.json");
        if (plan.isFile() && System.currentTimeMillis() - plan.lastModified() > 3 * 24 * 3600 * 1000L) wipe(dir);   // too old to finish
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no room for the index build");
        JSONArray chunks;
        if (plan.isFile()) {
            chunks = new JSONArray(new String(java.nio.file.Files.readAllBytes(plan.toPath()), StandardCharsets.UTF_8));
            Log.i(TAG, "carrying on with a build part-way through");
        } else {
            chunks = plan(pool);
            File tmp = new File(dir, "plan.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(chunks.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(plan)) throw new java.io.IOException("could not save the build plan");
        }
        int pagesLeft = 0, pagesAll = 0;
        for (int i = 0; i < chunks.length(); i++) {
            JSONArray c = chunks.getJSONArray(i);
            int n = c.getInt(6) - c.getInt(5) + 1;
            pagesAll += n;
            if (!new File(dir, i + ".tsv.gz").isFile()) pagesLeft += n;
        }
        total.set(pagesAll);
        done.set(pagesAll - pagesLeft);
        for (int i = 0; i < chunks.length(); i++) {
            File out = new File(dir, i + ".tsv.gz");
            if (out.isFile()) continue;
            JSONArray c = chunks.getJSONArray(i);
            Object[] l = {c.getString(0), c.getString(1), c.getString(2), c.isNull(3) ? null : c.getInt(3), c.isNull(4) ? null : c.getInt(4)};
            Map<String, Hit> got = new LinkedHashMap<>();
            List<Future<?>> fs = new ArrayList<>();
            for (int p = c.getInt(5); p <= c.getInt(6); p++) {
                final int page = p;
                fs.add(pool.submit(() -> {
                    add(got, l, page(l, page));
                    done.incrementAndGet();
                }));
            }
            for (Future<?> f : fs) f.get();
            File tmp = new File(dir, i + ".tmp");
            writeTable(tmp, new ArrayList<>(got.values()));
            if (!tmp.renameTo(out)) throw new java.io.IOException("could not save chunk " + i);
        }
        // every chunk is in: one entry per title, with every service it was found on
        Map<String, Hit> merged = new LinkedHashMap<>();
        for (int i = 0; i < chunks.length(); i++) {
            for (Hit h : readTable(new File(dir, i + ".tsv.gz"))) {
                Hit o = merged.get(h.key());
                if (o == null) merged.put(h.key(), h);
                else for (char k : h.services.toCharArray()) if (o.services.indexOf(k) < 0) o.services = order(o.services + k);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** The chunks to fetch: each list's length from its first page, and lists over 500 pages split by year. */
    private static JSONArray plan(ExecutorService pool) throws Exception {
        List<Object[]> lists = new ArrayList<>();   // {kind, svc, prov, lo, hi}
        for (String kind : new String[]{"movie", "tv"})
            for (String[] s : SERVICES) lists.add(new Object[]{kind, s[0], s[1], null, null});
        JSONArray chunks = new JSONArray();
        while (!lists.isEmpty()) {
            List<Future<JSONObject>> fs = new ArrayList<>();
            for (Object[] l : lists) fs.add(pool.submit(() -> page(l, 1)));
            List<Object[]> next = new ArrayList<>();
            for (int i = 0; i < lists.size(); i++) {
                Object[] l = lists.get(i);
                int n = fs.get(i).get().optInt("total_pages");
                if (n > MAX_PAGES) {
                    int lo = l[3] == null ? 1900 : (Integer) l[3];
                    int hi = l[4] == null ? Calendar.getInstance().get(Calendar.YEAR) + 2 : (Integer) l[4];
                    if (hi > lo) {
                        int mid = (lo + hi) / 2;
                        next.add(new Object[]{l[0], l[1], l[2], lo, mid});
                        next.add(new Object[]{l[0], l[1], l[2], mid + 1, hi});
                        continue;
                    }
                }
                for (int from = 1; from <= Math.min(n, MAX_PAGES); from += CHUNK) {
                    chunks.put(new JSONArray().put(l[0]).put(l[1]).put(l[2]).put(l[3] == null ? JSONObject.NULL : l[3])
                            .put(l[4] == null ? JSONObject.NULL : l[4]).put(from).put(Math.min(from + CHUNK - 1, Math.min(n, MAX_PAGES))));
                }
            }
            lists = next;
        }
        return chunks;
    }

    /** The first pages of every list, where new arrivals show up, merged into the index there is. */
    private List<Hit> refresh(ExecutorService pool) throws Exception {
        Map<String, Hit> got = new LinkedHashMap<>();
        List<Future<?>> fs = new ArrayList<>();
        for (String kind : new String[]{"movie", "tv"})
            for (String[] s : SERVICES) {
                Object[] l = {kind, s[0], s[1], null, null};
                for (int p = 1; p <= REFRESH_PAGES; p++) {
                    final int page = p;
                    fs.add(pool.submit(() -> add(got, l, page(l, page))));
                }
            }
        for (Future<?> f : fs) f.get();
        List<Hit> old = load();
        Map<String, Hit> merged = new LinkedHashMap<>();
        if (old != null) for (Hit h : old) merged.put(h.key(), h);
        for (Hit h : got.values()) {
            Hit o = merged.get(h.key());
            if (o == null) merged.put(h.key(), h);
            else for (char c : h.services.toCharArray()) if (o.services.indexOf(c) < 0) o.services = order(o.services + c);
        }
        return new ArrayList<>(merged.values());
    }

    private static void wipe(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private static void add(Map<String, Hit> got, Object[] list, JSONObject d) {
        JSONArray rs = d.optJSONArray("results");
        if (rs == null) return;
        boolean tv = "tv".equals(list[0]);
        String svc = (String) list[1];
        synchronized (got) {
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.optJSONObject(i);
                if (r == null) continue;
                String name = r.optString(tv ? "name" : "title", "");
                if (name.isEmpty()) continue;
                String key = (tv ? "tv:" : "movie:") + r.optInt("id");
                Hit h = got.get(key);
                if (h != null) {
                    if (h.services.indexOf(svc.charAt(0)) < 0) h.services = order(h.services + svc);
                    continue;
                }
                String date = r.optString(tv ? "first_air_date" : "release_date", "");
                String poster = r.isNull("poster_path") ? null : r.optString("poster_path", null);
                got.put(key, new Hit(r.optInt("id"), tv, name, date.length() >= 4 ? date.substring(0, 4) : "", poster, svc, r.optDouble("popularity", 0)));
            }
        }
    }

    private static String order(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : new char[]{'N', 'P', 'D'}) if (s.indexOf(c) >= 0) b.append(c);
        return b.toString();
    }

    /** One page of one list; a few tries, then an empty page so one failure doesn't stop the build. */
    private static JSONObject page(Object[] l, int page) {
        StringBuilder u = new StringBuilder("https://api.themoviedb.org/3/discover/").append(l[0])
                .append("?watch_region=").append(Tmdb.REGION).append("&with_watch_monetization_types=flatrate%7Cads&sort_by=popularity.desc&include_adult=false")
                .append("&with_watch_providers=").append(((String) l[2]).replace("|", "%7C")).append("&page=").append(page);
        if (l[3] != null) {
            String f = "movie".equals(l[0]) ? "primary_release_date" : "first_air_date";
            u.append('&').append(f).append(".gte=").append(l[3]).append("-01-01&").append(f).append(".lte=").append(l[4]).append("-12-31");
        }
        for (int t = 0; t < 4; t++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u.toString()).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Authorization", "Bearer " + BuildConfig.TMDB_TOKEN);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 429) { Thread.sleep(1500L * (t + 1)); continue; }
                if (code / 100 != 2) throw new java.io.IOException("TMDB " + code);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[16384];
                    for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                try { Thread.sleep(800L * (t + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return new JSONObject();
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT).replace("&", " and ");
    }
}
