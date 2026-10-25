package com.kartikmodi.halo.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The Ambient screen's turntable, drawn on its own surface by its own thread at the display's refresh rate
 * (the TV's WebView needs about 19 ms for any animated frame). It covers the page's #deck exactly.
 *
 * Three layers on the 613 x 462 deck from the design: the bare deck (the page's own picture, under this surface), the record
 * (black vinyl with the song's cover art on its label, centre 237,235, radius 172) and the tonearm
 * (assets/ambient/arm.webp, turning on its plate at 485,118). Play lifts the arm, swings it onto the
 * record and sets it down while the record spins up to 33 1/3 rpm; pause lets the record run down and puts
 * the arm back on its rest. Over a song the arm creeps inwards.
 */
final class TurntableView extends SurfaceView implements SurfaceHolder.Callback, Choreographer.FrameCallback {

    private static final float DW = 613f, DH = 462f;                 // the deck, in design pixels
    private static final float RX = 237f, RY = 235f, RR = 172f;      // the record
    private static final float AX = 311f, AY = 31f;                  // where arm.webp sits
    private static final float PX = 485f, PY = 118f;                 // the arm's pivot
    private static final float REST = -18f, START = 0f, END = 9f;    // arm angles, degrees
    private static final float RPS = 33.333f / 60f;                  // turns per second
    private static final long SWING_NS = 1_200_000_000L;

    private final Context app;
    private final HandlerThread thread = new HandlerThread("Turntable");
    private final Handler handler;
    private Choreographer choreographer;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();

    // Render thread only.
    private Bitmap arm, armShadow, record, previous, overlay;
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int shadowDx, shadowDy;
    private long fadeStart, lastFrame;
    private float angle, speed;
    private float armFrom = REST, armTo = REST, armNow = REST;
    private long swingStart;
    private boolean swingLifts, surfaceReady, ticking;
    private String artUrl;
    private Bitmap art;   // the current cover, kept to redraw the record at a new size

    // From the page.
    private volatile boolean playing;
    private volatile float progress;

    TurntableView(Context context) {
        super(context);
        app = context.getApplicationContext();
        setZOrderOnTop(true);   // above the page; the deck's rounded corners show it through
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            choreographer = Choreographer.getInstance();
            arm = asset("ambient/arm.webp");
            if (arm != null) {
                // The arm's shadow: its outline, blurred, drawn dark under it.
                Paint blur = new Paint();
                blur.setMaskFilter(new BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL));
                int[] off = new int[2];
                armShadow = arm.extractAlpha(blur, off);
                shadowDx = off[0];
                shadowDy = off[1];
            }
        });
    }

    private Bitmap asset(String name) {
        try (InputStream in = app.getAssets().open(name)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** Cover art for the record's label; only YouTube's thumbnail host is fetched. */
    void setArt(String url) {
        if (url == null || !url.startsWith("https://i.ytimg.com/")) return;
        handler.post(() -> {
            if (url.equals(artUrl)) return;
            artUrl = url;
            Bitmap src = download(url);
            if (src == null || !url.equals(artUrl)) return;
            art = src;
            previous = record;
            record = null;   // redrawn with the new label on the next frame
            fadeStart = System.nanoTime();
            startTicking();
        });
    }

    /** Playing or not, and how far through the song (0..1), which sets where the arm sits. */
    void setState(boolean playing, float progress) {
        this.playing = playing;
        this.progress = progress;
        handler.post(() -> {
            float target = this.playing ? START + (END - START) * Math.max(0f, Math.min(1f, this.progress)) : REST;
            if (Math.abs(target - armTo) > 0.05f) {
                armFrom = armNow;
                armTo = target;
                swingStart = System.nanoTime();
                swingLifts = Math.abs(armTo - armFrom) > 3f;   // a real move lifts the arm; creeping along a song doesn't
            }
            startTicking();
        });
    }

    void release() {
        handler.post(() -> {
            if (choreographer != null) choreographer.removeFrameCallback(this);
            ticking = false;
        });
        thread.quitSafely();
    }

    // ---- Surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        handler.post(() -> {
            surfaceReady = true;
            startTicking();
        });
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        handler.post(() -> {
            record = null;    // rebuilt at the new size
            overlay = null;
            startTicking();
        });
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Called on the UI thread; the render thread must stop drawing before this returns.
        final Object done = new Object();
        synchronized (done) {
            handler.post(() -> {
                surfaceReady = false;
                synchronized (done) { done.notify(); }
            });
            try {
                done.wait(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // ---- Render loop (render thread)

    private void startTicking() {
        if (ticking || !surfaceReady || choreographer == null) return;
        ticking = true;
        lastFrame = 0;
        choreographer.postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!surfaceReady) { ticking = false; return; }
        float dt = lastFrame == 0 ? 0f : Math.min(frameTimeNanos - lastFrame, 100_000_000L) / 1e9f;
        lastFrame = frameTimeNanos;

        // About 0.8 s to come up to speed, about 1.6 s to run down.
        float want = playing ? RPS : 0f;
        speed += Math.max(-RPS * dt / 1.6f, Math.min(RPS * dt / 0.8f, want - speed));
        angle = (angle + speed * 360f * dt) % 360f;

        long now = System.nanoTime();
        float f = Math.min(1f, (now - swingStart) / (float) (swingLifts ? SWING_NS : SWING_NS / 2));
        float e = f < 0.5f ? 4f * f * f * f : 1f - (float) Math.pow(-2f * f + 2f, 3) / 2f;   // ease in and out
        armNow = armFrom + (armTo - armFrom) * e;
        float lift = swingLifts ? (float) Math.sin(Math.PI * Math.min(1f, f * 1.15f)) : 0f;
        float fade = previous == null ? 1f : Math.min(1f, (now - fadeStart) / 700_000_000f);
        if (fade >= 1f) previous = null;

        draw(fade, lift);

        boolean moving = speed > 0.0005f || playing || f < 1f || previous != null;
        if (moving) choreographer.postFrameCallback(this);
        else ticking = false;
    }

    private void draw(float fade, float lift) {
        SurfaceHolder holder = getHolder();
        Canvas c;
        try {
            c = holder.lockHardwareCanvas();
        } catch (RuntimeException ex) {
            c = holder.lockCanvas();
        }
        if (c == null) return;
        try {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            int w = c.getWidth(), h = c.getHeight();
            float k = Math.min(w / DW, h / DH);
            // Only what moves is drawn here; the deck itself is the page's picture, showing through.
            // The record turns under its light, which stays still.
            int size = Math.max(64, Math.round(RR * 2 * k));
            if (record == null || record.getWidth() != size) record = makeRecord(art, size);
            if (overlay == null || overlay.getWidth() != size) overlay = makeOverlay(size);
            float left = (RX - RR) * k, top = (RY - RR) * k;
            if (previous != null) drawRotated(c, previous, left, top, size, 1f - fade);
            drawRotated(c, record, left, top, size, fade);
            paint.setAlpha(255);
            c.drawBitmap(overlay, left, top, paint);

            // The arm, and its shadow, which falls further away while it is lifted.
            if (arm != null) {
                c.save();
                c.scale(k, k);
                c.rotate(armNow, PX, PY);
                if (armShadow != null) {
                    shadowPaint.setColor(Color.argb(Math.round(90 - 25 * lift), 0, 0, 0));
                    c.drawBitmap(armShadow, AX + shadowDx + 5 + 7 * lift, AY + shadowDy + 7 + 9 * lift, shadowPaint);
                }
                c.drawBitmap(arm, AX, AY, paint);
                c.restore();
            }
        } finally {
            holder.unlockCanvasAndPost(c);
        }
    }

    private void drawRotated(Canvas c, Bitmap b, float left, float top, int size, float a) {
        matrix.reset();
        matrix.postScale((float) size / b.getWidth(), (float) size / b.getHeight());
        matrix.postRotate(angle, size / 2f, size / 2f);
        matrix.postTranslate(left, top);
        paint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, a))));
        c.drawBitmap(b, matrix, paint);
    }

    // ---- Bitmaps (render thread)

    private static Bitmap download(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Black vinyl with pressed grooves, and the cover art (cropped square) on the label; a plain label without one. */
    private static Bitmap makeRecord(Bitmap src, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float r = size / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(r, r, r, new int[]{0xFF1B1B1D, 0xFF1B1B1D, 0xFF0B0B0C}, new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, p);
        Paint groove = new Paint(Paint.ANTI_ALIAS_FLAG);
        groove.setStyle(Paint.Style.STROKE);
        java.util.Random rnd = new java.util.Random(7);
        float step = Math.max(1.2f, size / 310f);
        for (float rr = r * 0.4f; rr < r * 0.985f; rr += step) {
            float f = rr / r;
            boolean gap = Math.abs(f - 0.55f) < 0.006f || Math.abs(f - 0.68f) < 0.006f || Math.abs(f - 0.8f) < 0.006f || Math.abs(f - 0.9f) < 0.006f;
            groove.setStrokeWidth(gap ? step * 1.4f : Math.max(0.6f, step * 0.45f));
            groove.setColor(gap ? Color.argb(230, 0, 0, 0) : Color.argb(6 + rnd.nextInt(8), 255, 255, 255));
            c.drawCircle(r, r, rr, groove);
        }
        groove.setStrokeWidth(Math.max(1f, size / 340f));
        groove.setColor(Color.argb(30, 255, 255, 255));
        c.drawCircle(r, r, r - size / 340f, groove);

        float label = r * 0.36f;
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (src != null) {
            int sw = src.getWidth(), sh = src.getHeight();
            Rect from;
            if (Math.abs((float) sw / sh - 4f / 3f) < 0.02f) {   // 4:3 thumbnails carry the frame between black bars
                int t = Math.round(sh * 0.125f), fh = Math.round(sh * 0.75f);
                from = new Rect((sw - fh) / 2, t, (sw + fh) / 2, t + fh);
            } else {
                int side = Math.min(sw, sh);
                from = new Rect((sw - side) / 2, (sh - side) / 2, (sw + side) / 2, (sh + side) / 2);
            }
            int ls = Math.max(8, Math.round(label * 2));
            Bitmap sq = Bitmap.createBitmap(ls, ls, Bitmap.Config.ARGB_8888);
            new Canvas(sq).drawBitmap(src, from, new RectF(0, 0, ls, ls), new Paint(Paint.FILTER_BITMAP_FLAG));
            BitmapShader sh2 = new BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setTranslate(r - label, r - label);
            sh2.setLocalMatrix(m);
            lp.setShader(sh2);
            c.drawCircle(r, r, label, lp);
        } else {
            lp.setColor(0xFF7D1F2B);
            c.drawCircle(r, r, label, lp);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(Color.argb(204, 255, 255, 255));
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            t.setTextSize(label * 0.2f);
            c.drawText("AMBIENT", r, r - label * 0.35f, t);
            t.setTypeface(Typeface.SANS_SERIF);
            t.setTextSize(label * 0.13f);
            c.drawText("33⅓ RPM", r, r + label * 0.55f, t);
        }
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1f, size / 230f));
        ring.setColor(Color.argb(128, 0, 0, 0));
        c.drawCircle(r, r, label, ring);
        return out;
    }

    /** The light on the vinyl and the spindle: they stay still while the record turns under them. */
    private static Bitmap makeOverlay(int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float r = size / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int clear = Color.argb(0, 255, 255, 255);
        p.setShader(new SweepGradient(r, r,
                new int[]{clear, Color.argb(41, 255, 255, 255), clear, clear, Color.argb(28, 255, 255, 255), clear, clear},
                new float[]{0f, 0.06f, 0.15f, 0.5f, 0.57f, 0.66f, 1f}));
        c.save();
        c.rotate(-65, r, r);
        c.drawCircle(r, r, r, p);
        c.restore();
        // Keep the label clear of the light.
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawCircle(r, r, r * 0.375f, hole);

        float s = Math.max(3f, size / 57f);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setShader(new RadialGradient(r - s * 0.3f, r - s * 0.4f, s * 1.2f,
                new int[]{Color.WHITE, 0xFF9A9A9A, 0xFF555555}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, s, sp);
        return out;
    }
}
