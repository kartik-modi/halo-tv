package com.kartikmodi.halo.tonight;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Marvel and DC: every mainstream hero with a film or series, in a tight group of three rows. Heroes are grey
 * until you land on one; then a copy of it steps forward in colour with its power switched on, the background turns
 * to one of its films in its colour, and its films, series and animation (team-ups included) line up below, the
 * ones on your apps first. Which app each title is on is checked live with TMDB when the hero is chosen; a title on
 * two apps asks which one to open. Laid out on a 1920 by 1080 stage, scaled to the screen.
 */
final class HeroesView extends FrameLayout {

    interface Host {
        void openTitle(Title t, String service);
        void closeHeroes();
        void toast(String s);
        ExecutorService net();
    }

    static final int MARVEL_RED = 0xFFE62429, DC_BLUE = 0xFF4F8DFF;
    private static final float BUST_W = 3.5f, HEAD_TOP = 1f;
    private static final float[][] HEAD = {{90, 95, 100}, {104, 110, 116}}, TOP = {{140, 256, 374}, {132, 262, 390}};
    private static final float[] SPACING = {172, 222};
    private static final String[] SERVICE_NAMES = {"D", "Disney+", "N", "Netflix", "P", "Prime Video"};
    private static final int[] SERVICE_COLORS = {0xFF1F4FFF, 0xFFE50914, 0xFF1AA3E8};

    private static final class Hero {
        String name, img, bg;
        int color;
        JSONArray fx;
        String alter, actors;
        final List<Item> titles = new ArrayList<>();
        Bitmap picture, soft;
    }

    private static final class Item {
        String name, year, poster;
        int id;
        boolean tv;
        volatile String services;
        volatile boolean checked;
    }

    private enum Zone { HEROES, PILLS, CARDS, CHOOSE }

    private final Host host;
    private final Typeface display, body;
    private final FrameLayout stage, cluster, front;
    private final ImageView bgA, bgB;
    private final View tint;
    private final StageFx stageFx;
    private final TextView name, sub, label;
    private final LinearLayout logo, pills, track, chooser;
    private final Map<String, List<List<Hero>>> universes = new HashMap<>();
    private final Map<String, String> live = new HashMap<>();

    private String uni = "marvel";
    private List<List<Hero>> rows = new ArrayList<>();
    private final List<List<View>> cells = new ArrayList<>();
    private Zone zone = Zone.HEROES;
    private int row, col, card, pill, choice;
    private Hero current;
    private boolean flip;
    private final List<View> cards = new ArrayList<>();
    private final List<Item> cardItems = new ArrayList<>();
    private Spot spot;
    private final List<Spot> leaving = new ArrayList<>();
    /** The live availability checks get their own two threads, so opening a title never waits behind them. */
    private final ExecutorService checks = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.Set<Item> checking = new java.util.HashSet<>();
    private final Map<String, Bitmap> backdrops = new HashMap<>();
    private static final ColorMatrixColorFilter GREY = lit(0, false), GREY_DIM = lit(0, true);
    /** The hero whose backdrop, titles and live check have been built; they wait until the remote rests. */
    private Hero settled;
    private final Runnable settle = this::settle;
    private Boolean litDim;
    private int styledCard = -1;
    private boolean styledOn;

