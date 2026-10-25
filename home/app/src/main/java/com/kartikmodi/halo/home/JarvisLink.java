package com.kartikmodi.halo.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jarvis: what you say to the "Jarvis" Alexa skill arrives here through the relay (jarvis/relay), is
 * carried out on the Ambient screen, and the answer goes back for Alexa to speak.
 *
 * The TV can't be reached from the internet, so it keeps a long poll open to the relay. Commands are
 * understood by a small set of patterns: music, volume, notes, reminders, weather and time.
 */
final class JarvisLink {

    private static final String TAG = "Jarvis";
    private static JarvisLink instance;
    /** When the last command came from Alexa: its card and its trip to Amazon's home follow shortly after. */
    static volatile long lastAlexaCommandAt;
    /** When Jarvis last opened TONIGHT.: Alexa then sends the TV to Amazon's home, and HomeKeyService brings it back. */
    static volatile long lastTonightAt;

    /** "suggest a movie", "recommend a horror series", "what should I watch": something to watch, from TONIGHT. */
    private static final Pattern WATCH = Pattern.compile(
            "^(?:(?:suggest|recommend|pick|choose|find|give)(?: me| us)?\\b.*\\b(?:movie|film|series|show|something to watch|to watch)\\b.*"
            + "|what (?:should|can|shall) (?:i|we) watch.*|(?:i want|i need|i'd like|id like) (?:a |some )?.*\\b(?:movie|film|series|show)s?\\b.*)$");
    /** Wheel genres TONIGHT. understands, with the words people say for them. */
    private static final String[][] GENRE_WORDS = {
            {"Sci-Fi", "sci-fi|sci fi|scifi|science fiction|space"}, {"Horror", "horror|scary|spooky"},
            {"Comedy", "comedy|funny|comic|laugh"}, {"Romance", "romance|romantic|rom com|romcom|love story"},
            {"Thriller", "thriller|thrilling|suspense"}, {"Action", "action"}, {"Adventure", "adventure"},
            {"Animation", "animation|animated|cartoon|anime"}, {"Crime", "crime|heist|gangster|detective"},
            {"Documentary", "documentary|documentaries|true story"}, {"Drama", "drama"}, {"Family", "family"},
            {"Fantasy", "fantasy|magical|magic"}, {"History", "history|historical|period"}, {"Kids", "kids|children|child"},
            {"Music", "music|musical"}, {"Mystery", "mystery|whodunit|murder mystery"}, {"Reality", "reality"},
            {"War", "war"}, {"Western", "western|cowboy"}};

    /**
     * A search in TONIGHT.: "tonight search: inception" from the Tonight skill, or through Jarvis,
     * "find inception on tonight", "search for the office on tonight".
     */
    private static final Pattern SEARCH = Pattern.compile(
            "^(?:tonight search: ?(.+)|(?:search|find|look|look up|show)(?: for| me| up)? (.+?) (?:on|in|using|with) tonight)$");

    /** Opens TONIGHT.'s search with the words typed in; null when the sentence isn't a search. */
    private String tonightSearch(String t) {
        Matcher m = SEARCH.matcher(t);
        if (!m.matches()) return null;
        String q = (m.group(1) != null ? m.group(1) : m.group(2)).trim();
        Intent i = app.getPackageManager().getLeanbackLaunchIntentForPackage(MainActivity.TONIGHT_PKG);
        if (i == null) return "TONIGHT isn't installed on the TV.";
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("search", q);
        lastTonightAt = System.currentTimeMillis();
        app.startActivity(i);
        // TONIGHT. searches its own index on the TV; its top match is what Alexa says
        try (android.database.Cursor c = app.getContentResolver().query(
                android.net.Uri.parse("content://com.kartikmodi.halo.tonight.search/top?q=" + android.net.Uri.encode(q)), null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String title = c.getString(2), year = c.getString(3), svc = c.getString(4);
                java.util.List<String> on = new java.util.ArrayList<>();
                for (char k : svc.toCharArray()) on.add(k == 'N' ? "Netflix" : k == 'P' ? "Prime Video" : "Disney Plus");
                return "I found " + title + (year.isEmpty() ? "" : ", from " + year) + ", on " + String.join(" and ", on)
                        + (c.getCount() > 1 ? ". The other matches are on your TV." : ". It's on your TV.");
            }
            if (c != null) return "I couldn't find " + q + " on Netflix, Prime Video or Disney Plus.";
        } catch (Exception e) {
            Log.w(TAG, "TONIGHT search", e);
        }
        return "Searching TONIGHT for " + q + ".";
    }

