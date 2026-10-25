package com.kartikmodi.halo.home;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Ambient screen's small web server, on port 8765 of the TV's Wi-Fi address.
 *
 * A phone on the same network opens "/" to search YouTube and queue songs; the Ambient screen (a WebView
 * on this same server) plays them. The two never talk directly: the phone posts commands, the TV page
 * collects them on its next sync and reports its state back, and the phone reads that state.
 *
 * Runs in the launcher's process, which HomeKeyService keeps alive, and starts once per process.
 */
final class RemoteServer {

    static final int PORT = 8765;
    private static final String TAG = "RemoteServer";
    private static RemoteServer instance;

    private final Context app;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    /** Commands from phones, waiting for the TV page's next sync. */
    private final List<JSONObject> pending = new ArrayList<>();
    /** The TV page's last reported state: now playing, queue, notes, volume. */
    private JSONObject tvState = new JSONObject();
    private long lastTvSync;

    static synchronized void ensureStarted(Context context) {
        if (instance != null) return;
        instance = new RemoteServer(context.getApplicationContext());
        new Thread(instance::serve, "RemoteServer").start();
        JarvisLink.ensureStarted(context, instance);
    }

    private RemoteServer(Context app) {
        this.app = app;
    }

    private void serve() {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            while (true) {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            }
        } catch (IOException e) {
            Log.w(TAG, "server stopped", e);
            synchronized (RemoteServer.class) { instance = null; }
        }
    }

    // ---- HTTP plumbing: just enough HTTP/1.1 for one request per connection.

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            byte[] body = new byte[Math.min(contentLength, 64 * 1024)];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            String path = target;
            Map<String, String> query = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String pair : target.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }
            route(socket.getOutputStream(), method, path, query, new String(body, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) break;
        }
        if (c < 0 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static void sendJson(OutputStream out, Object json) throws IOException {
        send(out, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- Routes

    private void route(OutputStream out, String method, String path, Map<String, String> query, String body)
            throws IOException, JSONException {
        switch (path) {
            case "/":
                serveAsset(out, "ambient/phone.html", "text/html; charset=utf-8");
                return;
            // Home-screen app: manifest and icons for the phone page.
            case "/manifest.webmanifest":
                serveAsset(out, "ambient/manifest.webmanifest", "application/manifest+json");
                return;
            case "/favicon.ico":
            case "/favicon.png":
                serveAsset(out, "ambient/favicon.png", "image/png");
                return;
            case "/apple-touch-icon.png":
            case "/apple-touch-icon-precomposed.png":
            case "/icon-192.png":
            case "/icon-512.png":
            case "/icon-maskable-512.png":
                serveAsset(out, "ambient" + path, "image/png");
                return;
            case "/api/search":
                sendJson(out, search(query.containsKey("q") ? query.get("q") : ""));
                return;
            case "/api/suggest":
                sendJson(out, suggest(query.containsKey("q") ? query.get("q") : ""));
                return;
            case "/api/mictest":   // temporary: microphone test for the voice assistant
                sendJson(out, MicTest.run(app, query));
                return;
            case "/api/mictest.wav":
                java.io.File wav = MicTest.lastWav(app);
                if (!wav.exists()) break;
                send(out, 200, "audio/wav", java.nio.file.Files.readAllBytes(wav.toPath()));
                return;
            // Jarvis: the relay's address and key, set once from the computer that deployed it.
            case "/api/jarvis/setup":
                if (!"POST".equals(method)) break;
                JSONObject cfg = new JSONObject(body);
                JarvisLink.configure(app, cfg.getString("url"), cfg.getString("key"));
                sendJson(out, JarvisLink.status(app));
                return;
            case "/api/jarvis/openai":   // the key goes in, never out
                if (!"POST".equals(method)) break;
                OpenAi.setKey(app, new JSONObject(body).optString("key"));
                sendJson(out, new JSONObject().put("openai", OpenAi.configured(app)));
                return;
            case "/api/jarvis/status":
                sendJson(out, JarvisLink.status(app));
                return;
            case "/api/jarvis/ask":   // the phone's Jarvis chat
                if (!"POST".equals(method)) break;
                try {
                    String said = new JSONObject(body).optString("text");
                    sendJson(out, new JSONObject().put("reply", JarvisLink.tryLocally(said)).put("spoken", ambientOnline()));
                } catch (Exception e) {
                    sendJson(out, new JSONObject().put("reply", "Sorry, something went wrong doing that."));
                }
                return;
            case "/api/jarvis/test":   // try a sentence without Alexa: /api/jarvis/test?q=play cheques
                try {
                    sendJson(out, new JSONObject().put("reply", JarvisLink.tryLocally(query.containsKey("q") ? query.get("q") : "")));
                } catch (Exception e) {
                    sendJson(out, new JSONObject().put("error", String.valueOf(e)));
                }
                return;
            // "Talk to Jarvis" on a phone: its link (GET), or its key, set once from the computer that deployed the relay (POST).
            case "/api/jarvis/talk":
                if ("POST".equals(method)) JarvisLink.setPhoneKey(app, new JSONObject(body).optString("key"));
                sendJson(out, new JSONObject().put("url", JarvisLink.talkLink(app)));
                return;
            // Jarvis's long-term memory: the phone lists it and can delete one memory or all of them.
            case "/api/jarvis/memory": {
                JarvisMemory memory = JarvisMemory.get(app);
                if ("POST".equals(method)) {
                    JSONObject req = new JSONObject(body.isEmpty() ? "{}" : body);
                    if (req.optBoolean("clear")) memory.clear();
                    else if (req.has("delete")) memory.delete(req.optLong("delete"));
                }
                sendJson(out, new JSONObject().put("memories", memory.list()));
                return;
            }
            case "/api/command":
                if (!"POST".equals(method)) break;
                command(new JSONObject(body));
                sendJson(out, new JSONObject().put("ok", true));
                return;
            case "/api/state":
                sendJson(out, phoneState());
                return;
            case "/api/tv/sync":
                if (!"POST".equals(method)) break;
                sendJson(out, tvSync(body));
                return;
            default:
                // The Ambient screen's pictures and its blob script; only plain names from assets/ambient.
                if (path.matches("/ambient/[a-z_]+\\.(webp|jpg|js)")) {
                    String ext = path.substring(path.lastIndexOf('.') + 1);
                    serveAsset(out, path.substring(1), "js".equals(ext) ? "text/javascript; charset=utf-8" : "jpg".equals(ext) ? "image/jpeg" : "image/webp");
                    return;
                }
                break;
        }
        send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
    }

    private void serveAsset(OutputStream out, String name, String type) throws IOException {
        AssetManager assets = app.getAssets();
        try (InputStream in = assets.open(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            send(out, 200, type, buf.toByteArray());
        }
    }

    /** A phone's command. Playing something opens the Ambient screen if it isn't up. */
    void command(JSONObject cmd) {
        synchronized (pending) {
            pending.add(cmd);
        }
        String type = cmd.optString("type");
        boolean wantsScreen = type.equals("play") || type.equals("enqueue");
        if (wantsScreen && System.currentTimeMillis() - lastTvSync > 3000) openAmbient();
    }

    /** Whether the Ambient screen is open and in touch: it can then show and speak Jarvis's answers. */
    boolean ambientOnline() {
        return System.currentTimeMillis() - lastTvSync < 4000;
    }

    void openAmbientScreen() {
        openAmbient();
    }

    /** The Ambient screen's last reported state (queue, volume, notes, weather), for Jarvis. */
    synchronized JSONObject state() throws JSONException {
        return new JSONObject(tvState.toString());
    }

    private void openAmbient() {
        try {
            app.startActivity(new Intent(app, AmbientActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the Ambient screen", e);
        }
    }

    private JSONObject phoneState() throws JSONException {
        JSONObject state;
        synchronized (this) {
            state = new JSONObject(tvState.toString());
        }
        state.put("tvOnline", System.currentTimeMillis() - lastTvSync < 4000);
        state.put("openai", OpenAi.configured(app));
        state.remove("voice");
        return state;
    }

    /** The TV page reports its state and collects the phones' commands. */
    private JSONObject tvSync(String body) throws JSONException {
        synchronized (this) {
            if (!body.isEmpty()) tvState = new JSONObject(body);
            lastTvSync = System.currentTimeMillis();
        }
        JSONArray commands = new JSONArray();
        synchronized (pending) {
            for (JSONObject c : pending) commands.put(c);
            pending.clear();
        }
        return new JSONObject().put("commands", commands);
    }

    // ---- Search-as-you-type suggestions from YouTube's own suggestion service; no API key.

    private static JSONObject suggest(String q) throws JSONException {
        JSONArray out = new JSONArray();
        if (q.trim().isEmpty()) return new JSONObject().put("suggestions", out);
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&hl=en&gl=gb&q="
                            + java.net.URLEncoder.encode(q, "UTF-8")).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            // The answer is JSONP: window.google.ac.h([query, [[suggestion, ...], ...], {...}])
            String body = buf.toString("UTF-8");
            int open = body.indexOf('('), close = body.lastIndexOf(')');
            if (open < 0 || close <= open) return new JSONObject().put("suggestions", out);
            JSONArray items = new JSONArray(body.substring(open + 1, close)).getJSONArray(1);
            for (int i = 0; i < items.length() && out.length() < 8; i++) out.put(items.getJSONArray(i).getString(0));
        } catch (IOException e) {
            // No suggestions is fine; the search itself still works.
        }
        return new JSONObject().put("suggestions", out);
    }

    // ---- YouTube search through the same endpoint youtube.com's own search page uses; no API key.

    static JSONObject search(String q) throws JSONException {
        JSONArray results = new JSONArray();
        if (q.trim().isEmpty()) return new JSONObject().put("results", results);
        try {
            JSONObject client = new JSONObject()
                    .put("clientName", "WEB").put("clientVersion", "2.20250101.00.00").put("hl", "en").put("gl", "GB");
            JSONObject req = new JSONObject()
                    .put("context", new JSONObject().put("client", client))
                    .put("query", q)
                    .put("params", "EgIQAQ%3D%3D");   // videos only
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Cookie", "SOCS=CAI");
            try (OutputStream o = c.getOutputStream()) {
                o.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            collectVideos(new JSONObject(buf.toString("UTF-8")), results);
        } catch (IOException e) {
            return new JSONObject().put("results", results).put("error", "YouTube search failed: " + e.getMessage());
        }
        return new JSONObject().put("results", results);
    }

    private static void collectVideos(Object node, JSONArray out) throws JSONException {
        if (out.length() >= 25) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject v = o.optJSONObject("videoRenderer");
            if (v != null && v.has("videoId")) {
                String id = v.getString("videoId");
                String videoTitle = firstRun(v.optJSONObject("title")), channel = firstRun(v.optJSONObject("ownerText"));
                String[] name = SongName.parse(videoTitle, channel);
                out.put(new JSONObject()
                        .put("id", id)
                        .put("title", name[0])
                        .put("artist", name[1].isEmpty() ? channel : name[1])
                        .put("spoken", SongName.spoken(name[0], name[1]))
                        // Label uploads ("Song - Film | Cast") don't follow "Artist - Song"; let the AI name those.
                        .put("sure", !name[1].isEmpty() && !SongName.messy(name[0]) && !SongName.labelChannel(channel))
                        .put("videoTitle", videoTitle)
                        .put("channel", channel)
                        .put("duration", v.has("lengthText") ? v.getJSONObject("lengthText").optString("simpleText") : "")
                        .put("thumb", "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"));
                return;
            }
            JSONArray names = o.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) collectVideos(o.get(names.getString(i)), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectVideos(a.get(i), out);
        }
    }

    private static String firstRun(JSONObject text) {
        if (text == null) return "";
        JSONArray runs = text.optJSONArray("runs");
        if (runs != null && runs.length() > 0) return runs.optJSONObject(0).optString("text");
        return text.optString("simpleText");
    }
}
