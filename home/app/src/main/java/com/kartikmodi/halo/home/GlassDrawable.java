package com.kartikmodi.halo.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Clear liquid glass for Glass Home: a faint fill, a sheen over the top half, a rim lit from the top left
 * and an inner glow of the accent colour along the bottom. Nothing here blurs; what is behind shows through
 * (GlassBackdrop bends it). Focused, the glass brightens and the glow strengthens. A radius of -1 makes a
 * circle or capsule, for the top buttons.
 */
final class GlassDrawable extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final float density;
    private final int accentRgb;
    /** Pressed into the band behind the dock: fainter, so the tiles on it read as the nearer layer. */
    private final boolean band;
    private boolean focused;

    GlassDrawable(float radius, float density, int accent, boolean band) {
        this.radius = radius;
        this.density = density;
        this.accentRgb = accent & 0xFFFFFF;
        this.band = band;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 1.2f * density));
    }

    @Override
    public boolean isStateful() {
        return !band;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean f = false;
        for (int s : state) if (s == android.R.attr.state_focused) f = true;
        if (f == focused) return false;
        focused = f;
        updateShaders(getBounds());
        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        updateShaders(bounds);
    }

    private void updateShaders(Rect b) {
        if (b.isEmpty()) return;
        float w = b.width(), h = b.height();
        int base = band ? 0x0F : focused ? 0x2E : 0x14;
        // Lighter at the top left, clear through the middle, a little light again at the far edge.
        fill.setShader(new LinearGradient(b.left, b.top, b.left + w * 0.45f, b.bottom,
                new int[]{base << 24 | 0xFFFFFF, (base / 4) << 24 | 0xFFFFFF, (base / 2) << 24 | 0xFFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.5f,
                (focused ? 0x2A : 0x1A) << 24 | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        int glowA = band ? 0x30 : focused ? 0xB0 : 0x55;
        glow.setShader(new LinearGradient(0, b.bottom, 0, b.bottom - h * 0.45f,
                glowA << 24 | accentRgb, accentRgb, Shader.TileMode.CLAMP));
        int top = focused ? 0xE6 : band ? 0x66 : 0xA6;
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{top << 24 | 0xFFFFFF, 0x10FFFFFF, 0x00FFFFFF, (top / 2) << 24 | 0xFFFFFF},
                new float[]{0f, 0.35f, 0.6f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float inset = rim.getStrokeWidth() / 2f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        float r = radius < 0 ? Math.min(rect.width(), rect.height()) / 2f : radius;
        c.drawRoundRect(rect, r, r, fill);
        c.drawRoundRect(rect, r, r, glow);
        c.save();
        c.clipRect(rect.left, rect.top, rect.right, rect.centerY());
        c.drawRoundRect(rect, r, r, sheen);
        c.restore();
        c.drawRoundRect(rect, r, r, rim);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
