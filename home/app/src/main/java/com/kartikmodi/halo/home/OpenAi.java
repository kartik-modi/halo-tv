package com.kartikmodi.halo.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Optional OpenAI features for Jarvis, used once a key is saved from the phone's Settings tab:
 * a natural voice for the sun or moon, and a model for requests the built-in patterns don't cover.
 * The key stays on the TV; it is never sent back to the phone.
 */
final class OpenAi {

    private static final String VOICE_MODEL = "gpt-4o-mini-tts";
    private static final String VOICE = "ash";
    // In the manner of the AI butler from the Iron Man films (a style, not anyone's own voice).
    private static final String VOICE_STYLE = "A refined British AI butler: crisp Received Pronunciation, measured and precise, unflappably calm, " +
            "with dry, understated wit. Smooth low-to-mid male register, slightly clipped consonants, even pace; polished, never theatrical.";
    // OpenAI's named replacement for gpt-4.1-nano, which it shut down on 23 Oct 2026. Like the GPT-5 models it takes
    // max_completion_tokens instead of max_tokens and only its default temperature. reasoning_effort "none" is required
    // with tools in chat completions, and keeps answers quick and cheap: no thinking tokens are billed.
    private static final String CHAT_MODEL = "gpt-5.6-luna";
    private static final String EFFORT = "none";

    private OpenAi() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    static boolean configured(Context c) {
        return !prefs(c).getString("openai", "").isEmpty();
    }

    static void setKey(Context c, String key) {
        prefs(c).edit().putString("openai", key == null ? "" : key.trim()).apply();
    }

    /** The spoken answer as a stream of raw 24 kHz 16-bit mono PCM, readable as it arrives; null on failure. */
    static InputStream speechStream(Context c, String text) {
        String key = prefs(c).getString("openai", "");
        if (key.isEmpty()) return null;
        try {
            JSONObject body = new JSONObject().put("model", VOICE_MODEL).put("voice", VOICE).put("input", text)
                    .put("instructions", VOICE_STYLE).put("response_format", "pcm");
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/speech").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = conn.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) return null;
            return conn.getInputStream();
        } catch (Exception e) {
            return null;
        }
    }

    /** Spoken answer as MP3, or null when there is no key or the request fails. */
    static byte[] speech(Context c, String text) {
        String key = prefs(c).getString("openai", "");
        if (key.isEmpty()) return null;
        try {
            JSONObject body = new JSONObject().put("model", VOICE_MODEL).put("voice", VOICE).put("input", text)
                    .put("instructions", VOICE_STYLE).put("response_format", "mp3");
            return post(key, "https://api.openai.com/v1/audio/speech", body, 15000);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Asks the model to handle a request; it answers with a short spoken reply and, where it fits, calls
     * the TV's actions. Returns null when there is no key.
     */
    static JSONObject chat(Context c, String said, String context, JSONArray past, String earlier, String recalled) throws Exception {
        String key = prefs(c).getString("openai", "");
        if (key.isEmpty()) return null;
        JSONArray tools = new JSONArray()
                .put(tool("play_song", "Search YouTube and play a song now.", "query", "Song, artist or mood to search for"))
                .put(tool("queue_song", "Search YouTube and add a song to the end of the queue.", "query", "Song or artist"))
                .put(tool("control", "Music transport.", "action", "One of: pause, resume, next, previous"))
                .put(tool("set_volume", "Set the music volume.", "level", "0 to 100"))
                .put(tool("add_note", "Show a note on the TV.", "text", "The note"))
                .put(idTool("remove_note", "Delete one note or reminder, picked from notes_and_reminders_current_only by what the user describes.", false))
                .put(idTool("edit_note", "Change the words of one note or reminder (its time stays): fix, update or add to it.", true))
                .put(tool("clear_notes", "Delete every note and reminder. Only when the user clearly asks for all of them to go.", "confirm", "Always 'yes'"))
                .put(tool("get_news", "Fetch the latest news headlines (UK edition). Use for any news, current events or 'what's happening' question; then answer from them.",
                        "topic", "A category (world, UK, business, technology, science, health, sport, entertainment), a search topic, or empty for top stories"))
                .put(tool("set_reminder", "Remind the user at a clock time; it pops up on the TV with a chime. Only when they ask to be reminded.", "when_and_what",
                        "Like: 'at 19:30 to call mum' or 'in 20 minutes to check the oven' or 'tomorrow at 08:00 to take the bins out'"));
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content",
                        "You are Jarvis, the assistant in a TV's Ambient screen (clock, weather, notes, music player). " +
                        "The user spoke to you; your reply is read aloud. Rules: answer the question itself, fully, in one or two short sentences. " +
                        "Do any maths or date arithmetic yourself using the current local time below. Never say you will check, look up or get back later; " +
                        "there is no later. Reply in English, or in Hindi when the user spoke Hindi; never in any other language. Speak like a polished British AI butler: composed, precise, dryly witty, and address the user as sir now and then. No markdown, lists or emoji. " +
                        "Asked to suggest or recommend a song, film or anything to play, don't play it: name your pick in a few words and ask \"Shall I put it on?\"; " +
                        "if they then say yes (or play it), play exactly that. Play straight away only when asked to play, put on or listen to something. " +
                        "If an action is needed, call the tool and still write the spoken reply. Only act when asked to: something the user tells you about their life " +
                        "(plans, people, feelings) is conversation, so just reply. For several songs: play_song for the first, queue_song for the rest (max 8). " +
                        "Current state: " + context +
                        (earlier == null || earlier.isEmpty() ? "" : " Earlier in this conversation: " + earlier) +
                        (recalled == null || recalled.isEmpty() ? "" : " What you remember about the user from other days (use it where it helps; "
                                + "never recite it unprompted): " + recalled)));
        // The recent conversation, so follow-ups like "and tomorrow?" or "another one by him" make sense.
        for (int i = 0; i < past.length(); i++) messages.put(past.get(i));
        messages.put(new JSONObject().put("role", "user").put("content", said));
        JSONObject body = new JSONObject().put("model", CHAT_MODEL).put("messages", messages).put("tools", tools)
                .put("max_completion_tokens", 160).put("reasoning_effort", EFFORT);
        JSONObject res = new JSONObject(new String(post(key, "https://api.openai.com/v1/chat/completions", body, 12000), StandardCharsets.UTF_8));
        JSONObject message = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        return message.put("_messages", messages);
    }

