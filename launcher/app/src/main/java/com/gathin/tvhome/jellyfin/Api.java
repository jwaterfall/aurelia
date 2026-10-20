package com.gathin.tvhome.jellyfin;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP to a Jellyfin server, off the main thread, with answers delivered back on it. */
public final class Api {

    public interface Callback<T> {
        void onResult(T value);
        void onError(Exception e);
    }

    /** The server answered, but not with success. 401 means the token is no longer valid. */
    public static final class HttpError extends IOException {
        public final int code;

        HttpError(int code, String url) {
            super("HTTP " + code + " for " + url);
            this.code = code;
        }
    }

    private static final int TIMEOUT_MS = 5000;
    private static final String CLIENT = "TvHome";
    private static final ExecutorService io = Executors.newFixedThreadPool(4);
    private static Handler main;

    private Api() { }

    /** Created on first use rather than when the class loads, so the pure helpers here work in plain JVM tests. */
    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /** Runs {@code work} in the background and hands its result, or its failure, to {@code callback} on the main thread. */
    public static <T> void async(Callable<T> work, Callback<T> callback) {
        io.execute(() -> {
            try {
                T value = work.call();
                main().post(() -> callback.onResult(value));
            } catch (Exception e) {
                main().post(() -> callback.onError(e));
            }
        });
    }

    static void onMain(Runnable r) {
        main().post(r);
    }

    static void inBackground(Runnable r) {
        io.execute(r);
    }

    /**
     * The Authorization header every request carries. Without a token it still names the client and device,
     * which Quick Connect needs to know which device it is approving.
     */
    public static String authHeader(String deviceId, String token) {
        String device = Build.MODEL.replace("\"", "");
        StringBuilder h = new StringBuilder("MediaBrowser Client=\"").append(CLIENT)
                .append("\", Device=\"").append(device)
                .append("\", DeviceId=\"").append(deviceId)
                .append("\", Version=\"").append(com.gathin.tvhome.BuildConfig.VERSION_NAME).append('"');
        if (token != null && !token.isEmpty()) h.append(", Token=\"").append(token).append('"');
        return h.toString();
    }

    public static String getText(String url, String auth) throws IOException {
        return request("GET", url, auth, null);
    }

    public static JSONObject getJson(String url, String auth) throws IOException {
        return parse(request("GET", url, auth, null), url);
    }

    /** A quick question to a server that may be gone: gives up after {@code timeoutMs} rather than the usual wait. */
    public static JSONObject getJson(String url, String auth, int timeoutMs) throws IOException {
        return parse(request("GET", url, auth, null, timeoutMs), url);
    }

    public static JSONObject postJson(String url, String auth, JSONObject body) throws IOException {
        return parse(request("POST", url, auth, body), url);
    }

    /** A request whose answer, if any, is not needed. */
    public static void post(String url, String auth) throws IOException {
        request("POST", url, auth, null);
    }

    public static Bitmap getImage(String url, String auth) throws IOException {
        HttpURLConnection conn = open("GET", url, auth);
        try {
            check(conn, url);
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String request(String method, String url, String auth, JSONObject body) throws IOException {
        return request(method, url, auth, body, TIMEOUT_MS);
    }

    private static String request(String method, String url, String auth, JSONObject body, int timeoutMs)
            throws IOException {
        HttpURLConnection conn = open(method, url, auth);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        try {
            if (body != null || "POST".equals(method)) {
                byte[] bytes = (body != null ? body.toString() : "{}").getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }
            check(conn, url);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) text.write(buffer, 0, n);
                return text.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String method, String url, String auth) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        if (auth != null) conn.setRequestProperty("Authorization", auth);
        return conn;
    }

    private static void check(HttpURLConnection conn, String url) throws IOException {
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new HttpError(code, url);
    }

    private static JSONObject parse(String text, String url) throws IOException {
        try {
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("Not JSON from " + url, e);
        }
    }

    /**
     * Turns what someone typed into a base address: adds http:// and Jellyfin's default port when they are
     * missing, and drops a trailing slash. "192.168.1.10" becomes "http://192.168.1.10:8096".
     */
    public static String normalizeAddress(String typed) {
        String s = typed.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        s = s.replaceAll("/+$", "");
        String hostPart = s.replaceFirst("(?i)^https?://", "");
        int slash = hostPart.indexOf('/');
        String host = slash < 0 ? hostPart : hostPart.substring(0, slash);
        boolean hasPort = host.startsWith("[") ? host.contains("]:") : host.contains(":");
        if (!hasPort && s.toLowerCase().startsWith("http://")) {
            s = s.substring(0, s.length() - hostPart.length()) + host + ":8096" + (slash < 0 ? "" : hostPart.substring(slash));
        }
        return s;
    }
}
