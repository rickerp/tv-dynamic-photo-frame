package home.tvphotos;

import android.graphics.Bitmap;
import android.graphics.Color;

import io.nayuki.qrcodegen.QrCode;

/** A QR code as a bitmap: black modules on white, with the standard 4-module quiet zone. */
final class Qr {
    private Qr() {}

    /** @param px the wanted width; the code is drawn at the largest whole-pixel module size that fits. */
    static Bitmap make(String text, int px) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int border = 4;
        int modules = qr.size + 2 * border;
        int scale = Math.max(1, px / modules);
        int side = modules * scale;
        int[] pixels = new int[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int mx = x / scale - border, my = y / scale - border;
                boolean dark = mx >= 0 && my >= 0 && mx < qr.size && my < qr.size && qr.getModule(mx, my);
                pixels[y * side + x] = dark ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
    }
}
