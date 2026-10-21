package com.gathin.tvhome.jellyfin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What the signed-in user is watching, and opening it in the Jellyfin app when the launcher cannot play it. */
public final class Library {

    /** One card of Continue Watching. */
    public static final class MediaItem {
        public final String id;
        public final String title;
        public final String subtitle;
        public final String tag;
        public final String imageUrl;
        /** Percent watched; 0 for an episode that is next up but not started. */
        public final int progress;

        MediaItem(String id, String title, String subtitle, String tag, String imageUrl, int progress) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.tag = tag;
            this.imageUrl = imageUrl;
            this.progress = progress;
        }
    }

    /** No one is signed in, or the server no longer accepts the token (which signs the launcher out). */
    public static final class NotSignedIn extends IOException {
        NotSignedIn() {
            super("Not signed in to Jellyfin");
        }
    }

    private static final String TAG = "Jellyfin.Library";
    private static final String JELLYFIN_APP = "org.jellyfin.androidtv";
    private static final int LIMIT = 12;

    private Library() { }

    /** Items in progress, then the next episode of each show being watched. */
    public static void continueWatching(Account account, Api.Callback<List<MediaItem>> callback) {
        if (!account.isSignedIn()) {
            Api.onMain(() -> callback.onError(new NotSignedIn()));
            return;
        }
        String server = account.server();
        String user = account.userId();
        String auth = auth(account);
        Api.async(() -> {
            // Just back from the player: let its last report land first, so the row shows where it stopped.
            Playback.awaitReports();
            try {
                List<MediaItem> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                JSONObject resume = Api.getJson(server + "/Users/" + user + "/Items/Resume?limit=" + LIMIT
                        + "&MediaTypes=Video", auth);
                addItems(out, seen, resume, server, true);
                JSONObject nextUp = Api.getJson(server + "/Shows/NextUp?userId=" + user + "&limit=" + LIMIT, auth);
                addItems(out, seen, nextUp, server, false);
                return out;
            } catch (Api.HttpError e) {
                if (e.code == 401) {
                    Api.onMain(account::signOut);
                    throw new NotSignedIn();
                }
                throw e;
            }
        }, callback);
    }

    /** The user's profile picture, small, or null when they have none. Blocks; call it in the background. */
    public static Bitmap avatar(Account account) throws IOException {
        if (!account.isSignedIn() || account.imageTag().isEmpty()) return null;
        return Api.getImage(account.server() + "/Users/" + account.userId() + "/Images/Primary?tag="
                + account.imageTag() + "&maxWidth=96&quality=90", auth(account));
    }

    /** Header for requests as the signed-in user, artwork included. */
    public static String auth(Account account) {
        return Api.authHeader(account.deviceId(), account.token());
    }

    /** Opens {@code itemId} in the Jellyfin app, on its details page with Resume focused. */
    public static void open(Context context, String itemId) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setComponent(new ComponentName(JELLYFIN_APP, JELLYFIN_APP + ".ui.startup.StartupActivity"))
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setData(Uri.parse(itemId))
                    .putExtra("ItemId", itemId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not open Jellyfin item " + itemId, e);
        }
    }

    /** Opens the Jellyfin app itself, or returns false when it is not installed. */
    public static boolean openApp(Context context) {
        Intent intent = context.getPackageManager().getLeanbackLaunchIntentForPackage(JELLYFIN_APP);
        if (intent == null) return false;
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    private static void addItems(List<MediaItem> out, Set<String> seen, JSONObject page, String server, boolean resume) {
        JSONArray items = page.optJSONArray("Items");
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String type = it.optString("Type", "");
            if (!"Episode".equals(type) && !"Movie".equals(type)) continue;
            String id = it.optString("Id", "");
            if (id.isEmpty() || !seen.add(id)) continue;
            out.add(parse(it, server, resume));
        }
    }

    private static MediaItem parse(JSONObject it, String server, boolean resume) {
        String id = it.optString("Id", "");
        String name = it.optString("Name", "");
        long runtime = it.optLong("RunTimeTicks", 0);
        JSONObject userData = it.optJSONObject("UserData");
        long position = userData != null ? userData.optLong("PlaybackPositionTicks", 0) : 0;

        int progress = 0;
        if (resume && runtime > 0 && position > 0) progress = (int) Math.round(100.0 * position / runtime);
        // Started: how much is left. Next up (not started): how long it is.
        String left = resume ? remaining(runtime - position) : length(runtime);

        if ("Episode".equals(it.optString("Type"))) {
            String series = it.optString("SeriesName", "");
            int season = it.optInt("ParentIndexNumber", 1);
            int episode = it.optInt("IndexNumber", 1);
            StringBuilder sub = new StringBuilder("S").append(season).append(":E").append(episode);
            if (!name.isEmpty()) sub.append(" • ").append(name);
            if (!left.isEmpty()) sub.append(" • ").append(left);
            String backdropOf = it.optString("ParentBackdropItemId", "");
            String image = !backdropOf.isEmpty()
                    ? server + "/Items/" + backdropOf + "/Images/Backdrop/0?maxWidth=720&quality=85"
                    : server + "/Items/" + id + "/Images/Primary?maxWidth=720&quality=85";
            return new MediaItem(id, series.isEmpty() ? name : series, sub.toString(),
                    (resume ? "TV Series" : "Next up") + " • Season " + season, image, progress);
        }
        int year = it.optInt("ProductionYear", 0);
        String rating = it.optString("OfficialRating", "");
        StringBuilder sub = new StringBuilder(left);
        if (!rating.isEmpty()) sub.append(sub.length() > 0 ? " • " : "").append(rating);
        return new MediaItem(id, name, sub.length() > 0 ? sub.toString() : "Movie",
                year > 0 ? "Movie • " + year : "Movie",
                server + "/Items/" + id + "/Images/Backdrop/0?maxWidth=720&quality=85", progress);
    }

    /** "1h 4m left", "49m left", or empty under a minute. One tick is 100 ns. */
    static String remaining(long ticks) {
        String l = length(ticks);
        return l.isEmpty() ? "" : l + " left";
    }

    /** "1h 4m", "49m", or empty under a minute. */
    static String length(long ticks) {
        long minutes = ticks / 600_000_000L;
        if (minutes <= 0) return "";
        if (minutes < 60) return minutes + "m";
        return minutes / 60 + "h" + (minutes % 60 > 0 ? " " + minutes % 60 + "m" : "");
    }
}
