package home.tvphotos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * One slide: a single photo filling the screen, or a combination's photos each in its box. Every photo
 * is placed with {@link Framing#place}, the same maths as the phone's editor.
 */
final class SlideView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private String layout;
    private Bitmap[] bitmaps;
    private float[][] frames;

    SlideView(Context context) { super(context); }

    /** layout null = one photo on the whole screen. frames[i] = {x, y, z}. Takes ownership of the bitmaps. */
    void set(String layout, Bitmap[] bitmaps, float[][] frames) {
        clear();
        this.layout = layout;
        this.bitmaps = bitmaps;
        this.frames = frames;
        invalidate();
    }

    void clear() {
        if (bitmaps != null) {
            for (Bitmap b : bitmaps) if (b != null) b.recycle();
        }
        bitmaps = null;
        frames = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmaps == null) return;
        int[][] boxes = Framing.pixelBoxes(layout, getWidth(), getHeight());
        for (int i = 0; i < boxes.length && i < bitmaps.length; i++) {
            Bitmap b = bitmaps[i];
            if (b == null || b.isRecycled()) continue;
            int[] box = boxes[i];
            float[] f = frames[i];
            float[] p = Framing.place(box[2], box[3], b.getWidth(), b.getHeight(), f[0], f[1], f[2]);
            canvas.save();
            canvas.clipRect(box[0], box[1], box[0] + box[2], box[1] + box[3]);
            dst.set(box[0] + p[0], box[1] + p[1], box[0] + p[0] + p[2], box[1] + p[1] + p[3]);
            canvas.drawBitmap(b, null, dst, paint);
            canvas.restore();
        }
    }
}
