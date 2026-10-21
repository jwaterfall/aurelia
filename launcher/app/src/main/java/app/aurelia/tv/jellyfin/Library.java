package app.aurelia.tv.jellyfin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What the signed-in user is watching, and opening it in the Jellyfin app when the launcher cannot play it. */
public final class Library {

    /** One card of Continue Watching or Next Up. */
    public static final class MediaItem {
        public final String id;
        /** An episode's show; empty for a film. */
        public final String seriesId;
        public final String title;
        public final String subtitle;
        public final String tag;
        public final String imageUrl;
        /** Percent watched; 0 for an episode that is next up but not started. */
        public final int progress;

        MediaItem(String id, String seriesId, String title, String subtitle, String tag, String imageUrl,
                  int progress) {
            this.id = id;
            this.seriesId = seriesId;
            this.title = title;
            this.subtitle = subtitle;
            this.tag = tag;
            this.imageUrl = imageUrl;
            this.progress = progress;
        }
    }

    /** Home's two Jellyfin rows: what is in progress, and the next episode of each show being watched. */
    public static final class HomeRows {
        public final List<MediaItem> resume;
        public final List<MediaItem> nextUp;

        HomeRows(List<MediaItem> resume, List<MediaItem> nextUp) {
            this.resume = resume;
            this.nextUp = nextUp;
        }

        /** These rows without the card for {@code itemId}, in either row. */
        public HomeRows without(String itemId) {
            return new HomeRows(without(resume, itemId), without(nextUp, itemId));
        }

        /** {@code itemId} is in progress (its card is in Continue Watching), not just next up. */
        public boolean isResuming(String itemId) {
            for (MediaItem m : resume) if (m.id.equals(itemId)) return true;
            return false;
        }

