package com.kartikmodi.halo.tonight;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TONIGHT.: spin a genre wheel, browse what is on your Netflix, Prime Video and Disney+ in the UK as Series
 * and Films, and open a title straight in its app. Everything is fetched live as you move; the only thing
 * saved is the taste graph of what you open, which Claude reads to fill "For You".
 */
public class MainActivity extends Activity {

    private static final String TAG = "Tonight";
    private static final int HOT = WheelView.HOT, INK = 0xFF07070A;
    private static final String FOR_YOU = "For You";
    private static final String[] SERVICE_NAME = {"N", "Netflix", "D", "Disney+", "P", "Prime Video"};
    private static final int SLOTS_PER_ROW = 9, WALL = 7;

    private enum Zone { DIAL, REELS, TBTN, OVERLAY, HEROES, SEARCH }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Six at a time: the whole wheel loads in parallel when the app opens. */
    private final ExecutorService net = Executors.newFixedThreadPool(6);
    private final Random random = new Random();
    private ForYou forYou;

    private float u;
    private Typeface display, body;
    private Zone zone = Zone.DIAL;

    // the spin screen
    private WheelView wheel;
    private ImageView bg, poster;
    private FrameLayout card;
    private TextView kind, svcPill, heroTitle, heroMeta, heroText, heroAside, playBtn, tbtn, toast;
    private final LinearLayout[] tracks = new LinearLayout[2];
    private final TextView[] laneLabel = new TextView[2], laneCount = new TextView[2];
    private final List<Title>[] lanes = new List[]{new ArrayList<Title>(), new ArrayList<Title>()};
    private final int[] idx = {0, 0};
    private int lane;
    private boolean loading;
    private String status;
    private final Map<String, String> why = new HashMap<>();
    private Title shown;

    // the top-right pills: Marvel, DC, Tonight I want
    private View marvelPill, dcPill;
    private ImageView searchBtn;
    private SearchView search;
    private int tsel = 2;
    private HeroesView heroes;

    // Tonight I want
    private FrameLayout overlay;
    private TextView sentence, ocount;
    private LinearLayout wall;
    private final List<Title> wallTitles = new ArrayList<>();
    private int ovRow, ovSlot, ovIndex, ovToken;
    private final int[] ovSel = {Tmdb.wheelIndexOf("Thriller") - 1, 2, 0};
    /** Every genre on the wheel except Everything; Up or Down jumps to a random other one. */
    private static final String[] GENRE_WORDS = java.util.Arrays.copyOfRange(Tmdb.GENRES, 1, Tmdb.GENRES.length);
    private static final String[] KINDS = {"series", "films", "series or films"};
    private static final String[] SERVICES = {"Netflix, Disney+ & Prime", "Netflix", "Disney+", "Prime Video"};
    private static final String[] SERVICE_IDS = {Tmdb.ALL_SERVICES, String.valueOf(Tmdb.NETFLIX), String.valueOf(Tmdb.DISNEY), Tmdb.PRIME + "|" + Tmdb.PRIME_ADS};

