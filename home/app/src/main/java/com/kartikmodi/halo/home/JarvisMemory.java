package com.kartikmodi.halo.home;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Jarvis's long-term memory of the person who uses it, kept on the TV in one file (files/jarvis-memory.json).
 *
 * Memories are short sentences, each stored with an embedding. A question sends only the few memories that
 * match it (a vector search on the TV, after one tiny embedding call), never the whole list, so memory costs
 * next to nothing per question however much is remembered. After a conversation goes quiet, one call turns
 * what was said into new memories, updating or deleting ones that changed. The short-term memory of the
 * conversation itself (the last few turns) is JarvisLink's.
 */
final class JarvisMemory {

    private static final String TAG = "JarvisMemory";
    private static final int MAX_MEMORIES = 200;       // the least recently touched go first beyond this
    private static final int RECALL = 5;               // at most this many go with a question
    private static final float RECALL_SCORE = 0.30f;   // cosine similarity; below this a memory is unrelated
    private static final float SAME_SCORE = 0.90f;     // a new memory this close to an old one replaces it
    private static final long QUIET_MS = 90_000;       // a conversation is over after this long without a word
    private static final int MAX_PENDING = 40;

    private static JarvisMemory instance;

    static synchronized JarvisMemory get(Context context) {
        if (instance == null) instance = new JarvisMemory(context.getApplicationContext());
        return instance;
    }

    private static final class Memory {
        long id, updated;
        String text;
        float[] vec;
    }

    private final Context app;
    private final File file;
    private final List<Memory> memories = new ArrayList<>();
    private final List<String[]> pending = new ArrayList<>();   // turns not yet distilled: {said, reply}
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> distilSoon;

    private JarvisMemory(Context app) {
        this.app = app;
        file = new File(app.getFilesDir(), "jarvis-memory.json");
        load();
        if (!pending.isEmpty()) schedule();   // a conversation the TV was switched off during
    }

    // ---- Recall

