package home.tvphotos;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The photo folder, the PIN and the screensaver settings. Everything lives in the app's private storage. */
final class Store {
    /** Photos are stored to fit inside the TV's 1080p panel; larger files only cost space and decode time. */
    static final int MAX_W = 1920, MAX_H = 1080;
    static final int THUMB = 360;
    static final int[] INTERVALS = {10, 30, 60, 180, 300, 600};

    private static final Pattern NAME = Pattern.compile("^[0-9]{13}_[0-9a-f]{6}\\.jpg$");
    private static final SecureRandom RANDOM = new SecureRandom();

    final File photos, thumbs;
    /** Framings, Show-on-TV switches and combinations: see {@link Library}. */
    final File library;
    private final SharedPreferences prefs;

    Store(Context context) {
        photos = new File(context.getFilesDir(), "photos");
        thumbs = new File(context.getFilesDir(), "thumbs");
        library = new File(context.getFilesDir(), "library.json");
        //noinspection ResultOfMethodCallIgnored
        photos.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        thumbs.mkdirs();
        prefs = context.getSharedPreferences("tvphotos", Context.MODE_PRIVATE);
        synchronized (Store.class) {
            if (!prefs.contains("pin")) newPin();
        }
    }

    // ---- PIN and session token -------------------------------------------------------------

    /**
     * One PIN. It is never shown, except right after a new random one is made (on the TV or the phone page),
     * which is also the way back in when it is forgotten. Every change logs every phone out.
     */
    String newPin() {
        String pin = String.format(Locale.ROOT, "%04d", RANDOM.nextInt(10000));
        setPin(pin);
        return pin;
    }

    /** A PIN someone chose; false unless it is exactly 4 digits. */
    boolean setPin(String pin) {
        if (pin == null || !pin.matches("[0-9]{4}")) return false;
        byte[] t = new byte[16];
        RANDOM.nextBytes(t);
        StringBuilder hex = new StringBuilder();
        for (byte b : t) hex.append(String.format(Locale.ROOT, "%02x", b));
        prefs.edit().remove("pin_mode").putString("pin", pin).putString("token", hex.toString()).commit();
        return true;
    }

    String pin() { return prefs.getString("pin", ""); }

    String token() { return prefs.getString("token", ""); }

    // ---- Weather ----------------------------------------------------------------------------

    /** The town the forecast is for, or "" when none is set. */
    String weatherPlace() { return prefs.getString("w_place", ""); }

    double weatherLat() { return Double.longBitsToDouble(prefs.getLong("w_lat", 0)); }

    double weatherLon() { return Double.longBitsToDouble(prefs.getLong("w_lon", 0)); }

    boolean showWeather() { return prefs.getBoolean("w_on", true) && !weatherPlace().isEmpty(); }

    boolean weatherSwitch() { return prefs.getBoolean("w_on", true); }

    void setWeatherOn(boolean on) { prefs.edit().putBoolean("w_on", on).apply(); }

    void setWeatherPlace(String name, double lat, double lon) {
        prefs.edit().putString("w_place", name)
                .putLong("w_lat", Double.doubleToRawLongBits(lat))
                .putLong("w_lon", Double.doubleToRawLongBits(lon))
                .remove("w_json").remove("w_time")
                .apply();
    }

    /** The last forecast Open-Meteo returned, and when (ms), so a new screensaver can show it at once. */
    String weatherCache() { return prefs.getString("w_json", null); }

    long weatherCacheTime() { return prefs.getLong("w_time", 0); }

    void setWeatherCache(String json, long time) {
        prefs.edit().putString("w_json", json).putLong("w_time", time).apply();
    }

    // ---- Settings ---------------------------------------------------------------------------

    int intervalSeconds() { return prefs.getInt("interval", 60); }

    boolean showClock() { return prefs.getBoolean("clock", true); }

    /** Every photo fills the screen (cropped as needed); off shows each photo whole on black. */
    boolean fill() { return prefs.getBoolean("fill", true); }