    HeroesView(Context c, Typeface display, Typeface body, Host host) {
        super(c);
        this.host = host;
        this.display = display;
        this.body = body;
        setBackgroundColor(0xFF050507);
        setClipChildren(false);

        stage = new FrameLayout(c);
        stage.setClipChildren(false);
        addView(stage, new LayoutParams(1920, 1080));

        bgA = backdrop(c);
        bgB = backdrop(c);
        tint = new View(c);
        stage.addView(tint, match());
        View vignette = new View(c);
        vignette.setBackground(shaded((w, h) -> new android.graphics.RadialGradient(w * .5f, h * .38f, 1250,
                new int[]{0, 0, 0xD9030306}, new float[]{0, .45f, 1}, android.graphics.Shader.TileMode.CLAMP)));
        stage.addView(vignette, match());
        View floor = new View(c);
        floor.setBackground(shaded((w, h) -> new android.graphics.LinearGradient(0, 0, 0, h,
                new int[]{0, 0, 0xEB050507, 0xFF050507, 0xFF050507}, new float[]{0, .55f, .66f, .74f, 1}, android.graphics.Shader.TileMode.CLAMP)));

        stageFx = new StageFx(c);
        stage.addView(stageFx, match());
        cluster = new FrameLayout(c);
        cluster.setClipChildren(false);
        stage.addView(cluster, match());
        front = new FrameLayout(c);
        front.setClipChildren(false);
        stage.addView(front, match());
        stage.addView(floor, match());

        // top: back to TONIGHT. and the universe on the left, the hero's name in the middle, Marvel and DC on the right
        logo = new LinearLayout(c);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        stage.addView(logo, at(64, 50, ViewGroup.LayoutParams.WRAP_CONTENT, 56));

        LinearLayout title = new LinearLayout(c);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        name = text(76, display, 900, Color.WHITE);
        name.setAllCaps(true);
        name.setShadowLayer(24, 0, 4, 0xE6000000);
        title.addView(name);
        sub = text(19, body, 400, 0xC7FFFFFF);
        sub.setShadowLayer(16, 0, 2, 0xE6000000);
        sub.setPadding(0, 8, 0, 0);
        title.addView(sub);
        stage.addView(title, at(360, 34, 1200, ViewGroup.LayoutParams.WRAP_CONTENT));

        pills = new LinearLayout(c);
        pills.addView(universePill(c, true, display, 1f));
        View dc = universePill(c, false, display, 1f);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.leftMargin = 16;
        pills.addView(dc, dp);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        pp.topMargin = 46;
        pp.rightMargin = 58;
        stage.addView(pills, pp);

        // the titles row
        label = text(22, display, 800, 0xE0FFFFFF);
        label.setLetterSpacing(.16f);
        stage.addView(label, at(70, 742, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        track = new LinearLayout(c);
        track.setClipChildren(false);
        track.setClipToPadding(false);
        stage.addView(track, at(70, 784, ViewGroup.LayoutParams.WRAP_CONTENT, 290));

        chooser = new LinearLayout(c);
        chooser.setGravity(Gravity.CENTER_VERTICAL);
        chooser.setPadding(26, 14, 14, 14);
        chooser.setBackground(round(0xF00C0C10, 0x33FFFFFF, 99));
        chooser.setVisibility(GONE);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        cp.topMargin = 640;
        stage.addView(chooser, cp);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        float s = (r - l) / 1920f;
        stage.setPivotX(0);
        stage.setPivotY(0);
        stage.setScaleX(s);
        stage.setScaleY(s);
    }

    // ------------------------------------------------------------------ data

    private static android.graphics.drawable.Drawable shaded(java.util.function.BiFunction<Integer, Integer, android.graphics.Shader> f) {
        android.graphics.drawable.ShapeDrawable d = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.RectShape());
        d.setShaderFactory(new android.graphics.drawable.ShapeDrawable.ShaderFactory() {
            @Override public android.graphics.Shader resize(int w, int h) { return f.apply(w, h); }
        });
        return d;
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private List<List<Hero>> universe(String u) {
        List<List<Hero>> got = universes.get(u);
        if (got != null) return got;
        got = new ArrayList<>();
        try (InputStream in = getContext().getAssets().open("heroes/heroes.json")) {
            JSONArray rs = new JSONObject(readAll(in)).getJSONArray(u);
            for (int i = 0; i < rs.length(); i++) {
                List<Hero> row = new ArrayList<>();
                JSONArray hs = rs.getJSONArray(i);
                for (int k = 0; k < hs.length(); k++) {
                    JSONObject o = hs.getJSONObject(k);
                    Hero h = new Hero();
                    h.name = o.getString("n");
                    h.img = o.getString("img");
                    h.bg = o.isNull("bg") ? null : o.optString("bg", null);
                    h.color = Color.parseColor(o.getString("c"));
                    h.fx = o.getJSONArray("fx");
                    h.alter = join(o.optJSONArray("alter"), " · ");
                    h.actors = join(o.optJSONArray("actors"), ", ");
                    JSONArray ts = o.getJSONArray("titles");
                    for (int j = 0; j < ts.length(); j++) {
                        JSONObject t = ts.getJSONObject(j);
                        Item it = new Item();
                        it.name = t.getString("t");
                        it.year = t.optString("y");
                        it.poster = t.isNull("p") ? null : t.optString("p", null);
                        it.id = t.getInt("id");
                        it.tv = t.optBoolean("tv");
                        String key = (it.tv ? "tv:" : "movie:") + it.id;
                        String known = live.get(key);
                        it.services = known != null ? known : t.optString("s");
                        it.checked = known != null;
                        h.titles.add(it);
                    }
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    try (InputStream pic = getContext().getAssets().open("heroes/" + h.img + ".webp")) {
                        h.picture = BitmapFactory.decodeStream(pic, null, opt);
                    }
                    h.soft = soften(h.picture);
                    row.add(h);
                }
                got.add(row);
            }
        } catch (Exception e) {
            host.toast("Couldn't load the heroes");
        }
        universes.put(u, got);
        return got;
    }

    private static String join(JSONArray a, String sep) {
        List<String> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return TextUtils.join(sep, l);
    }

    /** A blurred silhouette: the picture's outline at a tenth of the size, drawn back up smooth. */
    private static Bitmap soften(Bitmap b) {
        if (b == null) return null;
        Bitmap a = b.extractAlpha();
        Bitmap small = Bitmap.createScaledBitmap(a, Math.max(1, b.getWidth() / 10), Math.max(1, b.getHeight() / 10), true);
        a.recycle();
        return small;
    }

    // ------------------------------------------------------------------ showing a universe

    void show(String u) {
        uni = u;
        rows = universe(u);
        for (Spot s : leaving) front.removeView(s.box);
        leaving.clear();
        if (spot != null) front.removeView(spot.box);
        spot = null;
        current = null;
        settled = null;
        litDim = null;
        removeCallbacks(settle);
        cluster.removeAllViews();
        cells.clear();
        boolean dc = u.equals("dc");
        int ui = dc ? 1 : 0;
        List<View> order = new ArrayList<>();
        List<Integer> depth = new ArrayList<>();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Hero> rw = rows.get(ri);
            List<View> cellRow = new ArrayList<>();
            int n = rw.size();
            for (int i = 0; i < n; i++) {
                Hero h = rw.get(i);
                float hh = HEAD[ui][ri], w = BUST_W * hh, ht = PowerView.BUST_H * hh;
                float cx = 960 + (i - (n - 1) / 2f) * SPACING[ui] + jit(i + ri * 10, 1) * 14;
                float top = TOP[ui][ri] + jit(i + ri * 10, 2) * 12 - HEAD_TOP * hh;
                ImageView img = new ImageView(getContext());
                img.setScaleType(ImageView.ScaleType.FIT_XY);
                img.setImageBitmap(h.picture);
                img.setColorFilter(lit(0, false));
                img.setLayoutParams(at(cx - w / 2, top, (int) w, (int) ht));
                img.setTag(new float[]{cx, top, w, ht, hh});
                final int r0 = ri, c0 = i;
                img.setOnClickListener(v -> { zone = Zone.HEROES; row = r0; col = c0; render(); });
                cellRow.add(img);
                order.add(img);
                depth.add(ri * 100 + 50 - Math.round(Math.abs(i - (n - 1) / 2f) * 3));
            }
            cells.add(cellRow);
        }
        // nearer rows and the middle of each row on top
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) idx.add(i);
        Collections.sort(idx, (a, b) -> Integer.compare(depth.get(a), depth.get(b)));
        for (int i : idx) cluster.addView(order.get(i));

        logo.removeAllViews();
        TextView chev = text(48, body, 300, Color.WHITE);
        chev.setText("‹");
        logo.addView(chev);
        View mark = dc ? dcMark(getContext(), 40) : marvelMark(getContext(), display, 30);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = 16;
        logo.addView(mark, mp);

        zone = Zone.HEROES;
        row = Math.min(1, rows.size() - 1);
        col = 0;
        String start = dc ? "Batman" : "Spider-Man";
        if (!rows.isEmpty()) for (int i = 0; i < rows.get(row).size(); i++) if (rows.get(row).get(i).name.equals(start)) col = i;
        chooser.setVisibility(GONE);
        render();
        settle();   // the first hero's titles straight away
    }