    /** The memories that bear on what was said, best first; empty if none, or without a key or network. */
    List<String> recall(String said) {
        synchronized (this) {
            if (memories.isEmpty()) return Collections.emptyList();
        }
        float[][] q = OpenAi.embed(app, Collections.singletonList(said), 2500);
        if (q == null || q[0] == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (Memory m : nearest(q[0], RECALL, RECALL_SCORE)) out.add(m.text);
        return out;
    }

    private synchronized List<Memory> nearest(float[] v, int n, float min) {
        List<Memory> found = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        for (Memory m : memories) {
            if (m.vec == null || m.vec.length != v.length) continue;
            float s = dot(m.vec, v);
            if (s < min) continue;
            int at = 0;
            while (at < scores.size() && scores.get(at) >= s) at++;
            if (at >= n) continue;
            scores.add(at, s);
            found.add(at, m);
            if (found.size() > n) { found.remove(n); scores.remove(n); }
        }
        return found;
    }

    private static float dot(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    // ---- Learning

    /** A finished exchange; distilled into memories once the conversation goes quiet. */
    void heard(String said, String reply) {
        synchronized (this) {
            pending.add(new String[]{said, reply == null ? "" : reply});
            while (pending.size() > MAX_PENDING) pending.remove(0);
            save();
        }
        schedule();
    }

    private synchronized void schedule() {
        if (distilSoon != null) distilSoon.cancel(false);
        distilSoon = worker.schedule(this::distil, QUIET_MS, TimeUnit.MILLISECONDS);
    }

    private void distil() {
        List<String[]> turns;
        synchronized (this) {
            if (pending.isEmpty()) return;
            turns = new ArrayList<>(pending);
        }
        try {
            StringBuilder talk = new StringBuilder(), said = new StringBuilder();
            for (String[] t : turns) {
                talk.append("User: ").append(t[0]).append("\nJarvis: ").append(t[1]).append('\n');
                said.append(t[0]).append(". ");
            }
            // The memories this conversation is about, with their ids, so the model can update rather than repeat.
            JSONArray related = new JSONArray();
            float[][] about = OpenAi.embed(app, Collections.singletonList(said.toString()), 8000);
            if (about != null && about[0] != null) {
                for (Memory m : nearest(about[0], 15, 0.2f)) related.put(new JSONObject().put("id", m.id).put("text", m.text));
            }
            JSONObject change = OpenAi.distil(app, talk.toString(), related);
            if (change == null) return;   // no key: try again after the next conversation
            apply(change);
            synchronized (this) {
                pending.subList(0, Math.min(turns.size(), pending.size())).clear();
                save();
            }
        } catch (Exception e) {
            Log.w(TAG, "distil failed", e);   // the turns stay pending and are tried with the next conversation
        }
    }

    private void apply(JSONObject change) throws Exception {
        JSONArray add = change.optJSONArray("add"), update = change.optJSONArray("update"), delete = change.optJSONArray("delete");
        List<String> texts = new ArrayList<>();
        List<Long> ids = new ArrayList<>();   // 0 for a new memory
        if (update != null) for (int i = 0; i < update.length(); i++) {
            JSONObject u = update.optJSONObject(i);
            if (u == null || u.optString("text").trim().isEmpty()) continue;
            texts.add(u.optString("text").trim());
            ids.add(u.optLong("id"));
        }
        if (add != null) for (int i = 0; i < add.length(); i++) {
            String t = add.optString(i, "").trim();
            if (!t.isEmpty()) { texts.add(t); ids.add(0L); }
        }
        float[][] vecs = texts.isEmpty() ? new float[0][] : OpenAi.embed(app, texts, 10000);
        if (vecs == null) throw new Exception("no embeddings");
        synchronized (this) {
            if (delete != null) for (int i = 0; i < delete.length(); i++) removeId(delete.optLong(i));
            long now = System.currentTimeMillis();
            for (int i = 0; i < texts.size(); i++) {
                Memory m = ids.get(i) != 0 ? byId(ids.get(i)) : null;
                if (m == null && vecs[i] != null) {   // a "new" memory that is really an old one
                    List<Memory> close = nearest(vecs[i], 1, SAME_SCORE);
                    if (!close.isEmpty()) m = close.get(0);
                }
                if (m == null) {
                    m = new Memory();
                    m.id = now * 100 + i;
                    memories.add(m);
                }
                m.text = texts.get(i);
                m.vec = vecs[i];
                m.updated = now;
            }
            // Keep the store small: the least recently touched go first.
            while (memories.size() > MAX_MEMORIES) {
                Memory oldest = memories.get(0);
                for (Memory m : memories) if (m.updated < oldest.updated) oldest = m;
                memories.remove(oldest);
            }
            if (!texts.isEmpty() || (delete != null && delete.length() > 0)) Log.i(TAG, memories.size() + " memories");
        }
    }

    private Memory byId(long id) {
        for (Memory m : memories) if (m.id == id) return m;
        return null;
    }

    private boolean removeId(long id) {
        Memory m = byId(id);
        return m != null && memories.remove(m);
    }

    // ---- Asked for directly: "remember that…", "forget…", "what do you remember about me", the phone

    /** Stores something the user asked to be remembered, now. False without a key or network. */
    boolean remember(String text) {
        try {
            // Worded like the other memories, and merged with any it changes; as said if that fails.
            JSONObject change = null;
            try {
                JSONArray related = new JSONArray();
                float[][] about = OpenAi.embed(app, Collections.singletonList(text), 4000);
                if (about != null && about[0] != null) {
                    for (Memory m : nearest(about[0], 10, 0.2f)) related.put(new JSONObject().put("id", m.id).put("text", m.text));
                }
                change = OpenAi.distil(app, "User: Remember that " + text + "\nJarvis: I'll remember that.", related);
            } catch (Exception ignored) {
            }
            boolean empty = change == null || (change.optJSONArray("add") == null || change.optJSONArray("add").length() == 0)
                    && (change.optJSONArray("update") == null || change.optJSONArray("update").length() == 0);
            apply(empty ? new JSONObject().put("add", new JSONArray().put(text)) : change);
            synchronized (this) { save(); }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Forgets the memory closest to what was said; returns its text, or null if nothing matched. */
    String forget(String what) {
        float[][] q = OpenAi.embed(app, Collections.singletonList(what), 4000);
        if (q == null || q[0] == null) return null;
        synchronized (this) {
            List<Memory> best = nearest(q[0], 1, 0.45f);
            if (best.isEmpty()) return null;
            memories.remove(best.get(0));
            pending.clear();   // or the conversation it came from would teach it again
            save();
            return best.get(0).text;
        }
    }

    synchronized boolean delete(long id) {
        boolean gone = removeId(id);
        if (gone) save();
        return gone;
    }

    synchronized void clear() {
        memories.clear();
        pending.clear();
        save();
    }

    /** Every memory, newest first, for the phone and for "what do you remember about me". */
    synchronized JSONArray list() {
        List<Memory> sorted = new ArrayList<>(memories);
        Collections.sort(sorted, (a, b) -> Long.compare(b.updated, a.updated));
        JSONArray out = new JSONArray();
        try {
            for (Memory m : sorted) out.put(new JSONObject().put("id", m.id).put("text", m.text).put("updated", m.updated));
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---- File

    private void load() {
        try {
            if (!file.exists()) return;
            byte[] raw = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONArray ms = root.optJSONArray("memories");
            if (ms != null) for (int i = 0; i < ms.length(); i++) {
                JSONObject o = ms.getJSONObject(i);
                Memory m = new Memory();
                m.id = o.getLong("id");
                m.text = o.getString("text");
                m.updated = o.optLong("updated");
                m.vec = unpack(o.optString("vec", ""));
                memories.add(m);
            }
            JSONArray p = root.optJSONArray("pending");
            if (p != null) for (int i = 0; i < p.length(); i++) {
                JSONArray t = p.getJSONArray(i);
                pending.add(new String[]{t.optString(0), t.optString(1)});
            }
        } catch (Exception e) {
            Log.w(TAG, "memory file unreadable; starting empty", e);
        }
    }

    /** Written to a temporary file and renamed over the old one, so a power cut never leaves half a file. */
    private void save() {
        try {
            JSONArray ms = new JSONArray();
            for (Memory m : memories) {
                ms.put(new JSONObject().put("id", m.id).put("text", m.text).put("updated", m.updated).put("vec", pack(m.vec)));
            }
            JSONArray p = new JSONArray();
            for (String[] t : pending) p.put(new JSONArray().put(t[0]).put(t[1]));
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(new JSONObject().put("memories", ms).put("pending", p).toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) Log.w(TAG, "couldn't replace the memory file");
        } catch (Exception e) {
            Log.w(TAG, "couldn't save memories", e);
        }
    }

    private static String pack(float[] v) {
        if (v == null) return "";
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return Base64.encodeToString(b.array(), Base64.NO_WRAP);
    }

    private static float[] unpack(String s) {
        if (s.isEmpty()) return null;
        ByteBuffer b = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.remaining() / 4];
        for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
        return v;
    }
}