    /** Second turn: the model has the tools' results (news headlines) and writes the spoken answer. */
    static String followUp(Context c, JSONObject first, JSONArray toolResults) throws Exception {
        String key = prefs(c).getString("openai", "");
        JSONArray messages = new JSONArray(first.getJSONArray("_messages").toString());
        JSONObject assistant = new JSONObject(first.toString());
        assistant.remove("_messages");
        messages.put(assistant);
        for (int i = 0; i < toolResults.length(); i++) messages.put(toolResults.get(i));
        JSONObject body = new JSONObject().put("model", CHAT_MODEL).put("messages", messages).put("max_completion_tokens", 160).put("reasoning_effort", EFFORT);
        JSONObject res = new JSONObject(new String(post(key, "https://api.openai.com/v1/chat/completions", body, 12000), StandardCharsets.UTF_8));
        return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
    }

    /** "Song by Artist" for a YouTube title the simple cleaner wasn't sure about. Null without a key. */
    static String songName(Context c, String videoTitle, String channel) {
        String key = prefs(c).getString("openai", "");
        if (key.isEmpty()) return null;
        try {
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content",
                            "Reply with only the song's name and main artist, as: <song> by <artist>. No quotes, nothing else. " +
                            "If the artist is unknown, reply with just the song name."))
                    .put(new JSONObject().put("role", "user").put("content", "YouTube title: " + videoTitle + "\nChannel: " + channel));
            JSONObject body = new JSONObject().put("model", CHAT_MODEL).put("messages", messages).put("max_completion_tokens", 30).put("reasoning_effort", EFFORT);
            JSONObject res = new JSONObject(new String(post(key, "https://api.openai.com/v1/chat/completions", body, 5000), StandardCharsets.UTF_8));
            String s = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            return s.isEmpty() || s.length() > 80 ? null : s.replaceAll("^[\"']|[\"'.]$", "");
        } catch (Exception e) {
            return null;
        }
    }

    /** Folds older conversation turns into a short running summary. Null without a key. */
    static String summarise(Context c, String prior, java.util.List<JSONObject> turns) {
        String key = prefs(c).getString("openai", "");
        if (key.isEmpty()) return null;
        try {
            StringBuilder t = new StringBuilder();
            for (JSONObject m : turns) t.append(m.optString("role")).append(": ").append(m.optString("content")).append('\n');
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content",
                            "Summarise this conversation between a user and Jarvis (a TV assistant) in at most 60 words: " +
                            "what the user asked for, preferences they showed, and anything to remember. Merge with the earlier summary."))
                    .put(new JSONObject().put("role", "user").put("content", "Earlier summary: " + (prior == null ? "" : prior) + "\nNew turns:\n" + t));
            JSONObject body = new JSONObject().put("model", CHAT_MODEL).put("messages", messages).put("max_completion_tokens", 120).put("reasoning_effort", EFFORT);
            JSONObject res = new JSONObject(new String(post(key, "https://api.openai.com/v1/chat/completions", body, 10000), StandardCharsets.UTF_8));
            String s = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** A short spoken news briefing from headlines. Null without a key. */
    static String briefing(Context c, String topic, JSONArray headlines) throws Exception {
        String key = prefs(c).getString("openai", "");
        if (key.isEmpty()) return null;
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content",
                        "You are Jarvis reading the news aloud. From these headlines, give a natural spoken briefing of the three or four most important stories, " +
                        "in two to four short sentences. British English. No lists, markdown or source names unless needed. Don't invent details beyond the headlines."))
                .put(new JSONObject().put("role", "user").put("content", (topic == null || topic.isEmpty() ? "Top stories" : "News about " + topic) + ":\n" + headlines.toString()));
        JSONObject body = new JSONObject().put("model", CHAT_MODEL).put("messages", messages).put("max_completion_tokens", 200).put("reasoning_effort", EFFORT);
        JSONObject res = new JSONObject(new String(post(key, "https://api.openai.com/v1/chat/completions", body, 12000), StandardCharsets.UTF_8));
        return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
    }

    /** What was said in a short recording from the phone, or null. Recordings are a few seconds; about $0.003 a minute. */
    static String transcribe(Context c, byte[] audio, String mime) {
        String key = prefs(c).getString("openai", "");
        if (key.isEmpty() || audio == null || audio.length == 0) return null;
        try {
            String ext = mime != null && mime.contains("mp4") ? "m4a" : mime != null && mime.contains("ogg") ? "ogg" : "webm";
            String boundary = "----jarvis" + System.nanoTime();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            // English or Hindi (often mixed); the prompt keeps it to those, and stops it inventing words for silence.
            String hint = "The speaker talks to Jarvis, a TV assistant, in English or Hindi (often mixed, Hinglish). Transcribe only what is said; if nothing is said, return nothing.";
            String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-4o-mini-transcribe\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n" + hint + "\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech." + ext + "\"\r\n"
                    + "Content-Type: " + (mime == null || mime.isEmpty() ? "audio/webm" : mime.split(";")[0]) + "\r\n\r\n";
            body.write(head.getBytes(StandardCharsets.UTF_8));
            body.write(audio);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/transcriptions").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream o = conn.getOutputStream()) {
                body.writeTo(o);
            }
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            String t = new JSONObject(buf.toString("UTF-8")).optString("text", "").trim();
            return heardForReal(t) ? t : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * False for what a transcriber writes when it hears only noise or the TV: another language's script
     * (Japanese and Chinese stock phrases are the usual ones), or the filler lines it invents for silence.
     */
    static boolean heardForReal(String t) {
        if (t == null || t.trim().length() < 2) return false;
        String letters = t.replaceAll("[\\s\\p{P}\\p{S}\\p{N}]", "");
        if (letters.isEmpty()) return false;
        // Only Latin (English, Hinglish) and Devanagari (Hindi) count as speech.
        if (!letters.replaceAll("[\\p{IsLatin}\\p{IsDevanagari}]", "").isEmpty()) return false;
        String l = t.toLowerCase(java.util.Locale.ROOT);
        return !l.matches(".*\\b(thanks for watching|thank you for watching|subscribe to|like and subscribe|subtitles by|transcribed by)\\b.*")
                && !l.matches("^\\W*(you|thank you|bye|okay|hmm+|uh+|um+)\\W*$");
    }

    // ---- Long-term memory (JarvisMemory): embeddings to find the few memories a question needs, and a
    // distiller that turns a finished conversation into memories.

    private static final String EMBED_MODEL = "text-embedding-3-small";
    static final int EMBED_DIMS = 256;   // plenty to match short sentences; a quarter of the storage of the full size

    /** Unit-length embeddings for some short texts, in order; null without a key or on failure. */
    static float[][] embed(Context c, java.util.List<String> texts, int timeout) {
        String key = prefs(c).getString("openai", "");
        if (key.isEmpty() || texts.isEmpty()) return null;
        try {
            JSONArray input = new JSONArray();
            for (String t : texts) input.put(t);
            JSONObject body = new JSONObject().put("model", EMBED_MODEL).put("input", input).put("dimensions", EMBED_DIMS);
            JSONArray data = new JSONObject(new String(post(key, "https://api.openai.com/v1/embeddings", body, timeout), StandardCharsets.UTF_8))
                    .getJSONArray("data");
            float[][] out = new float[texts.size()][];
            for (int i = 0; i < data.length(); i++) {
                JSONObject d = data.getJSONObject(i);
                JSONArray e = d.getJSONArray("embedding");
                float[] v = new float[e.length()];
                double norm = 0;
                for (int k = 0; k < v.length; k++) { v[k] = (float) e.getDouble(k); norm += v[k] * v[k]; }
                float inv = (float) (1 / Math.sqrt(Math.max(norm, 1e-12)));
                for (int k = 0; k < v.length; k++) v[k] *= inv;
                out[d.optInt("index", i)] = v;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What a finished conversation taught about the user, as changes to the memories it touches:
     * {"add": [text], "update": [{"id", "text"}], "delete": [id]}. Null without a key.
     */
    static JSONObject distil(Context c, String conversation, JSONArray related) throws Exception {
        String key = prefs(c).getString("openai", "");
        if (key.isEmpty()) return null;
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content",
                        "You keep the long-term memory of Jarvis, a TV assistant, about the person who uses it. From the conversation, "
                        + "keep only lasting things worth knowing on another day: their name, family and friends, preferences and tastes "
                        + "(music, films, food, temperature), routines, plans and dates, and things they explicitly ask to be remembered. "
                        + "Skip one-off requests, the weather, the time, small talk, and anything about Jarvis itself. "
                        + "Each memory is one short third-person sentence, e.g. \"Likes Coldplay, especially in the evening.\" "
                        + "Compare with the related memories: update one that changed, delete one that is now wrong, and never add a duplicate. "
                        + "Write dates as real dates (today is " + new java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.UK).format(new java.util.Date()) + "), never \"next Saturday\". "
                        + "Reply with JSON only: {\"add\": [text], \"update\": [{\"id\": id, \"text\": text}], \"delete\": [id]}. "
                        + "Usually there is nothing to keep: then all three lists are empty."))
                .put(new JSONObject().put("role", "user").put("content",
                        "Related memories: " + related + "\n\nConversation:\n" + conversation));
        JSONObject body = new JSONObject().put("model", CHAT_MODEL).put("messages", messages).put("max_completion_tokens", 300)
                .put("reasoning_effort", EFFORT).put("response_format", new JSONObject().put("type", "json_object"));
        JSONObject res = new JSONObject(new String(post(key, "https://api.openai.com/v1/chat/completions", body, 20000), StandardCharsets.UTF_8));
        return new JSONObject(res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "{}"));
    }

    /** A tool that names a note by its id, and for an edit also takes the new words. */
    private static JSONObject idTool(String name, String what, boolean withText) throws Exception {
        JSONObject props = new JSONObject().put("id", new JSONObject().put("type", "integer").put("description", "The note's id"));
        JSONArray required = new JSONArray().put("id");
        if (withText) {
            props.put("text", new JSONObject().put("type", "string").put("description", "The note's full new text"));
            required.put("text");
        }
        JSONObject params = new JSONObject().put("type", "object").put("properties", props).put("required", required);
        return new JSONObject().put("type", "function")
                .put("function", new JSONObject().put("name", name).put("description", what).put("parameters", params));
    }

    private static JSONObject tool(String name, String what, String arg, String argWhat) throws Exception {
        JSONObject params = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put(arg, new JSONObject().put("type", "string").put("description", argWhat)))
                .put("required", new JSONArray().put(arg));
        return new JSONObject().put("type", "function")
                .put("function", new JSONObject().put("name", name).put("description", what).put("parameters", params));
    }

    private static byte[] post(String key, String url, JSONObject body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(timeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream s = in) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = s.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
        }
        if (code != 200) throw new Exception("OpenAI " + code + ": " + buf.toString("UTF-8"));
        return buf.toByteArray();
    }
}
