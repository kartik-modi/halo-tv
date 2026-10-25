package com.kartikmodi.halo.tonight;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A hero's power while it is in the spotlight: Spider-Man's spider-sense, Iron Man's reactor, Thor's lightning
 * and the rest, drawn over (front) or behind (back) the hero's picture. Positions are fractions of the picture's
 * head-and-shoulders box, the part below the room left above the head; the hero's own picture can also shake,
 * shrink or stretch. Runs only while attached, so only the selected hero animates.
 */
final class PowerView extends View {
    /** The picture is 3.75 head-heights tall; the box powers are placed in is its lower 2.89. */
    static final float BUST_H = 3.75f, FX_H = 2.89f;
    private static final float[] HEAD = {.5f, .22f}, CHEST = {.5f, .66f}, BROW = {.5f, .085f};
    private static final float[][] EYES = {{.455f, .205f}, {.545f, .205f}};

    private final JSONArray fx;
    private final float w, h, hh, y0, fh;
    private final boolean front;
    private final Bitmap picture, soft;
    private final int color;
    private final View target;
    private final Typeface display;
    private final long start = SystemClock.uptimeMillis();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint layer = new Paint(), dstIn = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final List<float[]> parts = new ArrayList<>();
    private final RectF r = new RectF();

    PowerView(Context c, JSONArray fx, float w, float h, float hh, boolean front, Bitmap picture, Bitmap soft, int color,
              View target, Typeface display) {
        super(c);
        this.fx = fx;
        this.w = w;
        this.h = h;
        this.hh = hh;
        this.y0 = (BUST_H - FX_H) * hh;
        this.fh = FX_H * hh;
        this.front = front;
        this.picture = picture;
        this.soft = soft;
        this.color = color;
        this.target = target;
        this.display = display;
        dstIn.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        Random rnd = new Random(color);
        for (int i = 0; i < fx.length(); i++) seed(i, fx.optJSONArray(i).optString(0), rnd);
    }

    private static float rnd(Random r, float a, float b) {
        return a + r.nextFloat() * (b - a);
    }

    /** Particles: x, y (box fractions), duration, delay, dx, dy, size, effect index, n. */
    private void seed(int e, String name, Random r) {
        int n;
        switch (name) {
            case "dust": n = 14; break;
            case "bubbles": n = 16; break;
            case "stars": n = 18; break;
            case "hearts": n = 12; break;
            case "haha": n = 7; break;
            case "speed": n = 10; break;
            case "chaos": n = 6; break;
            case "ooze": n = 5; break;
            default: return;
        }
        for (int i = 0; i < n; i++) {
            float x, y, d = rnd(r, 2.4f, 4.4f), dl = rnd(r, 0, 3), dx = rnd(r, -70, 70), dy = rnd(r, -220, -110), s;
            switch (name) {
                case "dust": x = rnd(r, .15f, .85f); y = rnd(r, .5f, .8f); s = rnd(r, 10, 26); break;
                case "bubbles": x = rnd(r, .15f, .85f); y = rnd(r, .3f, .8f); s = rnd(r, 8, 24); break;
                case "stars": x = rnd(r, .05f, .95f); y = rnd(r, 0, .7f); dy = rnd(r, -40, -10); s = rnd(r, 3, 7); break;
                case "hearts": x = rnd(r, .1f, .9f); y = rnd(r, .2f, .7f); s = rnd(r, 26, 46); break;
                case "haha": x = rnd(r, .05f, .85f); y = rnd(r, .05f, .6f); s = rnd(r, 32, 58); break;
                case "speed": x = rnd(r, .4f, 1.1f); y = rnd(r, .1f, .8f); d = rnd(r, .5f, 1f); dl = rnd(r, 0, 1); s = rnd(r, 120, 260); break;
                case "chaos": x = rnd(r, .25f, .75f); y = rnd(r, .1f, .6f); d = rnd(r, 2.5f, 4.5f); dx = rnd(r, -50, 50); dy = rnd(r, -60, 20); s = hh * rnd(r, 1f, 1.6f); break;
                default: x = rnd(r, .2f, .8f); y = rnd(r, .15f, .7f); d = rnd(r, 2f, 4f); dx = rnd(r, -40, 40); dy = rnd(r, -40, 40); s = hh * rnd(r, .9f, 1.4f); break;
            }
            parts.add(new float[]{x, y, d, dl, dx, dy, s, e, i});
        }
    }

