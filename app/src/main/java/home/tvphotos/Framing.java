package home.tvphotos;

/**
 * Where a photo sits inside a box, and the boxes of each combination layout. Pure Java, so it is unit
 * tested on the computer; index.html has a line-for-line JS copy (frameBox) so the phone's editor shows
 * exactly what the TV draws.
 *
 * A framing is {x, y, z}: x and y are the point of the photo (0..1 of its width and height) placed at the
 * centre of the box, z the zoom relative to "cover" (1 = the photo just fills the box). Zooming out stops
 * at "fit" (the whole photo, with bars), zooming in at 4x.
 */
final class Framing {
    static final float MAX_ZOOM = 4f;
    /** Gap between the boxes of a combination, in pixels on a 1920-wide screen. */
    static final int GAP_1080P = 8;

    private Framing() {}

    /** z at which the whole photo fits in the box. */
    static float fitZoom(float w, float h, float pw, float ph) {
        float cover = Math.max(w / pw, h / ph);
        float fit = Math.min(w / pw, h / ph);
        return fit / cover;
    }

    /** {left, top, drawnWidth, drawnHeight} of the photo, in the box's pixels. */
    static float[] place(float w, float h, float pw, float ph, float x, float y, float z) {
        float cover = Math.max(w / pw, h / ph);
        z = Math.max(fitZoom(w, h, pw, ph), Math.min(MAX_ZOOM, z));
        float s = cover * z, dw = pw * s, dh = ph * s;
        float left = dw >= w ? clamp(w / 2 - x * dw, w - dw, 0) : (w - dw) / 2;
        float top = dh >= h ? clamp(h / 2 - y * dh, h - dh, 0) : (h - dh) / 2;
        return new float[]{left, top, dw, dh};
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static final float T = 1f / 3, TT = 2f / 3;

    /** Fractions of the screen {x, y, w, h} per box, before the gap; null for an unknown layout. */
    static float[][] boxes(String layout) {
        if (layout == null) return null;
        switch (layout) {
            case "pair": return new float[][]{{0, 0, .5f, 1}, {.5f, 0, .5f, 1}};
            case "row3": return new float[][]{{0, 0, T, 1}, {T, 0, T, 1}, {TT, 0, T, 1}};
            case "big3": return new float[][]{{0, 0, TT, 1}, {TT, 0, T, .5f}, {TT, .5f, T, .5f}};
            case "grid4": return new float[][]{{0, 0, .5f, .5f}, {.5f, 0, .5f, .5f}, {0, .5f, .5f, .5f}, {.5f, .5f, .5f, .5f}};
            default: return null;
        }
    }

    /** How many photos a layout takes; 0 for an unknown one. */
    static int cells(String layout) {
        float[][] b = boxes(layout);
        return b == null ? 0 : b.length;
    }

    /**
     * Boxes in screen pixels {left, top, width, height}, with half the gap taken off every inner edge.
     * A null layout is one box, the whole screen (a single photo).
     */
    static int[][] pixelBoxes(String layout, int screenW, int screenH) {
        float[][] f = boxes(layout);
        if (f == null) return new int[][]{{0, 0, screenW, screenH}};
        int half = Math.round(GAP_1080P * screenW / 1920f) / 2;
        int[][] out = new int[f.length][];
        for (int i = 0; i < f.length; i++) {
            int l = Math.round(f[i][0] * screenW), t = Math.round(f[i][1] * screenH);
            int r = Math.round((f[i][0] + f[i][2]) * screenW), b = Math.round((f[i][1] + f[i][3]) * screenH);
            if (l > 0) l += half;
            if (t > 0) t += half;
            if (r < screenW) r -= half;
            if (b < screenH) b -= half;
            out[i] = new int[]{l, t, r - l, b - t};
        }
        return out;
    }
}
