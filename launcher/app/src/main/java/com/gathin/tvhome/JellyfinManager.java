package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class JellyfinManager {
    private static final String TAG = "JellyfinManager";

    // From launcher/jellyfin.properties at build time (see app/build.gradle); empty when it is missing.
    static final String SERVER_URL = BuildConfig.JELLYFIN_SERVER.replaceAll("/+$", "");
    static final String API_KEY = BuildConfig.JELLYFIN_API_KEY;
    static final String USER_ID = BuildConfig.JELLYFIN_USER_ID;

    private static final String DEVICE = android.os.Build.MODEL;
    private static final String AUTH_HEADER = "MediaBrowser Client=\"TvHome\", Device=\"" + DEVICE
            + "\", DeviceId=\"tvhome-" + DEVICE.replaceAll("[^A-Za-z0-9]", "") + "\", Version=\"" + BuildConfig.VERSION_NAME
            + "\", Token=\"" + API_KEY + "\"";

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    static boolean isConfigured() {
        return !SERVER_URL.isEmpty() && !API_KEY.isEmpty() && !USER_ID.isEmpty();
    }

    public static void fetchContinueWatching(Callback<List<ContinueWatchingAdapter.Item>> callback) {
        if (!isConfigured()) {
            mainHandler.post(() -> callback.onError(new IllegalStateException("launcher/jellyfin.properties is missing")));
            return;
        }
        executor.execute(() -> {
            try {
                List<ContinueWatchingAdapter.Item> result = new ArrayList<>();
                Set<String> seenIds = new HashSet<>();

                // 1. Fetch Resume Items (in-progress movies and episodes)
                String resumeUrl = SERVER_URL + "/Users/" + USER_ID + "/Items/Resume?limit=12";
                JSONObject resumeJson = httpGetJson(resumeUrl);
                if (resumeJson != null && resumeJson.has("Items")) {
                    JSONArray items = resumeJson.getJSONArray("Items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        String type = it.optString("Type", "");
                        if (!"Episode".equals(type) && !"Movie".equals(type)) {
                            continue;
                        }
                        String id = it.optString("Id", "");
                        if (id.isEmpty() || seenIds.contains(id)) continue;
                        seenIds.add(id);

                        ContinueWatchingAdapter.Item parsed = parseItem(it, true);
                        if (parsed != null) result.add(parsed);
                    }
                }

                // 2. Fetch Next Up Episodes (next unwatched episodes in series)
                String nextUpUrl = SERVER_URL + "/Shows/NextUp?userId=" + USER_ID + "&limit=12";
                JSONObject nextUpJson = httpGetJson(nextUpUrl);
                if (nextUpJson != null && nextUpJson.has("Items")) {
                    JSONArray items = nextUpJson.getJSONArray("Items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        String id = it.optString("Id", "");
                        if (id.isEmpty() || seenIds.contains(id)) continue;
                        seenIds.add(id);

                        ContinueWatchingAdapter.Item parsed = parseItem(it, false);
                        if (parsed != null) result.add(parsed);
                    }
                }

                // Neither request answered: the server is down, not empty. Say so rather than "nothing in progress".
                if (resumeJson == null && nextUpJson == null) {
                    throw new java.io.IOException("Jellyfin did not answer at " + SERVER_URL);
                }
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                Log.e(TAG, "Error fetching Continue Watching", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    private static ContinueWatchingAdapter.Item parseItem(JSONObject it, boolean isResume) {
        String id = it.optString("Id", "");
        String type = it.optString("Type", "");
        String name = it.optString("Name", "");

        long runTimeTicks = it.optLong("RunTimeTicks", 0);
        JSONObject userData = it.optJSONObject("UserData");
        long playbackTicks = userData != null ? userData.optLong("PlaybackPositionTicks", 0) : 0;
        double playedPct = userData != null ? userData.optDouble("PlayedPercentage", 0) : 0;

        int progress = 0;
        if (isResume && runTimeTicks > 0 && playbackTicks > 0) {
            progress = (int) Math.round(((double) playbackTicks / (double) runTimeTicks) * 100.0);
            if (progress <= 0) progress = (int) Math.round(playedPct);
        }

        String remainingStr = "";
        if (runTimeTicks > playbackTicks) {
            long remTicks = runTimeTicks - playbackTicks;
            long remMin = remTicks / 600000000L; // 1 tick = 100ns, 10M ticks/sec, 600M ticks/min
            if (remMin >= 60) {
                long remHours = remMin / 60;
                long remMinsOnly = remMin % 60;
                remainingStr = remHours + "h " + (remMinsOnly > 0 ? remMinsOnly + "m" : "") + " left";
            } else if (remMin > 0) {
                remainingStr = remMin + "m left";
            }
        }

        String title;
        String subtitle;
        String tag;
        String imageUrl;

        if ("Episode".equals(type)) {
            String seriesName = it.optString("SeriesName", "");
            title = !seriesName.isEmpty() ? seriesName : name;
            int season = it.optInt("ParentIndexNumber", 1);
            int ep = it.optInt("IndexNumber", 1);

            StringBuilder sub = new StringBuilder();
            sub.append("S").append(season).append(":E").append(ep);
            if (!name.isEmpty()) sub.append(" \u2022 ").append(name);
            if (!remainingStr.isEmpty()) sub.append(" \u2022 ").append(remainingStr);
            subtitle = sub.toString();

            tag = "TV SERIES \u2022 SEASON " + season;

            String parentBackdropId = it.optString("ParentBackdropItemId", "");
            if (!parentBackdropId.isEmpty()) {
                imageUrl = SERVER_URL + "/Items/" + parentBackdropId + "/Images/Backdrop/0?maxWidth=720&quality=85";
            } else {
                imageUrl = SERVER_URL + "/Items/" + id + "/Images/Primary?maxWidth=720&quality=85";
            }
        } else {
            // Movie
            title = name;
            int year = it.optInt("ProductionYear", 0);
            tag = year > 0 ? "MOVIE \u2022 " + year : "MOVIE";

            String officialRating = it.optString("OfficialRating", "");
            StringBuilder sub = new StringBuilder();
            if (!remainingStr.isEmpty()) sub.append(remainingStr);
            if (!officialRating.isEmpty()) {
                if (sub.length() > 0) sub.append(" \u2022 ");
                sub.append(officialRating);
            }
            subtitle = sub.length() > 0 ? sub.toString() : "Movie";

            imageUrl = SERVER_URL + "/Items/" + id + "/Images/Backdrop/0?maxWidth=720&quality=85";
        }

        return new ContinueWatchingAdapter.Item(id, title, subtitle, tag, imageUrl, progress);
    }

    public static void launchItem(Context context, String itemId) {
        if (context == null || itemId == null || itemId.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setComponent(new ComponentName("org.jellyfin.androidtv", "org.jellyfin.androidtv.ui.startup.StartupActivity"));
            intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            intent.setData(Uri.parse(itemId));
            intent.putExtra("ItemId", itemId);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch Jellyfin item: " + itemId, e);
        }
    }

    private static JSONObject httpGetJson(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", AUTH_HEADER);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return new JSONObject(sb.toString());
            } else {
                Log.w(TAG, "HTTP " + code + " for " + urlStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "HTTP request failed: " + urlStr, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }
}
