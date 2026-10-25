package com.kartikmodi.halo.home;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * Temporary: records a few seconds from one of the TV's audio sources and reports how loud it heard
 * the room, in dBFS per quarter second, to find out whether a voice assistant can use the built-in
 * microphone. The last recording is kept as a WAV file to listen to.
 */
final class MicTest {

    private MicTest() {
    }

    static File lastWav(Context c) {
        return new File(c.getCacheDir(), "mictest.wav");
    }

    static JSONObject run(Context c, Map<String, String> q) throws JSONException {
        String name = q.containsKey("src") ? q.get("src") : "VOICE_RECOGNITION";
        int secs = q.containsKey("secs") ? Math.max(1, Math.min(15, Integer.parseInt(q.get("secs")))) : 5;
        int channels = q.containsKey("ch") ? Integer.parseInt(q.get("ch")) : 1;
        int rate = q.containsKey("rate") ? Integer.parseInt(q.get("rate")) : 16000;
        int source;
        switch (name) {
            case "MIC": source = MediaRecorder.AudioSource.MIC; break;
            case "VOICE_COMMUNICATION": source = MediaRecorder.AudioSource.VOICE_COMMUNICATION; break;
            case "UNPROCESSED": source = MediaRecorder.AudioSource.UNPROCESSED; break;
            case "CAMCORDER": source = MediaRecorder.AudioSource.CAMCORDER; break;
            default: name = "VOICE_RECOGNITION"; source = MediaRecorder.AudioSource.VOICE_RECOGNITION; break;
        }
        int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        JSONObject out = new JSONObject().put("source", name).put("rate", rate).put("channels", channels).put("secs", secs);
        int min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return out.put("ok", false).put("error", "format not supported (" + min + ")");
        AudioRecord rec;
        try {
            rec = new AudioRecord(source, rate, mask, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate * channels));
        } catch (Exception e) {
            return out.put("ok", false).put("error", e.toString());
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            return out.put("ok", false).put("error", "could not open the microphone (permission, or in use)");
        }
        short[] all = new short[rate * channels * secs];
        int got = 0;
        try {
            rec.startRecording();
            out.put("recordingState", rec.getRecordingState());
            while (got < all.length) {
                int n = rec.read(all, got, Math.min(rate * channels / 10, all.length - got));
                if (n <= 0) { out.put("readError", n); break; }
                got += n;
            }
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
        }
        // Loudness per quarter second, and overall.
        int chunk = rate * channels / 4;
        JSONArray db = new JSONArray();
        int peak = 0;
        for (int i = 0; i + chunk <= got; i += chunk) {
            double sum = 0;
            for (int k = i; k < i + chunk; k++) { sum += (double) all[k] * all[k]; peak = Math.max(peak, Math.abs(all[k])); }
            double rms = Math.sqrt(sum / chunk);
            db.put(Math.round(rms <= 0 ? -120 : 20 * Math.log10(rms / 32768.0) * 10) / 10.0);
        }
        out.put("ok", true).put("samples", got).put("dbfs", db)
                .put("peakDbfs", peak <= 0 ? -120 : Math.round(20 * Math.log10(peak / 32768.0) * 10) / 10.0);
        try {
            writeWav(lastWav(c), all, got, rate, channels);
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void writeWav(File f, short[] pcm, int n, int rate, int ch) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + n * 2).put("WAVE".getBytes()).put("fmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) ch).putInt(rate).putInt(rate * ch * 2)
                .putShort((short) (ch * 2)).putShort((short) 16).put("data".getBytes()).putInt(n * 2);
        for (int i = 0; i < n; i++) b.putShort(pcm[i]);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b.array()); }
    }
}
