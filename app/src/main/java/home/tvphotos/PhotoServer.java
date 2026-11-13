package home.tvphotos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The phone page: a small HTTP server on the home network. GET / is the page; everything else needs the
 * PIN cookie. Runs as a foreground service so the TV keeps it alive, and starts again at boot.
 */
public class PhotoServer extends Service {
    static final int PORT = 8080;
    private static final String TAG = "TVPhotos";
    private static final long MAX_UPLOAD = 60L * 1024 * 1024;
    private static final long KEEP_FREE = 300L * 1024 * 1024;
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    private ServerSocket server;
    private ExecutorService pool;
    private Store store;
    private final Object imageLock = new Object();
    private final Object loginLock = new Object();
    private int failures;
    private long lockedUntil;

    static void start(Context context) {
        Intent intent = new Intent(context, PhotoServer.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("server", "Photo page", NotificationManager.IMPORTANCE_MIN));
        String url = Store.url();
        Notification n = new Notification.Builder(this, "server")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TV Photos")
                .setContentText(url == null ? "Waiting for the network" : "Add photos at " + url)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);

        pool = Executors.newFixedThreadPool(4);
        Thread acceptor = new Thread(this::acceptLoop, "tvphotos-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }

    private void acceptLoop() {
        // The port can still be held for a moment after a restart; keep trying instead of giving up.
        while (server == null) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT), 16);
                server = s;
            } catch (IOException e) {
                Log.w(TAG, "port " + PORT + " busy, retrying", e);
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
        Log.i(TAG, "listening on " + Store.url());
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(30000);
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (server.isClosed()) return;
            } catch (RuntimeException e) {
                Log.w(TAG, "rejected connection", e);
            }
        }
    }

    // ---- HTTP -------------------------------------------------------------------------------

    private void handle(Socket socket) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            String request = readLine(in);
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length < 2) { text(out, 400, "Bad request"); return; }
            String method = parts[0];
            String target = parts[1];
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            route(method, path, query, headers, in, out);
            out.flush();
        } catch (IOException e) {
            Log.d(TAG, "connection ended: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.w(TAG, "request failed", e);
        }
    }

    private void route(String method, String path, String query, Map<String, String> headers,
                       InputStream in, OutputStream out) throws IOException {
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            page(out);
            return;
        }
        if ("POST".equals(method) && "/api/login".equals(path)) {
            login(body(in, headers, 200), out);
            return;
        }
        if (!signedIn(headers)) {
            json(out, 401, "{\"error\":\"PIN needed\"}");
            return;
        }
        if ("GET".equals(method) && "/api/photos".equals(path)) {
            list(out);
        } else if ("POST".equals(method) && "/api/upload".equals(path)) {
            upload(headers, query, in, out);
        } else if ("POST".equals(method) && "/api/playlist".equals(path)) {
            savePlaylist(body(in, headers, 1000), out);
        } else if ("POST".equals(method) && "/api/playlist/delete".equals(path)) {
            deletePlaylist(body(in, headers, 200), out);
        } else if ("POST".equals(method) && "/api/playlist/items".equals(path)) {
            playlistItems(body(in, headers, 64 * 1024), out);
        } else if ("POST".equals(method) && "/api/playlist/play".equals(path)) {
            playPlaylist(body(in, headers, 200), out);
        } else if ("POST".equals(method) && "/api/delete".equals(path)) {
            delete(body(in, headers, 64 * 1024), out);
        } else if ("POST".equals(method) && "/api/frame".equals(path)) {
            frame(body(in, headers, 500), out);
        } else if ("POST".equals(method) && "/api/show".equals(path)) {
            show(body(in, headers, 64 * 1024), out);
        } else if ("POST".equals(method) && "/api/combo".equals(path)) {
            combo(body(in, headers, 8 * 1024), out);
        } else if ("POST".equals(method) && "/api/combo/delete".equals(path)) {
            deleteCombo(body(in, headers, 200), out);
        } else if ("POST".equals(method) && "/api/settings".equals(path)) {
            settings(body(in, headers, 200), out);
        } else if ("POST".equals(method) && "/api/pin".equals(path)) {
            changePin(body(in, headers, 200), out);
        } else if ("GET".equals(method) && "/api/places".equals(path)) {
            places(query, out);
        } else if ("POST".equals(method) && "/api/weather".equals(path)) {
            weather(body(in, headers, 500), out);
        } else if ("GET".equals(method) && path.startsWith("/thumb/")) {
            file(out, store.thumb(path.substring(7)));
        } else if ("GET".equals(method) && path.startsWith("/photo/")) {
            file(out, store.photo(path.substring(7)));
        } else {
            text(out, 404, "Not found");
        }
    }

    private boolean signedIn(Map<String, String> headers) {
        String cookies = headers.get("cookie");
        if (cookies == null) return false;
        String token = store.token();
        for (String c : cookies.split(";")) {
            String kv = c.trim();
            if (kv.startsWith("tvp=") && !token.isEmpty() && constantEquals(kv.substring(4), token)) return true;
        }
        return false;
    }

    private static boolean constantEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    // ---- Handlers ---------------------------------------------------------------------------

    private void page(OutputStream out) throws IOException {
        byte[] html;
        try (InputStream a = getAssets().open("index.html")) { html = readAll(a); }
        head(out, 200, "text/html; charset=utf-8", html.length, "Cache-Control: no-store");
        out.write(html);
    }

    private void login(String body, OutputStream out) throws IOException {
        String pin = body.replaceAll("[^0-9]", "");
        synchronized (loginLock) {
            long now = System.currentTimeMillis();
            if (now < lockedUntil) {
                json(out, 429, "{\"error\":\"Too many wrong PINs. Wait a minute and try again.\"}");
                return;
            }
            if (pin.length() == 4 && constantEquals(pin, store.pin())) {
                failures = 0;
                byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                head(out, 200, "application/json", b.length, "Cache-Control: no-store",
                        "Set-Cookie: tvp=" + store.token() + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict");
                out.write(b);
                return;
            }
            if (++failures >= 5) {
                failures = 0;
                lockedUntil = now + 60_000;
            }
        }
        json(out, 403, "{\"error\":\"Wrong PIN. Forgot it? Open TV Photos on the TV and choose New random PIN.\"}");
    }

    private void list(OutputStream out) throws IOException {
        List<String> names = names();
        StringBuilder sb = new StringBuilder("{\"photos\":[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(names.get(i)).append('"');
        }
        sb.append("],").append(Library.read(store.library).toJson(names))
                .append(",\"interval\":").append(store.intervalSeconds())
                .append(",\"clock\":").append(store.showClock())
                .append(",\"fill\":").append(store.fill())
                .append(",\"qr\":").append(store.showQr())
                .append(",\"weather\":").append(store.weatherSwitch())
                .append(",\"place\":").append(JSONObject.quote(store.weatherPlace()))
                .append(",\"free\":").append(store.freeBytes())
                .append('}');
        json(out, 200, sb.toString());
    }

    /**
     * pin=1234 sets that PIN; random=1 makes a random one and returns it, the only time a PIN is ever shown.
     * Other phones are logged out; this one gets the new cookie and stays in.
     */
    private void changePin(String body, OutputStream out) throws IOException {
        Map<String, String> form = form(body);
        String shown = null;
        if ("1".equals(form.get("random"))) {
            shown = store.newPin();
        } else if (!store.setPin(form.get("pin"))) {
            json(out, 400, "{\"error\":\"The PIN must be 4 digits\"}");
            return;
        }
        byte[] b = (shown == null ? "{\"ok\":true}" : "{\"ok\":true,\"pin\":\"" + shown + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        head(out, 200, "application/json", b.length, "Cache-Control: no-store",
                "Set-Cookie: tvp=" + store.token() + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict");
        out.write(b);
    }

    /** GET /api/places?q=town: Open-Meteo's matches, for the phone to pick from. */
    private void places(String query, OutputStream out) throws IOException {
        String q = "";
        for (String pair : query.split("&")) {
            if (pair.startsWith("q=")) q = URLDecoder.decode(pair.substring(2), "UTF-8").trim();
        }
        if (q.length() < 2) { json(out, 400, "{\"error\":\"Type at least 2 letters\"}"); return; }
        try {
            JSONArray found = Weather.search(q);
            JSONArray list = new JSONArray();
            for (int i = 0; i < found.length(); i++) {
                JSONObject r = found.getJSONObject(i);
                StringBuilder label = new StringBuilder(r.optString("name"));
                for (String k : new String[]{"admin2", "admin1", "country"}) {
                    String v = r.optString(k, "");
                    if (!v.isEmpty() && label.indexOf(v) < 0) label.append(", ").append(v);
                }
                list.put(new JSONObject().put("name", r.optString("name")).put("label", label.toString())
                        .put("lat", r.getDouble("latitude")).put("lon", r.getDouble("longitude")));
            }
            json(out, 200, new JSONObject().put("places", list).toString());
        } catch (IOException | JSONException e) {
            json(out, 502, "{\"error\":\"Could not reach the weather service. Try again in a moment.\"}");
        }
    }

    /** on=0/1 switches the forecast; name, lat and lon set the town (and fetch a first forecast to check it). */
    private void weather(String body, OutputStream out) throws IOException {
        Map<String, String> form = form(body);
        if (form.containsKey("on")) store.setWeatherOn("1".equals(form.get("on")));
        if (form.containsKey("name")) {
            double lat, lon;
            try {
                lat = Double.parseDouble(form.get("lat"));
                lon = Double.parseDouble(form.get("lon"));
            } catch (RuntimeException e) {
                json(out, 400, "{\"error\":\"Unknown place\"}");
                return;
            }
            String name = form.get("name").trim();
            if (name.isEmpty() || name.length() > 60 || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                json(out, 400, "{\"error\":\"Unknown place\"}");
                return;
            }
            store.setWeatherPlace(name, lat, lon);
            try {
                String forecast = Weather.fetch(lat, lon);
                store.setWeatherCache(forecast, System.currentTimeMillis());
                JSONObject now = new JSONObject(forecast).getJSONObject("current");
                json(out, 200, new JSONObject().put("ok", true).put("place", name)
                        .put("now", Math.round(now.getDouble("temperature_2m")) + "° "
                                + Weather.words(now.getInt("weather_code"))).toString());
            } catch (IOException | JSONException e) {
                // Saved; the forecast itself just could not be fetched right now.
                json(out, 200, "{\"ok\":true,\"place\":" + JSONObject.quote(name) + "}");
            }
            return;
        }
        json(out, 200, "{\"ok\":true}");
    }

    private static Map<String, String> form(String body) throws IOException {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) form.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return form;
    }

    private static String queryParam(String query, String key) throws IOException {
        for (String pair : query.split("&")) {
            if (pair.startsWith(key + "=")) return URLDecoder.decode(pair.substring(key.length() + 1), "UTF-8");
        }
        return null;
    }

    /** ?playlist=id puts the new photo straight into that playlist. */
    private void upload(Map<String, String> headers, String query, InputStream in, OutputStream out) throws IOException {
        long length;
        try {
            length = Long.parseLong(headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException e) {
            length = -1;
        }
        if (length <= 0) { json(out, 411, "{\"error\":\"Empty upload\"}"); return; }
        if (length > MAX_UPLOAD) { json(out, 413, "{\"error\":\"That file is too big (60 MB max)\"}"); return; }
        if (store.freeBytes() < length + KEEP_FREE) { json(out, 507, "{\"error\":\"The TV is out of space\"}"); return; }

        File tmp = File.createTempFile("upload", ".bin", getCacheDir());
        try {
            try (FileOutputStream f = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long left = length;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("upload cut short");
                    f.write(buf, 0, n);
                    left -= n;
                }
            }
            String name;
            synchronized (imageLock) { // one decode at a time: the TV has little memory to spare
                try {
                    name = store.add(tmp);
                } catch (IOException e) {
                    json(out, 415, "{\"error\":\"Could not read that picture (" + e.getMessage() + ")\"}");
                    return;
                } catch (OutOfMemoryError e) {
                    json(out, 413, "{\"error\":\"That picture is too large for the TV\"}");
                    return;
                }
            }
            String playlist = queryParam(query, "playlist");
            if (playlist != null && Library.PLAYLIST_ID.matcher(playlist).matches()) {
                String added = name;
                Library.edit(store.library, lib -> lib.setInPlaylist(playlist, Collections.singletonList(added), true));
            }
            json(out, 200, "{\"ok\":true,\"name\":\"" + name + "\"}");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private void delete(String body, OutputStream out) throws IOException {
        List<String> gone = new ArrayList<>();
        Matcher m = QUOTED.matcher(body);
        while (m.find()) {
            if (store.delete(m.group(1))) gone.add(m.group(1));
        }
        int[] combos = {0};
        if (!gone.isEmpty()) Library.edit(store.library, lib -> combos[0] = lib.forgetPhotos(gone));
        json(out, 200, "{\"deleted\":" + gone.size() + ",\"combosRemoved\":" + combos[0] + "}");
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        for (File f : store.list()) names.add(f.getName());
        return names;
    }

    private boolean exists(String name) {
        File f = store.photo(name);
        return f != null && f.isFile();
    }

    /** name + x, y, z stores a framing; name + reset=1 goes back to the default. */
    private void frame(String body, OutputStream out) throws IOException {
        Map<String, String> form = form(body);
        String name = form.get("name");
        if (!exists(name)) { json(out, 404, "{\"error\":\"That photo is not on the TV any more\"}"); return; }
        if ("1".equals(form.get("reset"))) {
            Library.edit(store.library, lib -> lib.clearFrame(name));
        } else {
            float x, y, z;
            try {
                x = Float.parseFloat(form.get("x"));
                y = Float.parseFloat(form.get("y"));
                z = Float.parseFloat(form.get("z"));
            } catch (RuntimeException e) {
                json(out, 400, "{\"error\":\"Bad framing\"}");
                return;
            }
            if (Float.isNaN(x + y + z) || Float.isInfinite(x + y + z)) { json(out, 400, "{\"error\":\"Bad framing\"}"); return; }
            Library.edit(store.library, lib -> lib.setFrame(name, x, y, z));
        }
        json(out, 200, "{\"ok\":true}");
    }

    /** on=0 hides, on=1 shows: names=a.jpg,b.jpg for photos, combos=c..,c.. for combinations. */
    private void show(String body, OutputStream out) throws IOException {
        Map<String, String> form = form(body);
        if (!form.containsKey("on")) { json(out, 400, "{\"error\":\"on=0 or on=1\"}"); return; }
        boolean off = !"1".equals(form.get("on"));
        List<String> names = new ArrayList<>(), ids = new ArrayList<>();
        for (String n : form.getOrDefault("names", "").split(",")) if (exists(n)) names.add(n);
        for (String id : form.getOrDefault("combos", "").split(",")) if (Library.COMBO_ID.matcher(id).matches()) ids.add(id);
        int[] changed = {0};
        Library.edit(store.library, lib -> {
            for (String n : names) { lib.setHidden(n, off); changed[0]++; }
            for (String id : ids) if (lib.setComboHidden(id, off)) changed[0]++;
        });
        json(out, 200, "{\"ok\":true,\"changed\":" + changed[0] + "}");
    }

    /** A JSON combination ({@link Library#parseCombo}); with a known id it replaces that one. */
    private void combo(String body, OutputStream out) throws IOException {
        Library.Combo c;
        try {
            c = Library.parseCombo(new JSONObject(body));
        } catch (JSONException e) {
            c = null;
        }
        if (c == null) { json(out, 400, "{\"error\":\"Bad combination\"}"); return; }
        for (Library.Cell cell : c.cells) {
            if (!exists(cell.photo)) { json(out, 404, "{\"error\":\"A photo in it is not on the TV any more\"}"); return; }
        }
        Library.Combo combo = c;
        String[] id = {null};
        Library.edit(store.library, lib -> id[0] = lib.putCombo(combo));
        json(out, 200, "{\"ok\":true,\"id\":\"" + id[0] + "\"}");
    }

    // ---- Playlists ----------------------------------------------------------------------------

    private static String playlistId(Map<String, String> form) {
        String id = form.getOrDefault("id", "");
        return Library.PLAYLIST_ID.matcher(id).matches() ? id : null;
    }

    /** name creates a playlist (returns its id); id + name renames one. */
    private void savePlaylist(String body, OutputStream out) throws IOException {
        Map<String, String> form = form(body);
        String name = Library.cleanName(form.get("name"));
        if (name == null) { json(out, 400, "{\"error\":\"Give the playlist a name (up to 40 letters)\"}"); return; }
        if (form.containsKey("id")) {
            String id = playlistId(form);
            boolean[] ok = {false};
            if (id != null) Library.edit(store.library, lib -> ok[0] = lib.renamePlaylist(id, name));
            if (ok[0]) json(out, 200, "{\"ok\":true,\"id\":\"" + id + "\"}");
            else json(out, 404, "{\"error\":\"Unknown playlist\"}");
            return;
        }
        String[] id = {null};
        Library.edit(store.library, lib -> id[0] = lib.addPlaylist(name));
        json(out, 200, "{\"ok\":true,\"id\":\"" + id[0] + "\"}");
    }

    /** Deletes a playlist only; its photos and combinations stay. */
    private void deletePlaylist(String body, OutputStream out) throws IOException {
        String id = playlistId(form(body));
        boolean[] ok = {false};
        if (id != null) Library.edit(store.library, lib -> ok[0] = lib.deletePlaylist(id));
        json(out, ok[0] ? 200 : 404, ok[0] ? "{\"ok\":true}" : "{\"error\":\"Unknown playlist\"}");
    }

    /** id + add=1/0 + names=a.jpg,b.jpg + combos=c..,c..: puts items in a playlist or takes them out. */
    private void playlistItems(String body, OutputStream out) throws IOException {
        Map<String, String> form = form(body);
        String id = playlistId(form);
        if (id == null) { json(out, 404, "{\"error\":\"Unknown playlist\"}"); return; }
        boolean add = "1".equals(form.get("add"));
        List<String> names = names();
        java.util.Set<String> have = new java.util.HashSet<>(names);
        List<String> keys = new ArrayList<>();
        for (String n : form.getOrDefault("names", "").split(",")) {
            if (Store.validName(n) && (!add || have.contains(n))) keys.add(n);
        }
        List<String> ids = new ArrayList<>();
        for (String c : form.getOrDefault("combos", "").split(",")) {
            if (Library.COMBO_ID.matcher(c).matches()) ids.add(c);
        }
        int[] changed = {0};
        Library.edit(store.library, lib -> {
            List<String> all = new ArrayList<>(keys);
            if (add) { // only combinations that exist
                for (Library.Combo c : lib.combos(have)) if (ids.contains(c.id)) all.add(c.id);
            } else {
                all.addAll(ids);
            }
            changed[0] = lib.setInPlaylist(id, all, add);
        });
        if (changed[0] < 0) { json(out, 404, "{\"error\":\"Unknown playlist\"}"); return; }
        json(out, 200, "{\"ok\":true,\"changed\":" + changed[0] + "}");
    }

    /** id + on=1/0: whether the screensaver plays this playlist. */
    private void playPlaylist(String body, OutputStream out) throws IOException {
        Map<String, String> form = form(body);
        String id = playlistId(form);
        boolean on = "1".equals(form.get("on"));
        boolean[] ok = {false};
        if (id != null) Library.edit(store.library, lib -> ok[0] = lib.setPlaying(id, on));
        json(out, ok[0] ? 200 : 404, ok[0] ? "{\"ok\":true}" : "{\"error\":\"Unknown playlist\"}");
    }

    /** Deletes a combination only; its photos stay. */
    private void deleteCombo(String body, OutputStream out) throws IOException {
        String id = form(body).getOrDefault("id", "");
        if (!Library.COMBO_ID.matcher(id).matches()) { json(out, 400, "{\"error\":\"Unknown combination\"}"); return; }
        boolean[] gone = {false};
        Library.edit(store.library, lib -> gone[0] = lib.removeCombo(id));
        json(out, gone[0] ? 200 : 404, gone[0] ? "{\"ok\":true}" : "{\"error\":\"Unknown combination\"}");
    }

    private void settings(String body, OutputStream out) throws IOException {
        Map<String, String> form = form(body);
        if (form.containsKey("interval")) {
            try {
                if (!store.setInterval(Integer.parseInt(form.get("interval")))) {
                    json(out, 400, "{\"error\":\"Unknown interval\"}");
                    return;
                }
            } catch (NumberFormatException e) {
                json(out, 400, "{\"error\":\"Unknown interval\"}");
                return;
            }
        }
        if (form.containsKey("clock")) store.setClock("1".equals(form.get("clock")));
        if (form.containsKey("fill")) store.setFill("1".equals(form.get("fill")));
        if (form.containsKey("qr")) store.setQr("1".equals(form.get("qr")));
        json(out, 200, "{\"ok\":true}");
    }

    private void file(OutputStream out, File f) throws IOException {
        if (f == null || !f.isFile()) { text(out, 404, "Not found"); return; }
        head(out, 200, "image/jpeg", f.length(), "Cache-Control: private, max-age=31536000, immutable");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ---- Low-level helpers ------------------------------------------------------------------

    private static String body(InputStream in, Map<String, String> headers, int max) throws IOException {
        int length;
        try {
            length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException e) {
            length = 0;
        }
        if (length <= 0) return "";
        if (length > max) throw new IOException("body too large");
        byte[] b = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(b, off, length - off);
            if (n < 0) break;
            off += n;
        }
        return new String(b, 0, off, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) throw new IOException("header line too long");
        }
        if (c == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void json(OutputStream out, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        head(out, code, "application/json", b.length, "Cache-Control: no-store");
        out.write(b);
    }

    private static void text(OutputStream out, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        head(out, code, "text/plain; charset=utf-8", b.length);
        out.write(b);
    }

    private static void head(OutputStream out, int code, String type, long length, String... extra) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Connection: close\r\n");
        for (String e : extra) h.append(e).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 429: return "Too Many Requests";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }
}