    private static float jit(int i, int k) {
        return (float) (Math.sin(i * 12.9898 + k * 78.233) * 43758.5453 % 1);
    }

    // ------------------------------------------------------------------ render

    private void render() {
        if (rows.isEmpty()) return;
        for (int i = 0; i < pills.getChildCount(); i++) {
            boolean marvel = i == 0;
            stylePill(pills.getChildAt(i), marvel, zone == Zone.PILLS && pill == i, marvel == uni.equals("marvel"), 1f);
        }
        Hero h = rows.get(row).get(col);
        View cell = cells.get(row).get(col);
        if (h != current) {
            current = h;
            spotlight(cell, h);
            name.setText(h.name);
            sub.setText((h.alter.isEmpty() ? "" : h.alter + "  —  ") + "played by " + h.actors);
            track.animate().alpha(.25f).setDuration(120).start();
            removeCallbacks(settle);
            postDelayed(settle, 260);
        }
        boolean dim = zone == Zone.CARDS || zone == Zone.CHOOSE;
        if (litDim == null || litDim != dim) {
            litDim = dim;
            for (List<View> r : cells) for (View v : r) ((ImageView) v).setColorFilter(dim ? GREY_DIM : GREY);
        }
        if (settled != current) return;
        boolean on = dim && !cards.isEmpty();
        if (styledCard != (on ? card : -1)) {
            if (styledCard >= 0 && styledCard < cards.size()) styleCard(styledCard, false);
            if (on) styleCard(card, true);
            styledCard = on ? card : -1;
        }
        float shift = 0;
        if (on) shift = -Math.max(0, cards.get(card).getLeft() - 640);
        if (track.getTranslationX() != shift)
            track.animate().translationX(shift).setDuration(450).setInterpolator(new DecelerateInterpolator(2.5f)).start();
    }

