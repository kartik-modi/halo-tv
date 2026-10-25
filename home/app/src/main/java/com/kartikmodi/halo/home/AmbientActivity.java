package com.kartikmodi.halo.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/**
 * The always-on Ambient screen: clock, weather, notes and music sent from a phone. The screen itself
 * is assets/ambient/tv.html, served by RemoteServer so YouTube's embedded player sees a real origin.
 * The screen stays on while it is open.
 */
public class AmbientActivity extends Activity {

    private WebView web;
    private TurntableView deck;
    private BlobView blob;
    private TextToSpeech tts;
    private boolean ttsReady;
    private int langResult = 99;
    private String voiceEngine;
    /** The open Ambient screen, for HomeKeyService to say when Alexa starts listening. */
    private static AmbientActivity current;
    /** When the Ambient screen last went into the background, so it can be brought back after Alexa. */
    static volatile long pausedAt;

    static boolean alive() {
        return current != null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pausedAt = 0;
    }

    // Alexa's listening overlay pauses this screen while it stays in view, and that is exactly when Jarvis
    // should look alive: the blob only stops when the screen is hidden.
    @Override
    protected void onStart() {
        super.onStart();
        if (blob != null) blob.onResume();
    }

    @Override
    protected void onStop() {
        if (blob != null) blob.onPause();
        super.onStop();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RemoteServer.ensureStarted(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // The turntable and Jarvis move all the time, so they are drawn natively over the page, where it says.
        deck = new TurntableView(this);
        blob = new BlobView(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(deck, new FrameLayout.LayoutParams(1, 1));
        root.addView(blob, new FrameLayout.LayoutParams(1, 1));
        deck.setVisibility(View.INVISIBLE);
        blob.setVisibility(View.INVISIBLE);
        web.addJavascriptInterface(new Bridge(), "AmbientNative");
        startVoice();
        current = this;
        setContentView(root);
        // YouTube refuses most music in an embedded player on a page it can't place (127.0.0.1 counts),
        // so the page gets a regular https address and talks to RemoteServer by its absolute address.
        String base = getIntent().getStringExtra("base");
        if (base == null) base = PAGE_BASE;
        String ip = wifiAddress();
        String config = "window.AMBIENT_CONFIG={api:'http://127.0.0.1:" + RemoteServer.PORT + "',ip:'" + (ip != null ? ip : "") + "'};";
        // Its pictures come from RemoteServer; the page's own address stays the https one above.
        String assets = "<base href=\"http://127.0.0.1:" + RemoteServer.PORT + "/ambient/\">";
        web.loadDataWithBaseURL(base, readAsset("ambient/tv.html").replace("/*AMBIENT_CONFIG*/", config)
                .replace("<!--AMBIENT_BASE-->", assets), "text/html", "utf-8", null);
    }

    private static final String PAGE_BASE = "https://kartik-modi.github.io/halo-tv/ambient/";

    private String readAsset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        } catch (java.io.IOException e) {
            return "<body style=background:#000;color:#fff>Ambient page missing</body>";
        }
    }

