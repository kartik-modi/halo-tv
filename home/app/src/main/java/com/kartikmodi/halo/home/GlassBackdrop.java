package com.kartikmodi.halo.home;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Glass Home's backdrop: the motion video looping under Home, drawn with OpenGL so that inside every glass
 * shape (tiles, dock band, top buttons) the picture bends like a thick lens: clear and slightly magnified in
 * the middle, pulled in hard at the rounded rim, with a thin colour fringe. No blur. MainActivity sends the
 * glass rectangles whenever they move. Without the video file the same lens bends a still gradient; if
 * OpenGL itself fails, {@code onFailed} runs and Home falls back to plain glass on a still background.
 * Draws only when a video frame arrives or the glass moves, and the video is paused while Home is hidden.
 */
final class GlassBackdrop extends GLSurfaceView implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "GlassBackdrop";
    static final int MAX = 24;
    /**
     * The backdrop is drawn at a quarter of 1080p and scaled up by the display hardware: four times less
     * work for the GPU, and the motion is soft anyway. Logos, text and tiles stay sharp in their own layer.
     */
    private static final int SURFACE_W = 960, SURFACE_H = 540;

    private static final String VERTEX =
            "attribute vec2 a;\n"
            + "void main() { gl_Position = vec4(a, 0.0, 1.0); }\n";

    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + "uniform samplerExternalOES video;\n"
            + "uniform mat4 texMatrix;\n"
            + "uniform vec2 res;\n"
            + "uniform vec4 rect[" + MAX + "];\n"
            + "uniform float rad[" + MAX + "];\n"
            + "uniform int n;\n"
            + "uniform float bez;\n"
            + "uniform float hasVideo;\n"
            + "uniform vec3 deep;\n"
            + "uniform vec3 light;\n"
            // still fallback: the deep colour, lifted towards the light one in a soft pool at the top right
            + "vec3 still(vec2 px) {\n"
            + "  vec2 q = px / res;\n"
            + "  float pool = exp(-((q.x - 0.78) * (q.x - 0.78) * 3.0 + (q.y - 0.08) * (q.y - 0.08) * 5.0));\n"
            + "  return mix(deep, light, 0.28 * pool + 0.10 * (1.0 - abs(q.y - 0.45) * 2.0));\n"
            + "}\n"
            + "vec3 tap(vec2 px) {\n"
            + "  px = clamp(px, vec2(1.0), res - 1.0);\n"
            + "  if (hasVideo < 0.5) return still(px);\n"
            + "  vec2 uv = vec2(px.x / res.x, 1.0 - px.y / res.y);\n"
            + "  return texture2D(video, (texMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;\n"
            + "}\n"
            + "float sd(vec2 p, vec2 b, float r) {\n"
            + "  vec2 q = abs(p) - b + r;\n"
            + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 px = vec2(gl_FragCoord.x, res.y - gl_FragCoord.y);\n"
            + "  vec3 col = tap(px);\n"
            + "  for (int i = 0; i < " + MAX + "; i++) {\n"
            + "    if (i >= n) break;\n"
            + "    vec2 h = rect[i].zw * 0.5;\n"
            + "    vec2 c = rect[i].xy + h;\n"
            + "    vec2 p = px - c;\n"
            + "    if (abs(p.x) > h.x || abs(p.y) > h.y) continue;\n"
            + "    float r = min(rad[i], min(h.x, h.y));\n"
            + "    float d = sd(p, h, r);\n"
            + "    if (d >= 0.0) continue;\n"
            + "    vec2 e = vec2(1.0, 0.0);\n"
            + "    vec2 nrm = normalize(vec2(sd(p + e.xy, h, r) - sd(p - e.xy, h, r),\n"
            + "                              sd(p + e.yx, h, r) - sd(p - e.yx, h, r)) + 1e-5);\n"
            + "    float b = min(bez, min(h.x, h.y) * 0.8);\n"
            + "    float k = clamp(1.0 + d / b, 0.0, 1.0);\n"      // 0 in the flat middle, 1 at the rim
            + "    float bend = k * k * b * 0.45;\n"                // a thick lens's rim, never folding over
            + "    vec2 q = c + p * 0.93 - nrm * bend;\n"           // slight magnification, rim pulls inward
            + "    float ca = k * k * b * 0.05;\n"                  // thin colour fringe at the rim
            + "    col = vec3(tap(q - nrm * ca).r, tap(q).g, tap(q + nrm * ca).b);\n"
            + "    col = col * 1.08 + 0.025 + k * k * 0.06;\n"      // glass gathers a little light
            + "    break;\n"
            + "  }\n"
            + "  gl_FragColor = vec4(col, 1.0);\n"
            + "}\n";

    private final File video;
    private final float[] deep, light;
    private final Runnable onFailed;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private final float[] rects = new float[MAX * 4];
    private final float[] radii = new float[MAX];
    private int count;

    private final float[] texMatrix = new float[16];
    private FloatBuffer quad;
    private int program, texture;
    private int uTexMatrix, uRes, uRect, uRad, uN, uBez, uHasVideo, uDeep, uLight;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private MediaPlayer player;
    private boolean prepared, resumed;
    private volatile boolean frameAvailable, hasFrame;
    private int width, height;

    GlassBackdrop(Context context, File video, int background, int accent, Runnable onFailed) {
        super(context);
        this.video = video;
        this.deep = rgb(background);
        this.light = rgb(accent);
        this.onFailed = onFailed;
        Matrix.setIdentityM(texMatrix, 0);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        getHolder().setFixedSize(SURFACE_W, SURFACE_H);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
    }

    private static float[] rgb(int c) {
        return new float[]{((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f};
    }

    /** The glass shapes in this view's pixels, topmost first: x, y, width, height per shape, and radii. */
    void setGlass(float[] r, float[] rad, int n) {
        // View pixels to the smaller surface's pixels.
        float f = getWidth() > 0 ? SURFACE_W / (float) getWidth() : 1f;
        synchronized (lock) {
            count = Math.min(n, MAX);
            for (int i = 0; i < count * 4; i++) rects[i] = r[i] * f;
            for (int i = 0; i < count; i++) radii[i] = rad[i] * f;
        }
        requestRender();
    }

    // ---- lifecycle, from MainActivity ----

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (player != null && prepared) player.start();
    }

    @Override
    public void onPause() {
        resumed = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        super.onPause();
    }

    void release() {
        resumed = false;
        if (player != null) {
            player.release();
            player = null;
        }
        if (surface != null) surface.release();
        surface = null;
    }

    // ---- video ----

    private void startVideo(SurfaceTexture st) {
        if (!video.isFile()) {
            Log.i(TAG, "no motion video at " + video + "; still background");
            return;
        }
        main.post(() -> {
            try {
                surface = new Surface(st);
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(video.getAbsolutePath());
                mp.setSurface(surface);
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                mp.setOnPreparedListener(p -> {
                    prepared = true;
                    if (resumed) p.start();
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    Log.w(TAG, "motion video error " + what + "/" + extra + "; still background");
                    hasFrame = false;
                    requestRender();
                    return true;
                });
                mp.prepareAsync();
                player = mp;
            } catch (Exception e) {
                Log.w(TAG, "motion video failed; still background", e);
            }
        });
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        frameAvailable = true;
        requestRender();
    }

    // ---- GL ----

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            program = link(compile(GLES20.GL_VERTEX_SHADER, VERTEX), compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
        } catch (RuntimeException e) {
            Log.w(TAG, "glass shader failed; plain glass", e);
            main.post(onFailed);
            return;
        }
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        uTexMatrix = GLES20.glGetUniformLocation(program, "texMatrix");
        uRes = GLES20.glGetUniformLocation(program, "res");
        uRect = GLES20.glGetUniformLocation(program, "rect");
        uRad = GLES20.glGetUniformLocation(program, "rad");
        uN = GLES20.glGetUniformLocation(program, "n");
        uBez = GLES20.glGetUniformLocation(program, "bez");
        uHasVideo = GLES20.glGetUniformLocation(program, "hasVideo");
        uDeep = GLES20.glGetUniformLocation(program, "deep");
        uLight = GLES20.glGetUniformLocation(program, "light");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        if (surfaceTexture == null) {
            surfaceTexture = new SurfaceTexture(texture);
            surfaceTexture.setOnFrameAvailableListener(this);
            startVideo(surfaceTexture);
        } else {
            // EGL context lost and recreated: the video goes on in the same SurfaceTexture.
            surfaceTexture.detachFromGLContext();
            surfaceTexture.attachToGLContext(texture);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = w;
        height = h;
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (program == 0) return;
        if (frameAvailable && surfaceTexture != null) {
            frameAvailable = false;
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(texMatrix);
                hasFrame = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "updateTexImage", e);
            }
        }
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
        GLES20.glUniform2f(uRes, width, height);
        synchronized (lock) {
            GLES20.glUniform4fv(uRect, MAX, rects, 0);
            GLES20.glUniform1fv(uRad, MAX, radii, 0);
            GLES20.glUniform1i(uN, count);
        }
        GLES20.glUniform1f(uBez, width * 0.028f);
        GLES20.glUniform1f(uHasVideo, hasFrame ? 1f : 0f);
        GLES20.glUniform3fv(uDeep, 1, deep, 0);
        GLES20.glUniform3fv(uLight, 1, light, 0);
        int a = GLES20.glGetAttribLocation(program, "a");
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static int compile(int type, String code) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, code);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES20.glGetProgramInfoLog(p));
        return p;
    }
}
