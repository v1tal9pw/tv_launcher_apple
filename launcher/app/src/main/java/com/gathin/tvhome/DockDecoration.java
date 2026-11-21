package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** A broad, frosted dock with a luminous upper lip and soft colour reflected through its glass. */
public class DockDecoration extends RecyclerView.ItemDecoration {
    private final int dockSize;
    private final float density;
    private boolean enabled = true, band = true, evenPadding;
    private float opacity = 1f;
    private boolean names;
    void setShowNames(boolean value) { names = value; }
    private final Paint body = new Paint(3), warmth = new Paint(3), reflection = new Paint(3);
    private final Paint edge = new Paint(3), innerEdge = new Paint(3);
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint[] layers = {body, warmth, reflection, edge, innerEdge};
    private final RectF rect = new RectF(), inner = new RectF();
    private Bitmap cachedBand;
    private int cachedW, cachedH;
    private float cachedRadius;
    private int cachedAlpha = -1;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f * density);
        innerEdge.setStyle(Paint.Style.STROKE);
        innerEdge.setStrokeWidth(.5f * density);
    }
    void setEnabled(boolean value) { enabled = value; }
    DockDecoration configure(Config config) { band=config.dockBand;evenPadding=config.dockEvenPadding;
        opacity=config.dockOpacity/100f;return this; }
    void releaseCache() {
        if (cachedBand != null) {
            cachedBand.recycle();
            cachedBand = null;
        }
        cachedW = cachedH = 0;
        cachedAlpha = -1;
    }
    static float radiusPx(float tileRadius,float padding){return tileRadius+padding;}
    static float evenPaddingPx(int inner,int columns,int tile,float available){
        return columns<1?0f:Math.max(0f,Math.min(inner/(float)columns-tile,available));
    }

    /** Extra space under the dock row that the rows below it do not have. */
    int rowInset() { return enabled ? Math.round(8 * density) : 0; }

    static int partialRowOffset(int availableWidth, int columns, int apps) {
        if (columns < 1 || apps < 1 || apps >= columns) return 0;
        return Math.round((columns - apps) * Math.max(0, availableWidth) / (2f * columns));
    }

    @Override public void getItemOffsets(@NonNull Rect out, @NonNull View view,
            @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        // With fewer apps than dock slots the App Library row follows the dock directly; it is not part of it.
        if (parent.getAdapter() instanceof AppAdapter && ((AppAdapter) parent.getAdapter()).isLibraryAt(pos)) return;
        if (enabled && pos >= 0 && pos < dockSize) {
            out.top = 0;
            out.bottom = rowInset();
            if (parent.getAdapter() instanceof AppAdapter) {
                int apps = ((AppAdapter) parent.getAdapter()).appCount();
                int width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
                int shift = partialRowOffset(width, dockSize, apps);
                out.left = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -shift : shift;
                out.right = -out.left;
            }
        }
    }

    private Bitmap bandCache(float width, float height, float radius, int alpha) {
        int pad = Math.max(2, (int) Math.ceil(density * 2f));
        int bw = Math.max(1, Math.round(width) + pad * 2);
        int bh = Math.max(1, Math.round(height) + pad * 2);
        if (cachedBand != null && !cachedBand.isRecycled()
                && cachedW == bw && cachedH == bh
                && Math.abs(cachedRadius - radius) < 0.5f && cachedAlpha == alpha) {
            return cachedBand;
        }
        releaseCache();
        try {
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.translate(pad, pad);
            body.setShader(new LinearGradient(0, 0, 0, height,
                    new int[]{0x65FFFFFF, 0x38F6F8F7, 0x50F8F4EC},
                    new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));
            warmth.setShader(new RadialGradient(width * .31f, height * .65f, width * .32f,
                    0x25FFF1CE, 0x00FFF1CE, Shader.TileMode.CLAMP));
            reflection.setShader(new RadialGradient(width * .64f, -height * .2f, width * .35f,
                    0x25DAFFFF, 0x00DAFFFF, Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(0, 0, 0, height,
                    new int[]{0xC5ECFFFF, 0x5CD9E9E9, 0x38FFFFFF},
                    new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
            innerEdge.setShader(new LinearGradient(0, 0, 0, height,
                    0x38FFFFFF, 0x0CFFFFFF, Shader.TileMode.CLAMP));
            for (Paint paint : layers) paint.setAlpha(alpha);
            RectF local = new RectF(0f, 0f, width, height);
            c.drawRoundRect(local, radius, radius, body);
            c.drawRoundRect(local, radius, radius, warmth);
            c.drawRoundRect(local, radius, radius, reflection);
            c.drawRoundRect(local, radius, radius, edge);
            inner.set(local);
            inner.inset(density, density);
            c.drawRoundRect(inner, radius - density, radius - density, innerEdge);
            bmp.prepareToDraw();
            cachedBand = bmp;
            cachedW = bw;
            cachedH = bh;
            cachedRadius = radius;
            cachedAlpha = alpha;
            return bmp;
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    @Override public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
            @NonNull RecyclerView.State state) {
        if (!enabled || !band) return;
        boolean found = false;
        float tileWidth=0f;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            // Follow the laid-out slots, not app identities already shifted by notifyItemMoved.
            int pos = parent.getChildLayoutPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            tileWidth=card.getWidth();
            float l = child.getLeft() + card.getLeft();
            // Reordering translates individual tiles. The dock belongs to the first row's layout
            // and must never travel with those tiles into another row.
            float t = child.getTop() + card.getTop();
            if (!found) { rect.set(l, t, l + card.getWidth(), t + card.getHeight()); found = true; }
            else rect.union(l, t, l + card.getWidth(), t + card.getHeight());
        }
        if (!found) return;
        // The viewport has a side gutter beyond the tiles, so the glass can surround a zoomed
        // edge tile and still leave a visible gap. Keep the rounded ends inside that viewport.
        float side = Math.max(0f, Math.min(20 * density,
                Math.min(rect.left, parent.getWidth() - rect.right) - density));
        float padding=evenPadding?evenPaddingPx(parent.getWidth(),dockSize,(int)tileWidth,side):16*density;
        rect.inset(-(evenPadding?padding:side), -padding);
        if (names) rect.bottom += 22 * density;
        float radius = evenPadding?radiusPx(AppAdapter.cornerRadius((int)tileWidth,density),padding):36*density;
        int alpha = Math.round(255 * opacity);
        int pad = Math.max(2, (int) Math.ceil(density * 2f));
        Bitmap cached = bandCache(rect.width(), rect.height(), radius, alpha);
        if (cached != null) {
            canvas.drawBitmap(cached, rect.left - pad, rect.top - pad, blit);
        }
    }
}