        private static List<MediaItem> without(List<MediaItem> items, String itemId) {
            List<MediaItem> out = new ArrayList<>(items);
            out.removeIf(m -> m.id.equals(itemId));
            return out;
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

    /**
     * Items in progress, and the next episode of each show being watched; a show already in progress is left out
     * of Next Up, as its card in Continue Watching leads on to that episode anyway.
     */
    public static void homeRows(Account account, Api.Callback<HomeRows> callback) {
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
                List<MediaItem> resume = new ArrayList<>();
                List<MediaItem> nextUp = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                addItems(resume, seen, Api.getJson(server + "/Users/" + user + "/Items/Resume?limit=" + LIMIT
                        + "&MediaTypes=Video", auth), server, true);
                for (MediaItem m : resume) if (!m.seriesId.isEmpty()) seen.add(m.seriesId);
                addItems(nextUp, seen, Api.getJson(server + "/Shows/NextUp?userId=" + user + "&limit=" + LIMIT,
                        auth), server, false);
                return new HomeRows(resume, nextUp);
            } catch (Api.HttpError e) {
                if (e.code == 401) {
                    Api.onMain(account::signOut);
                    throw new NotSignedIn();
                }
                throw e;
            }
        }, callback);
    }

    /**
     * Takes {@code itemId} out of Continue Watching without marking it watched: its resume point goes back to the
     * start. Jellyfin 10.9 and later take this at /UserItems, older servers at the per-user address. Blocks.
     */
    public static void clearResume(Account account, String itemId) throws IOException {
        String server = account.server();
        String user = account.userId();
        JSONObject body;
        try {
            body = new JSONObject().put("PlaybackPositionTicks", 0);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        try {
            Api.postJson(server + "/UserItems/" + itemId + "/UserData?userId=" + user, auth(account), body);
        } catch (Api.HttpError e) {
            if (e.code != 404 && e.code != 405) throw e;
            Api.postJson(server + "/Users/" + user + "/Items/" + itemId + "/UserData", auth(account), body);
        }
    }

    // ---- Home's rows as last seen, to show at once on the next start --------------------------------------

    private static final String HOME_ROWS_FILE = "home_rows.json";

    /** Keeps {@code rows} for the next start, for the account they belong to; written in the background. */
    public static void keepHomeRows(Context context, Account account, HomeRows rows) {
        String owner = account.server() + "|" + account.userId();
        Api.inBackground(() -> writeHomeRows(context, owner, rows));
    }

    private static void writeHomeRows(Context context, String owner, HomeRows rows) {
        try {
            JSONObject o = new JSONObject()
                    .put("account", owner)
                    .put("resume", toJson(rows.resume))
                    .put("nextUp", toJson(rows.nextUp));
            File file = new File(context.getFilesDir(), HOME_ROWS_FILE);
            File part = new File(file.getPath() + ".part");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(file)) Log.w(TAG, "Could not keep Home's rows");
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not keep Home's rows", e);
        }
    }

    /** Home's rows as they were last time for this account, or null. Reads a small file. */
    public static HomeRows keptHomeRows(Context context, Account account) {
        if (!account.isSignedIn()) return null;
        File file = new File(context.getFilesDir(), HOME_ROWS_FILE);
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject o = new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8));
            if (!o.optString("account").equals(account.server() + "|" + account.userId())) return null;
            return new HomeRows(fromJson(o.optJSONArray("resume")), fromJson(o.optJSONArray("nextUp")));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static JSONArray toJson(List<MediaItem> items) throws JSONException {
        JSONArray a = new JSONArray();
        for (MediaItem m : items) {
            a.put(new JSONObject().put("id", m.id).put("seriesId", m.seriesId).put("title", m.title)
                    .put("subtitle", m.subtitle).put("tag", m.tag).put("imageUrl", m.imageUrl)
                    .put("progress", m.progress));
        }
        return a;
    }

    private static List<MediaItem> fromJson(JSONArray a) {
        List<MediaItem> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m == null) continue;
            out.add(new MediaItem(m.optString("id"), m.optString("seriesId"), m.optString("title"),
                    m.optString("subtitle"), m.optString("tag"), m.optString("imageUrl"), m.optInt("progress")));
        }
        return out;
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

    /** The Jellyfin app is installed, to fall back on for what the launcher does not do. */
    public static boolean hasApp(Context context) {
        return context.getPackageManager().getLeanbackLaunchIntentForPackage(JELLYFIN_APP) != null;
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
            String series = it.optString("SeriesId", "");
            if (!resume && seen.contains(series)) continue;
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
            JSONObject tags = it.optJSONObject("ImageTags");
            String image = !backdropOf.isEmpty()
                    ? server + "/Items/" + backdropOf + "/Images/Backdrop/0?maxWidth=720&quality=85"
                            + tag(firstTag(it.optJSONArray("ParentBackdropImageTags")))
                    : server + "/Items/" + id + "/Images/Primary?maxWidth=720&quality=85"
                            + tag(tags != null ? tags.optString("Primary", "") : "");
            return new MediaItem(id, it.optString("SeriesId", ""), series.isEmpty() ? name : series, sub.toString(),
                    "TV Series • Season " + season, image, progress);
        }
        int year = it.optInt("ProductionYear", 0);
        String rating = it.optString("OfficialRating", "");
        StringBuilder sub = new StringBuilder(left);
        if (!rating.isEmpty()) sub.append(sub.length() > 0 ? " • " : "").append(rating);
        return new MediaItem(id, "", name, sub.length() > 0 ? sub.toString() : "Movie",
                year > 0 ? "Movie • " + year : "Movie",
                server + "/Items/" + id + "/Images/Backdrop/0?maxWidth=720&quality=85"
                        + tag(firstTag(it.optJSONArray("BackdropImageTags"))), progress);
    }

    /**
     * The picture's version for its address: the server then lets it be kept for good, as a new picture gets a
     * new tag (and ui.ImageDisk keeps only tagged pictures). Empty when the server gave none.
     */
    private static String tag(String tag) {
        return tag.isEmpty() ? "" : "&tag=" + tag;
    }

    private static String firstTag(JSONArray tags) {
        return tags != null && tags.length() > 0 ? tags.optString(0, "") : "";
    }

    /** "1h 4m left", "49m left", or empty under a minute. One tick is 100 ns. */
    static String remaining(long ticks) {
        String l = length(ticks);
        return l.isEmpty() ? "" : l + " left";
    }

    /** "1h 4m", "49m", or empty under a minute. */
    public static String length(long ticks) {
        long minutes = ticks / 600_000_000L;
        if (minutes <= 0) return "";
        if (minutes < 60) return minutes + "m";
        return minutes / 60 + "h" + (minutes % 60 > 0 ? " " + minutes % 60 + "m" : "");
    }
}
