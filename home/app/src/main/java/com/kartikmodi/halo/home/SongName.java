package com.kartikmodi.halo.home;

import java.util.Locale;

/**
 * Turns a YouTube video title into a song name and artist, for the screen and for Jarvis to say:
 * "Shubh - Cheques (Official Music Video)" → "Cheques", "Shubh";
 * "WAVY (OFFICIAL VIDEO) KARAN AUJLA | LATEST PUNJABI SONGS 2024" with channel "Karan Aujla" → "Wavy", "Karan Aujla".
 */
final class SongName {

    private SongName() {
    }

    /** Channels that upload other people's songs: labels and lyrics channels. Their name isn't the artist. */
    static boolean labelChannel(String channel) {
        return channel != null && channel.matches("(?i).*\\b(music|records|recordings|entertainment|series|films|studios?|lyrics?|tunes|beats|media|productions?|label|clouds|vibes|nation|hits|official)\\b.*|(?i).*\\d+clouds.*");
    }

    /** {song, artist}; artist may be empty. */
    static String[] parse(String title, String channel) {
        String t = title == null ? "" : title;
        String ch = channel == null ? "" : channel.replaceAll("(?i)\\s*(-\\s*topic|vevo|official|music|records|tv)$", "").trim();
        // Everything after a bar or double slash is channel blurb.
        t = t.split("\\s[|｜/]{1,2}\\s|\\s//\\s")[0];
        // Bracketed extras: (Official Video), [Lyrics], 【MV】.
        t = t.replaceAll("[\\(\\[【「][^\\)\\]】」]*[\\)\\]】」]", " ");
        // Loose extras.
        t = t.replaceAll("(?i)\\b(official\\s+)?(music\\s+|lyric(al)?\\s+|full\\s+|audio\\s+|4k\\s+|hd\\s+)?(video|audio|song|visuali[sz]er|lyrics?)\\b", " ");
        t = t.replaceAll("(?i)\\b(new|latest)?\\s*(punjabi|hindi|bollywood|tamil|telugu)?\\s*songs?\\s*(20\\d\\d)?\\b", " ");
        t = t.replaceAll("(?i)\\s(ft\\.?|feat\\.?|featuring)\\s.*$", "");
        t = t.replaceAll("(?i)\\b(hd|4k|mv|explicit|remastered|20\\d\\d)\\b", " ");
        t = t.replaceAll("\\s+", " ").replaceAll("^[\\s\\-:|,]+|[\\s\\-:|,]+$", "").trim();

        String song = t, artist = "";
        int dash = t.indexOf(" - ");
        if (dash < 0) dash = t.indexOf(" – ");
        if (dash > 0) {
            artist = t.substring(0, dash).trim();
            song = t.substring(dash + 3).trim();
        } else if (!ch.isEmpty() && !labelChannel(channel)) {
            String lt = t.toLowerCase(Locale.ROOT), lc = ch.toLowerCase(Locale.ROOT);
            if (lt.endsWith(" " + lc)) song = t.substring(0, t.length() - ch.length()).trim();
            else if (lt.startsWith(lc + " ")) song = t.substring(ch.length()).trim();
            artist = ch;
        }
        song = song.replaceAll("^[\\s\\-:|,\"']+|[\\s\\-:|,\"']+$", "");
        if (song.isEmpty()) song = title == null ? "" : title;
        return new String[]{tidyCase(song), tidyCase(artist)};
    }

    /** How Jarvis says it: "Cheques by Shubh". */
    static String spoken(String song, String artist) {
        return artist == null || artist.isEmpty() || song.toLowerCase(Locale.ROOT).contains(artist.toLowerCase(Locale.ROOT))
                ? song : song + " by " + artist;
    }

    /** Whether the cleaned name still looks like a messy title, worth asking the AI about. */
    static boolean messy(String song) {
        return song.split("\\s+").length > 6 || song.matches(".*[#@|].*");
    }

    private static String tidyCase(String s) {
        if (s.isEmpty() || !s.equals(s.toUpperCase(Locale.ROOT)) || !s.matches(".*[A-Z].*")) return s;
        // ALL CAPS → Title Case.
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) continue;
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return b.toString().trim();
    }
}
