package com.kartikmodi.halo.tonight;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.kartikmodi.halo.tonight.SearchIndex.Hit;

/**
 * Search: an on-screen keyboard on the left, and on the right everything on UK Netflix, Prime Video and Disney+
 * that matches, refreshed with every letter. The index is kept on the TV (SearchIndex) and searched in memory;
 * the "Tonight" Alexa skill searches the same index through SearchProvider. Drawn on a 1920x1080 stage scaled to
 * the screen, like HeroesView.
 */
final class SearchView extends FrameLayout {

    interface Host {
        void openTitle(Title t, String service);
        void closeSearch();
        void toast(String s);
    }

    private static final String TAG = "TonightSearch";
    private static final int HOT = WheelView.HOT;
    private static final String[] SERVICE_NAMES = {"N", "Netflix", "P", "Prime Video", "D", "Disney+"};
    private static final int[] SERVICE_COLORS = {0xFFE50914, 0xFF1AA3E8, 0xFF1F4FFF};
    private static final int COLS = 6, MAX = 24, CARD_W = 160, CARD_H = 240, GAP_X = 24, ROW_H = 318;
    private static final String KEYS = "abcdefghijklmnopqrstuvwxyz1234567890";
    /** The bottom row of the keyboard: label, columns it spans. */
    private static final String SPACE = "SPACE", DELETE = "⌫", CLEAR = "CLEAR";

    private enum Zone { KEYS, RESULTS, CHOOSE }

    private final Host host;
    private final Typeface display, body;
    private final FrameLayout stage, grid;
    private final TextView query, status;
    private final List<TextView> keys = new ArrayList<>();
    private final List<int[]> keyCells = new ArrayList<>();   // {row, col, span}
    private final List<LinearLayout> cards = new ArrayList<>();
    private final LinearLayout chooser;
    private final ExecutorService http = Executors.newSingleThreadExecutor();
    private final LruCache<String, List<Hit>> cache = new LruCache<>(200);

    private final SearchIndex index;
    private final StringBuilder typed = new StringBuilder();
    private List<Hit> hits = new ArrayList<>();
    private Zone zone = Zone.KEYS;
    private int key, result, choice;
    private volatile int asked;
    private int shown;
    private boolean caret = true;

    SearchView(Context c, Typeface display, Typeface body, Host host) {
        super(c);
        this.host = host;
        this.index = SearchIndex.get(c);
        this.display = display;
        this.body = body;
        setBackgroundColor(0xFF050507);
        setClickable(true);

        stage = new FrameLayout(c);
        stage.setClipChildren(false);
        addView(stage, new LayoutParams(1920, 1080));
        View glow = new View(c);
        ShapeDrawable g = new ShapeDrawable(new RectShape());
        g.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override public Shader resize(int w, int h) {
                return new RadialGradient(w * .18f, h * .1f, 1300, new int[]{0x33FF4D2E, 0x0AFF4D2E, 0}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
            }
        });
        glow.setBackground(g);
        stage.addView(glow, match());

