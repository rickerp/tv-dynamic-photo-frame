package home.tvphotos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver: the visible photos and combinations in a shuffled crossfade, with the clock, the date,
 * the weather and a faint QR code. The remote's left and right buttons step back and forward through the
 * slides; any other button closes it.
 */
public class PhotoDream extends DreamService {
    private static final int FADE_MS = 1500;
    /** A quicker fade when stepping with the remote, so it answers the button at once. */
    private static final int STEP_FADE_MS = 400;
    private static final int HISTORY = 50;

    /** One slide: a photo (layout null) or a combination. A null frame means the photo's default. */
    private static final class Slide {
        final String key, layout;
        final String[] photos;
        final float[][] frames;

        Slide(String key, String layout, String[] photos, float[][] frames) {
            this.key = key;
            this.layout = layout;
            this.photos = photos;
            this.frames = frames;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService loader;
    private final List<Slide> queue = new ArrayList<>();
    /** Slides already shown, oldest first; cursor is the one on screen. */
    private final List<Slide> history = new ArrayList<>();
    private int cursor = -1;
    /** Bumped by every new request, so a slow decode that is no longer wanted is dropped. */
    private int generation;
    private final Runnable advance = () -> step(1, false);
    /** Slides in a row that could not be decoded; stops a tight loop if every file is unreadable. */
    private int failures;
    /** What was playing when the round was built; see playingSignature. */
    private String playingSig;
    private Store store;
    private SlideView front, back;
    private TextView empty;
    private int screenW, screenH, intervalMs;
    private boolean fill;
    private TextView weatherNow, weatherDays;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    /** Faint, but dark enough on white for a phone camera to read (checked against photos on the TV). */
    private static final float QR_ALPHA = 0.55f;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true); // to get the left and right buttons; dispatchKeyEvent closes it on any other
        setFullscreen(true);
        setScreenBright(true);

        store = new Store(this);
        intervalMs = store.intervalSeconds() * 1000;
        fill = store.fill();
        loader = Executors.newSingleThreadExecutor();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = new SlideView(this);
        front = new SlideView(this);
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        root.addView(front, new FrameLayout.LayoutParams(-1, -1));

        empty = new TextView(this);
        empty.setTextColor(0xCCFFFFFF);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        empty.setTranslationZ(2f);
        root.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        // Bottom-right: clock, date and the forecast, any of them switched off on the phone page.
        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.VERTICAL);
        corner.setGravity(Gravity.END);
        if (store.showClock()) {
            TextClock time = new TextClock(this);
            // Always 24-hour, whatever the TV's own clock setting is.
            time.setFormat12Hour("HH:mm");
            time.setFormat24Hour("HH:mm");
            style(time, 64, Typeface.create("sans-serif-light", Typeface.NORMAL));
            TextClock date = new TextClock(this);
            date.setFormat12Hour("EEEE, d MMMM");
            date.setFormat24Hour("EEEE, d MMMM");
            style(date, 20, Typeface.create("sans-serif", Typeface.NORMAL));
            corner.addView(time, row());
            corner.addView(date, row());
        }
        if (store.showWeather()) {
            weatherNow = new TextView(this);
            style(weatherNow, 20, Typeface.create("sans-serif", Typeface.NORMAL));
            weatherDays = new TextView(this);
            style(weatherDays, 16, Typeface.create("sans-serif", Typeface.NORMAL));
            weatherDays.setAlpha(0.85f);
            weatherNow.setVisibility(View.GONE);
            weatherDays.setVisibility(View.GONE);
            LinearLayout.LayoutParams wp = row();
            wp.topMargin = dp(10);
            corner.addView(weatherNow, wp);
            corner.addView(weatherDays, row());
        }
        if (corner.getChildCount() > 0) {
            // A fixed half-screen width with full-width, right-aligned rows: sized to its first contents, the
            // column cut the date and the forecast once the weather text arrived after the first layout.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(screenW / 2, -2, Gravity.BOTTOM | Gravity.END);
            int margin = dp(48);
            lp.setMargins(margin, margin, margin, dp(40));
            corner.setTranslationZ(2f);
            root.addView(corner, lp);
        }