    private float X(float f) { return f * w; }

    private float Y(float f) { return y0 + f * fh; }

    private static boolean isBack(String name) {
        switch (name) {
            case "mandala": case "chaos": case "ooze": case "dust": case "stars": case "radar": case "speed": return true;
            default: return false;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        if (!front && soft != null) {
            // a dark backing, so faces behind never show through, and the hero's glow
            p.setColorFilter(null);
            p.setColor(0xE6000000);
            r.set(0, 0, w, h);
            c.drawBitmap(soft, null, r, p);
            p.setColor(color);
            p.setAlpha(170);
            r.set(-w * .03f, -h * .03f, w * 1.03f, h * 1.03f);
            c.drawBitmap(soft, null, r, p);
            p.setAlpha(255);
        }
        for (int i = 0; i < fx.length(); i++) {
            JSONArray e = fx.optJSONArray(i);
            String name = e.optString(0);
            if (name.equals("shake") || name.equals("shrink") || name.equals("stretch")) {
                if (front) move(name, t);
                continue;
            }
            if (isBack(name) == front) continue;
            draw(c, name, e, i, t);
        }
        postInvalidateOnAnimation();
    }

    private void draw(Canvas c, String name, JSONArray e, int ei, float t) {
        int col = e.length() > 1 && e.opt(1) instanceof String && e.optString(1).startsWith("#") ? Color.parseColor(e.optString(1)) : color;
        switch (name) {
            case "reactor": {
                float y = (float) e.optDouble(2, .7);
                glow(c, X(.5f), Y(y), hh * .75f, col, t);
                ring(c, X(.5f), Y(y), hh * .6f, col, t, 0, 3);
                break;
            }
            case "eyes": {
                JSONArray pts = e.optJSONArray(2);
                float[][] at = EYES;
                if (pts != null) {
                    at = new float[pts.length()][];
                    for (int k = 0; k < pts.length(); k++) at[k] = new float[]{(float) pts.optJSONArray(k).optDouble(0), (float) pts.optJSONArray(k).optDouble(1)};
                }
                for (float[] a : at) glow(c, X(a[0]), Y(a[1]), hh * .2f, col, t);
                break;
            }
            case "chest": {
                float[] at = CHEST;
                Object a = e.opt(3);
                if (a instanceof JSONArray) at = new float[]{(float) ((JSONArray) a).optDouble(0), (float) ((JSONArray) a).optDouble(1)};
                else if (a instanceof Number) at = new float[]{.5f, ((Number) a).floatValue()};
                glow(c, X(at[0]), Y(at[1]), hh * ("big".equals(e.optString(2)) ? 1.2f : .8f), col, t);
                break;
            }
            case "gem":
                glow(c, X(BROW[0]), Y(BROW[1]), hh * .55f, col, t);
                ring(c, X(BROW[0]), Y(BROW[1]), hh * .4f, col, t, 0, 3);
                break;
            case "cyeye": {
                JSONArray a = e.optJSONArray(1);
                float x = a == null ? EYES[0][0] : (float) a.optDouble(0), y = a == null ? EYES[0][1] : (float) a.optDouble(1);
                glow(c, X(x), Y(y), hh * .32f, 0xFFFF2A2A, t);
                break;
            }
            case "bolts": bolts(c, col, "small".equals(e.optString(2)), t); break;
            case "spidey": spidey(c, t); break;
            case "mandala": mandala(c, col, t); break;
            case "sheen": case "kinetic": sheen(c, col, name.equals("kinetic"), t); break;
            case "radar":
                for (int k = 0; k < 3; k++) ring(c, X(HEAD[0]), Y(HEAD[1]), hh * 2.6f, 0xFFFF2020, t, k * .6f, 2);
                break;
            case "glint": {
                float x = (float) e.optDouble(1, .5), y = (float) e.optDouble(2, .5);
                star(c, X(x), Y(y), 80, t);
                star(c, X(x + .08f), Y(y - .04f), 54, t - 1);
                break;
            }
            case "quip": quip(c, e.optString(1), t); break;
            case "arrow": arrow(c, col, t); break;
            case "scan": scan(c, t); break;
            default: particles(c, name, ei, col, t); break;
        }
    }

    private static float pulse(float t, float period) {
        return (float) (.5 - .5 * Math.cos(2 * Math.PI * t / period));
    }

    private void glow(Canvas c, float x, float y, float size, int col, float t) {
        float k = pulse(t, 1.6f), rad = size / 2 * (1 - .14f * k);
        p.setShader(new RadialGradient(x, y, rad, new int[]{Color.WHITE, Color.WHITE, col, col & 0x00FFFFFF},
                new float[]{0, .12f, .3f, .68f}, Shader.TileMode.CLAMP));
        p.setAlpha((int) (255 * (1 - .45f * k)));
        c.drawCircle(x, y, rad, p);
        p.setShader(null);
        p.setAlpha(255);
    }

    private void ring(Canvas c, float x, float y, float size, int col, float t, float delay, float stroke) {
        if (t < delay) return;
        float q = ((t - delay) % 1.8f) / 1.8f, s = .3f + 1.3f * q;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(col);
        p.setAlpha((int) (230 * (1 - q)));
        p.setShadowLayer(14, 0, 0, col);
        c.drawCircle(x, y, size / 2 * s, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255);
    }

    private void bolts(Canvas c, int col, boolean small, float t) {
        int n = small ? 4 : 6;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(small ? 3 : 4);
        p.setStrokeJoin(Paint.Join.BEVEL);
        p.setColor(col);
        p.setShadowLayer(12, 0, 0, col);
        Path path = new Path();
        for (int i = 0; i < n; i++) {
            float tt = t - i * .37f;
            if (tt < 0) continue;
            int cycle = (int) (tt / 2.2f);
            float q = (tt % 2.2f) / 2.2f;
            float a = q < .06f ? 1 : q < .1f ? .2f : q < .16f ? 1 : 0;
            if (a == 0) continue;
            Random r = new Random(cycle * 31L + i * 7L + color);
            float side = i % 2 == 1 ? 1 : -1, x = w / 2 + side * hh * rnd(r, .5f, 1.3f), y = y0 + fh * rnd(r, .05f, .5f);
            float len = (small ? .6f : 1.2f) * hh, ang = rnd(r, .3f, 2.8f);
            path.reset();
            path.moveTo(x, y);
            for (int s = 0; s < 7; s++) {
                x += Math.cos(ang) * len / 7 + rnd(r, -12, 12);
                y += Math.sin(ang) * len / 7 + rnd(r, -12, 12);
                path.lineTo(x, y);
            }
            p.setAlpha((int) (255 * a));
            c.drawPath(path, p);
        }
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255);
    }