    /** Media and colour keys never reach the page on their own; hand them to it by name. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String name = null;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                name = "playpause";
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                name = "next";
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                name = "prev";
                break;
            default:
                break;
        }
        if (name == null) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) web.evaluateJavascript("window.remoteKey('" + name + "')", null);
        return true;
    }

    // ---- Jarvis's voice: the TV's own speech engine (Amazon's IVONA), word by word to the page so the
    // sun or moon can move its mouth.

    private void startVoice() {
        startVoice("com.ivona.tts.oem");
    }

    /** IVONA ships without voices on some TVs and then speaks silently; Pico always has UK English. */
    private void startVoice(String engine) {
        TextToSpeech.OnInitListener ready = status -> {
            if (status != TextToSpeech.SUCCESS) {
                if (!"com.svox.pico".equals(engine)) { tts.shutdown(); startVoice("com.svox.pico"); }
                return;
            }
            langResult = tts.setLanguage(Locale.UK);
            voiceEngine = engine;
            if (langResult < TextToSpeech.LANG_AVAILABLE && !"com.svox.pico".equals(engine)) {
                tts.shutdown();
                startVoice("com.svox.pico");
                return;
            }
            tts.setSpeechRate(1.0f);
            // Speech is rendered to a file and played here, so its loudness can move the mouth.
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { play(speechFile(".wav")); }
                @Override public void onError(String id) { toPage("done"); }
            });
            ttsReady = true;
        };
        tts = new TextToSpeech(this, ready, engine);
    }

    private final java.util.concurrent.ExecutorService voiceThread = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile long voiceTurn;

    // What is playing now: either a streamed OpenAI voice (track) or a file from the TV's voice (voice).
    private AudioTrack track;
    private MediaPlayer voice;
    private Visualizer meter;
    /** Loudness of each 50 ms of the streamed voice, so the mouth matches what is heard, not what was received. */
    private final java.util.List<Float> levels = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile long streamedFrames;
    private volatile boolean streamDone;
    private volatile double meterLevel, bright = 0.5;

    private static final int PCM_RATE = 24000;           // OpenAI's raw PCM: 24 kHz, 16-bit, mono
    private static final int FRAMES_PER_LEVEL = PCM_RATE / 20;

    private File speechFile(String ext) {
        return new File(getCacheDir(), "jarvis" + ext);
    }

    /**
     * Streams OpenAI's voice and plays it as it arrives, so speech starts within moments instead of after
     * the whole answer has downloaded. Returns false if it couldn't start.
     */
    private boolean streamOpenAi(String text, long turn) {
        java.io.InputStream in = OpenAi.speechStream(this, text);
        if (in == null) return false;
        AudioTrack t = null;
        try (java.io.InputStream s = in) {
            int min = AudioTrack.getMinBufferSize(PCM_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(PCM_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, PCM_RATE / 2))   // about a quarter second
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            levels.clear();
            streamedFrames = 0;
            streamDone = false;
            byte[] buf = new byte[4800];
            byte[] carry = new byte[1];
            boolean odd = false, started = false;
            double sum = 0;
            int inBlock = 0;
            int n;
            while ((n = s.read(buf)) > 0) {
                if (turn != voiceTurn) return true;   // a newer answer took over
                // Keep whole 16-bit samples.
                byte[] chunk = buf;
                int len = n;
                if (odd) {
                    chunk = new byte[n + 1];
                    chunk[0] = carry[0];
                    System.arraycopy(buf, 0, chunk, 1, n);
                    len = n + 1;
                }
                odd = (len % 2) == 1;
                if (odd) { carry[0] = chunk[len - 1]; len--; }
                for (int i = 0; i + 1 < len; i += 2) {
                    int x = (short) ((chunk[i] & 0xff) | (chunk[i + 1] << 8));
                    sum += (double) x * x;
                    if (++inBlock == FRAMES_PER_LEVEL) {
                        levels.add((float) Math.min(1.0, Math.sqrt(sum / inBlock) / 7000.0));
                        sum = 0;
                        inBlock = 0;
                    }
                }
                if (!started) {
                    final AudioTrack playing = t;
                    stopFilePlayer();
                    track = playing;
                    t.play();
                    started = true;
                    toPage("start");
                    runOnUiThread(this::tick);
                }
                t.write(chunk, 0, len);   // blocks while the buffer is full, so this keeps pace with playback
                streamedFrames += len / 2;
            }
            streamDone = true;
            return started;
        } catch (Exception e) {
            if (t != null && track != t) t.release();
            return false;
        }
    }

    /** Plays a spoken answer from a file (the TV's own voice) and measures its loudness. */
    private void play(File f) {
        runOnUiThread(() -> {
            stopVoice();
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                mp.setDataSource(f.getAbsolutePath());
                mp.prepare();
                voice = mp;
                try {
                    meter = new Visualizer(mp.getAudioSessionId());
                    meter.setCaptureSize(Visualizer.getCaptureSizeRange()[0]);
                    meter.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                        @Override public void onWaveFormDataCapture(Visualizer v, byte[] wave, int rate) {
                            double sum = 0;
                            for (byte b : wave) { int x = (b & 0xff) - 128; sum += x * x; }
                            meterLevel = Math.min(1.0, Math.sqrt(sum / wave.length) / 40.0);
                        }
                        // Share of energy in the upper bands: high for "ee" and "s", low for "oh" and "oo".
                        @Override public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                            int bins = fft.length / 2;
                            double low = 0, high = 0;
                            for (int k = 1; k < bins; k++) {
                                double re = fft[2 * k], im = fft[2 * k + 1], m = re * re + im * im;
                                if (k < bins / 8) low += m; else high += m;
                            }
                            double total = low + high;
                            if (total > 0) bright = 0.7 * bright + 0.3 * Math.min(1.0, high / total * 2.5);
                        }
                    }, Visualizer.getMaxCaptureRate(), true, true);
                    meter.setEnabled(true);
                } catch (RuntimeException e) {
                    meter = null;
                    toPage("flap");   // no meter on this TV: the page moves the mouth by itself
                }
                mp.setOnCompletionListener(m -> { stopVoice(); toPage("done"); });
                mp.start();
                toPage("start");
                tick();
            } catch (Exception e) {
                stopVoice();
                toPage("done");
            }
        });
    }

    /** 20 times a second while speaking: how far the voice has played, how long it is, and how loud it is now. */
    private void tick() {
        double ms, total = -1, level;
        if (track != null) {
            long played = track.getPlaybackHeadPosition() & 0xffffffffL;
            ms = played * 1000.0 / PCM_RATE;
            int i = (int) (played / FRAMES_PER_LEVEL);
            level = i < levels.size() ? levels.get(i) : 0;
            if (streamDone) {
                total = streamedFrames * 1000.0 / PCM_RATE;
                if (played >= streamedFrames - 200) { stopVoice(); toPage("done"); return; }
            }
        } else if (voice != null) {
            try {
                ms = voice.getCurrentPosition();
                total = voice.getDuration();
            } catch (IllegalStateException e) {
                return;
            }
            level = meterLevel;
        } else {
            return;
        }
        if (web != null) web.evaluateJavascript(String.format(Locale.US,
                "window.jarvisProgress && jarvisProgress(%.0f,%.0f,%.2f,%.2f)", ms, total, level, bright), null);
        ticker.postDelayed(this::tick, 50);
    }

    private void stopFilePlayer() {
        runOnUiThread(() -> {
            if (meter != null) { try { meter.setEnabled(false); meter.release(); } catch (RuntimeException ignored) { } meter = null; }
            if (voice != null) { try { voice.release(); } catch (RuntimeException ignored) { } voice = null; }
        });
    }

    private void stopVoice() {
        ticker.removeCallbacksAndMessages(null);
        if (meter != null) { try { meter.setEnabled(false); meter.release(); } catch (RuntimeException ignored) { } meter = null; }
        if (voice != null) { try { voice.release(); } catch (RuntimeException ignored) { } voice = null; }
        if (track != null) { try { track.pause(); track.flush(); track.release(); } catch (RuntimeException ignored) { } track = null; }
    }

    /** When Jarvis's voice last started and last finished (ms), so a phone's follow-up waits until it is quiet. */
    static volatile long voiceStartedAt, voiceEndedAt;

    private void toPage(String event) {
        long now = System.currentTimeMillis();
        if ("start".equals(event)) voiceStartedAt = now;
        else if ("done".equals(event)) voiceEndedAt = now;
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.jarvisVoice && jarvisVoice('" + event + "')", null); });
    }

    /** From HomeKeyService: Alexa's listening screen opened (the mic button is held) or closed. */
    static void alexaListening(boolean on) {
        AmbientActivity a = current;
        if (a == null || a.web == null) return;
        a.runOnUiThread(() -> a.web.evaluateJavascript("window.jarvisListening && jarvisListening(" + on + ")", null));
    }

    /** Puts a native view over a box the page gives in CSS pixels. */
    private void place(View v, float x, float y, float w, float h) {
        runOnUiThread(() -> {
            float k = getResources().getDisplayMetrics().density;
            int pw = Math.round(w * k), ph = Math.round(h * k);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            if (lp.width != pw || lp.height != ph) {
                lp.width = pw;
                lp.height = ph;
                v.setLayoutParams(lp);
            }
            v.setX(Math.round(x * k));
            v.setY(Math.round(y * k));
            v.setVisibility(View.VISIBLE);
        });
    }

    /** Calls from the page (its own thread): where the turntable and Jarvis sit, and what they are doing. */
    private final class Bridge {
        @JavascriptInterface
        public void discArt(String url) {
            deck.setArt(url);
        }

        @JavascriptInterface
        public void deckRect(float x, float y, float w, float h) {
            place(deck, x, y, w, h);
        }

        /** Playing or paused, and how far through the song (0..1). */
        @JavascriptInterface
        public void deckState(boolean playing, float progress) {
            deck.setState(playing, progress);
        }

        @JavascriptInterface
        public void blobRect(float x, float y, float size) {
            place(blob, x, y, size, size);
        }

        /** idle, listening, thinking or speaking. */
        @JavascriptInterface
        public void blobMode(String mode) {
            blob.setMode(mode);
        }

        /** The voice's loudness, 0..1, while Jarvis speaks. */
        @JavascriptInterface
        public void blobLevel(float level) {
            blob.setLevel(level);
        }

        /** Says a Jarvis answer; the page hears start, word and done. Returns false if there is no voice. */
        /** Diagnostics: engine, language result, voices. */
        @JavascriptInterface
        public String voiceInfo() {
            if (tts == null) return "no tts";
            StringBuilder b = new StringBuilder("engine=" + voiceEngine + " ready=" + ttsReady + " lang=" + langResult);
            try {
                for (android.speech.tts.TextToSpeech.EngineInfo e : tts.getEngines()) b.append(" | engine ").append(e.name);
                if (tts.getVoices() != null) for (android.speech.tts.Voice v : tts.getVoices()) b.append(" | ").append(v.getName()).append(v.isNetworkConnectionRequired() ? "(net)" : "").append(v.getFeatures() != null && v.getFeatures().contains("notInstalled") ? "(notInstalled)" : "");
                if (tts.getVoice() != null) b.append(" | current=").append(tts.getVoice().getName());
            } catch (Exception e) { b.append(" err=").append(e); }
            return b.toString();
        }

        /** Says a Jarvis answer: OpenAI's voice when a key is saved, else the TV's own. */
        @JavascriptInterface
        public boolean speak(String text) {
            final long turn = ++voiceTurn;
            runOnUiThread(AmbientActivity.this::stopVoice);
            if (tts != null) tts.stop();
            voiceThread.execute(() -> {
                if (turn != voiceTurn) return;   // a newer answer came in while this one waited
                if (OpenAi.configured(AmbientActivity.this) && streamOpenAi(text, turn)) return;
                if (turn != voiceTurn) return;
                if (OpenAi.configured(AmbientActivity.this)) Log.w("Jarvis", "OpenAI voice failed; using the TV voice");
                if (!ttsReady) { toPage("flap"); return; }
                tts.synthesizeToFile(text, null, speechFile(".wav"), "jarvis" + System.currentTimeMillis());
            });
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        if (tts != null) tts.shutdown();
        stopVoice();
        voiceThread.shutdownNow();
        if (deck != null) deck.release();
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }

    private static String wifiAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