        TextView head = text(30, display, 800, 0xCCFFFFFF);
        head.setText("‹  SEARCH");
        head.setLetterSpacing(.18f);
        stage.addView(head, at(96, 64, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView where = text(19, body, 400, 0x80FFFFFF);
        where.setText("Everything on Netflix, Prime Video and Disney+ in the UK");
        stage.addView(where, at(96, 108, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        query = text(40, body, 500, Color.WHITE);
        query.setSingleLine(true);
        query.setEllipsize(TextUtils.TruncateAt.START);
        query.setGravity(Gravity.CENTER_VERTICAL);
        query.setPadding(28, 0, 28, 0);
        query.setBackground(round(0x14FFFFFF, 0x40FFFFFF, 18, 2));
        stage.addView(query, at(96, 160, 548, 88));

        // the keyboard: a-z and 0-9 in a 6 x 6 grid, then space, delete and clear
        for (int i = 0; i < KEYS.length(); i++) addKey(String.valueOf(KEYS.charAt(i)).toUpperCase(), i / 6, i % 6, 1);
        addKey(SPACE, 6, 0, 3);
        addKey(DELETE, 6, 3, 2);
        addKey(CLEAR, 6, 5, 1);

        status = text(22, display, 800, 0x99FFFFFF);
        status.setLetterSpacing(.14f);
        stage.addView(status, at(720, 160, 1120, 40));

        FrameLayout viewport = new FrameLayout(c);
        viewport.setClipChildren(true);
        viewport.setClipToPadding(false);
        stage.addView(viewport, at(700, 206, 1180, 860));
        grid = new FrameLayout(c);
        grid.setClipChildren(false);
        viewport.addView(grid, new LayoutParams(1180, ROW_H * (MAX / COLS) + 40));
        for (int i = 0; i < MAX; i++) {
            LinearLayout card = card();
            grid.addView(card, at(20 + (i % COLS) * (CARD_W + GAP_X), 20 + (i / COLS) * ROW_H, CARD_W, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setVisibility(GONE);
            final int k = i;
            card.setOnClickListener(v -> { zone = Zone.RESULTS; result = k; render(); enter(); });
            cards.add(card);
        }

        chooser = new LinearLayout(c);
        chooser.setGravity(Gravity.CENTER_VERTICAL);
        chooser.setPadding(26, 18, 26, 18);
        chooser.setBackground(round(0xF00B0B10, 0x33FFFFFF, 22));
        chooser.setElevation(30);
        chooser.setVisibility(GONE);
        stage.addView(chooser, at(700, 960, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        blink();
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

    /** Opens with this text already typed (from Alexa), or empty. */
    void show(String q) {
        typed.setLength(0);
        if (q != null) typed.append(q.toLowerCase(java.util.Locale.UK).trim());
        zone = Zone.KEYS;
        key = 0;
        result = 0;
        chooser.setVisibility(GONE);
        hits = new ArrayList<>();
        pendingFocus = q != null && !q.trim().isEmpty();   // a spoken search: straight to the results once they arrive
        changed();
    }

    private boolean pendingFocus;

    // ------------------------------------------------------------------ typing and asking

    private void type(String s) {
        if (typed.length() >= 60) return;
        typed.append(s);
        changed();
    }

    private void changed() {
        render();
        String q = typed.toString().trim();
        if (q.isEmpty()) {
            asked++;
            show(new ArrayList<>(), "");
            return;
        }
        List<Hit> known = cache.get(q);
        if (known != null) {
            asked++;
            show(known, q);
            return;
        }
        final int me = ++asked;
        http.execute(() -> {
            if (me != asked) return;   // typed on since: only the newest question goes out
            long t0 = System.currentTimeMillis();
            if (!index.exists()) { post(this::waitForIndex); return; }
            List<Hit> got = index.query(q, MAX);
            cache.put(q, got);
            Log.i(TAG, "\"" + q + "\": " + got.size() + " in " + (System.currentTimeMillis() - t0) + " ms");
            post(() -> { if (me >= shown && me == asked) show(got, q); });
        });
    }

    /** The first index is still being built: show how far it has got, and search once it is ready. */
    private void waitForIndex() {
        if (getVisibility() != VISIBLE) return;
        if (index.exists()) { changed(); return; }
        int d = index.done.get(), t = index.total.get();
        status.setText(t > 0 ? "BUILDING THE SEARCH INDEX FOR THE FIRST TIME… " + (100 * d / t) + "%"
                : "BUILDING THE SEARCH INDEX FOR THE FIRST TIME…");
        postDelayed(this::waitForIndex, 700);
    }

    private void show(List<Hit> got, String q) {
        shown = asked;
        hits = got;
        result = Math.min(result, Math.max(0, hits.size() - 1));
        if (hits.isEmpty() && zone != Zone.KEYS) { zone = Zone.KEYS; chooser.setVisibility(GONE); }
        for (int i = 0; i < cards.size(); i++) {
            LinearLayout c = cards.get(i);
            if (i >= hits.size()) { c.setVisibility(GONE); continue; }
            c.setVisibility(VISIBLE);
            bind(c, hits.get(i));
        }
        if (q.isEmpty()) status.setText("START TYPING: RESULTS APPEAR WITH EVERY LETTER");
        else if (hits.isEmpty()) status.setText("NOTHING ON YOUR APPS MATCHES “" + q.toUpperCase(java.util.Locale.UK) + "”");
        else status.setText((hits.size() >= MAX ? "TOP " + MAX : String.valueOf(hits.size())) + (hits.size() == 1 ? " RESULT" : " RESULTS"));
        if (pendingFocus && !hits.isEmpty()) {
            pendingFocus = false;
            zone = Zone.RESULTS;
            result = 0;
        }
        render();
    }

    // ------------------------------------------------------------------ the remote

    /** Every key while search is open; true when it was used. */
    boolean key(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE;
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT, right = k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (k == KeyEvent.KEYCODE_DEL) { delete(); return true; }
        if (!(ok || back || up || down || left || right)) {
            // a real keyboard, or the TV's own: letters, digits and space go straight in
            int ch = e.getUnicodeChar();
            if (ch == ' ' || Character.isLetterOrDigit(ch)) { type(String.valueOf((char) Character.toLowerCase(ch))); return true; }
            return false;
        }
        switch (zone) {
            case CHOOSE: {
                Hit h = hits.get(result);
                if (back) zone = Zone.RESULTS;
                else if (left) choice = Math.max(0, choice - 1);
                else if (right) choice = Math.min(h.services.length() - 1, choice + 1);
                else if (ok) {
                    zone = Zone.RESULTS;
                    open(h, String.valueOf(h.services.charAt(choice)));
                }
                if (zone != Zone.CHOOSE) chooser.setVisibility(GONE);
                else styleChooser(h);
                break;
            }
            case RESULTS: {
                int r = result / COLS, c = result % COLS;
                if (back) zone = Zone.KEYS;
                else if (left) { if (c == 0) zone = Zone.KEYS; else result--; }
                else if (right) result = Math.min(hits.size() - 1, result + 1);
                else if (up) { if (r > 0) result -= COLS; }
                else if (down) result = Math.min(hits.size() - 1, result + COLS);
                else if (ok) { enter(); return true; }
                break;
            }
            default: {
                if (back) { host.closeSearch(); return true; }
                int[] cell = keyCells.get(key);
                if (left) key = keyAt(cell[0], cell[1] - 1, key);
                else if (right) {
                    int next = keyAt(cell[0], cell[1] + cell[2], -1);
                    if (next >= 0) key = next;
                    else if (!hits.isEmpty()) { zone = Zone.RESULTS; result = Math.min(result, hits.size() - 1); if (result % COLS != 0) result -= result % COLS; }
                } else if (up) key = keyAt(cell[0] - 1, cell[1], key);
                else if (down) key = keyAt(cell[0] + 1, cell[1], key);
                else if (ok) press(keys.get(key).getText().toString());
                break;
            }
        }
        render();
        return true;
    }

    private void press(String label) {
        switch (label) {
            case SPACE: if (typed.length() > 0 && typed.charAt(typed.length() - 1) != ' ') type(" "); break;
            case DELETE: delete(); break;
            case CLEAR: typed.setLength(0); changed(); break;
            default: type(label.toLowerCase(java.util.Locale.UK));
        }
    }

    private void delete() {
        if (typed.length() == 0) return;
        typed.setLength(typed.length() - 1);
        changed();
    }

    /** The key covering row r, column c; 'otherwise' when there is none. */
    private int keyAt(int r, int c, int otherwise) {
        for (int i = 0; i < keyCells.size(); i++) {
            int[] k = keyCells.get(i);
            if (k[0] == r && c >= k[1] && c < k[1] + k[2]) return i;
        }
        return otherwise;
    }

    private void enter() {
        Hit h = hits.get(result);
        if (h.services.isEmpty()) {
            host.toast(h.name + " isn't on Netflix, Prime Video or Disney+ in the UK");
            return;
        }
        if (h.services.length() == 1) {
            open(h, h.services);
            return;
        }
        chooser.removeAllViews();
        TextView q = text(20, display, 800, 0xCCFFFFFF);
        q.setText("OPEN IN");
        q.setLetterSpacing(.14f);
        chooser.addView(q);
        for (int k = 0; k < h.services.length(); k++) {
            TextView b = text(22, body, 600, Color.WHITE);
            b.setText(serviceName(String.valueOf(h.services.charAt(k))));
            b.setPadding(26, 12, 26, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = 14;
            chooser.addView(b, lp);
        }
        choice = 0;
        zone = Zone.CHOOSE;
        chooser.setVisibility(VISIBLE);
        styleChooser(h);
    }

    private void styleChooser(Hit h) {
        for (int k = 0; k < h.services.length(); k++) {
            TextView b = (TextView) chooser.getChildAt(k + 1);
            boolean on = k == choice;
            b.setBackground(round(on ? serviceColor(String.valueOf(h.services.charAt(k))) : 0x14FFFFFF, on ? Color.WHITE : 0x40FFFFFF, 99, on ? 3 : 2));
        }
    }

    private void open(Hit h, String service) {
        host.openTitle(new Title(h.id, h.tv, h.name, h.year, "", h.poster == null ? "" : h.poster, "", new ArrayList<>(), 0), service);
    }

    // ------------------------------------------------------------------ drawing

    private void render() {
        query.setText(typed.length() == 0 ? "" : typed.toString() + (caret ? "|" : " "));
        if (typed.length() == 0) {
            query.setHint("Search films & series");
            query.setHintTextColor(0x66FFFFFF);
        }
        for (int i = 0; i < keys.size(); i++) {
            boolean on = zone == Zone.KEYS && i == key;
            TextView k = keys.get(i);
            k.setBackground(round(on ? Color.WHITE : 0x12FFFFFF, on ? Color.WHITE : 0x1FFFFFFF, 12, 1.5f));
            k.setTextColor(on ? 0xFF0A0A0A : 0xE6FFFFFF);
        }
        boolean inResults = zone == Zone.RESULTS || zone == Zone.CHOOSE;
        for (int i = 0; i < cards.size(); i++) {
            LinearLayout c = cards.get(i);
            if (c.getVisibility() != VISIBLE) continue;
            boolean on = inResults && i == result;
            c.animate().scaleX(on ? 1.1f : 1).scaleY(on ? 1.1f : 1).translationY(on ? -8 : 0).setDuration(160).start();
            ((FrameLayout) c.getChildAt(0)).setForeground(on ? round(0, Color.WHITE, 12, 4) : null);
            c.setAlpha(inResults && !on ? .7f : 1);
        }
        // keep the focused row in view
        float y = 0;
        if (inResults) y = -Math.max(0, (result / COLS) - 1) * ROW_H;
        if (grid.getTranslationY() != y) grid.animate().translationY(y).setDuration(300).setInterpolator(new DecelerateInterpolator(2)).start();
    }

    private void blink() {
        postDelayed(() -> {
            caret = !caret;
            if (getVisibility() == VISIBLE && typed.length() > 0) query.setText(typed.toString() + (caret ? "|" : " "));
            blink();
        }, 530);
    }

    private void addKey(String label, int row, int col, int span) {
        TextView k = text(label.length() > 1 ? 18 : 28, label.length() > 1 ? display : body, label.length() > 1 ? 800 : 500, Color.WHITE);
        k.setText(label);
        k.setGravity(Gravity.CENTER);
        if (label.length() > 1 && !label.equals(DELETE)) k.setLetterSpacing(.12f);
        int w = 84 * span + 8 * (span - 1);
        stage.addView(k, at(96 + col * 92, 280 + row * 80, w, 72));
        final int i = keys.size();
        k.setOnClickListener(v -> { zone = Zone.KEYS; key = i; press(label); render(); });
        keys.add(k);
        keyCells.add(new int[]{row, col, span});
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPivotY(CARD_H / 2f);
        FrameLayout po = new FrameLayout(getContext());
        ImageView img = new ImageView(getContext());
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF15151B);
        po.addView(img, match());
        TextView none = text(18, display, 800, 0x66FFFFFF);   // title on a blank card when there is no poster
        none.setGravity(Gravity.CENTER);
        none.setPadding(12, 0, 12, 0);
        po.addView(none, match());
        LinearLayout chips = new LinearLayout(getContext());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = cp.topMargin = 8;
        po.addView(chips, cp);
        rounded(po, 12);
        c.addView(po, new LinearLayout.LayoutParams(CARD_W, CARD_H));
        TextView tt = text(16, body, 500, Color.WHITE);
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setPadding(0, 10, 0, 0);
        c.addView(tt, new LinearLayout.LayoutParams(CARD_W, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView meta = text(14, body, 400, 0x8CFFFFFF);
        c.addView(meta);
        return c;
    }

    private void bind(LinearLayout c, Hit h) {
        FrameLayout po = (FrameLayout) c.getChildAt(0);
        ImageView img = (ImageView) po.getChildAt(0);
        TextView none = (TextView) po.getChildAt(1);
        LinearLayout chips = (LinearLayout) po.getChildAt(2);
        ((TextView) c.getChildAt(1)).setText(h.name);
        ((TextView) c.getChildAt(2)).setText((h.year.isEmpty() ? "" : h.year + " · ") + (h.tv ? "Series" : "Film"));
        img.setTag(h.poster);
        img.setImageDrawable(null);
        none.setText(h.poster == null ? h.name : "");
        if (h.poster != null) {
            String want = h.poster;
            Images.load(Tmdb.image(h.poster, "w185"), bm -> { if (bm != null && want.equals(img.getTag())) img.setImageBitmap(bm); });
        }
        chips.removeAllViews();
        for (int k = 0; k < 3; k++) {
            String code = SERVICE_NAMES[k * 2];
            if (!h.services.contains(code)) continue;
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
    }

    // ------------------------------------------------------------------ helpers

    private static String serviceName(String code) {
        for (int i = 0; i < SERVICE_NAMES.length; i += 2) if (SERVICE_NAMES[i].equals(code)) return SERVICE_NAMES[i + 1];
        return code;
    }

    private static int serviceColor(String code) {
        for (int i = 0; i < SERVICE_NAMES.length; i += 2) if (SERVICE_NAMES[i].equals(code)) return SERVICE_COLORS[i / 2];
        return Color.GRAY;
    }

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

    private static void rounded(View v, float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LayoutParams at(float x, float y, int w, int h) {
        LayoutParams p = new LayoutParams(w, h);
        p.leftMargin = (int) x;
        p.topMargin = (int) y;
        return p;
    }
}