    /** The slow part of choosing a hero: the backdrop, the titles row and the live check. */
    private void settle() {
        removeCallbacks(settle);
        Hero h = current;
        if (h == null || settled == h) return;
        settled = h;
        backdrop(h, cells.get(row).get(col));
        styledCard = -1;
        fillRail(h);
        track.animate().alpha(1).setDuration(200).start();
        checkLive(h);
        render();
    }

    /** Saturation and brightness for a hero: 0 grey, 1 full colour; dim while the titles row has focus. */
    private static ColorMatrixColorFilter lit(float f, boolean dim) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(f);
        float b = dim && f == 0 ? .45f : .74f + (1.05f - .74f) * f, contrast = 1.08f - .04f * f, k = b * contrast;
        ColorMatrix s = new ColorMatrix(new float[]{
                k, 0, 0, 0, 128 * (1 - contrast) * b,
                0, k, 0, 0, 128 * (1 - contrast) * b,
                0, 0, k, 0, 128 * (1 - contrast) * b,
                0, 0, 0, 1, 0});
        m.postConcat(s);
        return new ColorMatrixColorFilter(m);
    }

    // ------------------------------------------------------------------ the spotlight

    /** A copy of the selected hero, above the whole group; the hero in the group stays put, hidden. */
    private final class Spot {
        final View cell;
        final Hero hero;
        final FrameLayout box;
        final ImageView img;
        final PowerView back, fore;
        ValueAnimator anim;
        float f;

        Spot(View cell, Hero h) {
            this.cell = cell;
            this.hero = h;
            float[] g = (float[]) cell.getTag();
            float w = g[2], ht = g[3], hh = g[4];
            box = new FrameLayout(getContext());
            box.setClipChildren(false);
            box.setLayoutParams(at(g[0] - w / 2, g[1], (int) w, (int) ht));
            box.setPivotX(w / 2);
            box.setPivotY(ht * .3f);
            img = new ImageView(getContext());
            img.setScaleType(ImageView.ScaleType.FIT_XY);
            img.setImageBitmap(h.picture);
            back = new PowerView(getContext(), h.fx, w, ht, hh, false, h.picture, h.soft, h.color, img, display);
            fore = new PowerView(getContext(), h.fx, w, ht, hh, true, h.picture, null, h.color, img, display);
            box.addView(back, match());
            box.addView(img, match());
            box.addView(fore, match());
            apply(0);
        }

        void apply(float v) {
            f = v;
            box.setScaleX(1 + .16f * v);
            box.setScaleY(1 + .16f * v);
            box.setTranslationY(22 * v);
            img.setColorFilter(lit(v, false));
            back.setAlpha(v);
            fore.setAlpha(v);
            if (spot == this) stageFx.setAlpha(v);
        }

        void to(float target, Runnable done) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(f, target);
            anim.setDuration(420);
            anim.setInterpolator(new DecelerateInterpolator(2.5f));
            anim.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
            if (done != null) anim.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator a) { cancelled = true; }

                @Override
                public void onAnimationEnd(Animator a) { if (!cancelled) done.run(); }
            });
            anim.start();
        }
    }

    private void spotlight(View cell, Hero h) {
        if (spot != null && spot.cell == cell) return;
        if (spot != null) {
            Spot old = spot;
            leaving.add(old);
            old.box.setTranslationZ(0);
            front.removeView(old.box);
            front.addView(old.box, 0);   // behind the one coming forward
            old.fore.setVisibility(GONE);
            old.to(0, () -> {
                old.cell.setVisibility(VISIBLE);   // same pixels as the copy: no flash
                front.removeView(old.box);
                leaving.remove(old);
            });
        }
        Spot back = null;
        for (Spot s : leaving) if (s.cell == cell) back = s;
        if (back != null) {   // turned around while it was still stepping back
            leaving.remove(back);
            front.removeView(back.box);
            front.addView(back.box);
            back.fore.setVisibility(VISIBLE);
            spot = back;
        } else {
            spot = new Spot(cell, h);
            front.addView(spot.box);
            cell.setVisibility(INVISIBLE);
        }
        float[] g = (float[]) cell.getTag();
        stageFx.set(h, g[0], g[1], g[4]);
        spot.to(1, null);
    }

    // ------------------------------------------------------------------ background

    private ImageView backdrop(Context c) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setScaleX(1.04f);
        v.setScaleY(1.04f);
        stage.addView(v, match());
        return v;
    }

    /** One of the hero's films, darkened and washed in the hero's colour. */
    private void backdrop(Hero h, View cell) {
        ImageView next = flip ? bgA : bgB, prev = flip ? bgB : bgA;
        flip = !flip;
        next.setImageBitmap(h.bg == null ? null : backdrops.get(h.bg));
        if (h.bg != null && !backdrops.containsKey(h.bg)) {
            String bg = h.bg;
            checks.execute(() -> {
                Bitmap b = null;
                try (InputStream in = getContext().getAssets().open("heroes/" + bg + ".webp")) {
                    b = BitmapFactory.decodeStream(in);
                } catch (Exception ignored) {
                }
                Bitmap got = b;
                post(() -> {
                    backdrops.put(bg, got);
                    if (current == h) next.setImageBitmap(got);
                });
            });
        }
        float r = Color.red(h.color) / 255f, g = Color.green(h.color) / 255f, bl = Color.blue(h.color) / 255f;
        float[] lum = {.299f, .587f, .114f};
        float[] m = new float[20];
        float[] tintc = {r, g, bl};
        for (int i = 0; i < 3; i++) {
            float k = .16f + .5f * tintc[i];
            m[i * 5] = lum[0] * k;
            m[i * 5 + 1] = lum[1] * k;
            m[i * 5 + 2] = lum[2] * k;
        }
        m[18] = 1;
        next.setColorFilter(new ColorMatrixColorFilter(m));
        next.animate().alpha(1).setDuration(700).start();
        prev.animate().alpha(0).setDuration(700).start();
        float[] gm = (float[]) cell.getTag();
        GradientDrawable d = new GradientDrawable();
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setGradientCenter(gm[0] / 1920f, (gm[1] + gm[3] * .3f) / 1080f);
        d.setGradientRadius(900);
        d.setColors(new int[]{(h.color & 0x00FFFFFF) | 0x8C000000, (h.color & 0x00FFFFFF) | 0x33000000, 0});
        tint.setBackground(d);
    }

    // ------------------------------------------------------------------ the titles row

    private void fillRail(Hero h) {
        track.removeAllViews();
        cards.clear();
        cardItems.clear();
        List<Item> on = new ArrayList<>(), off = new ArrayList<>();
        for (Item t : h.titles) (t.services.isEmpty() ? off : on).add(t);
        for (Item t : on) addCard(t);
        if (!off.isEmpty()) {
            TextView div = text(18, display, 800, 0x66FFFFFF);
            div.setText("NOT ON\nYOUR APPS");
            div.setLetterSpacing(.14f);
            div.setGravity(Gravity.CENTER);
            div.setBackground(dashedLeft());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(120, 210);
            lp.rightMargin = 20;
            track.addView(div, lp);
        }
        for (Item t : off) addCard(t);
        card = 0;
        updateLabel();
    }

    private void updateLabel() {
        if (current == null) return;
        int n = 0;
        for (Item t : current.titles) if (!t.services.isEmpty()) n++;
        label.setText(current.titles.isEmpty() ? "NO FILMS OR SERIES FOUND"
                : current.titles.size() + (current.titles.size() == 1 ? " FILM OR SERIES" : " FILMS & SERIES") + "  ·  " + n + " ON YOUR APPS");
    }

    private void addCard(Item t) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(LinearLayout.VERTICAL);
        FrameLayout po = new FrameLayout(getContext());
        ImageView img = new ImageView(getContext());
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF15151B);
        po.addView(img, match());
        LinearLayout chips = new LinearLayout(getContext());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = cp.topMargin = 8;
        po.addView(chips, cp);
        rounded(po, 12);
        c.addView(po, new LinearLayout.LayoutParams(140, 210));
        TextView tt = text(15, body, 500, Color.WHITE);
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setText(t.name);
        tt.setPadding(0, 9, 0, 0);
        c.addView(tt, new LinearLayout.LayoutParams(140, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView meta = text(13, body, 400, 0x8CFFFFFF);
        meta.setText(t.year + " · " + (t.tv ? "Series" : "Film"));
        c.addView(meta);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(140, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 20;
        track.addView(c, lp);
        final int i = cards.size();
        c.setOnClickListener(v -> { zone = Zone.CARDS; card = i; render(); enter(); });
        cards.add(c);
        cardItems.add(t);
        if (t.poster != null) Images.load(Tmdb.image(t.poster, "w185"), bm -> { if (bm != null) img.setImageBitmap(bm); });
        bindChips(i);
        c.setAlpha(t.services.isEmpty() ? .42f : 1);
    }

    private void bindChips(int i) {
        Item t = cardItems.get(i);
        FrameLayout po = (FrameLayout) ((LinearLayout) cards.get(i)).getChildAt(0);
        LinearLayout chips = (LinearLayout) po.getChildAt(1);
        chips.removeAllViews();
        for (int k = 0; k < 3; k++) {
            String code = SERVICE_NAMES[k * 2];
            if (!t.services.contains(code)) continue;
            TextView chip = text(14, display, 800, Color.WHITE);
            chip.setText(code.equals("D") ? "D+" : code);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(6, 0, 6, 0);
            chip.setMinWidth(24);
            chip.setBackground(round(SERVICE_COLORS[k], 0, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 24);
            lp.rightMargin = 5;
            chips.addView(chip, lp);
        }
        ImageView img = (ImageView) po.getChildAt(0);
        boolean off = t.services.isEmpty();
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(off ? .15f : 1);
        img.setColorFilter(new ColorMatrixColorFilter(m));
    }

    private void styleCard(int i, boolean on) {
        View c = cards.get(i);
        boolean off = cardItems.get(i).services.isEmpty();
        c.setAlpha(on || !off ? 1 : .42f);
        c.animate().scaleX(on ? 1.1f : 1).scaleY(on ? 1.1f : 1).translationY(on ? -8 : 0).setDuration(300).start();
        FrameLayout po = (FrameLayout) ((LinearLayout) c).getChildAt(0);
        po.setForeground(on ? round(0, Color.WHITE, 12, 4) : null);
    }

    /** Which apps stream each of the hero's titles today, from TMDB; the cards update as the answers come in. */
    private void checkLive(Hero h) {
        for (Item t : h.titles) {
            if (t.checked || !checking.add(t)) continue;
            checks.execute(() -> {
                if (current != h) { post(() -> checking.remove(t)); return; }   // moved on: check it next time
                String s;
                try {
                    s = Tmdb.services(t.tv, t.id);
                } catch (Exception e) {
                    post(() -> checking.remove(t));
                    return;   // keep what the app shipped with
                }
                post(() -> {
                    checking.remove(t);
                    if (s.equals(t.services)) { t.checked = true; return; }
                    t.services = s;
                    t.checked = true;
                    live.put((t.tv ? "tv:" : "movie:") + t.id, s);
                    int i = cardItems.indexOf(t);
                    if (current == h && i >= 0) {
                        bindChips(i);
                        styleCard(i, (zone == Zone.CARDS || zone == Zone.CHOOSE) && i == card);
                        updateLabel();
                    }
                });
            });
        }
    }

    // ------------------------------------------------------------------ opening a title

    private void enter() {
        Item t = cardItems.get(card);
        if (t.services.isEmpty()) {
            host.toast(t.name + " isn't on Netflix, Prime Video or Disney+ in the UK");
            return;
        }
        if (t.services.length() == 1) {
            open(t, t.services);
            return;
        }
        // on more than one of your apps: ask which
        chooser.removeAllViews();
        TextView q = text(20, display, 800, 0xCCFFFFFF);
        q.setText("OPEN IN");
        q.setLetterSpacing(.14f);
        chooser.addView(q);
        for (int k = 0; k < t.services.length(); k++) {
            TextView b = text(22, body, 600, Color.WHITE);
            b.setText(serviceName(String.valueOf(t.services.charAt(k))));
            b.setPadding(26, 12, 26, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = 14;
            chooser.addView(b, lp);
        }
        choice = 0;
        zone = Zone.CHOOSE;
        chooser.setVisibility(VISIBLE);
        styleChooser(t);
    }

    private void styleChooser(Item t) {
        for (int k = 0; k < t.services.length(); k++) {
            TextView b = (TextView) chooser.getChildAt(k + 1);
            int c = serviceColor(String.valueOf(t.services.charAt(k)));
            boolean on = k == choice;
            b.setBackground(round(on ? c : 0x14FFFFFF, on ? Color.WHITE : 0x40FFFFFF, 99, on ? 3 : 2));
        }
    }

    private void open(Item t, String service) {
        Title title = new Title(t.id, t.tv, t.name, t.year, "", t.poster == null ? "" : t.poster, "", new ArrayList<>(), 0);
        host.openTitle(title, service);
    }

    private static String serviceName(String code) {
        for (int i = 0; i < SERVICE_NAMES.length; i += 2) if (SERVICE_NAMES[i].equals(code)) return SERVICE_NAMES[i + 1];
        return code;
    }

    private static int serviceColor(String code) {
        for (int i = 0; i < SERVICE_NAMES.length; i += 2) if (SERVICE_NAMES[i].equals(code)) return SERVICE_COLORS[i / 2];
        return Color.GRAY;
    }

    // ------------------------------------------------------------------ the remote

    void key(boolean ok, boolean back, boolean up, boolean down, boolean left, boolean right) {
        if (rows.isEmpty()) { host.closeHeroes(); return; }
        if (zone == Zone.HEROES && (ok || down) && settled != current) settle();
        switch (zone) {
            case CHOOSE: {
                Item t = cardItems.get(card);
                if (back) { zone = Zone.CARDS; chooser.setVisibility(GONE); }
                else if (left) choice = Math.max(0, choice - 1);
                else if (right) choice = Math.min(t.services.length() - 1, choice + 1);
                else if (ok) {
                    zone = Zone.CARDS;
                    chooser.setVisibility(GONE);
                    open(t, String.valueOf(t.services.charAt(choice)));
                }
                if (zone == Zone.CHOOSE) styleChooser(t);
                break;
            }
            case PILLS:
                if (back) { host.closeHeroes(); return; }
                if (left) pill = 0;
                else if (right) pill = 1;
                else if (down) zone = Zone.HEROES;
                else if (ok) {
                    String u = pill == 0 ? "marvel" : "dc";
                    if (!u.equals(uni)) { show(u); return; }
                    zone = Zone.HEROES;
                }
                break;
            case CARDS:
                if (back || up) zone = Zone.HEROES;
                else if (left) card = Math.max(0, card - 1);
                else if (right) card = Math.min(cards.size() - 1, card + 1);
                else if (ok) { enter(); return; }
                break;
            default: {
                if (back) { host.closeHeroes(); return; }
                int n = cells.get(row).size();
                if (left) col = Math.max(0, col - 1);
                else if (right) col = Math.min(n - 1, col + 1);
                else if (up) {
                    if (row > 0) { col = nearest(row - 1); row--; }
                    else { zone = Zone.PILLS; pill = uni.equals("marvel") ? 0 : 1; }
                } else if (down) {
                    if (row < cells.size() - 1) { col = nearest(row + 1); row++; }
                    else if (!cards.isEmpty()) { zone = Zone.CARDS; card = 0; }
                } else if (ok && !cards.isEmpty()) { zone = Zone.CARDS; card = 0; }
                break;
            }
        }
        render();
    }

    private int nearest(int to) {
        float x = ((float[]) cells.get(row).get(col).getTag())[0];
        int best = 0;
        float bd = Float.MAX_VALUE;
        List<View> r = cells.get(to);
        for (int i = 0; i < r.size(); i++) {
            float d = Math.abs(((float[]) r.get(i).getTag())[0] - x);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the Marvel and DC pills, here and on the spin screen

    static View marvelMark(Context c, Typeface display, float size) {
        TextView t = new TextView(c);
        t.setTypeface(display);
        t.setFontVariationSettings("'wght' 900");
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setIncludeFontPadding(false);
        t.setTextColor(Color.WHITE);
        t.setText("MARVEL");
        t.setBackgroundColor(MARVEL_RED);
        t.setPadding((int) (size * .26f), (int) (size * .08f), (int) (size * .26f), (int) (size * .02f));
        return t;
    }

    static View dcMark(Context c, float size) {
        ImageView v = new ImageView(c);
        v.setImageResource(R.drawable.dc_logo);
        v.setLayoutParams(new LinearLayout.LayoutParams((int) size, (int) size));
        return v;
    }

    /** A glass pill with the Marvel or DC mark; s scales it from its 1920-wide size. */
    static View universePill(Context c, boolean marvel, Typeface display, float s) {
        LinearLayout p = new LinearLayout(c);
        p.setGravity(Gravity.CENTER);
        p.setPadding((int) ((marvel ? 26 : 22) * s), 0, (int) ((marvel ? 26 : 22) * s), 0);
        p.setMinimumHeight((int) (52 * s));
        if (marvel) p.addView(marvelMark(c, display, 34 * s));
        else p.addView(dcMark(c, 38 * s), new LinearLayout.LayoutParams((int) (38 * s), (int) (38 * s)));
        stylePill(p, marvel, false, false, s);
        return p;
    }

    static void stylePill(View p, boolean marvel, boolean focused, boolean current, float s) {
        int brand = marvel ? MARVEL_RED : DC_BLUE;
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(99 * s);
        d.setColor(focused ? 0x29FFFFFF : current ? 0x24FFFFFF : 0x0FFFFFFF);
        d.setStroke(Math.max(1, (int) ((focused ? 3 : 1.5f) * s)), focused ? brand : 0x38FFFFFF);
        p.setBackground(d);
        p.animate().scaleX(focused ? 1.08f : 1).scaleY(focused ? 1.08f : 1).setDuration(200).start();
        p.setElevation(0);
    }

    // ------------------------------------------------------------------ helpers

    private TextView text(float size, Typeface face, int weight, int color) {
        TextView t = new TextView(getContext());
        t.setTypeface(face);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static GradientDrawable round(int fill, int stroke, float r) {
        return round(fill, stroke, r, 1.5f);
    }

    private static GradientDrawable round(int fill, int stroke, float r, float width) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(r);
        d.setColor(fill);
        if (stroke != 0) d.setStroke((int) Math.max(1, width), stroke);
        return d;
    }

    private static android.graphics.drawable.Drawable dashedLeft() {
        return new android.graphics.drawable.Drawable() {
            private final Paint p = new Paint();

            @Override
            public void draw(Canvas c) {
                p.setColor(0x33FFFFFF);
                for (int y = 0; y < getBounds().height(); y += 8) c.drawRect(0, y, 1, y + 4, p);
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private static void rounded(View v, float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams at(float x, float y, int w, int h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.leftMargin = (int) x;
        p.topMargin = (int) y;
        return p;
    }

    // ------------------------------------------------------------------ powers on the whole stage

    /** Moon Knight's moon and Batman's bat-signal, behind the group. */
    private static final class StageFx extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String kind;
        private float cx, top, hh;
        private long start;
        private Path bat;

        StageFx(Context c) {
            super(c);
        }

        void set(Hero h, float cx, float top, float hh) {
            kind = null;
            for (int i = 0; i < h.fx.length(); i++) {
                String n = h.fx.optJSONArray(i).optString(0);
                if (n.equals("moon") || n.equals("batsignal")) kind = n;
            }
            this.cx = cx;
            this.top = top;
            this.hh = hh;
            start = SystemClock.uptimeMillis();
            setAlpha(0);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (kind == null) return;
            float t = (SystemClock.uptimeMillis() - start) / 1000f;
            if (kind.equals("moon")) {
                float x = cx, y = top + (PowerView.BUST_H - PowerView.FX_H) * hh - 120 + 230;
                p.setShader(new RadialGradient(x, y, 330, 0x40E6ECF7, 0, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, 330, p);
                p.setShader(new RadialGradient(x - 28, y - 32, 260, new int[]{Color.WHITE, 0xFFDFE5EF, 0xFFA9B2C2, 0xFF7F899B},
                        new float[]{0, .4f, .7f, 1}, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, 230, p);
                p.setShader(null);
            } else {
                // the bat-signal, in the 1120 by 720 box it was drawn in
                c.save();
                c.translate(cx - 160, -10);
                float k = (float) (.5 - .5 * Math.cos(Math.PI * t / 3));
                p.setAlpha((int) (215 + 40 * k));
                p.setShader(new LinearGradient(0, 720, 0, 140, 0x66FFF6C0, 0x08FFF6C0, Shader.TileMode.CLAMP));
                Path beam = new Path();
                beam.moveTo(120, 720); beam.lineTo(200, 720); beam.lineTo(560, 190); beam.lineTo(380, 140); beam.close();
                c.drawPath(beam, p);
                c.save();
                c.scale(1, 112 / 190f, 480, 160);
                p.setShader(new RadialGradient(480, 160, 190, new int[]{0xBFFFE680, 0xBFFFE680, 0x00FFE680}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
                c.drawCircle(480, 160, 190, p);
                c.restore();
                p.setShader(null);
                p.setColor(0xFF0B0B0B);
                c.translate(380, 105);
                c.scale(2, 2);
                c.drawPath(bat(), p);
                c.restore();
            }
            postInvalidateOnAnimation();
        }

        private Path bat() {
            if (bat != null) return bat;
            String d = "50 12 C47 12 46 6 45 3 C44 7 43 11 40 12 C33 12 29 7 27 1 C13 7 3 17 0 31 C6 25 14 23 20 28 C23 22 28 20 33 24 C37 28 42 36 50 50 "
                    + "C58 36 63 28 67 24 C72 20 77 22 80 28 C86 23 94 25 100 31 C97 17 87 7 73 1 C71 7 67 12 60 12 C57 11 56 7 55 3 C54 6 53 12 50 12";
            String[] s = d.replace("C", " ").trim().split("\\s+");
            bat = new Path();
            bat.moveTo(Float.parseFloat(s[0]), Float.parseFloat(s[1]));
            for (int i = 2; i + 5 < s.length; i += 6)
                bat.cubicTo(Float.parseFloat(s[i]), Float.parseFloat(s[i + 1]), Float.parseFloat(s[i + 2]),
                        Float.parseFloat(s[i + 3]), Float.parseFloat(s[i + 4]), Float.parseFloat(s[i + 5]));
            bat.close();
            return bat;
        }
    }
}