    /** Opens TONIGHT. on a random pick; null when the sentence isn't asking for something to watch. */
    private String tonight(String t) {
        if (!WATCH.matcher(t).matches()) return null;
        Intent i = app.getPackageManager().getLeanbackLaunchIntentForPackage(MainActivity.TONIGHT_PKG);
        if (i == null) return "TONIGHT isn't installed on the TV.";
        String genre = null;
        for (String[] g : GENRE_WORDS) if (t.matches(".*\\b(?:" + g[1] + ")\\b.*")) { genre = g[0]; break; }
        String kind = t.matches(".*\\b(?:series|show|shows|tv)\\b.*") ? "series" : t.matches(".*\\b(?:movie|movies|film|films)\\b.*") ? "film" : "";
        // the whole sentence goes too: TONIGHT. has Claude read it ("a Marvel movie", "something with Tom Hanks")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("suggest", true).putExtra("kind", kind).putExtra("q", t);
        if (genre != null) i.putExtra("genre", genre);
        lastTonightAt = System.currentTimeMillis();
        app.startActivity(i);
        return "Finding that on TONIGHT for you.";
    }

    private final Context app;
    private final RemoteServer server;
    private volatile boolean running;
    private volatile int generation;
    private Thread thread;

    private JarvisLink(Context app, RemoteServer server) {
        this.app = app;
        this.server = server;
    }

    static synchronized void ensureStarted(Context context, RemoteServer server) {
        if (instance == null) instance = new JarvisLink(context.getApplicationContext(), server);
        instance.restart();
    }

    static synchronized void configure(Context context, String url, String key) {
        prefs(context).edit().putString("url", url).putString("key", key).apply();
        if (instance != null) instance.restart();
    }

    /** The phone's "Talk to Jarvis" link (the relay's page, with this TV's phone key); empty until paired. */
    static String talkLink(Context context) {
        SharedPreferences p = prefs(context);
        String url = p.getString("url", ""), k = p.getString("phoneKey", "");
        return url.isEmpty() || k.isEmpty() ? "" : url + "/talk#k=" + k;
    }

    static void setPhoneKey(Context context, String key) {
        prefs(context).edit().putString("phoneKey", key == null ? "" : key.trim()).apply();
    }

    static JSONObject status(Context context) throws org.json.JSONException {
        SharedPreferences p = prefs(context);
        return new JSONObject().put("configured", p.contains("url")).put("url", p.getString("url", ""))
                .put("running", instance != null && instance.running);
    }

    /** Runs a sentence as if it came from Alexa, and shows it on the screen; for testing. */
    static String tryLocally(String text) throws Exception {
        JarvisLink j = instance;
        if (j == null) return "Jarvis isn't running.";
        String speech = j.handle(text);
        j.server.command(new JSONObject().put("type", "jarvis").put("heard", text).put("reply", speech));
        return speech;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    private synchronized void restart() {
        running = false;
        if (thread != null) thread.interrupt();
        SharedPreferences p = prefs(app);
        String url = p.getString("url", null), key = p.getString("key", null);
        if (url == null || key == null) return;
        running = true;
        final int gen = ++generation;
        thread = new Thread(() -> loop(url, key, gen), "JarvisLink");
        thread.start();
    }

    // ---- Relay

    private volatile String lastSpoken = "";
    private volatile long lastSpokenAt;

    /** Up to 8 s for the voice to start (OpenAI's takes a moment), then until it ends; 60 s at most. */
    private static void waitUntilQuiet(long since) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (AmbientActivity.voiceStartedAt < since && System.currentTimeMillis() - t0 < 8000) Thread.sleep(100);
        if (AmbientActivity.voiceStartedAt < since) return;   // no voice on this TV: the page shows the answer only
        while (AmbientActivity.voiceEndedAt < AmbientActivity.voiceStartedAt && System.currentTimeMillis() - t0 < 60000) Thread.sleep(150);
    }

    /** True when what a phone heard is mostly the words of Jarvis's last answer (said in the past 90 s). */
    private boolean isEcho(String heard) {
        if (heard.isEmpty() || System.currentTimeMillis() - lastSpokenAt > 90000) return false;
        java.util.Set<String> said = words(lastSpoken);
        java.util.Set<String> got = words(heard);
        if (said.isEmpty() || got.size() < 2) return false;
        int common = 0;
        for (String w : got) if (said.contains(w)) common++;
        return common >= 0.6 * got.size();
    }

