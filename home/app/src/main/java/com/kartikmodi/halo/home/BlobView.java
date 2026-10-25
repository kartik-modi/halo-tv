package com.kartikmodi.halo.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Jarvis on the Ambient screen: a liquid chrome blob, drawn by the GPU on its own surface at the display's
 * frame rate (the TV's WebView can't animate that smoothly). The shape is a disc whose edge is moved by
 * travelling waves; the surface is shaded from a photo of chrome (assets/ambient/blob.webp). The shader is
 * the same as assets/ambient/blob.js, which draws it in a desktop browser; keep the two in step.
 *
 * The page says what Jarvis is doing (idle, listening, thinking, speaking) and how loud the voice is.
 */
final class BlobView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private static final String VERT = "attribute vec2 pos; varying vec2 uv; void main() { uv = pos; gl_Position = vec4(pos, 0.0, 1.0); }";
    private static final String FRAG =
            "precision mediump float;\n"
            + "uniform sampler2D cap;\n"
            + "uniform float t, speak, listen, think, lvl;\n"
            + "varying vec2 uv;\n"
            + "vec2 rot(vec2 p, float a) { float c = cos(a), s = sin(a); return vec2(c * p.x - s * p.y, s * p.x + c * p.y); }\n"
            + "void main() {\n"
            + "  vec2 p = uv;\n"
            + "  float a = atan(p.y, p.x), d = length(p);\n"
            + "  float r = 0.80\n"
            + "    + 0.030 * sin(3.0 * a + t * 0.9) + 0.022 * sin(5.0 * a - t * 1.3) + 0.018 * sin(2.0 * a + t * 0.55)\n"
            + "    + listen * 0.035 * sin(t * 3.2)\n"
            + "    + think * 0.03 * sin(4.0 * a - t * 4.0)\n"
            + "    + speak * lvl * (0.07 * sin(6.0 * a - t * 9.0) + 0.045 * sin(9.0 * a + t * 12.0) + 0.05);\n"
            + "  float q = d / r;\n"
            + "  if (q > 1.02) { gl_FragColor = vec4(0.0); return; }\n"
            + "  float z = sqrt(max(0.0, 1.0 - min(q, 1.0) * min(q, 1.0)));\n"
            + "  vec3 n = normalize(vec3(p / r, z * 0.9 + 0.1));\n"
            + "  float flow = 0.22 + 0.25 * think + 0.35 * speak * lvl;\n"
            + "  vec2 w = vec2(sin(3.1 * p.y + t * 0.7 + sin(2.3 * p.x - t * 0.4)), cos(2.7 * p.x - t * 0.6 + sin(1.9 * p.y + t * 0.5)));\n"
            + "  n.xy += w * flow * (0.35 + 0.65 * (1.0 - z));\n"
            + "  n = normalize(n);\n"
            + "  vec2 m = rot(n.xy, t * (0.08 + 0.5 * think)) * 0.43 + 0.5;\n"
            + "  vec3 c = texture2D(cap, m).rgb;\n"
            + "  c = mix(c, vec3(dot(c, vec3(0.333))), 0.6);\n"
            + "  c *= 0.82 + 0.28 * z;\n"
            + "  c += pow(max(0.0, dot(n, normalize(vec3(-0.45, 0.55, 0.7)))), 24.0) * 0.55;\n"
            + "  c += speak * lvl * 0.12;\n"
            + "  float alpha = smoothstep(1.0, 0.965, q);\n"
            + "  gl_FragColor = vec4(c * alpha, alpha);\n"
            + "}\n";

    private final Context app;
    private final long start = SystemClock.elapsedRealtime();
    private int prog, uT, uSpeak, uListen, uThink, uLvl;
    private FloatBuffer quad;

    // Set from the page's thread, read on the GL thread.
    private volatile float wantSpeak, wantListen, wantThink, target;
    // GL thread only: eased towards the wanted values so the blob never jumps.
    private float speak, listen, think, lvl;

    BlobView(Context context) {
        super(context);
        app = context.getApplicationContext();
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);   // over the page; the corners outside the blob are clear
        setPreserveEGLContextOnPause(true);   // coming back after Alexa needs no reload, so no hitch
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    void setMode(String mode) {
        wantSpeak = "speaking".equals(mode) ? 1f : 0f;
        wantListen = "listening".equals(mode) ? 1f : 0f;
        wantThink = "thinking".equals(mode) ? 1f : 0f;
        if (!"speaking".equals(mode)) target = 0f;
    }

    void setLevel(float v) {
        target = Math.max(0f, Math.min(1f, v));
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VERT));
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FRAG));
        GLES20.glLinkProgram(prog);
        GLES20.glUseProgram(prog);
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        int pos = GLES20.glGetAttribLocation(prog, "pos");
        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad);
        uT = GLES20.glGetUniformLocation(prog, "t");
        uSpeak = GLES20.glGetUniformLocation(prog, "speak");
        uListen = GLES20.glGetUniformLocation(prog, "listen");
        uThink = GLES20.glGetUniformLocation(prog, "think");
        uLvl = GLES20.glGetUniformLocation(prog, "lvl");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        Bitmap cap = null;
        try (InputStream in = app.getAssets().open("ambient/blob.webp")) {
            cap = BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        }
        if (cap == null) cap = Bitmap.createBitmap(new int[]{0xFFC8C8C8}, 1, 1, Bitmap.Config.ARGB_8888);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, cap, 0);
        cap.recycle();
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "cap"), 0);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        speak += (wantSpeak - speak) * 0.06f;
        listen += (wantListen - listen) * 0.06f;
        think += (wantThink - think) * 0.06f;
        lvl += (target - lvl) * (target > lvl ? 0.45f : 0.15f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUniform1f(uT, (SystemClock.elapsedRealtime() - start) / 1000f);
        GLES20.glUniform1f(uSpeak, speak);
        GLES20.glUniform1f(uListen, listen);
        GLES20.glUniform1f(uThink, think);
        GLES20.glUniform1f(uLvl, lvl);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
}
