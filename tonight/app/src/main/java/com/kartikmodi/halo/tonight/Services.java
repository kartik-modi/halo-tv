package com.kartikmodi.halo.tonight;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The streaming services Tonight shows, and the country it shows them for: both set at install time by
 * services= and region= in tonight/secrets.properties, e.g.
 *   services=Netflix:8,1796; Prime Video:9,119,2100; Disney+:337; Apple TV:350:com.apple.atve.androidtv.appletv
 * Each entry is the service's name, its TMDB provider ids for the country, and optionally its Android TV package.
 * Well-known services also get a short code, a colour, their packages and their search link from KNOWN below; any
 * other service works too, opened by its package with JustWatch's link for the title when there is one.
 * A title's services are kept as one character per service ("NPD"), in the order configured.
 */
final class Services {
    private Services() {}

    static final String REGION = BuildConfig.REGION;
    /** "the UK", "India", "the US": for sentences such as "isn't on your apps in India". */
    static final String COUNTRY = countryName(REGION);
    /** Changes when the country or the services change, so the search index is rebuilt for them. */
    static final String SIGNATURE = REGION + "|" + BuildConfig.SERVICES;

    static final class Service {
        final char code;
        final String name, chip;
        final int color;
        /** TMDB provider ids, "|"-separated as discover's with_watch_providers wants them. */
        final String providers;
        final int[] ids;
        /** Android packages to try, first installed wins: phone/Google TV and Fire TV builds differ. */
        final String[] packages;
        /** The service's own search link with %s for the title, or null; used when JustWatch has no link. */
        final String search;
        /** JustWatch's technicalName starts with this for the service's offers. */
        final String justWatch;
        /** Start afresh: an app that ignores a link while it is already showing something (Disney+). */
        final boolean fresh;

        Service(char code, String name, String chip, int color, int[] ids, String[] packages, String search, String justWatch, boolean fresh) {
            this.code = code;
            this.name = name;
            this.chip = chip;
            this.color = color;
            this.ids = ids;
            StringBuilder b = new StringBuilder();
            for (int id : ids) b.append(b.length() == 0 ? "" : "|").append(id);
            this.providers = b.toString();
            this.packages = packages;
            this.search = search;
            this.justWatch = justWatch;
            this.fresh = fresh;
        }

        boolean netflix() {
            return code == 'N';
        }

        /** The first of its packages installed on this TV, else the first one listed. */
        String installedPackage(Context c) {
            PackageManager pm = c.getPackageManager();
            for (String p : packages) {
                try {
                    pm.getPackageInfo(p, 0);
                    return p;
                } catch (PackageManager.NameNotFoundException ignored) {
                    // try the next
                }
            }
            return packages.length > 0 ? packages[0] : null;
        }
    }

    /** {name keys, code, chip, colour, packages, search link, JustWatch prefix, fresh} */
    private static final Object[][] KNOWN = {
            {"netflix", 'N', "N", 0xFFE50914, new String[]{"com.netflix.ninja", "com.netflix.mediaclient"}, null, "netflix", false},
            {"prime video", 'P', "P", 0xFF1AA3E8, new String[]{"com.amazon.firebat", "com.amazon.amazonvideo.livingroom"},
                    "https://app.primevideo.com/search?phrase=%s", "amazon", false},
            {"jiohotstar|hotstar", 'J', "JH", 0xFF1B2A6B, new String[]{"in.startv.hotstar", "in.startv.hotstaronly"}, null, "hotstar", false},
            {"disney", 'D', "D+", 0xFF1F4FFF, new String[]{"com.disney.disneyplus"}, "https://www.disneyplus.com/search?q=%s", "disneyplus", true},
            {"apple tv", 'A', "tv", 0xFF3A3A3C, new String[]{"com.apple.atve.androidtv.appletv", "com.apple.atve.amazon.appletv"}, null, "apple", false},
            {"max|hbo", 'M', "M", 0xFF002BE7, new String[]{"com.wbd.stream", "com.hbo.hbonow"}, null, "max", false},
            {"hulu", 'H', "H", 0xFF1CE783, new String[]{"com.hulu.livingroomplus", "com.hulu.plus"}, null, "hulu", false},
            {"paramount", 'U', "P+", 0xFF0064FF, new String[]{"com.cbs.ott", "com.cbs.app"}, null, "paramount", false},
            {"peacock", 'K', "PK", 0xFF2C2C2C, new String[]{"com.peacocktv.peacockandroid"}, null, "peacock", false},
            {"zee5", 'Z', "Z5", 0xFF8230C6, new String[]{"com.graymatrix.did"}, null, "zee5", false},
            {"sony liv|sonyliv", 'S', "SL", 0xFFE1A325, new String[]{"com.sonyliv"}, null, "sonyliv", false},
            {"crunchyroll", 'C', "CR", 0xFFF47521, new String[]{"com.crunchyroll.crunchyroid"}, null, "crunchyroll", false},
            {"mubi", 'B', "MU", 0xFF0F2BFF, new String[]{"com.mubi"}, null, "mubi", false},
    };
    private static final int[] PALETTE = {0xFF7C4DFF, 0xFF00A884, 0xFFE91E63, 0xFFFF9800, 0xFF00ACC1, 0xFF8BC34A};