    private static java.util.Set<String> words(String t) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String w : t.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3) out.add(w);
        return out;
    }

    private void loop(String base, String key, int gen) {
        long backoff = 1000;
        while (running && gen == generation && !Thread.currentThread().isInterrupted()) {
            try {
                // The relay answers within 20 s; a poll silent for longer was left behind by a redeploy, so start again.
                JSONObject got = new JSONObject(http("GET", base + "/tv/poll?key=" + URLEncoder.encode(key, "UTF-8"), null, 25000));
                backoff = 1000;
                JSONArray cmds = got.optJSONArray("commands");
                if (cmds == null) continue;
                for (int i = 0; i < cmds.length(); i++) {
                    JSONObject c = cmds.getJSONObject(i);
                    String heard = c.optString("text");
                    boolean fromPhone = "phone".equals(c.optString("from"));
                    if (!fromPhone) lastAlexaCommandAt = System.currentTimeMillis();
                    String speech;
                    try {
                        // From the phone's "Talk to Jarvis": sometimes a recording rather than words.
                        String audio = c.optString("audio", "");
                        if (heard.isEmpty() && !audio.isEmpty()) {
                            String t = OpenAi.transcribe(app, android.util.Base64.decode(audio, android.util.Base64.DEFAULT), c.optString("mime"));
                            heard = t == null ? "" : t;
                        }
                        if (fromPhone && isEcho(heard)) {   // the phone heard Jarvis's own answer from the speaker
                            http("POST", base + "/tv/reply?key=" + URLEncoder.encode(key, "UTF-8"),
                                    new JSONObject().put("id", c.optString("id")).put("speech", "").put("spoken", false).put("heard", heard).put("ignored", true).toString(), 10000);
                            continue;
                        }
                        speech = heard.isEmpty() ? "Sorry, I couldn't make that out." : handle(heard);
                    } catch (Exception e) {
                        Log.w(TAG, "command failed: " + heard, e);
                        speech = "Sorry, something went wrong doing that.";
                    }
                    // With the Ambient screen up, the sun or moon says the answer; otherwise Alexa does.
                    boolean onScreen = server.ambientOnline();
                    long saidAt = System.currentTimeMillis();
                    if (onScreen) server.command(new JSONObject().put("type", "jarvis").put("heard", heard).put("reply", speech));
                    lastSpoken = speech;
                    lastSpokenAt = saidAt;
                    String reply = new JSONObject().put("id", c.optString("id")).put("speech", speech).put("spoken", onScreen).put("heard", heard).toString();
                    String replyUrl = base + "/tv/reply?key=" + URLEncoder.encode(key, "UTF-8");
                    if (onScreen && fromPhone) {
                        // A phone listens again after the answer: tell it only once Jarvis has finished saying it,
                        // or it records the answer from the speaker and takes it for a new question. On its own
                        // thread, so Alexa's requests aren't held up meanwhile.
                        new Thread(() -> {
                            try {
                                waitUntilQuiet(saidAt);
                                http("POST", replyUrl, reply, 10000);
                            } catch (Exception e) {
                                Log.w(TAG, "phone reply failed: " + e.getMessage());
                            }
                        }, "JarvisPhoneReply").start();
                        continue;
                    }
                    http("POST", replyUrl, reply, 10000);
                    // Alexa puts its own card over the screen while it handles the request; bring the
                    // Ambient screen back in front so Jarvis is seen answering.
                    if (onScreen && !fromPhone && AmbientActivity.alive()) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(server::openAmbientScreen, 400);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.w(TAG, "relay unreachable: " + e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    private static String http(String method, String url, String body, int timeout) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(timeout);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        }
        return buf.toString("UTF-8");
    }

    // ---- Understanding a command

    private static final Pattern PLAY = Pattern.compile("^(?:play|put on|play me)\\s+(.+)$");
    private static final Pattern QUEUE = Pattern.compile("^(?:queue|add)\\s+(.+?)(?:\\s+to the queue)?$|^play\\s+(.+)\\s+next$");
    private static final Pattern NOTE = Pattern.compile("^(?:note|add a note|make a note|write)\\s*(?:that|saying|to say)?\\s*(.+)$");
    private static final Pattern VOLUME_TO = Pattern.compile("volume\\s+(?:to\\s+)?(\\d{1,3})|(\\d{1,3})\\s*(?:percent|%)\\s*volume");

    // ---- Conversation memory: recent turns for the model, older ones folded into a short summary, all
    // forgotten after a quiet spell so it never grows without end.

    private static final long FORGET_AFTER_MS = 15 * 60 * 1000;
    private static final int KEEP_MESSAGES = 12;   // 6 exchanges verbatim
    private final java.util.ArrayList<JSONObject> history = new java.util.ArrayList<>();
    private String summary = "";
    private long lastTurnAt;

    String handle(String raw) throws Exception {
        synchronized (history) {
            if (System.currentTimeMillis() - lastTurnAt > FORGET_AFTER_MS) { history.clear(); summary = ""; }
        }
        String mem = aboutMemory(raw.trim().toLowerCase(Locale.UK).replaceAll("[.?!]+$", ""), raw.trim());
        if (mem != null) {   // talking about the memory itself is not something to remember
            remember(raw, mem);
            return mem;
        }
        String reply = answer(raw);
        remember(raw, reply);
        JarvisMemory.get(app).heard(raw, reply);
        return reply;
    }

    // ---- Long-term memory, asked for directly (JarvisMemory keeps it; the model is given the relevant bits).

    private static final Pattern MEM_LIST = Pattern.compile("^(?:what|tell me what) (?:do|did) you (?:remember|know) about me$|^what have you remembered(?: about me)?$");
    private static final Pattern MEM_CLEAR = Pattern.compile("^forget (?:everything|all of it|it all|all)(?: about me| you know| you remember)?$");
    private static final Pattern MEM_FORGET = Pattern.compile("^forget (?:that |about )?(.+)$");
    private static final Pattern MEM_ADD = Pattern.compile("^remember (?:that )?(.+)$");

    /** The reply to "remember…", "forget…" or "what do you remember about me"; null for anything else. */
    private String aboutMemory(String t, String original) {
        JarvisMemory memory = JarvisMemory.get(app);
        if (MEM_LIST.matcher(t).matches()) {
            JSONArray all = memory.list();
            if (all.length() == 0) return "Nothing yet, sir. I'll pick things up as we talk.";
            StringBuilder b = new StringBuilder("I remember " + all.length() + (all.length() == 1 ? " thing" : " things") + ". Most recently: ");
            for (int i = 0; i < Math.min(3, all.length()); i++) {
                if (i > 0) b.append(' ');
                b.append(all.optJSONObject(i).optString("text"));
            }
            return b.append(all.length() > 3 ? " The full list is on your phone." : "").toString();
        }
        if (MEM_CLEAR.matcher(t).matches()) {
            memory.clear();
            return "Done, sir. I've forgotten everything.";
        }
        Matcher m = MEM_FORGET.matcher(t);
        if (m.matches()) {
            String gone = memory.forget(m.group(1));
            return gone == null ? "I don't seem to remember that, sir." : "Forgotten: " + gone;
        }
        m = MEM_ADD.matcher(t);
        if (m.matches()) {
            String what = original.replaceFirst("(?i)^remember (?:that )?", "").replaceAll("[.?!]+$", "");
            return memory.remember(what) ? "I'll remember that, sir." : "I couldn't save that just now, sir.";
        }
        return null;
    }

    private void remember(String said, String reply) throws org.json.JSONException {
        java.util.List<JSONObject> old = null;
        String prior;
        synchronized (history) {
            lastTurnAt = System.currentTimeMillis();
            history.add(new JSONObject().put("role", "user").put("content", said));
            history.add(new JSONObject().put("role", "assistant").put("content", reply));
            if (history.size() <= KEEP_MESSAGES + 4) return;
            // Fold the oldest turns into the summary, keeping the most recent ones word for word.
            old = new java.util.ArrayList<>(history.subList(0, history.size() - KEEP_MESSAGES));
            history.subList(0, history.size() - KEEP_MESSAGES).clear();
            prior = summary;
        }
        final java.util.List<JSONObject> fold = old;
        new Thread(() -> {
            String s = OpenAi.summarise(app, prior, fold);
            synchronized (history) {
                if (s != null) summary = s;
                else if (summary.length() > 600) summary = summary.substring(summary.length() - 600);
            }
        }, "JarvisMemory").start();
    }

    private String answer(String raw) throws Exception {
        String t = raw.toLowerCase(Locale.UK).trim().replaceAll("[?.!,]+$", "").replaceAll("^(?:jarvis\\s+|please\\s+|can you\\s+)+", "");
        JSONObject st = server.state();
        JSONObject now = currentItem(st);

        // A search in TONIGHT., then something to watch: TONIGHT. picks it.
        String found = tonightSearch(t);
        if (found != null) return found;
        String watch = tonight(t);
        if (watch != null) return watch;

        // Transport.
        if (t.matches("(pause|stop)( the)?( music| song| it)?")) {
            server.command(new JSONObject().put("type", "pause"));
            return "Paused.";
        }
        if (t.matches("(resume|continue|unpause|play)( the)?( music| song| it)?")) {
            server.command(new JSONObject().put("type", "resume"));
            return now != null ? "Playing " + say(now) + "." : "There's nothing in the queue yet. Ask me to play a song.";
        }
        if (t.matches("(next|skip|another)( the| this| that)?( song| track| one| it)?( please)?")) {
            server.command(new JSONObject().put("type", "next"));
            return "Skipping.";
        }
        if (t.matches("(previous|go back|back|last)( song| track)?")) {
            server.command(new JSONObject().put("type", "prev"));
            return "Going back.";
        }
        if (t.matches("start over|restart( the)?( song| track)?|play (it|this) again")) {
            server.command(new JSONObject().put("type", "seek").put("value", 0));
            return "From the top.";
        }

        // Volume.
        Matcher vm = VOLUME_TO.matcher(t);
        if (t.contains("volume") && vm.find()) {
            int v = Integer.parseInt(vm.group(1) != null ? vm.group(1) : vm.group(2));
            server.command(new JSONObject().put("type", "volume").put("value", Math.min(100, v)));
            return "Volume " + Math.min(100, v) + ".";
        }
        if (t.matches(".*(louder|volume up|turn (it |the volume |the music )?up|(increase|raise|boost|bump up|crank up) (the )?(volume|music|sound)).*")) {
            int v = Math.min(100, st.optInt("volume", 50) + 15);
            server.command(new JSONObject().put("type", "volume").put("value", v));
            return "Volume " + v + ".";
        }
        if (t.matches(".*(quieter|softer|volume down|turn (it |the volume |the music )?down|(lower|decrease|reduce|drop) (the )?(volume|music|sound)).*")) {
            int v = Math.max(0, st.optInt("volume", 50) - 15);
            server.command(new JSONObject().put("type", "volume").put("value", v));
            return "Volume " + v + ".";
        }

        // What's playing.
        if (t.matches(".*(what('s| is) (playing|this song|this)|what song is this|name of this song).*")) {
            if (now == null) return "Nothing is playing right now.";
            return "This is " + say(now) + ".";
        }

        // Reminders.
        if (t.startsWith("remind me") || t.startsWith("set a reminder")) return reminder(t);

        // Notes and reminders: read from what is on the screen now, never from memory.
        if (t.matches(".*\\b(to ?dos?|to-dos?|notes?|reminders?|my list|tasks?|agenda|plans?|schedule)\\b.*")
                && t.matches("^(what|whats|what's|read|show|list|any|do i have|have i got|tell|give|remind me what|which|how many).*")) {
            return agenda(t, st);
        }

        // Notes.
        // Only all of them, said plainly; one note ("remove the milk note") is the model's, which can see each one.
        if (t.matches("(clear|delete|remove|wipe|erase|get rid of)( all)?( of)?( my| the)?( notes| the notes| my notes)( please)?")
                || t.matches("(clear|delete|remove|wipe|erase|get rid of) (all|every|each) (of )?(my |the )?(notes?|note)( please)?")) {
            server.command(new JSONObject().put("type", "clearNotes"));
            return "Notes cleared.";
        }
        Matcher nm = NOTE.matcher(t);
        if (nm.matches() && !nm.group(1).isEmpty()) {
            String text = capitalise(raw.trim().replaceAll("(?i)^(?:jarvis\\s+)?(?:note|add a note|make a note|write)\\s*(?:that|saying|to say)?\\s*", ""));
            server.command(new JSONObject().put("type", "note").put("text", text).put("color", "sun"));
            return "Noted: " + text;
        }

        // News: "what's the news", "tech news", "news about India", "latest headlines".
        if ((t.matches(".*\\b(news|headlines)\\b.*") || t.matches("^(what'?s|whats|what is) (happening|going on|the latest) (with|in|at|on|about) .+"))
                && !t.matches(".*\\b(note|remind)\\b.*")) {
            String topic = t.replaceAll("\\b(what'?s|what is|whats|the|latest|today'?s|any|top|me|tell|give|read|news|headlines|in|on|about|from|for|stories|some|please|happening|going|there|is|are|with|at|latest)\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            JSONArray heads = News.headlines(topic, 8);
            if (heads.length() == 0) return "I couldn't find any news" + (topic.isEmpty() ? "" : " about " + topic) + " just now.";
            String brief = OpenAi.briefing(app, topic, heads);
            if (brief != null && !brief.isEmpty()) return brief;
            StringBuilder b = new StringBuilder(topic.isEmpty() ? "Here are the top stories. " : "Here's the latest on " + topic + ". ");
            for (int i = 0; i < Math.min(3, heads.length()); i++) b.append(heads.getJSONObject(i).optString("title")).append(". ");
            return b.toString().trim();
        }

        // Weather, air, sun.
        JSONObject wx = st.optJSONObject("wx");
        if (t.matches(".*\\b(air|aqi|pollution|air quality)\\b.*")) {
            if (wx == null || !wx.has("aqi")) return "I don't have the air quality right now.";
            return "Air quality " + (wx.has("city") ? "in " + wx.optString("city") + " " : "") + "is " +
                    wx.optString("aqiBand").toLowerCase(Locale.UK) + ", with an index of " + wx.optInt("aqi") + ".";
        }
        if (t.matches(".*\\b(sunset|sunrise|sun (set|rise)|get dark|dark)\\b.*")) {
            if (wx == null) return "I don't have the sun times right now.";
            return "Sunrise is at " + wx.optString("sunrise") + " and sunset at " + wx.optString("sunset") + ".";
        }
        if (t.matches(".*\\b(weather|temperature|hot|cold|warm|rain|raining|umbrella|forecast|outside|jacket|coat)\\b.*")) {
            if (wx == null) return "I don't have the weather yet. Open the Ambient screen and ask again.";
            if (OpenAi.configured(app) && t.matches(".*\\b(should|need|carry|take|bring|wear|worth|go out|good day|walk|run)\\b.*")) {
                String advice = ai(raw, st);
                if (advice != null) return advice;
            }
            JSONObject tm = wx.optJSONObject("tomorrow");
            if (t.contains("tomorrow") && tm != null) {
                return "Tomorrow looks " + tm.optString("desc").toLowerCase(Locale.UK) + ", with a high of " + tm.optInt("hi") +
                        " and a low of " + tm.optInt("lo") + (tm.has("rain") && !tm.isNull("rain") ? ", and a " + tm.optInt("rain") + " percent chance of rain." : ".");
            }
            StringBuilder s = new StringBuilder("It's " + wx.optInt("temp") + " degrees and " + wx.optString("desc").toLowerCase(Locale.UK));
            if (wx.has("city")) s.append(" in ").append(wx.optString("city"));
            s.append(". Today, a high of ").append(wx.optInt("hi")).append(" and a low of ").append(wx.optInt("lo"));
            if (wx.has("rain")) s.append(", with a ").append(wx.optInt("rain")).append(" percent chance of rain");
            return s.append(".").toString();
        }

        // Time.
        if (t.matches(".*\\b(what time|the time|time is it)\\b.*")) {
            Calendar c = Calendar.getInstance();
            return "It's " + spokenTime(c);
        }

        // Ambient screen.
        if (t.matches(".*(open|show|start|launch).*(ambient|clock|screen).*")) {
            server.openAmbientScreen();
            return "Here's the Ambient screen.";
        }

        // Music: anything else starting with play, or queue.
        Matcher qm = QUEUE.matcher(t);
        if (qm.matches()) {
            String q = qm.group(1) != null ? qm.group(1) : qm.group(2);
            JSONObject hit = firstResult(q);
            if (hit == null) return "I couldn't find " + q + ".";
            server.command(new JSONObject().put("type", "enqueue").put("item", hit));
            return "Added " + say(hit) + " to the queue.";
        }
        Matcher pm = PLAY.matcher(t);
        if (pm.matches()) {
            String q = pm.group(1).replaceAll("^(some|a song by|songs by|music by|a song)\\s+", "");
            if (q.matches("(some )?music|something|a song|anything")) {
                if (now != null) {
                    server.command(new JSONObject().put("type", "resume"));
                    return "Playing " + say(now) + ".";
                }
                q = "top hits " + Calendar.getInstance().get(Calendar.YEAR);
            }
            JSONObject hit = firstResult(q);
            if (hit == null) return "I couldn't find " + q + " on YouTube.";
            server.command(new JSONObject().put("type", "play").put("item", hit));
            return "Playing " + say(hit) + ".";
        }

        String smart = ai(raw, st);
        if (smart != null) return smart;
        return "Sorry, I can't do that yet. I can play songs, pause or skip, change the volume, add notes, " +
                "set reminders, and tell you the weather or air quality.";
    }

    /** Anything the patterns don't cover goes to OpenAI, when a key is saved; its actions run here. */
    private String ai(String said, JSONObject st) throws Exception {
        JSONObject ctx = new JSONObject();
        Calendar c = Calendar.getInstance();
        ctx.put("local_time", String.format(Locale.UK, "%tA %<td %<tB %<tY, %<tH:%<tM", c));
        JSONObject now = currentItem(st);
        if (now != null) ctx.put("now_playing", now.optString("title")).put("playing", st.optBoolean("playing"));
        ctx.put("volume", st.optInt("volume"));
        if (st.has("wx")) ctx.put("weather", st.get("wx"));
        // The notes and reminders on screen now are the only list there is; anything from earlier in the
        // conversation that isn't here was deleted.
        JSONArray notes = st.optJSONArray("notes");
        JSONArray current = new JSONArray();
        if (notes != null) {
            for (int i = 0; i < notes.length(); i++) {
                JSONObject n = notes.getJSONObject(i);
                JSONObject e = new JSONObject().put("id", n.optLong("id")).put("text", n.optString("text"));
                if (n.has("at") && !n.isNull("at")) {
                    Calendar due = Calendar.getInstance();
                    due.setTimeInMillis(n.optLong("at"));
                    e.put("reminder_at", String.format(Locale.UK, "%tA %<td %<tB %<tH:%<tM", due)).put("done", n.optBoolean("fired"));
                }
                current.put(e);
            }
        }
        ctx.put("notes_and_reminders_current_only", current);
        JSONArray past = new JSONArray();
        String mem;
        synchronized (history) {
            for (JSONObject m : history) past.put(m);
            mem = summary;
        }
        // Only the few long-term memories that bear on this question, found by meaning.
        String recalled = android.text.TextUtils.join(" | ", JarvisMemory.get(app).recall(said));
        JSONObject msg = OpenAi.chat(app, said, ctx.toString(), past, mem, recalled);
        if (msg == null) return null;
        StringBuilder done = new StringBuilder();
        JSONArray calls = msg.optJSONArray("tool_calls");
        JSONArray results = new JSONArray();
        boolean needsAnswer = false;
        boolean first = true;
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i);
                JSONObject f = call.getJSONObject("function");
                JSONObject a = new JSONObject(f.optString("arguments", "{}"));
                if (f.getString("name").equals("get_news")) {
                    // Information for the model, not an action: it answers from these in a second turn.
                    results.put(new JSONObject().put("role", "tool").put("tool_call_id", call.optString("id"))
                            .put("content", News.headlines(a.optString("topic"), 8).toString()));
                    needsAnswer = true;
                    continue;
                }
                String r = act(f.getString("name"), a, first);
                results.put(new JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", r == null ? "done" : r));
                if (f.getString("name").equals("play_song")) first = false;
                if (r != null && done.length() < 200) done.append(r).append(' ');
            }
        }
        if (needsAnswer) {
            String answer = OpenAi.followUp(app, msg, results);
            if (!answer.isEmpty()) return answer;
        }
        String content = msg.optString("content", "");
        if (msg.isNull("content")) content = "";
        return !content.trim().isEmpty() ? content.trim() : done.length() > 0 ? done.toString().trim() : "Done.";
    }

    /** "What are my todos for tomorrow?": reminders for the day asked about, then the plain notes. */
    private String agenda(String t, JSONObject st) throws Exception {
        JSONArray notes = st.optJSONArray("notes");
        if (notes == null || notes.length() == 0) return "Your list is empty. Nothing to do!";
        Calendar now = Calendar.getInstance();
        int want = t.contains("tomorrow") ? 1 : t.contains("today") || t.contains("tonight") ? 0 : -1;
        java.util.List<String> timed = new java.util.ArrayList<>(), plain = new java.util.ArrayList<>();
        for (int i = 0; i < notes.length(); i++) {
            JSONObject n = notes.getJSONObject(i);
            if (n.has("at") && !n.isNull("at")) {
                if (n.optBoolean("fired")) continue;   // already gone off
                Calendar due = Calendar.getInstance();
                due.setTimeInMillis(n.optLong("at"));
                int day = due.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR);
                if (due.get(Calendar.YEAR) != now.get(Calendar.YEAR)) day = day < 0 ? 1 : day;
                if (want >= 0 && day != want) continue;
                timed.add(n.optString("text").replaceAll("[.]$", "") + " at " + spokenTime(due) + (want < 0 && day == 1 ? " tomorrow" : ""));
            } else {
                plain.add(n.optString("text").replaceAll("[.]$", ""));
            }
        }
        String when = want == 1 ? "tomorrow" : want == 0 ? "today" : "";
        StringBuilder b = new StringBuilder();
        if (!timed.isEmpty()) b.append(when.isEmpty() ? "Your reminders: " : "For " + when + ", you have: ").append(String.join("; ", timed)).append(". ");
        else if (!when.isEmpty()) b.append("Nothing's scheduled for ").append(when).append(". ");
        if (!plain.isEmpty()) b.append(timed.isEmpty() && when.isEmpty() ? "Your notes: " : "On your list: ").append(String.join("; ", plain)).append(".");
        return b.toString().replace("..", ".").trim();
    }

    private String act(String name, JSONObject a, boolean firstPlay) throws Exception {
        switch (name) {
            case "play_song":
            case "queue_song": {
                JSONObject hit = firstResult(a.optString("query"));
                if (hit == null) return "I couldn't find " + a.optString("query") + ".";
                boolean play = name.equals("play_song") && firstPlay;
                server.command(new JSONObject().put("type", play ? "play" : "enqueue").put("item", hit));
                return (play ? "Playing " : "Queued ") + say(hit) + ".";
            }
            case "control": {
                String act = a.optString("action");
                String type = act.equals("previous") ? "prev" : act;
                if (!type.matches("pause|resume|next|prev")) return null;
                server.command(new JSONObject().put("type", type));
                return null;
            }
            case "set_volume":
                server.command(new JSONObject().put("type", "volume").put("value", Math.max(0, Math.min(100, a.optInt("level", 50)))));
                return null;
            case "add_note":
                server.command(new JSONObject().put("type", "note").put("text", capitalise(a.optString("text"))).put("color", "sun"));
                return "Noted.";
            case "remove_note":
                server.command(new JSONObject().put("type", "deleteNote").put("id", a.optLong("id")));
                return null;
            case "edit_note":
                server.command(new JSONObject().put("type", "editNote").put("id", a.optLong("id")).put("text", capitalise(a.optString("text"))));
                return null;
            case "clear_notes":
                server.command(new JSONObject().put("type", "clearNotes"));
                return null;
            case "set_reminder": {
                // The model's own reply stands if it reached for a reminder without a time.
                String r = reminder("remind me " + a.optString("when_and_what").toLowerCase(Locale.UK));
                return r.startsWith("When should I remind you") ? null : r;
            }
            default:
                return null;
        }
    }

    /** A song as Jarvis says it: "Cheques by Shubh", not the whole video title. */
    private String say(JSONObject item) {
        String spoken = item.optString("spoken", "");
        if (spoken.isEmpty()) {   // queued before names were cleaned
            String[] n = SongName.parse(item.optString("videoTitle", item.optString("title")), item.optString("channel", item.optString("artist")));
            spoken = SongName.spoken(n[0], n[1]);
        }
        if (!item.optBoolean("sure", true)) {
            String ai = OpenAi.songName(app, item.optString("videoTitle", item.optString("title")), item.optString("channel", ""));
            if (ai != null) return ai;
        }
        return spoken;
    }

    private JSONObject firstResult(String q) throws Exception {
        JSONArray results = RemoteServer.search(q).optJSONArray("results");
        return results != null && results.length() > 0 ? results.getJSONObject(0) : null;
    }

    private static JSONObject currentItem(JSONObject st) {
        JSONArray queue = st.optJSONArray("queue");
        int i = st.optInt("index", -1);
        return queue != null && i >= 0 && i < queue.length() ? queue.optJSONObject(i) : null;
    }

    // ---- Reminders: "remind me at 7 to call mum", "remind me to call mum at 7:30pm", "in 10 minutes"

    private static final Pattern IN = Pattern.compile("\\bin\\s+(an?|\\d+|one|two|three|four|five|ten|fifteen|twenty|thirty|forty five)\\s*(minutes?|mins?|hours?|hrs?)\\b");
    private static final Pattern AT = Pattern.compile("\\b(?:at|for)\\s+(\\d{1,2})(?::|\\.|\\s)?(\\d{2})?\\s*(am|pm|a\\.m\\.|p\\.m\\.|o'clock)?\\b");
    private static final Pattern TOMORROW = Pattern.compile("\\btomorrow\\b");

    private String reminder(String t) throws Exception {
        String body = t.replaceFirst("^(remind me|set a reminder)\\s*", "");
        Calendar when = Calendar.getInstance();
        boolean found = false;
        Matcher in = IN.matcher(body);
        Matcher at = AT.matcher(body);
        if (in.find()) {
            int n = number(in.group(1));
            boolean hours = in.group(2).startsWith("h");
            when.add(hours ? Calendar.HOUR_OF_DAY : Calendar.MINUTE, n);
            body = body.replace(in.group(0), " ");
            found = true;
        } else if (at.find()) {
            int h = Integer.parseInt(at.group(1)), m = at.group(2) != null ? Integer.parseInt(at.group(2)) : 0;
            String ap = at.group(3) == null ? "" : at.group(3).replace(".", "");
            if (ap.equals("pm") && h < 12) h += 12;
            if (ap.equals("am") && h == 12) h = 0;
            if (h > 23 || m > 59) return "Sorry, I didn't understand the time.";
            Calendar now = Calendar.getInstance();
            when.set(Calendar.HOUR_OF_DAY, h);
            when.set(Calendar.MINUTE, m);
            when.set(Calendar.SECOND, 0);
            // "at 7" with no am/pm: the next 7 o'clock still to come.
            if (ap.isEmpty() && h < 12 && when.before(now)) when.add(Calendar.HOUR_OF_DAY, 12);
            if (when.before(now)) when.add(Calendar.DATE, 1);
            body = body.replace(at.group(0), " ");
            found = true;
        }
        if (TOMORROW.matcher(body).find()) {
            if (when.get(Calendar.DATE) == Calendar.getInstance().get(Calendar.DATE)) when.add(Calendar.DATE, 1);
            body = body.replaceAll("\\btomorrow\\b", " ");
        }
        if (!found) return "When should I remind you? Say something like: remind me at 7 to call mum.";
        String what = body.replaceAll("^\\s*(to|that|about)\\s+", "").replaceAll("\\s+", " ").trim();
        if (what.isEmpty()) what = "Reminder";
        what = capitalise(what);
        server.command(new JSONObject().put("type", "reminder").put("text", what).put("at", when.getTimeInMillis()));
        boolean tomorrow = when.get(Calendar.DATE) != Calendar.getInstance().get(Calendar.DATE);
        return "Okay, I'll remind you " + (tomorrow ? "tomorrow " : "") + "at " + spokenTime(when) + ": " + what + ".";
    }

    private static int number(String s) {
        switch (s) {
            case "a": case "an": case "one": return 1;
            case "two": return 2; case "three": return 3; case "four": return 4; case "five": return 5;
            case "ten": return 10; case "fifteen": return 15; case "twenty": return 20; case "thirty": return 30;
            case "forty five": return 45;
            default: return Integer.parseInt(s);
        }
    }

    private static String spokenTime(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + (m == 0 ? "" : ":" + (m < 10 ? "0" + m : String.valueOf(m))) + (h < 12 ? " a.m." : " p.m.");
    }

    private static String capitalise(String s) {
        s = s.trim();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