    private TasteGraph graph;
    private SoundPool sounds;
    private int tickSound, diceSound;
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        u = getResources().getDisplayMetrics().widthPixels / 100f;
        display = getResources().getFont(R.font.bigshoulders);
        body = getResources().getFont(R.font.outfit);
        graph = new TasteGraph(this);
        IndexJob.schedule(this);
        net.execute(() -> SearchIndex.get(this).preload());
        forYou = new ForYou(this);
        sounds = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        tickSound = sounds.load(this, R.raw.tick, 1);
        diceSound = sounds.load(this, R.raw.dice, 1);
        build();
        // every opening starts somewhere new: every genre from a random page, in random order
        preloadAll();
        handleIntent(getIntent(), true);
        // read anything opened since the last run into the graph
        if (Taste.available() && !graph.unthemed().isEmpty()) net.execute(() -> Taste.grow(graph));
    }

    /** Opened again from the launcher's button while still running: new titles, back on the wheel. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, false);
    }

    /**
     * keep: brought back after Alexa sent the TV to Amazon's home; leave everything as it is.
     * suggest: asked through Alexa ("ask Jarvis to suggest a horror film"): spin to the genre, or a random one,
     * and land on a random title there. Otherwise, opened again from the button: new titles.
     */
    private void handleIntent(Intent intent, boolean fresh) {
        if (intent == null || intent.getBooleanExtra("keep", false)) return;
        if (zone == Zone.HEROES) closeHeroes();
        String spoken = intent.getStringExtra("search");
        if (spoken != null) {   // "Alexa, ask Tonight to search for Inception"
            intent.removeExtra("search");
            if (zone == Zone.OVERLAY) openOverlay(false);
            openSearch(spoken);
            return;
        }
        if (zone == Zone.SEARCH) closeSearch();
        if (intent.getBooleanExtra("suggest", false)) {
            intent.removeExtra("suggest");   // a later recreate must not pick again
            String q = intent.getStringExtra("q");
            if (q != null && Taste.available()) asked(q, intent.getStringExtra("genre"), intent.getStringExtra("kind"));
            else suggest(intent.getStringExtra("genre"), intent.getStringExtra("kind"));
            return;
        }
        if (fresh) return;
        if (zone == Zone.OVERLAY) openOverlay(false);
        zone = Zone.DIAL;
        if (askedLabel != null) {
            askedLabel = null;
            wheel.setLabels(wheelLabels(), 0);
        }
        preloadAll();
    }

    /** The wheel spot for the last spoken request ("MARVEL"), shown first on the wheel until the next fresh start. */
    private String askedLabel;

    /**
     * A spoken request: Claude reads it into filters, TMDB finds what matches on your three services, and the
     * matches get their own spot on the wheel, which spins there and lands on a random one. Filters loosen
     * step by step when nothing matches; without any match it falls back to the genre words.
     */
    private void asked(String request, String genreHint, String kindHint) {
        if (zone == Zone.OVERLAY) openOverlay(false);
        showToast("Looking for " + request + "…");
        net.execute(() -> {
            org.json.JSONObject f = Taste.interpret(request);
            List<Title>[] got = new List[]{new ArrayList<Title>(), new ArrayList<Title>()};
            String label = null, kind = kindHint == null ? "" : kindHint;
            if (f != null) {
                label = f.optString("label", "").trim();
                String k = f.optString("kind", "any");
                if (k.equals("film") || k.equals("series")) kind = k;
                String companies = ids("company", f.optJSONArray("companies"));
                String keywords = ids("keyword", f.optJSONArray("keywords"));
                String people = ids("person", f.optJSONArray("people"));
                List<String> genres = new ArrayList<>();
                org.json.JSONArray g = f.optJSONArray("genres");
                if (g != null) for (int i = 0; i < g.length(); i++) genres.add(g.optString(i));
                int from = f.optInt("year_from", 0), to = f.optInt("year_to", 0);
                // strictest first, then without genres, then without keywords
                for (int pass = 0; pass < 3 && got[0].isEmpty() && got[1].isEmpty(); pass++) {
                    for (int s = 0; s < 2; s++) {
                        boolean series = s == 0;
                        if ((kind.equals("film") && series) || (kind.equals("series") && !series)) continue;
                        StringBuilder q = new StringBuilder();
                        if (!companies.isEmpty()) q.append("&with_companies=").append(companies);
                        if (!people.isEmpty() && !series) q.append("&with_people=").append(people);
                        if (pass < 2 && !keywords.isEmpty()) q.append("&with_keywords=").append(keywords);
                        int w = pass == 0 && !genres.isEmpty() ? Tmdb.wheelIndexOf(genres.get(0)) : -1;
                        String gq = w > 0 ? Tmdb.filter(w, series) : "";
                        // TMDB refuses a second with_keywords: a genre that is a keyword tag on TV gives way to the request's own
                        if (gq.contains("&with_keywords=") && q.indexOf("&with_keywords=") >= 0) gq = "";
                        q.append(gq);
                        if (from > 0) q.append(series ? "&first_air_date.gte=" : "&primary_release_date.gte=").append(from).append("-01-01");
                        if (to > 0) q.append(series ? "&first_air_date.lte=" : "&primary_release_date.lte=").append(to).append("-12-31");
                        if (q.length() == 0) continue;   // nothing to narrow by: not a match set
                        try {
                            for (Title t : Tmdb.discover(series, -1, Tmdb.ALL_SERVICES, 1, q.toString()).titles)
                                if (gq.isEmpty() || Tmdb.fits(t, w)) got[s].add(t);
                        } catch (Exception e) {
                            Log.w(TAG, "asked " + request, e);
                        }
                    }
                }
            }
            final String finalKind = kind, finalLabel = label;
            main.post(() -> {
                if (got[0].isEmpty() && got[1].isEmpty()) {   // nothing specific: the genre words
                    suggest(genreHint, finalKind);
                    return;
                }
                Collections.shuffle(got[0], random);
                Collections.shuffle(got[1], random);
                String name = finalLabel == null || finalLabel.isEmpty() ? "Asked" : finalLabel;
                if (name.length() > 14) name = name.substring(0, 14);
                // never the same name as a genre's own spot
                for (String g : Tmdb.GENRES) if (g.equalsIgnoreCase(name)) name = name + " pick";
                if (FOR_YOU.equalsIgnoreCase(name)) name = "Asked";
                askedLabel = name;
                cache.put(name, new Loaded(got[0], got[1], new HashMap<>(), null));
                wheel.setLabels(wheelLabels(), wheel.selected());
                picking = true;
                pickKind = finalKind;
                zone = Zone.DIAL;
                wheel.select(0);
                showCurrent(false);
            });
        });
    }

    /** TMDB ids for names, joined with "|" (any of them), URL-encoded. */
    private static String ids(String type, org.json.JSONArray names) {
        if (names == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length() && i < 3; i++) {
            int id = Tmdb.searchId(type, names.optString(i));
            if (id > 0) sb.append(sb.length() > 0 ? "%7C" : "").append(id);
        }
        return sb.toString();
    }

    /** The genre and kind Alexa asked for, waiting for that genre's titles to arrive. */
    private boolean picking;
    private String pickKind = "";

    private void suggest(String genre, String kind) {
        if (zone == Zone.OVERLAY) openOverlay(false);
        String[] labels = wheel.labels();
        int target = -1;
        if (genre != null) for (int i = 0; i < labels.length; i++) if (labels[i].equalsIgnoreCase(genre)) target = i;
        if (target < 0) target = random.nextInt(labels.length);
        picking = true;
        pickKind = kind == null ? "" : kind;
        zone = Zone.DIAL;
        wheel.select(target);
        showCurrent(false);
    }

    /** Lands on a random title of the genre at the pointer, in the row Alexa asked for. */
    private void applyPick() {
        if (!picking || cache.get(currentGenre()) == null) return;
        picking = false;
        int k = "series".equals(pickKind) ? 0 : "film".equals(pickKind) ? 1 : random.nextInt(2);
        if (lanes[k].isEmpty()) k = 1 - k;
        if (lanes[k].isEmpty()) return;
        lane = k;
        idx[k] = random.nextInt(lanes[k].size());
        zone = Zone.REELS;
        render();
        showToast("Tonight's pick: " + lanes[k].get(idx[k]).name);
    }

    private String[] wheelLabels() {
        boolean forYou = Taste.available() && graph.size() >= 3;
        List<String> l = new ArrayList<>();
        if (askedLabel != null) l.add(askedLabel);
        if (forYou) l.add(FOR_YOU);
        Collections.addAll(l, Tmdb.GENRES);
        return l.toArray(new String[0]);
    }

    private String currentGenre() {
        return wheel.labels()[wheel.selected()];
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(INK);
        root.setClipChildren(false);
        setContentView(root);

        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0.6f);
        root.addView(bg, match());
        View shadeL = new View(this);
        shadeL.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF505050A, 0xBF05050A, 0x3305050A, 0xB305050A}));
        root.addView(shadeL, match());
        View shadeB = new View(this);
        shadeB.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF205050A, 0x8005050A, 0x0005050A, 0x0005050A}));
        root.addView(shadeB, match());

        wheel = new WheelView(this, display, u);
        wheel.setLabels(wheelLabels(), 0);   // before the first render, which reads the chosen genre
        wheel.setTicker(this::tick);
        root.addView(wheel, at(0, 0, (int) (30 * u), ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.wordmark);
        brand.setScaleType(ImageView.ScaleType.FIT_START);
        brand.setAdjustViewBounds(true);
        root.addView(brand, at(3.4f * u, 3f * u, (int) (12 * u), (int) (1.9f * u)));

        tbtn = text(1.2f, display, 800, 0xFFFFD2C8);
        tbtn.setText("●  TONIGHT I WANT…");
        tbtn.setLetterSpacing(0.1f);
        tbtn.setGravity(Gravity.CENTER);
        tbtn.setPadding((int) (1.4f * u), (int) (0.6f * u), (int) (1.4f * u), (int) (0.6f * u));
        // Marvel and DC sit to the left of Tonight I want
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        float ps = u * 2.35f / 52;   // as tall as Tonight I want
        marvelPill = HeroesView.universePill(this, true, display, ps);
        dcPill = HeroesView.universePill(this, false, display, ps);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.rightMargin = (int) (0.8f * u);
        top.addView(marvelPill, gap);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(gap);
        top.addView(dcPill, gap2);
        top.addView(tbtn);
        // Search sits to the right of Tonight I want
        searchBtn = new ImageView(this);
        searchBtn.setImageResource(R.drawable.ic_search);
        searchBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int sb = (int) (2.35f * u);
        searchBtn.setPadding(sb / 4, sb / 4, sb / 4, sb / 4);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(sb, sb);
        sbp.leftMargin = (int) (0.8f * u);
        top.addView(searchBtn, sbp);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        tp.topMargin = (int) (2.6f * u);
        tp.rightMargin = (int) (3f * u);
        root.addView(top, tp);
        styleTbtn();

        // hero: anchored above the reels, grows upward
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.BOTTOM);
        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        kind = text(1.05f, display, 800, HOT);
        kind.setLetterSpacing(0.18f);
        row1.addView(kind);
        svcPill = text(1f, body, 600, Color.WHITE);
        svcPill.setLetterSpacing(0.12f);
        svcPill.setPadding((int) (0.9f * u), (int) (0.3f * u), (int) (0.9f * u), (int) (0.3f * u));
        svcPill.setBackground(pill(0x73000000, 0x2EFFFFFF));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = (int) (0.9f * u);
        row1.addView(svcPill, sp);
        hero.addView(row1);
        heroTitle = text(5.8f, display, 900, Color.WHITE);
        heroTitle.setAllCaps(true);
        heroTitle.setIncludeFontPadding(false);
        heroTitle.setLineSpacing(0, 0.86f);
        heroTitle.setMaxLines(3);
        hero.addView(heroTitle, gap(0.9f));
        heroMeta = text(1.15f, body, 400, 0xB8FFFFFF);
        hero.addView(heroMeta, gap(0.9f));
        heroText = text(1.3f, body, 300, 0xD1FFFFFF);
        heroText.setMaxLines(2);
        heroText.setEllipsize(TextUtils.TruncateAt.END);
        heroText.setLineSpacing(0, 1.3f);
        hero.addView(heroText, gap(0.9f));
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        playBtn = text(1.2f, body, 600, Color.BLACK);
        playBtn.setPadding((int) (1.7f * u), (int) (0.8f * u), (int) (1.7f * u), (int) (0.8f * u));
        playBtn.setBackground(pill(0xFFFFFFFF, 0));
        row2.addView(playBtn);
        heroAside = text(1.15f, body, 400, 0xCCFFFFFF);
        heroAside.setMaxLines(1);
        heroAside.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ap.leftMargin = (int) (1.2f * u);
        row2.addView(heroAside, ap);
        hero.addView(row2, gap(1.1f));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams((int) (34 * u), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        hp.leftMargin = (int) (31 * u);
        hp.bottomMargin = (int) (24 * u);
        root.addView(hero, hp);

        // the tilted poster card, the character breaking out over its top
        card = new FrameLayout(this);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setCameraDistance(60 * u * 8);
        card.setRotationY(-14);
        card.setRotationX(4);
        poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF14141A);
        rounded(poster, 1.3f * u);
        card.addView(poster, match());
        int cw = (int) (13.4f * u), ch = (int) (cw * 1.5f);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cw, ch, Gravity.TOP | Gravity.RIGHT);
        cp.topMargin = (int) (9.5f * u);
        cp.rightMargin = (int) (7 * u);
        root.addView(card, cp);
        // Series and Films reels
        LinearLayout reels = new LinearLayout(this);
        reels.setOrientation(LinearLayout.VERTICAL);
        reels.setClipChildren(false);
        reels.setClipToPadding(false);
        for (int k = 0; k < 2; k++) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setClipChildren(false);
            LinearLayout label = new LinearLayout(this);
            label.setOrientation(LinearLayout.VERTICAL);
            laneLabel[k] = text(1.9f, display, 900, 0x66FFFFFF);
            laneLabel[k].setText(k == 0 ? "SERIES" : "FILMS");
            laneLabel[k].setLetterSpacing(0.04f);
            label.addView(laneLabel[k]);
            laneCount[k] = text(0.85f, body, 500, 0x66FFFFFF);
            laneCount[k].setLetterSpacing(0.14f);
            label.addView(laneCount[k]);
            r.addView(label, new LinearLayout.LayoutParams((int) (8.6f * u), ViewGroup.LayoutParams.WRAP_CONTENT));
            tracks[k] = new LinearLayout(this);
            tracks[k].setClipChildren(false);
            tracks[k].setClipToPadding(false);
            tracks[k].setPadding(0, (int) (0.9f * u), 0, (int) (0.9f * u));
            for (int i = 0; i < SLOTS_PER_ROW; i++) {
                ImageView m = new ImageView(this);
                m.setScaleType(ImageView.ScaleType.CENTER_CROP);
                m.setBackgroundColor(0xFF15151C);
                rounded(m, 0.7f * u);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams((int) (5.3f * u), (int) (7.95f * u));
                mp.rightMargin = (int) (0.9f * u);
                tracks[k].addView(m, mp);
            }
            r.addView(tracks[k]);
            reels.addView(r);
        }
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT);
        rp.leftMargin = (int) (31 * u);
        rp.bottomMargin = (int) (1.6f * u);
        root.addView(reels, rp);

        buildOverlay(root);

        toast = text(1.2f, body, 400, Color.WHITE);
        toast.setPadding((int) (1.8f * u), (int) (1.1f * u), (int) (1.8f * u), (int) (1.1f * u));
        toast.setBackground(pill(0xF00A0A0E, 0x33FFFFFF));
        toast.setAlpha(0);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tl.bottomMargin = (int) (27 * u);
        root.addView(toast, tl);
        render();
    }

    private void buildOverlay(FrameLayout root) {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFF08080B);
        overlay.setVisibility(View.GONE);
        overlay.setClipChildren(false);
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.wordmark);
        brand.setScaleType(ImageView.ScaleType.FIT_START);
        brand.setAdjustViewBounds(true);
        overlay.addView(brand, at(3.4f * u, 3f * u, (int) (12 * u), (int) (1.9f * u)));
        TextView back = text(1.05f, body, 500, 0x8CFFFFFF);
        back.setText("BACK  to Spin");
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        bp.topMargin = (int) (3 * u);
        bp.rightMargin = (int) (3 * u);
        overlay.addView(back, bp);
        sentence = text(4.3f, display, 800, Color.WHITE);
        sentence.setAllCaps(true);
        sentence.setLineSpacing(0, 1.05f);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams((int) (93 * u), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = (int) (3.4f * u);
        sp.topMargin = (int) (8.5f * u);
        overlay.addView(sentence, sp);
        ocount = text(1.15f, body, 500, 0x99FFFFFF);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = (int) (3.4f * u);
        cp.topMargin = (int) (24.5f * u);
        overlay.addView(ocount, cp);
        wall = new LinearLayout(this);
        wall.setGravity(Gravity.BOTTOM);
        wall.setClipChildren(false);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (22 * u), Gravity.BOTTOM);
        wp.leftMargin = (int) (3.4f * u);
        wp.bottomMargin = (int) (3.4f * u);
        overlay.addView(wall, wp);
        root.addView(overlay, match());
    }

    // ------------------------------------------------------------------ data: the spin screen

    /** What one genre loaded: its two rows, Claude's reasons (For You), or the error. */
    private static final class Loaded {
        final List<Title> series, films;
        final Map<String, String> why;
        final String error;

        Loaded(List<Title> series, List<Title> films, Map<String, String> why, String error) {
            this.series = series;
            this.films = films;
            this.why = why;
            this.error = error;
        }
    }

    /** Titles a genre row aims for after the genre check thins a page out (Tmdb.genre). */
    private static final int ROW_WANT = 16;

    /** Every genre on the wheel, loaded together when the app opens; the dice refetches only its own genre. */
    private final Map<String, Loaded> cache = new HashMap<>();
    private final Map<String, Integer> pending = new HashMap<>();
    private final Map<String, Integer> pagesSeen = new HashMap<>();
    private int generation, fetchSeq;

    /** A fresh start: every genre from a random page, the one at the pointer first, then outward. */
    private void preloadAll() {
        generation++;
        cache.clear();
        pending.clear();
        String[] labels = wheel.labels();
        int n = labels.length, sel = wheel.selected();
        for (int d = 0; d <= n / 2; d++) {
            fetch(labels[(sel + d) % n], false);
            fetch(labels[((sel - d) % n + n) % n], false);
        }
        showCurrent(false);
    }

    /** Fetches one genre in the background. reshuffle: fetch again even if it is already loaded. */
    private void fetch(String genre, boolean reshuffle) {
        if (genre.equals(askedLabel)) return;   // a spoken request's matches come only from asked()
        if (!reshuffle && (cache.containsKey(genre) || pending.containsKey(genre))) return;
        final int token = ++fetchSeq, gen = generation;
        final long started = System.currentTimeMillis();
        pending.put(genre, token);
        net.execute(() -> {
            List<Title>[] got = new List[]{new ArrayList<Title>(), new ArrayList<Title>()};
            Map<String, String> reasons = new HashMap<>();
            String err = null;
            try {
                if (FOR_YOU.equals(genre)) forYou(got, reasons, true);
                else {
                    int w = Tmdb.wheelIndexOf(genre);
                    for (int k = 0; k < 2; k++) {
                        boolean series = k == 0;
                        String key = genre + k;
                        // before the first fetch the page count is unknown: try within the first five
                        Integer seen;
                        synchronized (pagesSeen) { seen = pagesSeen.get(key); }
                        int span = seen != null && seen > 1 ? Math.min(seen, 8) : 5;
                        int page = 1 + random.nextInt(span);
                        Tmdb.Page p = Tmdb.genre(series, w, Tmdb.ALL_SERVICES, page, ROW_WANT);
                        if (p.titles.isEmpty() && page > 1) p = Tmdb.genre(series, w, Tmdb.ALL_SERVICES, 1, ROW_WANT);
                        synchronized (pagesSeen) { pagesSeen.put(key, p.totalPages); }
                        got[k].addAll(p.titles);
                        Collections.shuffle(got[k], random);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "load " + genre, e);
                err = "Couldn't reach TMDB. Check the TV's internet, then roll the dice to try again.";
            }
            final String error = err;
            main.post(() -> {
                if (gen != generation || !Integer.valueOf(token).equals(pending.get(genre))) return;   // superseded
                pending.remove(genre);
                cache.put(genre, new Loaded(got[0], got[1], reasons, error));
                Log.i(TAG, "loaded " + genre + " (" + got[0].size() + " series, " + got[1].size() + " films) in "
                        + (System.currentTimeMillis() - started) + " ms; " + cache.size() + "/" + wheel.labels().length + " genres ready");
                if (genre.equals(currentGenre())) showCurrent(reshuffle);
                else if (nearPointer(genre)) prefetchPosters(cache.get(genre));
            });
        });
    }

    /** Shows the genre at the pointer from the cache straight away, or its loading state. */
    private void showCurrent(boolean spin) {
        String genre = currentGenre();
        Loaded l = cache.get(genre);
        idx[0] = idx[1] = 0;
        why.clear();
        if (l == null) {
            loading = true;
            status = FOR_YOU.equals(genre) ? "Reading your taste…" : null;
            lanes[0] = new ArrayList<>();
            lanes[1] = new ArrayList<>();
            fetch(genre, false);
        } else {
            loading = false;
            status = l.error;
            lanes[0] = l.series;
            lanes[1] = l.films;
            why.putAll(l.why);
            lane = lanes[0].isEmpty() && !lanes[1].isEmpty() ? 1 : 0;
            if (spin) slotSpin();
        }
        render();
        applyPick();
        // posters of the genres either side, so the next spin fills at once
        String[] labels = wheel.labels();
        int n = labels.length, sel = wheel.selected();
        for (int d = -2; d <= 2; d++) {
            Loaded near = cache.get(labels[((sel + d) % n + n) % n]);
            if (near != null && d != 0) prefetchPosters(near);
        }
    }

    private boolean nearPointer(String genre) {
        String[] labels = wheel.labels();
        int n = labels.length, sel = wheel.selected();
        for (int d = -2; d <= 2; d++) if (labels[((sel + d) % n + n) % n].equals(genre)) return true;
        return false;
    }

    /** Warms the image cache with the posters a row shows first. */
    private void prefetchPosters(Loaded l) {
        for (List<Title> row : new List[]{l.series, l.films})
            for (int i = 0; i < row.size() && i < SLOTS_PER_ROW; i++) Images.prefetch(Tmdb.image(row.get(i).posterPath, "w185"));
    }

    /** Claude's picks, kept on the TV: dealt at once, refreshed in the background (ForYou). */
    private void forYou(List<Title>[] got, Map<String, String> reasons, boolean shuffle) throws Exception {
        forYou.deal(graph, random, got, reasons, net);
    }

    private Title focused() {
        List<Title> l = lanes[lane];
        return l.isEmpty() ? null : l.get(Math.min(idx[lane], l.size() - 1));
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        wheel.setFocusedLook(zone == Zone.DIAL);
        styleTbtn();
        for (int k = 0; k < 2; k++) {
            boolean active = k == lane;
            laneLabel[k].setTextColor(active ? Color.WHITE : 0x66FFFFFF);
            int n = lanes[k].size();
            laneCount[k].setText(loading ? "…" : n == 1 ? "1 TITLE" : n + " TITLES");
            laneCount[k].setTextColor(active ? HOT : 0x66FFFFFF);
            int i = Math.min(idx[k], Math.max(0, n - 1));
            int start = Math.max(0, Math.min(i - 3, n - SLOTS_PER_ROW));
            for (int s = 0; s < SLOTS_PER_ROW; s++) {
                ImageView m = (ImageView) tracks[k].getChildAt(s);
                int t = start + s;
                if (t >= n) { m.setVisibility(View.INVISIBLE); continue; }
                m.setVisibility(View.VISIBLE);
                Title title = lanes[k].get(t);
                bindPoster(m, Tmdb.image(title.posterPath, "w185"), title.key());
                boolean on = zone == Zone.REELS && k == lane && t == i;
                m.setAlpha(on ? 1f : active ? 0.85f : 0.55f);
                m.animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).translationY(on ? -0.5f * u : 0).setDuration(160).start();
                m.setForeground(on ? ring() : null);
            }
        }
        Title t = focused();
        if (t == null) {
            shown = null;
            kind.setText("");
            svcPill.setVisibility(View.GONE);
            heroTitle.setText(loading ? currentGenre() : "Nothing here");
            setTitleSize(heroTitle.getText().toString());
            heroMeta.setText("");
            heroText.setText(loading ? (status != null ? status : "Finding what's on your Netflix, Prime Video and Disney+…")
                    : status != null ? status : "Nothing in " + currentGenre() + " is on your services right now. Spin on.");
            playBtn.setVisibility(View.GONE);
            heroAside.setText("");
            poster.setImageDrawable(null);
            return;
        }
        if (t != shown) showTitle(t);
    }

    private void showTitle(Title t) {
        shown = t;
        kind.setText(t.series ? "SERIES" : "FILM");
        heroTitle.setText(t.name);
        setTitleSize(t.name);
        heroText.setText(t.overview);
        playBtn.setVisibility(View.VISIBLE);
        bindPoster(poster, Tmdb.image(t.posterPath, "w342"), null);
        card.animate().rotationY(t.name.length() % 2 == 0 ? 12 : -14).setDuration(500).setInterpolator(new OvershootInterpolator(1.2f)).start();
        // the blurred backdrop: a tiny copy stretched over the screen, which softens it like a blur
        String backdrop = !t.backdropPath.isEmpty() && !"null".equals(t.backdropPath) ? Tmdb.image(t.backdropPath, "w300") : Tmdb.image(t.posterPath, "w185");
        Images.load(backdrop, b -> {
            if (shown != t || b == null) return;
            Bitmap tiny = Bitmap.createScaledBitmap(b, 32, Math.max(2, 32 * b.getHeight() / b.getWidth()), true);
            BitmapDrawable d = new BitmapDrawable(getResources(), tiny);
            d.setFilterBitmap(true);
            bg.setImageDrawable(d);
        });
        fillDetails(t);
        // details once the focus has rested a moment
        main.removeCallbacks(detailRunnable);
        main.postDelayed(detailRunnable, 220);
    }

    private final Runnable detailRunnable = () -> {
        Title t = shown;
        if (t == null) return;
        net.execute(() -> {
            try {
                Tmdb.details(t);
            } catch (Exception e) {
                Log.w(TAG, "details " + t.name, e);
            }
            main.post(() -> { if (shown == t) fillDetails(t); });
        });
    };

    private void fillDetails(Title t) {
        String m = t.detailed ? t.meta + (t.genresText.isEmpty() ? "" : " · " + t.genresText) : t.year;
        heroMeta.setText(m);
        String s = serviceName(t.service);
        svcPill.setVisibility(s == null ? View.GONE : View.VISIBLE);
        if (s != null) svcPill.setText("●  " + s.toUpperCase());
        svcPill.setTextColor(Color.WHITE);
        playBtn.setText("▶  Play on " + (s == null ? "…" : s));
        String reason = why.get(t.key());
        if (reason != null && !reason.isEmpty()) heroAside.setText(reason);
        else if (t.leadName != null) heroAside.setText("Starring " + (t.leadCharacter != null ? t.leadCharacter + " · " + t.leadName : t.leadName));
        else heroAside.setText("");
    }

    private void tick(float volume) {
        if (sounds != null) sounds.play(tickSound, volume, volume, 1, 0, 0.95f + random.nextFloat() * 0.1f);
    }

    /** The dice: two dice tumbling on wood, a sound of its own. */
    private void rattle() {
        if (sounds != null) sounds.play(diceSound, 0.6f, 0.6f, 1, 0, 1f);
    }

    private void setTitleSize(String name) {
        float s = name.length() <= 10 ? 5.8f : name.length() <= 16 ? 4.6f : 3.7f;
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, s * u);
    }

    private void bindPoster(ImageView v, String url, String tag) {
        v.setTag(url);
        Bitmap hit = Images.cached(url);
        if (hit != null) { v.setImageBitmap(hit); return; }
        v.setImageDrawable(null);
        Images.load(url, b -> { if (url.equals(v.getTag()) && b != null) v.setImageBitmap(b); });
    }

    /** Both rows spin in like a slot machine after a shuffle. */
    private void slotSpin() {
        for (int k = 0; k < 2; k++) for (int s = 0; s < SLOTS_PER_ROW; s++) {
            View m = tracks[k].getChildAt(s);
            m.setTranslationY(-6 * u);
            m.setAlpha(0.2f);
            m.animate().translationY(0).alpha(1).setStartDelay(s * 45L).setDuration(520).setInterpolator(new OvershootInterpolator(1.3f)).start();
        }
    }

    private void styleTbtn() {
        HeroesView.stylePill(marvelPill, true, zone == Zone.TBTN && tsel == 0, false, u * 2.35f / 52);
        HeroesView.stylePill(dcPill, false, zone == Zone.TBTN && tsel == 1, false, u * 2.35f / 52);
        boolean on = zone == Zone.TBTN && tsel == 2;
        tbtn.setBackground(pill(on ? HOT : 0x1FFF4D2E, on ? HOT : 0x80FF4D2E));
        tbtn.setTextColor(on ? 0xFF0A0A0A : 0xFFFFD2C8);
        boolean sOn = zone == Zone.TBTN && tsel == 3;
        searchBtn.setBackground(pill(sOn ? 0x29FFFFFF : 0x0FFFFFFF, sOn ? Color.WHITE : 0x38FFFFFF));
        searchBtn.animate().scaleX(sOn ? 1.08f : 1).scaleY(sOn ? 1.08f : 1).setDuration(200).start();
    }

    // ------------------------------------------------------------------ opening a title

    private void open(Title t) {
        open(t, null);
    }

    /** service: the app to open it in ("N", "D", "P"); null for the first one TMDB lists. */
    private void open(Title t, String service) {
        if (t == null) return;
        showToast("Opening " + t.name + (service == null ? "" : " on " + serviceName(service)) + "…");
        net.execute(() -> {
            try {
                Tmdb.details(t);
            } catch (Exception ignored) {
            }
            if (service != null) t.service = service;
            Intent i = JustWatch.intentFor(t);
            main.post(() -> {
                graph.opened(t);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    showToast(serviceName(t.service) + " isn't installed on this TV");
                    return;
                }
                if (Taste.available()) net.execute(() -> {
                    Taste.grow(graph);
                    main.post(() -> {
                        String[] l = wheelLabels();
                        if (l.length != wheel.labels().length) wheel.setLabels(l, wheel.selected() + (l.length - wheel.labels().length));
                    });
                });
            });
        });
    }

    private void showToast(String s) {
        toast.setText(s);
        toast.animate().cancel();
        toast.setAlpha(1);
        toast.animate().alpha(0).setStartDelay(2200).setDuration(400).start();
    }

    private static String serviceName(String code) {
        if (code == null) return null;
        for (int i = 0; i < SERVICE_NAME.length; i += 2) if (SERVICE_NAME[i].equals(code)) return SERVICE_NAME[i + 1];
        return null;
    }

    // ------------------------------------------------------------------ Marvel and DC

    private void openHeroes(String universe) {
        if (heroes == null) {
            heroes = new HeroesView(this, display, body, new HeroesView.Host() {
                @Override public void openTitle(Title t, String service) { open(t, service); }
                @Override public void closeHeroes() { MainActivity.this.closeHeroes(); }
                @Override public void toast(String s) { showToast(s); }
                @Override public java.util.concurrent.ExecutorService net() { return net; }
            });
            FrameLayout root = (FrameLayout) toast.getParent();
            root.addView(heroes, match());
            toast.bringToFront();
        }
        heroes.setVisibility(View.VISIBLE);
        heroes.show(universe);
        zone = Zone.HEROES;
    }

    private void openSearch(String q) {
        if (search == null) {
            search = new SearchView(this, display, body, new SearchView.Host() {
                @Override public void openTitle(Title t, String service) { open(t, service); }
                @Override public void closeSearch() { MainActivity.this.closeSearch(); }
                @Override public void toast(String s) { showToast(s); }
            });
            FrameLayout root = (FrameLayout) toast.getParent();
            root.addView(search, match());
            toast.bringToFront();
        }
        search.setVisibility(View.VISIBLE);
        search.show(q);
        zone = Zone.SEARCH;
    }

    private void closeSearch() {
        if (search != null) search.setVisibility(View.GONE);
        zone = Zone.TBTN;
        tsel = 3;
        render();
    }

    private void closeHeroes() {
        if (heroes != null) heroes.setVisibility(View.GONE);
        zone = Zone.TBTN;
        render();
    }

    // ------------------------------------------------------------------ Tonight I want

    private void openOverlay(boolean on) {
        zone = on ? Zone.OVERLAY : Zone.TBTN;
        overlay.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) { ovRow = 0; ovIndex = 0; renderSentence(); loadWall(); }
        render();
    }

    private void renderSentence() {
        String[][] parts = {{"Tonight I want some ", GENRE_WORDS[ovSel[0]]}, {" — ", KINDS[ovSel[1]]}, {" — on ", SERVICES[ovSel[2]]}};
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i][0]);
            int a = sb.length();
            sb.append(" ").append(parts[i][1]).append(" ");
            int z = sb.length();
            boolean sel = ovRow == 0 && ovSlot == i;
            sb.setSpan(new ForegroundColorSpan(sel ? 0xFF0A0A0A : HOT), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (sel) sb.setSpan(new BackgroundColorSpan(HOT), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else sb.setSpan(new UnderlineSpan(), a + 1, z - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sentence.setText(sb);
    }

    private void loadWall() {
        final int token = ++ovToken;
        final int w = Tmdb.wheelIndexOf(GENRE_WORDS[ovSel[0]]);
        final String kindSel = KINDS[ovSel[1]], services = SERVICE_IDS[ovSel[2]];
        ocount.setText("Looking…");
        net.execute(() -> {
            List<Title> got = new ArrayList<>();
            String err = null;
            try {
                for (int k = 0; k < 2; k++) {
                    boolean series = k == 0;
                    if (("series".equals(kindSel) && !series) || ("films".equals(kindSel) && series)) continue;
                    got.addAll(Tmdb.genre(series, w, services, 1, ROW_WANT).titles);
                }
                got.sort((a, b) -> Double.compare(b.popularity, a.popularity));
            } catch (Exception e) {
                err = "Couldn't reach TMDB. Check the TV's internet.";
            }
            final String error = err;
            main.post(() -> {
                if (token != ovToken) return;
                wallTitles.clear();
                for (Title t : got) if (wallTitles.size() < WALL) wallTitles.add(t);
                ovIndex = 0;
                ocount.setText(error != null ? error : wallTitles.isEmpty() ? "Nothing on your services matches that. Change a word."
                        : "On your services now · Enter to browse them");
                buildWall();
            });
        });
    }

    private void buildWall() {
        wall.removeAllViews();
        for (int i = 0; i < wallTitles.size(); i++) {
            Title t = wallTitles.get(i);
            FrameLayout tile = new FrameLayout(this);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0xFF15151C);
            tile.addView(img, match());
            TextView label = text(2.6f, display, 900, Color.WHITE);
            label.setAllCaps(true);
            label.setShadowLayer(0.6f * u, 0, 0, 0xCC000000);
            label.setVisibility(View.GONE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            lp.leftMargin = lp.rightMargin = lp.bottomMargin = (int) (1.2f * u);
            tile.addView(label, lp);
            rounded(tile, 1f * u);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams((int) (10.4f * u), (int) (15.6f * u));
            tp.rightMargin = (int) (1.3f * u);
            wall.addView(tile, tp);
            tile.setAlpha(0);
            tile.setTranslationY(3 * u);
            tile.animate().alpha(1).translationY(0).setStartDelay(i * 60L).setDuration(500).setInterpolator(new OvershootInterpolator(1.2f)).start();
            label.setText(t.name);
            bindPoster(img, Tmdb.image(t.posterPath, "w342"), null);
        }
        renderWall();
    }

    private void renderWall() {
        renderSentence();
        for (int i = 0; i < wall.getChildCount(); i++) {
            FrameLayout tile = (FrameLayout) wall.getChildAt(i);
            ImageView img = (ImageView) tile.getChildAt(0);
            TextView label = (TextView) tile.getChildAt(1);
            Title t = wallTitles.get(i);
            boolean on = ovRow == 1 && i == ovIndex;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            int w = (int) ((on ? 30 : 10.4f) * u), h = (int) ((on ? 22 : 15.6f) * u);
            if (lp.width != w) {
                animateSize(tile, lp, w, h);
                String url = on && !t.backdropPath.isEmpty() && !"null".equals(t.backdropPath) ? Tmdb.image(t.backdropPath, "w780") : Tmdb.image(t.posterPath, "w342");
                bindPoster(img, url, null);
            }
            label.setVisibility(on ? View.VISIBLE : View.GONE);
            tile.setForeground(on ? ring() : null);
        }
    }

    private void animateSize(View v, LinearLayout.LayoutParams lp, int w, int h) {
        int w0 = lp.width, h0 = lp.height;
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(420);
        a.setInterpolator(new OvershootInterpolator(0.8f));
        a.addUpdateListener(x -> {
            float f = (float) x.getAnimatedValue();
            lp.width = (int) (w0 + (w - w0) * f);
            lp.height = (int) (h0 + (h - h0) * f);
            v.setLayoutParams(lp);
        });
        a.start();
    }

    // ------------------------------------------------------------------ the remote

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        if (zone == Zone.SEARCH) return search.key(e) || super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE || k == KeyEvent.KEYCODE_DEL;
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT, right = k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!(ok || back || up || down || left || right)) return super.dispatchKeyEvent(e);
        switch (zone) {
            case OVERLAY:
                overlayKey(ok, back, up, down, left, right);
                return true;
            case HEROES:
                heroes.key(ok, back, up, down, left, right);
                return true;
            case TBTN:
                if (ok) {
                    if (tsel == 3) openSearch(null);
                    else if (tsel == 2) openOverlay(true);
                    else openHeroes(tsel == 0 ? "marvel" : "dc");
                    return true;
                } else if (down || back) zone = Zone.REELS;
                else if (left) { if (tsel > 0) tsel--; else zone = Zone.DIAL; }
                else if (right) tsel = Math.min(3, tsel + 1);
                render();
                return true;
            case DIAL:
                if (up || down) {
                    wheel.select(wheel.selected() + (down ? 1 : -1));
                    showCurrent(false);   // already loaded: the rows change with every step
                } else if (ok) {
                    wheel.roll();
                    rattle();
                    fetch(currentGenre(), true);   // only this genre; the others keep what they loaded
                } else if (right) {
                    zone = Zone.REELS;
                    if (lanes[lane].isEmpty() && !lanes[1 - lane].isEmpty()) lane = 1 - lane;
                    render();
                } else if (back) {
                    finish();
                }
                return true;
            default:   // REELS
                int n = lanes[lane].size();
                if (right) idx[lane] = Math.min(Math.max(0, n - 1), idx[lane] + 1);
                else if (left) { if (idx[lane] == 0) zone = Zone.DIAL; else idx[lane]--; }
                else if (down) { if (lane == 0 && !lanes[1].isEmpty()) lane = 1; }
                else if (up) { if (lane == 1 && !lanes[0].isEmpty()) lane = 0; else { zone = Zone.TBTN; tsel = 2; } }
                else if (back) zone = Zone.DIAL;
                else if (ok) { open(focused()); return true; }
                render();
                return true;
        }
    }


    private void overlayKey(boolean ok, boolean back, boolean up, boolean down, boolean left, boolean right) {
        if (back) { openOverlay(false); return; }
        if (ovRow == 0) {
            if (right) ovSlot = Math.min(2, ovSlot + 1);
            else if (left) ovSlot = Math.max(0, ovSlot - 1);
            else if (up || down) {
                if (ovSlot == 0) {
                    // a random genre, never the one already showing
                    int next = random.nextInt(GENRE_WORDS.length - 1);
                    ovSel[0] = next >= ovSel[0] ? next + 1 : next;
                } else {
                    int n = ovSlot == 1 ? KINDS.length : SERVICES.length;
                    ovSel[ovSlot] = (ovSel[ovSlot] + (up ? n - 1 : 1)) % n;
                }
                renderSentence();
                loadWall();
                return;
            } else if (ok && !wallTitles.isEmpty()) { ovRow = 1; ovIndex = 0; }
        } else {
            if (right) ovIndex = Math.min(wallTitles.size() - 1, ovIndex + 1);
            else if (left) ovIndex = Math.max(0, ovIndex - 1);
            else if (up) ovRow = 0;
            else if (ok) { open(wallTitles.get(ovIndex)); return; }
        }
        renderWall();
    }

    // ------------------------------------------------------------------ helpers

    private TextView text(float size, Typeface face, int weight, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(99 * u);
        d.setColor(fill);
        if (stroke != 0) d.setStroke(Math.max(1, (int) (0.1f * u)), stroke);
        return d;
    }

    private GradientDrawable ring() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(0.8f * u);
        d.setStroke((int) (0.22f * u), Color.WHITE);
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

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams at(float x, float y, int w, int h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.leftMargin = (int) x;
        p.topMargin = (int) y;
        return p;
    }

    private LinearLayout.LayoutParams gap(float top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = (int) (top * u);
        return p;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
        if (sounds != null) sounds.release();
    }
}