    static final List<Service> ALL = Collections.unmodifiableList(parse(BuildConfig.SERVICES));

    private static List<Service> parse(String config) {
        List<Service> out = new ArrayList<>();
        StringBuilder used = new StringBuilder();
        int other = 0;
        for (String entry : config.split(";")) {
            String[] f = entry.trim().split(":");
            if (f.length < 2 || f[0].trim().isEmpty()) continue;
            String name = f[0].trim();
            List<Integer> ids = new ArrayList<>();
            for (String id : f[1].split(",")) {
                try {
                    ids.add(Integer.parseInt(id.trim()));
                } catch (NumberFormatException ignored) {
                    // a typo in the config: skip it
                }
            }
            if (ids.isEmpty()) continue;
            int[] idArr = new int[ids.size()];
            for (int i = 0; i < idArr.length; i++) idArr[i] = ids.get(i);
            String pkg = f.length > 2 && !f[2].trim().isEmpty() ? f[2].trim() : null;
            Object[] k = known(name);
            Service s;
            if (k != null && used.indexOf(String.valueOf(k[1])) < 0) {
                String[] pkgs = (String[]) k[4];
                if (pkg != null) {
                    String[] p2 = new String[pkgs.length + 1];
                    p2[0] = pkg;
                    System.arraycopy(pkgs, 0, p2, 1, pkgs.length);
                    pkgs = p2;
                }
                s = new Service((Character) k[1], name, (String) k[2], (Integer) k[3], idArr, pkgs, (String) k[5], (String) k[6], (Boolean) k[7]);
            } else {
                char code = (char) ('1' + other);
                if (code > '9') continue;   // nine services of one's own is plenty
                String chip = fold(name).replaceAll("[^a-z0-9]", "");
                chip = (chip.length() > 2 ? chip.substring(0, 2) : chip).toUpperCase(Locale.ROOT);
                s = new Service(code, name, chip, PALETTE[other % PALETTE.length], idArr,
                        pkg == null ? new String[0] : new String[]{pkg}, null, fold(name).replaceAll("[^a-z0-9]", ""), false);
                other++;
            }
            used.append(s.code);
            out.add(s);
        }
        return out;
    }

    private static Object[] known(String name) {
        String n = fold(name);
        // whole words, so "max" is Max and not ManoramaMax
        for (Object[] k : KNOWN) for (String key : ((String) k[0]).split("\\|"))
            if (java.util.regex.Pattern.compile("(^|[^a-z0-9])" + java.util.regex.Pattern.quote(key) + "($|[^a-z0-9])").matcher(n).find()) return k;
        return null;
    }

    static Service of(String code) {
        return code == null || code.isEmpty() ? null : of(code.charAt(0));
    }

    static Service of(char code) {
        for (Service s : ALL) if (s.code == code) return s;
        return null;
    }

    /** The service a TMDB provider id belongs to; null if it isn't one of ours. */
    static Service forProvider(int id) {
        for (Service s : ALL) for (int i : s.ids) if (i == id) return s;
        return null;
    }

    static String name(String code) {
        Service s = of(code);
        return s == null ? null : s.name;
    }

    static int color(String code) {
        Service s = of(code);
        return s == null ? Color.GRAY : s.color;
    }

    static String chip(String code) {
        Service s = of(code);
        return s == null ? code : s.chip;
    }

    /** Every service's provider ids, for "all of them". */
    static String allProviders() {
        StringBuilder b = new StringBuilder();
        for (Service s : ALL) b.append(b.length() == 0 ? "" : "|").append(s.providers);
        return b.toString();
    }

    /** Service codes in the configured order, each once. */
    static String order(String codes) {
        StringBuilder b = new StringBuilder();
        for (Service s : ALL) if (codes.indexOf(s.code) >= 0) b.append(s.code);
        return b.toString();
    }

    /** "Netflix, Prime Video and Disney+". */
    static String list(String and) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ALL.size(); i++) {
            if (i > 0) b.append(i == ALL.size() - 1 ? " " + and + " " : ", ");
            b.append(ALL.get(i).name);
        }
        return b.length() == 0 ? "your apps" : b.toString();
    }

    /** "isn't on Netflix, Prime Video or Disney+ in India" */
    static String notOn() {
        return "isn't on " + list("or") + " in " + COUNTRY;
    }

    /** TMDB's language for titles and overviews: English as written in this country, e.g. en-IN. */
    static String language() {
        return "en-" + REGION;
    }

    private static String countryName(String code) {
        String n = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
        if (n.isEmpty() || n.equalsIgnoreCase(code)) return code;
        switch (code) {
            case "GB": return "the UK";
            case "US": return "the US";
            case "AE": return "the UAE";
            case "NL": return "the Netherlands";
            case "PH": return "the Philippines";
            default: return n;
        }
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace("+", " plus").trim();
    }
}
