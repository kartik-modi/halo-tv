package com.kartikmodi.halo.home;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** Glass Home: the band is clear glass, and its last drawn rectangle feeds GlassBackdrop's lens. */
    private GlassDrawable glassBand;
    private final RectF lastBand = new RectF();
    private boolean bandShown;

    /** @param translucent the cinematic shelf's band: the artwork and the glow show through it. */
    DockDecoration(int dockSize, float density, boolean translucent) {
        this.dockSize = dockSize;
        this.density = density;
        band.setColor(translucent ? 0x8C121014 : 0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    /** Draws the band as clear glass tinted with the accent (config.xml glass_home). */
    void useGlass(int accent) {
        glassBand = new GlassDrawable(26 * density, density, accent, true);
    }

    /** The band's rectangle in the grid's coordinates as last drawn, or false when there was none. */
    boolean bandRect(RectF out) {
        if (!bandShown) return false;
        out.set(lastBand);
        return true;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        bandShown = any;
        if (!any) return;
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        lastBand.set(rect);
        if (glassBand != null) {
            glassBand.setBounds(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
            glassBand.draw(c);
            return;
        }
        float radius = 26 * density;
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
