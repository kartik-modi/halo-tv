package com.kartikmodi.halo.tonight;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * The genre wheel: a whole circle whose centre sits off the left edge, the chosen genre pointing right.
 * Genres fade with their distance from the chosen one; next to it sit its title count and the shuffle dice.
 */
final class WheelView extends View {
    static final int HOT = 0xFFFF4D2E;
    private String[] labels = new String[0];
    private int selected;
    private float angle;          // current rotation in "items", animated towards 'selected'
    private boolean focused, rolling;
    private float rollT;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), dice = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private ValueAnimator spin;
    /** Called once for every genre that passes the pointer, like a prize wheel's flapper. */
    interface Ticker { void tick(float volume); }
    private Ticker ticker;
    private int lastCell;

    void setTicker(Ticker t) {
        ticker = t;
    }
    private final float u;        // 1% of the screen width

    WheelView(Context ctx, Typeface display, float unit) {
        super(ctx);
        u = unit;
        text.setTypeface(display);
        text.setFontVariationSettings("'wght' 800");   // the variable font defaults to its thinnest weight
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1, u * 0.08f));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setLabels(String[] l, int sel) {
        labels = l;
        selected = sel;
        angle = sel;
        lastCell = sel;
        invalidate();
    }

    String[] labels() {
        return labels;
    }

    int selected() {
        return selected;
    }

    void select(int i) {
        if (labels.length == 0) return;
        int n = labels.length;
        // spin the short way round, so stepping from the last genre to the first keeps turning the same way
        int to = ((i % n) + n) % n;
        float target = angle + shortest(to - selected, n);
        selected = to;
        if (spin != null) spin.cancel();
        spin = ValueAnimator.ofFloat(angle, target);
        spin.setDuration(520);
        // a springy settle for one step; a calmer one when the wheel has far to go, or it overshoots by genres
        spin.setInterpolator(new OvershootInterpolator(Math.abs(target - angle) > 1.5f ? 0.35f : 1.1f));
        spin.addUpdateListener(a -> {
            angle = (float) a.getAnimatedValue();
            int cell = Math.round(angle);
            if (cell != lastCell) {
                lastCell = cell;
                if (ticker != null) ticker.tick(0.55f);
            }
            invalidate();
        });
        spin.start();
    }

    private static float shortest(int d, int n) {
        d = ((d % n) + n) % n;
        return d > n / 2 ? d - n : d;
    }

    void setFocusedLook(boolean f) {
        focused = f;
        invalidate();
    }

    void roll() {
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(650);
        a.addUpdateListener(v -> { rollT = (float) v.getAnimatedValue(); rolling = rollT < 1; invalidate(); });
        a.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = labels.length;
        if (n == 0) return;
        float cx = -15 * u, cy = getHeight() * 0.5f, R = 21 * u, step = 360f / n;
        // the circle itself, lit when the wheel has focus
        ring.setColor(focused ? 0x8CFF4D2E : 0x1FFFFFFF);
        c.drawCircle(cx, cy, R - 1.6f * u, ring);
        ring.setColor(0x59FF4D2E);
        c.drawCircle(cx, cy, R - 9 * u, ring);
        // the genre at the pointer right now: highlighted, with the dice, even mid-spin
        int atPointer = ((Math.round(angle) % n) + n) % n;
        for (int i = 0; i < n; i++) {
            // signed distance in items from the current angle, wrapped into -n/2..n/2
            float diff = ((i - angle) % n + n) % n;
            if (diff > n / 2f) diff -= n;
            float d = Math.abs(diff);
            if (d > 2.6f) continue;
            float alpha = i == atPointer ? 1 : Math.max(0, 0.8f - d * 0.22f);
            boolean on = i == atPointer;
            c.save();
            c.translate(cx, cy);
            c.rotate(diff * step);
            c.translate(R, 0);
            float size = on ? 3f * u : 1.9f * u;
            text.setTextSize(size);
            text.setColor(on ? HOT : 0xFFFFFFFF);
            text.setAlpha((int) (alpha * (on ? 255 : 160)));
            line.setColor(on ? HOT : 0x40FFFFFF);
            line.setAlpha((int) (alpha * 255));
            float lw = on ? 2.2f * u : 1.2f * u, lh = on ? 0.3f * u : Math.max(1, u * 0.08f);
            r.set(0, -lh / 2, lw, lh / 2);
            c.drawRoundRect(r, lh, lh, line);
            float tx = lw + 0.9f * u;
            String label = labels[i].toUpperCase();
            c.drawText(label, tx, size * 0.36f, text);
            if (on) {
                float end = tx + text.measureText(label) + 0.7f * u;
                drawDice(c, end, rolling ? rollT : 0);
            }
            c.restore();
        }
    }

    /** A rounded die showing five, filled red when the wheel has focus (OK shuffles). */
    private void drawDice(Canvas c, float x, float t) {
        float s = 2.6f * u;
        c.save();
        c.translate(x + s / 2, 0);
        if (t > 0) { c.rotate(360 * t); float k = 1 + 0.25f * (float) Math.sin(Math.PI * t); c.scale(k, k); }
        r.set(-s / 2, -s / 2, s / 2, s / 2);
        dice.setStyle(focused ? Paint.Style.FILL : Paint.Style.STROKE);
        dice.setStrokeWidth(0.14f * u);
        dice.setColor(focused ? HOT : 0x99FF4D2E);
        c.drawRoundRect(r, 0.7f * u, 0.7f * u, dice);
        dice.setStyle(Paint.Style.FILL);
        dice.setColor(focused ? 0xFF0A0A0A : HOT);
        float p = s * 0.24f, dot = s * 0.09f;
        float[][] pips = {{-p, -p}, {p, -p}, {0, 0}, {-p, p}, {p, p}};
        for (float[] pp : pips) c.drawCircle(pp[0], pp[1], dot, dice);
        c.restore();
    }
}
