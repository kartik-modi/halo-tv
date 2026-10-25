package com.kartikmodi.halo.tonight;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Downloads TMDB images into memory only: an LRU of decoded bitmaps capped by size, nothing on disk.
 * What is on screen loads on its own threads, newest request first; warming the cache for the genres
 * either side runs on a separate low-priority thread that keeps only the latest few dozen requests.
 * The same image is never fetched twice at once.
 */
final class Images {
    private Images() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** On screen now: newest first, so a fast spin shows where it stopped, not where it passed. */
    private static final LinkedBlockingDeque<Runnable> VISIBLE_QUEUE = new LinkedBlockingDeque<>();
    private static final ThreadPoolExecutor VISIBLE = lifo(3, VISIBLE_QUEUE);
    /** Prefetch: one thread, newest first, the queue trimmed to the latest requests. */
    private static final LinkedBlockingDeque<Runnable> PREFETCH_QUEUE = new LinkedBlockingDeque<>();
    private static final ThreadPoolExecutor PREFETCH = lifo(1, PREFETCH_QUEUE);
    private static final int PREFETCH_KEEP = 40;

    /** About 28 MB of decoded images; a 185 px poster is ~0.2 MB, a 342 px one ~0.7 MB. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(28 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** Downloads in flight, with everyone waiting for each. */
    private static final Map<String, List<Consumer<Bitmap>>> WAITING = new HashMap<>();

    private static ThreadPoolExecutor lifo(int threads, LinkedBlockingDeque<Runnable> q) {
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, q) {
            @Override
            public void execute(Runnable r) {
                // newest at the head; workers take from the head
                if (getPoolSize() < getCorePoolSize()) super.execute(r);
                else q.offerFirst(r);
            }
        };
    }

    static Bitmap cached(String url) {
        return url == null ? null : CACHE.get(url);
    }

    /** For what is on screen. Calls back on the main thread with the bitmap, or null if it could not be loaded. */
    static void load(String url, Consumer<Bitmap> done) {
        if (url == null) { done.accept(null); return; }
        Bitmap hit = CACHE.get(url);
        if (hit != null) { done.accept(hit); return; }
        if (join(url, done)) return;
        VISIBLE.execute(() -> deliver(url, fetch(url)));
    }

    /** Warms the cache in the background, below anything on screen. */
    static void prefetch(String url) {
        if (url == null || CACHE.get(url) != null) return;
        synchronized (WAITING) {
            if (WAITING.containsKey(url)) return;
        }
        PREFETCH.execute(() -> {
            if (CACHE.get(url) != null) return;
            if (join(url, null)) return;
            deliver(url, fetch(url));
        });
        while (PREFETCH_QUEUE.size() > PREFETCH_KEEP) PREFETCH_QUEUE.pollLast();   // drop the oldest
    }

    /** True when this url is already downloading (the callback is added to it); false: the caller fetches it. */
    private static boolean join(String url, Consumer<Bitmap> done) {
        synchronized (WAITING) {
            List<Consumer<Bitmap>> w = WAITING.get(url);
            if (w != null) {
                if (done != null) w.add(done);
                return true;
            }
            w = new ArrayList<>();
            if (done != null) w.add(done);
            WAITING.put(url, w);
            return false;
        }
    }

    private static void deliver(String url, Bitmap b) {
        List<Consumer<Bitmap>> w;
        synchronized (WAITING) {
            w = WAITING.remove(url);
        }
        if (w != null && !w.isEmpty()) MAIN.post(() -> { for (Consumer<Bitmap> c : w) c.accept(b); });
    }

    /** Blocking download and decode. */
    static Bitmap fetch(String url) {
        Bitmap hit = CACHE.get(url);
        if (hit != null) return hit;
        try {
            byte[] bytes = Net.get(url, null);
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (b != null) CACHE.put(url, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