        String url = Store.url();
        if (store.showQr() && url != null) {
            // Bottom-left, opposite the clock, and kept faint: scanning it opens the phone page.
            ImageView code = new ImageView(this);
            Bitmap qrBitmap = Qr.make(url, dp(80));
            code.setImageBitmap(qrBitmap);
            GradientDrawable card = new GradientDrawable();
            card.setColor(Color.WHITE);
            card.setCornerRadius(dp(8));
            code.setBackground(card);
            code.setClipToOutline(true);
            code.setAlpha(QR_ALPHA);
            // Exactly the bitmap's size: whole-pixel modules stay sharp for the phone's camera.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(qrBitmap.getWidth(), qrBitmap.getHeight(),
                    Gravity.BOTTOM | Gravity.START);
            int margin = dp(48);
            lp.setMargins(margin, margin, margin, dp(44));
            code.setTranslationZ(2f);
            root.addView(code, lp);
        }
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        step(1, false);
        updateWeather();
    }

    // ---- Weather ----------------------------------------------------------------------------

    /**
     * Shows the stored forecast at once, then asks Open-Meteo for a new one only when it is over 30 minutes
     * old, and again every 30 minutes while the screensaver runs. Nothing is fetched when it is not showing.
     */
    private void updateWeather() {
        if (weatherNow == null) return;
        String cached = store.weatherCache();
        long age = System.currentTimeMillis() - store.weatherCacheTime();
        showWeather(cached != null && age < Weather.STALE_MS ? cached : null);
        if (cached != null && age < Weather.REFRESH_MS) {
            handler.postDelayed(this::updateWeather, Weather.REFRESH_MS - age);
            return;
        }
        double lat = store.weatherLat(), lon = store.weatherLon();
        net.execute(() -> {
            String fresh = null;
            try {
                fresh = Weather.fetch(lat, lon);
                store.setWeatherCache(fresh, System.currentTimeMillis());
            } catch (IOException | RuntimeException ignored) {
                // Offline or Open-Meteo is down: keep what is on screen, try again later.
            }
            String shown = fresh;
            handler.post(() -> {
                if (shown != null) showWeather(shown);
                handler.postDelayed(this::updateWeather, shown != null ? Weather.REFRESH_MS : 5 * 60 * 1000L);
            });
        });
    }

    /** "⛅ 17°  Partly cloudy  ·  19° / 12°", then the next three days, all in Celsius. */
    private void showWeather(String json) {
        if (weatherNow == null) return;
        try {
            if (json == null) throw new JSONException("none");
            JSONObject w = new JSONObject(json);
            JSONObject now = w.getJSONObject("current");
            JSONObject daily = w.getJSONObject("daily");
            JSONArray days = daily.getJSONArray("time");
            JSONArray codes = daily.getJSONArray("weather_code");
            JSONArray max = daily.getJSONArray("temperature_2m_max");
            JSONArray min = daily.getJSONArray("temperature_2m_min");
            int code = now.getInt("weather_code");
            weatherNow.setText(Weather.icon(code, now.optInt("is_day", 1) == 1) + "  "
                    + Math.round(now.getDouble("temperature_2m")) + "°  " + Weather.words(code)
                    + "  ·  " + Math.round(max.getDouble(0)) + "° / " + Math.round(min.getDouble(0)) + "°");
            StringBuilder next = new StringBuilder();
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int i = 1; i < days.length() && i <= 3; i++) {
                if (next.length() > 0) next.append("     ");
                Date d = in.parse(days.getString(i));
                next.append(d == null ? "" : out.format(d)).append("  ")
                        .append(Weather.icon(codes.getInt(i), true)).append("  ")
                        .append(Math.round(max.getDouble(i))).append("° / ")
                        .append(Math.round(min.getDouble(i))).append('°');
            }
            weatherDays.setText(next);
            weatherNow.setVisibility(View.VISIBLE);
            weatherDays.setVisibility(next.length() > 0 ? View.VISIBLE : View.GONE);
        } catch (JSONException | ParseException e) {
            weatherNow.setVisibility(View.GONE);
            weatherDays.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (loader != null) loader.shutdownNow();
        net.shutdownNow();
        if (front != null) front.clear();
        if (back != null) back.clear();
        super.onDetachedFromWindow();
    }

    // ---- Remote ------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                step(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, true);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) wakeUp();
        return true;
    }

    // ---- Slides -----------------------------------------------------------------------------

    /**
     * +1: forward through the history, then a new random slide. -1: back through the history (nothing
     * happens at its start). Either way the timer starts again from now. byRemote makes the fade quick.
     */
    private void step(int direction, boolean byRemote) {
        handler.removeCallbacks(advance);
        Slide s;
        if (direction < 0) {
            if (cursor <= 0) { handler.postDelayed(advance, intervalMs); return; }
            s = history.get(--cursor);
        } else if (cursor < history.size() - 1) {
            s = history.get(++cursor);
        } else {
            s = nextSlide();
            if (s == null) { showEmpty(); return; }
            history.add(s);
            if (history.size() > HISTORY) history.remove(0);
            cursor = history.size() - 1;
        }
        empty.setVisibility(View.GONE);
        load(s, direction, byRemote);
    }

    private void showEmpty() {
        String url = Store.url();
        String where = url == null ? "the TV Photos page" : url;
        empty.setText(store.list().isEmpty()
                ? "No photos yet.\n\nOn your phone, open\n" + where + "\nand add some."
                : "Every photo is hidden.\n\nOn your phone, open\n" + where + "\nto show some.");
        empty.setVisibility(View.VISIBLE);
        handler.postDelayed(advance, 30_000);
    }

    private void load(Slide queued, int direction, boolean quick) {
        int gen = ++generation;
        loader.execute(() -> {
            Slide s = refresh(queued);
            int n = s == null ? 0 : s.photos.length;
            Bitmap[] bitmaps = new Bitmap[n];
            float[][] frames = new float[n][];
            boolean ok = s != null;
            for (int i = 0; i < n && ok; i++) {
                frames[i] = s.frames[i];
                bitmaps[i] = decode(store.photo(s.photos[i]), s.layout, i, frames);
                ok = bitmaps[i] != null;
            }
            boolean usable = ok;
            String layout = s == null ? null : s.layout;
            handler.post(() -> {
                if (gen != generation) { recycle(bitmaps); return; }
                if (!usable) {
                    // Hidden, deleted or unreadable since it was queued: forget it and keep going the same way.
                    recycle(bitmaps);
                    history.remove(queued);
                    cursor = Math.min(cursor, history.size()) - (direction < 0 ? 0 : 1);
                    if (++failures > 20) { failures = 0; showEmpty(); } else step(direction, quick);
                    return;
                }
                failures = 0;
                show(layout, bitmaps, frames, quick);
                handler.postDelayed(advance, intervalMs);
            });
        });
    }

    /**
     * Shuffled, refilled each round from the folder and the library, so new uploads, framings, switches and
     * combinations show up without restarting the screensaver.
     */
    private Slide nextSlide() {
        Library lib = Library.read(store.library);
        String sig = playingSignature(lib);
        if (!sig.equals(playingSig)) queue.clear(); // playlists switched on or off: start a new round now
        playingSig = sig;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!queue.isEmpty()) return queue.remove(0);
            Set<String> names = new HashSet<>();
            for (File f : store.list()) {
                String name = f.getName();
                names.add(name);
                if (!lib.hidden(name)) {
                    queue.add(new Slide(name, null, new String[]{name}, new float[][]{lib.frame(name)}));
                }
            }
            for (Library.Combo c : lib.combos(names)) {
                if (!c.off) queue.add(slide(c));
            }
            // Only the switched-on playlists, when there are any (and they have something to show).
            List<Set<String>> on = new ArrayList<>();
            for (Library.Playlist pl : lib.playlists()) if (pl.on) on.add(new HashSet<>(pl.items));
            List<String> keys = new ArrayList<>();
            for (Slide sl : queue) keys.add(sl.key);
            Set<String> keep = new HashSet<>(Playing.filter(keys, on));
            queue.removeIf(sl -> !keep.contains(sl.key));
            Collections.shuffle(queue);
            String last = cursor >= 0 && cursor < history.size() ? history.get(cursor).key : null;
            if (queue.size() > 1 && queue.get(0).key.equals(last)) queue.add(queue.remove(0));
        }
        return null;
    }

    /** Changes whenever a playlist is switched on or off, or a playing playlist gains or loses items. */
    private static String playingSignature(Library lib) {
        StringBuilder sb = new StringBuilder();
        for (Library.Playlist pl : lib.playlists()) {
            if (pl.on) sb.append(pl.id).append(':').append(pl.items.hashCode()).append(';');
        }
        return sb.toString();
    }

    private static Slide slide(Library.Combo c) {
        String[] photos = new String[c.cells.size()];
        float[][] frames = new float[photos.length][];
        for (int i = 0; i < photos.length; i++) {
            Library.Cell cell = c.cells.get(i);
            photos[i] = cell.photo;
            frames[i] = new float[]{cell.x, cell.y, cell.z};
        }
        return new Slide(c.id, c.layout, photos, frames);
    }

    /**
     * The slide as the library has it now, read just before it is shown: a framing, a Show switch or a
     * combination may have changed on the phone since it was queued. Null when it is hidden or gone.
     */
    private Slide refresh(Slide s) {
        Library lib = Library.read(store.library);
        if (s.layout == null) {
            String name = s.photos[0];
            File f = store.photo(name);
            if (f == null || !f.isFile() || lib.hidden(name)) return null;
            return new Slide(s.key, null, s.photos, new float[][]{lib.frame(name)});
        }
        Set<String> names = new HashSet<>();
        for (File f : store.list()) names.add(f.getName());
        for (Library.Combo c : lib.combos(names)) {
            if (c.id.equals(s.key)) return c.off ? null : slide(c);
        }
        return null;
    }

    /**
     * Decodes a photo no larger than its box needs. A single photo without a framing of its own gets the
     * default here, once its shape is known: fill the screen, or (Fill off) show it whole unless it is
     * already close to the screen's shape.
     */
    private Bitmap decode(File f, String layout, int index, float[][] frames) {
        if (f == null || !f.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int[] box = Framing.pixelBoxes(layout, screenW, screenH)[index];
            if (frames[index] == null) {
                float ratio = (float) o.outWidth / o.outHeight, screen = (float) screenW / screenH;
                boolean crop = fill || Math.abs(ratio / screen - 1f) < 0.2f;
                frames[index] = new float[]{.5f, .5f, crop ? 1f : 0f};
            }
            float cover = Math.max((float) box[2] / o.outWidth, (float) box[3] / o.outHeight);
            float z = Math.max(Framing.fitZoom(box[2], box[3], o.outWidth, o.outHeight),
                    Math.min(Framing.MAX_ZOOM, frames[index][2]));
            float scale = cover * z; // photo pixels to screen pixels
            o.inSampleSize = 1;
            while (o.inSampleSize * 2 * scale <= 1f) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private void show(String layout, Bitmap[] bitmaps, float[][] frames, boolean quick) {
        SlideView incoming = back, outgoing = front;
        incoming.animate().cancel();
        outgoing.animate().cancel();
        outgoing.setAlpha(1f);
        incoming.set(layout, bitmaps, frames);
        incoming.setAlpha(0f);
        incoming.setTranslationZ(1f); // above the outgoing slide, below the clock (Z 2)
        outgoing.setTranslationZ(0f);
        incoming.animate().alpha(1f).setDuration(quick ? STEP_FADE_MS : FADE_MS).withEndAction(outgoing::clear).start();
        front = incoming;
        back = outgoing;
    }

    private static void recycle(Bitmap[] bitmaps) {
        for (Bitmap b : bitmaps) if (b != null) b.recycle();
    }

    private void style(TextView v, int sp, Typeface face) {
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(face);
        v.setShadowLayer(dp(6), 0, dp(1), 0xAA000000);
        v.setGravity(Gravity.END);
        v.setMaxLines(1);
    }

    private static LinearLayout.LayoutParams row() { return new LinearLayout.LayoutParams(-1, -2); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