    private void spidey(Canvas c, float t) {
        float cx = X(HEAD[0]), cy = Y(HEAD[1]);
        float[][] rays = {{-165, 1}, {-135, 1.08f}, {-105, 1.15f}, {-75, 1.15f}, {-45, 1.08f}, {-15, 1}, {190, .95f}, {350, .95f}};
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Color.WHITE);
        p.setShadowLayer(6, 0, 0, Color.WHITE);
        Path path = new Path();
        for (int i = 0; i < rays.length; i++) {
            float k = rays[i][1], an = (float) Math.toRadians(rays[i][0]);
            float tingle = pulse(t + i * .07f, 1f);
            float s = .9f + .18f * tingle;
            float r0 = hh * .62f * k * s, r1 = hh * 1f * k * s, nx = (float) Math.cos(an), ny = (float) Math.sin(an), px = -ny, py = nx;
            path.reset();
            path.moveTo(cx + nx * r0, cy + ny * r0);
            for (int j = 1; j <= 4; j++) {
                float rr = r0 + (r1 - r0) * j / 4, o = (j % 2 == 1 ? 1 : -1) * hh * .07f;
                path.lineTo(cx + nx * rr + px * o, cy + ny * rr + py * o);
            }
            p.setAlpha((int) (255 * (.35f + .65f * tingle)));
            c.drawPath(path, p);
        }
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255);
    }

    private void mandala(Canvas c, int col, float t) {
        float cx = X(.5f), cy = Y(.26f), s = hh * 2.3f / 100;
        p.setStyle(Paint.Style.STROKE);
        p.setColor(col);
        p.setStrokeWidth(.8f * s);
        p.setShadowLayer(16, 0, 0, col);
        for (int ring = 0; ring < 2; ring++) {
            c.save();
            c.translate(cx, cy);
            c.rotate(ring == 0 ? t * 40 : -t * 25.7f);
            c.scale(s, s);
            c.translate(-50, -50);
            p.setStrokeWidth(.8f);
            if (ring == 0) {
                c.drawCircle(50, 50, 48, p);
                c.drawCircle(50, 50, 44, p);
                for (int i = 0; i < 36; i++) {
                    c.save();
                    c.rotate(i * 10, 50, 50);
                    c.drawLine(50, 3, 50, i % 3 == 0 ? 10 : 7, p);
                    c.restore();
                }
                c.drawRect(22, 22, 78, 78, p);
                c.save();
                c.rotate(45, 50, 50);
                c.drawRect(22, 22, 78, 78, p);
                c.restore();
            } else {
                c.drawCircle(50, 50, 30, p);
                p.setPathEffect(new DashPathEffect(new float[]{2, 3}, 0));
                c.drawCircle(50, 50, 26, p);
                p.setPathEffect(null);
                Path tri = new Path();
                tri.moveTo(50, 22); tri.lineTo(74, 64); tri.lineTo(26, 64); tri.close();
                tri.moveTo(50, 78); tri.lineTo(26, 36); tri.lineTo(74, 36); tri.close();
                c.drawPath(tri, p);
            }
            c.restore();
        }
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
    }

    /** A bright band sweeping across the hero, kept inside the hero's own outline. */
    private void sheen(Canvas c, int col, boolean kinetic, float t) {
        if (picture == null) return;
        float period = kinetic ? 1.5f : 2.6f, q = (t % period) / period;
        q = (float) (.5 - .5 * Math.cos(Math.PI * q));
        float band = w * (kinetic ? .45f : .35f), x = -band + (w + 2 * band) * q;
        layer.setAlpha(kinetic ? 230 : 128);
        layer.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
        int save = c.saveLayer(0, 0, w, h, layer);
        p.setShader(new LinearGradient(x - band / 2, 0, x + band / 2, h * .12f,
                new int[]{0, col, Color.WHITE, col, 0}, new float[]{0, .4f, .5f, .6f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        r.set(0, 0, w, h);
        c.drawBitmap(picture, null, r, dstIn);
        c.restoreToCount(save);
    }

    private void star(Canvas c, float x, float y, float size, float t) {
        if (t < 0) return;
        float k = (float) Math.sin(Math.PI * ((t % 2f) / 2f));
        if (k <= 0) return;
        c.save();
        c.translate(x, y);
        c.rotate(45 * k);
        c.scale(.4f + .6f * k, .4f + .6f * k);
        float half = size / 2;
        p.setAlpha((int) (255 * k));
        p.setShader(new RadialGradient(0, 0, half * .3f, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, half * .3f, p);
        p.setShader(new LinearGradient(-half, 0, half, 0, new int[]{0, Color.WHITE, 0}, null, Shader.TileMode.CLAMP));
        c.drawRect(-half, -1.5f, half, 1.5f, p);
        p.setShader(new LinearGradient(0, -half, 0, half, new int[]{0, Color.WHITE, 0}, null, Shader.TileMode.CLAMP));
        c.drawRect(-1.5f, -half, 1.5f, half, p);
        p.setShader(null);
        p.setAlpha(255);
        c.restore();
    }

    private void quip(Canvas c, String text, float t) {
        float k = Math.max(0, Math.min(1, (t - .3f) / .5f));
        if (k <= 0) return;
        float s = (float) (1 + 1.6 * Math.pow(k - 1, 3) + .6 * Math.pow(k - 1, 2)) ;   // a small overshoot
        p.setTypeface(display);
        p.setFakeBoldText(true);
        p.setTextSize(28);
        float tw = p.measureText(text), bx = X(.66f), by = Y(-.04f), bw = tw + 32, bh = 52;
        c.save();
        c.scale(s, s, bx, by + bh);
        p.setColor(Color.WHITE);
        p.setShadowLayer(18, 0, 8, 0x80000000);
        r.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(r, 16, 16, p);
        p.clearShadowLayer();
        Path tail = new Path();
        tail.moveTo(bx + 16, by + bh - 1); tail.lineTo(bx + 34, by + bh - 1); tail.lineTo(bx + 22, by + bh + 13); tail.close();
        c.drawPath(tail, p);
        p.setColor(0xFF111111);
        c.drawText(text, bx + 16, by + 36, p);
        c.restore();
        p.setFakeBoldText(false);
    }

    private void arrow(Canvas c, int col, float t) {
        float q = (t % 2.2f) / 2.2f;
        if (q > .55f) return;
        float k = q / .55f;
        k = k * k * (3 - 2 * k);
        float x = -1.4f * 240 + (2.2f * 240 + 1.4f * 240) * k, y = Y(.44f);
        p.setAlpha((int) (255 * Math.min(1, q / .055f)));
        p.setShadowLayer(8, 0, 0, col);
        p.setColor(col);
        p.setStrokeWidth(3);
        c.drawLine(x, y, x + 214, y, p);
        p.setColor(Color.WHITE);
        Path head = new Path();
        head.moveTo(x + 214, y - 6); head.lineTo(x + 240, y); head.lineTo(x + 214, y + 6); head.close();
        c.drawPath(head, p);
        p.clearShadowLayer();
        p.setAlpha(255);
    }

    private void scan(Canvas c, float t) {
        float q = pulse(t, 4f), y = Y(.06f) + (Y(.6f) - Y(.06f)) * q;
        p.setShader(new LinearGradient(X(.2f), 0, X(.8f), 0, new int[]{0, 0xFFFF2A2A, 0}, null, Shader.TileMode.CLAMP));
        p.setShadowLayer(16, 0, 0, 0xFFFF2A2A);
        c.drawRect(X(.2f), y - 2.5f, X(.8f), y + 2.5f, p);
        p.clearShadowLayer();
        p.setShader(null);
    }

    private void particles(Canvas c, String name, int ei, int col, float t) {
        for (float[] q : parts) {
            if ((int) q[7] != ei) continue;
            float d = q[2], dl = q[3], s = q[6];
            int i = (int) q[8];
            if (name.equals("chaos") || name.equals("ooze")) {
                float e = pulse(t, d * 2);
                float x = X(q[0]) + q[4] * e, y = Y(q[1]) + q[5] * e, rad = s / 2 * (.8f + .4f * e);
                int base = name.equals("chaos") ? col : 0xFF3B4CFF;
                p.setShader(new RadialGradient(x, y, rad, base, base & 0x00FFFFFF, Shader.TileMode.CLAMP));
                p.setAlpha(name.equals("chaos") ? 200 : 150);
                c.drawCircle(x, y, rad, p);
                p.setShader(null);
                p.setAlpha(255);
                continue;
            }
            if (t < dl) continue;
            float k = ((t - dl) / d) % 1f, a = k < .15f ? k / .15f : 1 - (k - .15f) / .85f;
            if (name.equals("speed")) {
                float x = X(q[0]) + 160 - 420 * k, y = Y(q[1]);
                int sc = i % 2 == 1 ? 0xFFFFCC00 : 0xFFFF6A00;
                p.setShader(new LinearGradient(x, 0, x + s, 0, sc & 0x00FFFFFF, sc, Shader.TileMode.CLAMP));
                p.setAlpha((int) (230 * (k < .2f ? k / .2f : 1 - (k - .2f) / .8f)));
                c.drawRoundRect(x, y - 2, x + s, y + 2, 2, 2, p);
                p.setShader(null);
                p.setAlpha(255);
                continue;
            }
            float x = X(q[0]) + q[4] * k, y = Y(q[1]) + q[5] * k;
            int alpha = (int) (255 * Math.max(0, a));
            switch (name) {
                case "dust":
                    p.setShader(new RadialGradient(x, y, s, col, col & 0x00FFFFFF, Shader.TileMode.CLAMP));
                    p.setAlpha(alpha * 7 / 10);
                    c.drawCircle(x, y, s, p);
                    p.setShader(null);
                    break;
                case "bubbles":
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2);
                    p.setColor(0xCCA0FFFF);
                    p.setAlpha(alpha);
                    p.setShadowLayer(8, 0, 0, 0xFF18C3C8);
                    c.drawCircle(x, y, s / 2, p);
                    p.clearShadowLayer();
                    p.setStyle(Paint.Style.FILL);
                    break;
                case "stars":
                    p.setColor(Color.WHITE);
                    p.setAlpha(alpha);
                    p.setShadowLayer(10, 0, 0, 0xFFFF9A3D);
                    c.drawCircle(x, y, s / 2, p);
                    p.clearShadowLayer();
                    break;
                case "hearts":
                    p.setTypeface(Typeface.DEFAULT);
                    p.setTextSize(s);
                    int hc = i % 2 == 1 ? 0xFFFF2D55 : 0xFF3D6BFF;
                    p.setColor(hc);
                    p.setAlpha(alpha);
                    p.setShadowLayer(10, 0, 0, hc);
                    c.drawText(i % 3 == 0 ? "♥" : "♦", x, y, p);
                    p.clearShadowLayer();
                    break;
                case "haha":
                    p.setTypeface(display);
                    p.setFakeBoldText(true);
                    p.setTextSize(s);
                    int lc = i % 2 == 1 ? 0xFF7DFF3A : 0xFFB45CFF;
                    p.setColor(lc);
                    p.setAlpha(alpha);
                    p.setShadowLayer(12, 0, 0, lc);
                    c.drawText(i % 3 == 0 ? "HA HA" : "HA", x, y, p);
                    p.clearShadowLayer();
                    p.setFakeBoldText(false);
                    break;
                default:
                    break;
            }
            p.setAlpha(255);
        }
    }

    /** Hulk's shake, Ant-Man's shrink, Mr. Fantastic's stretch: moves the hero's own picture. */
    private void move(String name, float t) {
        float[][] keys;
        float period;
        switch (name) {
            case "shake":
                period = 2.4f;
                keys = new float[][]{{0, 0, 0, 1, 1}, {.7f, 0, 0, 1, 1}, {.72f, -6, 2, 1, 1}, {.74f, 6, -2, 1, 1}, {.76f, -5, 1, 1, 1}, {.78f, 4, 0, 1, 1}, {.8f, 0, 0, 1, 1}, {1, 0, 0, 1, 1}};
                target.setPivotX(w / 2);
                target.setPivotY(h * .6f);
                break;
            case "shrink":
                period = 3f;
                keys = new float[][]{{0, 0, 0, 1, 1}, {.6f, 0, 0, 1, 1}, {.66f, 0, 0, .8f, .8f}, {.72f, 0, 0, 1.04f, 1.04f}, {.78f, 0, 0, 1, 1}, {1, 0, 0, 1, 1}};
                target.setPivotX(w / 2);
                target.setPivotY(h * .6f);
                break;
            default:
                period = 2.8f;
                keys = new float[][]{{0, 0, 0, 1, 1}, {.55f, 0, 0, 1, 1}, {.63f, 0, 0, .96f, 1.05f}, {.71f, 0, 0, 1.04f, .97f}, {.79f, 0, 0, .99f, 1.01f}, {.87f, 0, 0, 1, 1}, {1, 0, 0, 1, 1}};
                target.setPivotX(w / 2);
                target.setPivotY(h);
                break;
        }
        float q = (t % period) / period;
        for (int i = 1; i < keys.length; i++) {
            if (q > keys[i][0]) continue;
            float[] a = keys[i - 1], b = keys[i];
            float f = (q - a[0]) / Math.max(1e-4f, b[0] - a[0]);
            target.setTranslationX(a[1] + (b[1] - a[1]) * f);
            target.setTranslationY(a[2] + (b[2] - a[2]) * f);
            target.setScaleX(a[3] + (b[3] - a[3]) * f);
            target.setScaleY(a[4] + (b[4] - a[4]) * f);
            return;
        }
    }
}
