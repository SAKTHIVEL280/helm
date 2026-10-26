package com.helm.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Weather from Open-Meteo: free, no API key, no account. The city is chosen by name in the widget's
 * options (Helm never asks for your location). Results are cached for 30 minutes.
 */
final class Weather {
    static final class Now {
        int temp, high, low, code;
        String city;
        long fetched;
        final List<int[]> days = new ArrayList<>(); // {code, high, low}
    }

    static final class Place {
        final String name;
        final double lat, lon;

        Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final long FRESH = 30 * 60 * 1000L;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean inFlight;

    private Weather() {}

    static Now cached(Context c) {
        String s = Prefs.get(c).sp.getString(Prefs.WEATHER_CACHE, null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            Now n = new Now();
            n.temp = o.getInt("t");
            n.high = o.getInt("hi");
            n.low = o.getInt("lo");
            n.code = o.getInt("c");
            n.city = o.optString("city", "");
            n.fetched = o.getLong("at");
            JSONArray d = o.optJSONArray("days");
            if (d != null) for (int i = 0; i < d.length(); i++) {
                JSONArray a = d.getJSONArray(i);
                n.days.add(new int[]{a.getInt(0), a.getInt(1), a.getInt(2)});
            }
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean configured(Context c) {
        return !Prefs.get(c).sp.getString(Prefs.WEATHER_CITY, "").isEmpty();
    }

    /** Refreshes if stale; {@code done} gets the latest (possibly cached) value on the main thread. */
    private static final List<Consumer<Now>> WAITING = new ArrayList<>();
    private static boolean again;

    static void refresh(Context c, boolean force, Consumer<Now> done) {
        Prefs p = Prefs.get(c);
        Now cur = cached(c);
        if (!configured(c)) return;
        if (!force && cur != null && System.currentTimeMillis() - cur.fetched < FRESH) return;
        if (inFlight) {
            // Every caller hears back; a forced refresh (new city or unit) runs again after this one.
            if (done != null) WAITING.add(done);
            if (force) again = true;
            return;
        }
        inFlight = true;
        if (done != null) WAITING.add(done);
        double lat = Double.longBitsToDouble(p.sp.getLong(Prefs.WEATHER_LAT, 0));
        double lon = Double.longBitsToDouble(p.sp.getLong(Prefs.WEATHER_LON, 0));
        String city = p.sp.getString(Prefs.WEATHER_CITY, "");
        boolean f = p.sp.getInt(Prefs.WEATHER_UNIT, 0) == 1;
        NET.execute(() -> {
            Now n = null;
            try {
                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min"
                                + "&timezone=auto&forecast_days=5%s",
                        lat, lon, f ? "&temperature_unit=fahrenheit" : "");
                JSONObject o = new JSONObject(get(url));
                JSONObject now = o.getJSONObject("current");
                JSONObject daily = o.getJSONObject("daily");
                n = new Now();
                n.temp = (int) Math.round(now.getDouble("temperature_2m"));
                n.code = now.getInt("weather_code");
                JSONArray codes = daily.getJSONArray("weather_code");
                JSONArray max = daily.getJSONArray("temperature_2m_max");
                JSONArray min = daily.getJSONArray("temperature_2m_min");
                n.high = (int) Math.round(max.getDouble(0));
                n.low = (int) Math.round(min.getDouble(0));
                for (int i = 1; i < codes.length(); i++) {
                    n.days.add(new int[]{codes.getInt(i), (int) Math.round(max.getDouble(i)), (int) Math.round(min.getDouble(i))});
                }
                n.city = city;
                n.fetched = System.currentTimeMillis();
                JSONObject save = new JSONObject().put("t", n.temp).put("hi", n.high).put("lo", n.low)
                        .put("c", n.code).put("city", city).put("at", n.fetched);
                JSONArray d = new JSONArray();
                for (int[] day : n.days) d.put(new JSONArray().put(day[0]).put(day[1]).put(day[2]));
                save.put("days", d);
                p.sp.edit().putString(Prefs.WEATHER_CACHE, save.toString()).apply();
            } catch (Exception ignored) {
            }
            final Now result = n != null ? n : cached(c);
            MAIN.post(() -> {
                inFlight = false;
                List<Consumer<Now>> run = new ArrayList<>(WAITING);
                WAITING.clear();
                if (again) {
                    // Settings changed mid-fetch: this result is for the old city or unit. Everyone waits for the re-run.
                    again = false;
                    refresh(c, true, n2 -> {
                        for (Consumer<Now> d : run) d.accept(n2);
                    });
                    return;
                }
                if (result != null) for (Consumer<Now> d : run) d.accept(result);
            });
        });
    }

    /** City search by name. */
    static void search(String query, Consumer<List<Place>> done) {
        NET.execute(() -> {
            List<Place> out = new ArrayList<>();
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&name="
                        + URLEncoder.encode(query, "UTF-8");
                JSONObject o = new JSONObject(get(url));
                JSONArray r = o.optJSONArray("results");
                if (r != null) for (int i = 0; i < r.length(); i++) {
                    JSONObject x = r.getJSONObject(i);
                    String name = x.getString("name");
                    String country = x.optString("country", "");
                    String admin = x.optString("admin1", "");
                    String label = name + (admin.isEmpty() || admin.equals(name) ? "" : ", " + admin)
                            + (country.isEmpty() ? "" : ", " + country);
                    out.add(new Place(label, x.getDouble("latitude"), x.getDouble("longitude")));
                }
            } catch (Exception ignored) {
            }
            MAIN.post(() -> done.accept(out));
        });
    }

    static void choose(Context c, Place place) {
        Prefs.get(c).sp.edit()
                .putString(Prefs.WEATHER_CITY, place.name.split(",")[0])
                .putLong(Prefs.WEATHER_LAT, Double.doubleToRawLongBits(place.lat))
                .putLong(Prefs.WEATHER_LON, Double.doubleToRawLongBits(place.lon))
                .remove(Prefs.WEATHER_CACHE)
                .apply();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestProperty("User-Agent", "Helm-Launcher");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }

    /** WMO weather code → dot icon name. */
    static String icon(int code) {
        if (code == 0) return "sun";
        if (code <= 2) return "partly";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "fog";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snow";
        if (code >= 95) return "storm";
        if (code >= 51) return "rain";
        return "cloud";
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "Cloudy";
    }
}
