package home.tvphotos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the photo files themselves don't say: each photo's framing and Show-on-TV switch, the
 * combinations and the playlists. Kept in files/library.json. A Library is a snapshot: read() for looking, edit() to
 * change it, which reads, applies and writes under one lock (the page server and the screensaver run in
 * the same process).
 */
final class Library {
    static final Pattern COMBO_ID = Pattern.compile("^c[0-9]{13}_[0-9a-f]{6}$");
    static final Pattern PLAYLIST_ID = Pattern.compile("^p[0-9]{13}_[0-9a-f]{6}$");
    static final int MAX_NAME = 40;
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Cell {
        final String photo;
        final float x, y, z;

        Cell(String photo, float x, float y, float z) {
            this.photo = photo;
            this.x = clamp(x, 0, 1);
            this.y = clamp(y, 0, 1);
            this.z = clamp(z, 0.05f, Framing.MAX_ZOOM);
        }
    }

    static final class Combo {
        String id;
        final String layout;
        boolean off;
        final List<Cell> cells;

        Combo(String id, String layout, boolean off, List<Cell> cells) {
            this.id = id;
            this.layout = layout;
            this.off = off;
            this.cells = cells;
        }
    }

    /** A named set of photo names and combination ids; "on" means the screensaver plays it. */
    static final class Playlist {
        final String id;
        String name;
        boolean on;
        final List<String> items;

        Playlist(String id, String name, boolean on, List<String> items) {
            this.id = id;
            this.name = name;
            this.on = on;
            this.items = items;
        }
    }

    interface Change { void apply(Library lib); }

    private final Map<String, float[]> frames = new HashMap<>();
    private final Set<String> hidden = new HashSet<>();
    private final List<Combo> combos = new ArrayList<>();
    private final List<Playlist> playlists = new ArrayList<>();

    private Library() {}

    // ---- Reading and writing ----------------------------------------------------------------

    static Library read(File file) {
        synchronized (LOCK) { return load(file); }
    }

    static Library edit(File file, Change change) throws IOException {
        synchronized (LOCK) {
            Library lib = load(file);
            change.apply(lib);
            lib.save(file);
            return lib;
        }
    }