    /** The small QR code in the screensaver that opens the phone page. */
    boolean showQr() { return prefs.getBoolean("qr", true); }

    void setFill(boolean on) { prefs.edit().putBoolean("fill", on).apply(); }

    void setQr(boolean on) { prefs.edit().putBoolean("qr", on).apply(); }

    boolean setInterval(int seconds) {
        for (int allowed : INTERVALS) {
            if (allowed == seconds) {
                prefs.edit().putInt("interval", seconds).apply();
                return true;
            }
        }
        return false;
    }

    void setClock(boolean on) { prefs.edit().putBoolean("clock", on).apply(); }

    // ---- Photos -----------------------------------------------------------------------------

    static boolean validName(String name) { return name != null && NAME.matcher(name).matches(); }

    /** Newest first. */
    List<File> list() {
        File[] files = photos.listFiles((dir, name) -> validName(name));
        if (files == null) return new ArrayList<>();
        List<File> out = new ArrayList<>(Arrays.asList(files));
        // Names start with the upload time in milliseconds, so name order is upload order; file times tie
        // for uploads made within the same second.
        Collections.sort(out, (a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    File photo(String name) { return validName(name) ? new File(photos, name) : null; }

    File thumb(String name) { return validName(name) ? new File(thumbs, name) : null; }

    boolean delete(String name) {
        if (!validName(name)) return false;
        boolean gone = new File(photos, name).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(thumbs, name).delete();
        return gone;
    }

    long freeBytes() { return photos.getUsableSpace(); }

    /**
     * Turns an uploaded file into a stored photo: decoded at a reduced size, turned upright from its EXIF
     * orientation, scaled to fit 1920x1080 and saved as JPEG, plus a square thumbnail for the phone page.
     */
    String add(File upload) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(upload.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("not a picture this TV can read");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(upload.getPath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) {
            // No EXIF: keep it as it is.
        }
        boolean swap = orientation >= ExifInterface.ORIENTATION_TRANSPOSE; // 5..8 turn by 90 degrees
        int w = swap ? bounds.outHeight : bounds.outWidth;
        int h = swap ? bounds.outWidth : bounds.outHeight;
        double scale = Math.min(1.0, Math.min((double) MAX_W / w, (double) MAX_H / h));
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (w / (opts.inSampleSize * 2) >= targetW && h / (opts.inSampleSize * 2) >= targetH) opts.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeFile(upload.getPath(), opts);
        if (decoded == null) throw new IOException("not a picture this TV can read");

        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(-90); break;
            default: break;
        }
        float s = (float) targetW / (swap ? decoded.getHeight() : decoded.getWidth());
        m.postScale(s, s);
        Bitmap upright = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
        if (upright != decoded) decoded.recycle();

        String name = String.format(Locale.ROOT, "%013d_%06x.jpg", System.currentTimeMillis(), RANDOM.nextInt(0x1000000));
        try {
            writeJpeg(upright, new File(photos, name), 88);
            int side = Math.min(upright.getWidth(), upright.getHeight());
            Bitmap square = Bitmap.createBitmap(upright, (upright.getWidth() - side) / 2,
                    (upright.getHeight() - side) / 2, side, side);
            Bitmap thumb = Bitmap.createScaledBitmap(square, THUMB, THUMB, true);
            writeJpeg(thumb, new File(thumbs, name), 80);
            if (thumb != square) thumb.recycle();
            if (square != upright) square.recycle();
        } finally {
            upright.recycle();
        }
        return name;
    }

    private static void writeJpeg(Bitmap bitmap, File target, int quality) throws IOException {
        File tmp = new File(target.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw new IOException("could not save");
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not save");
        }
    }

    // ---- Network ----------------------------------------------------------------------------

    /** The TV's address on the home network (Wi-Fi or Ethernet), or null when it has none. */
    static String address() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String name = ni.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    static String url() {
        String ip = address();
        return ip == null ? null : "http://" + ip + ":" + PhotoServer.PORT;
    }
}
