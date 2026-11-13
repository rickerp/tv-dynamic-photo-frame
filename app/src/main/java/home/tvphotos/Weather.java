package home.tvphotos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * The forecast, from Open-Meteo (https://open-meteo.com): free, no account and no API key. A request carries
 * only the town's coordinates. It is made when the screensaver starts and the last forecast is over 30
 * minutes old, every 30 minutes while it runs, and once when the town is changed on the phone page.
 */
final class Weather {
    static final long REFRESH_MS = 30 * 60 * 1000L;
    /** An older forecast than this is not shown at all. */
    static final long STALE_MS = 6 * 60 * 60 * 1000L;

    private Weather() {}

    /** Current temperature and code, and 4 days of codes and highs and lows, in Celsius, in the town's time zone. */
    static String fetch(double lat, double lon) throws IOException {
        return get(String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                        + "&temperature_unit=celsius&timezone=auto&forecast_days=4", lat, lon));
    }

    /** Up to 6 towns matching the name, as Open-Meteo's geocoding answers them. */
    static JSONArray search(String name) throws IOException, JSONException {
        String json = get("https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name="
                + URLEncoder.encode(name, "UTF-8"));
        JSONArray results = new JSONObject(json).optJSONArray("results");
        return results == null ? new JSONArray() : results;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("User-Agent", "TVPhotos/1.4");
        try {
            if (c.getResponseCode() != 200) throw new IOException("Open-Meteo answered " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather code to a small picture. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";             // sun / moon
        if (code == 1) return day ? "🌤️" : "🌙";       // sun behind small cloud
        if (code == 2) return "⛅";                                           // sun behind cloud
        if (code == 3) return "☁️";                                     // cloud
        if (code == 45 || code == 48) return "🌫️";                // fog
        if (code >= 51 && code <= 57) return "🌦️";                // drizzle
        if (code >= 61 && code <= 67) return "🌧️";                // rain
        if (code >= 71 && code <= 77) return "🌨️";                // snow
        if (code >= 80 && code <= 82) return "🌦️";                // showers
        if (code == 85 || code == 86) return "🌨️";                // snow showers
        if (code >= 95) return "⛈️";                                    // thunderstorm
        return "";
    }

    /** WMO weather code to a word or two. */
    static String words(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }
}