    private static Library load(File file) {
        Library lib = new Library();
        if (!file.isFile()) return lib;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) Math.min(file.length(), 4 * 1024 * 1024)];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONObject root = new JSONObject(new String(b, 0, off, StandardCharsets.UTF_8));
            JSONObject photos = root.optJSONObject("photos");
            if (photos != null) {
                for (Iterator<String> it = photos.keys(); it.hasNext(); ) {
                    String name = it.next();
                    if (!Store.validName(name)) continue;
                    JSONObject p = photos.getJSONObject(name);
                    if (p.has("z")) {
                        Cell c = new Cell(name, (float) p.optDouble("x", .5), (float) p.optDouble("y", .5), (float) p.optDouble("z", 1));
                        lib.frames.put(name, new float[]{c.x, c.y, c.z});
                    }
                    if (p.optBoolean("off")) lib.hidden.add(name);
                }
            }
            JSONArray list = root.optJSONArray("combos");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    Combo c = parseCombo(list.getJSONObject(i));
                    if (c != null && c.id != null) lib.combos.add(c);
                }
            }
            JSONArray lists = root.optJSONArray("playlists");
            if (lists != null) {
                for (int i = 0; i < lists.length(); i++) {
                    JSONObject o = lists.getJSONObject(i);
                    String id = o.optString("id"), name = cleanName(o.optString("name"));
                    if (!PLAYLIST_ID.matcher(id).matches() || name == null) continue;
                    List<String> items = new ArrayList<>();
                    JSONArray a = o.optJSONArray("items");
                    for (int j = 0; a != null && j < a.length(); j++) {
                        String k = a.optString(j);
                        if (validKey(k) && !items.contains(k)) items.add(k);
                    }
                    lib.playlists.add(new Playlist(id, name, o.optBoolean("on"), items));
                }
            }
        } catch (IOException | JSONException e) {
            // A damaged file loses framings and combinations, never photos: start empty.
        }
        return lib;
    }

    private void save(File file) throws IOException {
        String text;
        try {
            JSONObject photos = new JSONObject();
            Set<String> names = new HashSet<>(frames.keySet());
            names.addAll(hidden);
            for (String name : names) {
                JSONObject p = new JSONObject();
                float[] f = frames.get(name);
                if (f != null) p.put("x", f[0]).put("y", f[1]).put("z", f[2]);
                if (hidden.contains(name)) p.put("off", true);
                photos.put(name, p);
            }
            JSONArray list = new JSONArray();
            for (Combo c : combos) list.put(comboJson(c));
            JSONArray lists = new JSONArray();
            for (Playlist pl : playlists) lists.put(playlistJson(pl, null));
            text = new JSONObject().put("photos", photos).put("combos", list).put("playlists", lists).toString();
        } catch (JSONException e) {
            throw new IOException("could not save the library", e);
        }
        File tmp = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not save the library");
        }
    }

    // ---- Combinations as JSON (file, page and the page's saves share one shape) -------------

    /**
     * {"id":..., "layout":"pair", "off":false, "cells":[{"p":"name.jpg","x":0.5,"y":0.5,"z":1}]}.
     * Null when the layout is unknown, a name is invalid or the cell count does not match the layout.
     */
    static Combo parseCombo(JSONObject o) throws JSONException {
        String layout = o.optString("layout");
        JSONArray cells = o.optJSONArray("cells");
        if (cells == null || Framing.cells(layout) == 0 || cells.length() != Framing.cells(layout)) return null;
        List<Cell> list = new ArrayList<>();
        for (int i = 0; i < cells.length(); i++) {
            JSONObject c = cells.getJSONObject(i);
            String p = c.optString("p");
            double x = c.optDouble("x", .5), y = c.optDouble("y", .5), z = c.optDouble("z", 1);
            if (!Store.validName(p) || !finite(x) || !finite(y) || !finite(z)) return null;
            list.add(new Cell(p, (float) x, (float) y, (float) z));
        }
        String id = o.optString("id", "");
        return new Combo(COMBO_ID.matcher(id).matches() ? id : null, layout, o.optBoolean("off"), list);
    }

    private static JSONObject comboJson(Combo c) throws JSONException {
        JSONArray cells = new JSONArray();
        for (Cell cell : c.cells) {
            cells.put(new JSONObject().put("p", cell.photo).put("x", cell.x).put("y", cell.y).put("z", cell.z));
        }
        return new JSONObject().put("id", c.id).put("layout", c.layout).put("off", c.off).put("cells", cells);
    }

    /** Only the items in "keep" when it is given (the page never sees deleted photos). */
    private static JSONObject playlistJson(Playlist pl, Set<String> keep) throws JSONException {
        JSONArray items = new JSONArray();
        for (String k : pl.items) if (keep == null || keep.contains(k)) items.put(k);
        return new JSONObject().put("id", pl.id).put("name", pl.name).put("on", pl.on).put("items", items);
    }

    /**
     * The page's view: "meta" (framing and hidden per photo), "combos" and "playlists", leaving out
     * anything that names a photo no longer on the TV.
     */
    String toJson(Collection<String> existing) {
        try {
            Set<String> have = new HashSet<>(existing);
            JSONObject meta = new JSONObject();
            Set<String> names = new HashSet<>(frames.keySet());
            names.addAll(hidden);
            for (String name : names) {
                if (!have.contains(name)) continue;
                JSONObject p = new JSONObject();
                float[] f = frames.get(name);
                if (f != null) p.put("x", f[0]).put("y", f[1]).put("z", f[2]);
                if (hidden.contains(name)) p.put("off", true);
                meta.put(name, p);
            }
            JSONArray list = new JSONArray();
            Set<String> keys = new HashSet<>(have);
            for (Combo c : combos(have)) {
                list.put(comboJson(c));
                keys.add(c.id);
            }
            JSONArray lists = new JSONArray();
            for (Playlist pl : playlists) lists.put(playlistJson(pl, keys));
            return "\"meta\":" + meta + ",\"combos\":" + list + ",\"playlists\":" + lists;
        } catch (JSONException e) {
            return "\"meta\":{},\"combos\":[],\"playlists\":[]";
        }
    }

    // ---- Looking ----------------------------------------------------------------------------

    /** {x, y, z}, or null when the photo has not been framed by hand. */
    float[] frame(String name) { return frames.get(name); }

    boolean hidden(String name) { return hidden.contains(name); }

    /** The combinations whose photos are all still on the TV, in the order they were made. */
    List<Combo> combos(Set<String> existing) {
        List<Combo> out = new ArrayList<>();
        for (Combo c : combos) {
            boolean ok = true;
            for (Cell cell : c.cells) ok &= existing.contains(cell.photo);
            if (ok) out.add(c);
        }
        return out;
    }

    /** In the order they were made. */
    List<Playlist> playlists() { return playlists; }

    /** A photo name or a combination id. */
    static boolean validKey(String key) {
        return key != null && (Store.validName(key) || COMBO_ID.matcher(key).matches());
    }

    /** Trimmed, 1 to 40 characters, no control characters; null when not usable. */
    static String cleanName(String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.isEmpty() || name.length() > MAX_NAME) return null;
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return null;
        return name;
    }

    // ---- Changing (inside edit()) -----------------------------------------------------------

    /** A new, empty playlist that does not play yet; returns its id. The name must be clean. */
    String addPlaylist(String name) {
        String id = String.format(Locale.ROOT, "p%013d_%06x", System.currentTimeMillis(), RANDOM.nextInt(0x1000000));
        playlists.add(new Playlist(id, name, false, new ArrayList<>()));
        return id;
    }

    private Playlist playlist(String id) {
        for (Playlist pl : playlists) if (pl.id.equals(id)) return pl;
        return null;
    }

    boolean renamePlaylist(String id, String name) {
        Playlist pl = playlist(id);
        if (pl == null) return false;
        pl.name = name;
        return true;
    }

    /** Deletes the playlist only; its photos and combinations stay. */
    boolean deletePlaylist(String id) { return playlists.remove(playlist(id)); }

    /** Adds (at the end, skipping ones already in it) or removes keys; -1 for an unknown playlist. */
    int setInPlaylist(String id, Collection<String> keys, boolean add) {
        Playlist pl = playlist(id);
        if (pl == null) return -1;
        int changed = 0;
        for (String k : keys) {
            if (add ? !pl.items.contains(k) && pl.items.add(k) : pl.items.remove(k)) changed++;
        }
        return changed;
    }

    boolean setPlaying(String id, boolean on) {
        Playlist pl = playlist(id);
        if (pl == null) return false;
        pl.on = on;
        return true;
    }

    private void forgetKeys(Collection<String> keys) {
        for (Playlist pl : playlists) pl.items.removeAll(keys);
    }

    void setFrame(String name, float x, float y, float z) {
        Cell c = new Cell(name, x, y, z);
        frames.put(name, new float[]{c.x, c.y, c.z});
    }

    void clearFrame(String name) { frames.remove(name); }

    void setHidden(String name, boolean off) {
        if (off) hidden.add(name);
        else hidden.remove(name);
    }

    /** False when no combination has that id. */
    boolean setComboHidden(String id, boolean off) {
        for (Combo c : combos) {
            if (c.id.equals(id)) { c.off = off; return true; }
        }
        return false;
    }

    /** Adds a new combination (given a fresh id) or replaces the one with the same id; returns the id. */
    String putCombo(Combo combo) {
        if (combo.id != null) {
            for (int i = 0; i < combos.size(); i++) {
                if (combos.get(i).id.equals(combo.id)) { combos.set(i, combo); return combo.id; }
            }
        }
        combo.id = String.format(Locale.ROOT, "c%013d_%06x", System.currentTimeMillis(), RANDOM.nextInt(0x1000000));
        combos.add(combo);
        return combo.id;
    }

    boolean removeCombo(String id) {
        for (Iterator<Combo> it = combos.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(id)) {
                it.remove();
                forgetKeys(Collections.singleton(id));
                return true;
            }
        }
        return false;
    }

    /**
     * Forgets deleted photos: their framing, their switch, every combination that used them, and their
     * places in the playlists.
     */
    int forgetPhotos(Collection<String> names) {
        List<String> gone = new ArrayList<>(names);
        for (String name : names) {
            frames.remove(name);
            hidden.remove(name);
        }
        for (Iterator<Combo> it = combos.iterator(); it.hasNext(); ) {
            Combo c = it.next();
            for (Cell cell : c.cells) {
                if (names.contains(cell.photo)) { it.remove(); gone.add(c.id); break; }
            }
        }
        forgetKeys(gone);
        return gone.size() - names.size();
    }

    private static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return (lo + hi) / 2;
        return Math.max(lo, Math.min(hi, v));
    }
}
