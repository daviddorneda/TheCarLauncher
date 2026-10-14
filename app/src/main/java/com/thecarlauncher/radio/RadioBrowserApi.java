package com.thecarlauncher.radio;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the Radio Browser community API (https://www.radio-browser.info).
 * All network queries execute on a background thread and return results via Callback on the main thread.
 */
public final class RadioBrowserApi {
    public interface Callback {
        void onSuccess(List<RadioStation> stations);
        void onError(String message);
    }

    private static final String[] SERVERS = {
            "https://de1.api.radio-browser.info",
            "https://de2.api.radio-browser.info",
            "https://fi1.api.radio-browser.info",
            "https://all.api.radio-browser.info"
    };

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RadioBrowserApi() {}

    /** Searches stations by name */
    public static void searchByName(final String name, final Callback callback) {
        searchAdvanced(name, null, null, 30, callback);
    }

    /** Searches stations with filters (name, country code, tag/genre) */
    public static void searchAdvanced(final String name, final String countryCode, final String tag,
                                       final int limit, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder params = new StringBuilder("?limit=").append(Math.min(limit, 100))
                            .append("&hidebroken=true&order=votes&reverse=true");

                    if (name != null && !name.trim().isEmpty()) {
                        params.append("&name=").append(URLEncoder.encode(name.trim(), "UTF-8").replace("+", "%20"));
                    }
                    if (countryCode != null && !countryCode.trim().isEmpty()) {
                        params.append("&countrycode=").append(URLEncoder.encode(countryCode.trim(), "UTF-8").replace("+", "%20"));
                    }
                    if (tag != null && !tag.trim().isEmpty()) {
                        params.append("&tag=").append(URLEncoder.encode(tag.trim(), "UTF-8").replace("+", "%20"));
                    }

                    List<RadioStation> results = fetchFromServers("/json/stations/search" + params.toString());
                    postSuccess(callback, results);
                } catch (final Exception e) {
                    postError(callback, e.getMessage() != null ? e.getMessage() : "Search failed");
                }
            }
        }, "radio-browser-search").start();
    }

    /** Fetches top voted stations */
    public static void getTopStations(final int limit, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = "/json/stations/topvote/" + Math.min(limit, 50) + "?hidebroken=true";
                    List<RadioStation> results = fetchFromServers(path);
                    postSuccess(callback, results);
                } catch (final Exception e) {
                    postError(callback, e.getMessage() != null ? e.getMessage() : "Failed to load top stations");
                }
            }
        }, "radio-browser-top").start();
    }

    private static List<RadioStation> fetchFromServers(String pathWithParams) throws Exception {
        Exception lastException = null;
        for (String server : SERVERS) {
            try {
                String json = fetchString(server + pathWithParams);
                List<RadioStation> list = parseStationsJson(json);
                if (list != null) {
                    return list;
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        if (lastException != null) throw lastException;
        return new ArrayList<>();
    }

    private static List<RadioStation> parseStationsJson(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        List<RadioStation> list = new ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String name = obj.optString("name", "").trim();

            // Use url_resolved if valid, fallback to url
            String urlResolved = obj.optString("url_resolved", "").trim();
            String url = obj.optString("url", "").trim();
            String streamUrl = RadioStation.isValidUrl(urlResolved) ? urlResolved : url;

            if (!RadioStation.isValidUrl(streamUrl)) {
                continue;
            }

            String favicon = obj.optString("favicon", "").trim();
            String homepage = obj.optString("homepage", "").trim();
            String stationUuid = obj.optString("stationuuid", "").trim();
            String country = obj.optString("countrycode", "").trim();
            if (country.isEmpty()) country = obj.optString("country", "").trim();
            String tags = obj.optString("tags", "").trim();

            RadioStation station = new RadioStation(name, streamUrl, favicon, homepage, stationUuid, country, tags);
            list.add(station);
        }
        return list;
    }

    private static String fetchString(String urlStr) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            con.setConnectTimeout(5000);
            con.setReadTimeout(8000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "TheCarLauncher/4 (Android head unit launcher)");

            int responseCode = con.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new java.io.IOException("HTTP " + responseCode);
            }

            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 1_500_000) {
                bo.write(buf, 0, n);
            }
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static void postSuccess(final Callback cb, final List<RadioStation> list) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onSuccess(list);
            }
        });
    }

    private static void postError(final Callback cb, final String errorMsg) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(errorMsg);
            }
        });
    }
}
