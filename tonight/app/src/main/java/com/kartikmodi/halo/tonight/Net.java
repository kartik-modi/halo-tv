package com.kartikmodi.halo.tonight;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Small blocking HTTP helpers; always called off the main thread. */
final class Net {
    private Net() {}

    static byte[] get(String url, Map<String, String> headers) throws IOException {
        return send("GET", url, headers, null);
    }

    static byte[] post(String url, Map<String, String> headers, String body) throws IOException {
        return send("POST", url, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] send(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "TONIGHT-tv/1.0");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException(method + " " + code + " " + url.replaceAll("\\?.*", ""));
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
