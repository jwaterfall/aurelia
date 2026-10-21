package app.aurelia.tv.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

import androidx.annotation.Nullable;

import app.aurelia.tv.BuildConfig;
import app.aurelia.tv.jellyfin.Api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * New versions, from this repository's GitHub releases ({@code BuildConfig.UPDATE_REPO}): a release tagged
 * v2.5.0 with the APK attached (.github/workflows/release.yml). Checked every few hours in the background, and as
 * the profile button's menu opens; when one is newer, the menu offers it. Taking it downloads the APK and hands it to
 * Android's installer, which asks once to confirm, and after the first update installs the next ones without
 * asking. Once installed, the new version opens itself again ({@link UpdatedReceiver}).
 */
public final class Updates {

    /** How often to look: a release can wait a few hours, and GitHub allows 60 unsigned requests an hour. */
    private static final long CHECK_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final long PANEL_CHECK_AFTER_MS = 60 * 1000L;
    private static final String PREFS = "updates";
    private static final String KEY_CHECKED = "checked";
    private static final String KEY_VERSION = "version";
    private static final String KEY_URL = "url";
    private static final int DOWNLOAD_TIMEOUT_MS = 30_000;

    private Updates() { }

    /** A release newer than this build. */
    public static final class Release {
        public final String version;
        public final String apkUrl;

        Release(String version, String apkUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
        }
    }

    /** The newer release found last time, without asking GitHub; null when there is none. */
    @Nullable
    public static Release known(Context context) {
        SharedPreferences p = prefs(context);
        String version = p.getString(KEY_VERSION, "");
        String url = p.getString(KEY_URL, "");
        if (version.isEmpty() || url.isEmpty() || !isNewer(version, BuildConfig.VERSION_NAME)) return null;
        return new Release(version, url);
    }

    /** Whether it is time to ask GitHub again. */
    public static boolean due(Context context) {
        return since(context) > CHECK_EVERY_MS;
    }

    /**
     * Whether the profile button's menu should ask GitHub as it opens. Someone opening it may be looking for a release
     * made minutes ago, so it does not wait for {@link #due}; opening it again straight away asks only once.
     */
    public static boolean dueForMenu(Context context) {
        return since(context) > PANEL_CHECK_AFTER_MS;
    }

    private static long since(Context context) {
        return System.currentTimeMillis() - prefs(context).getLong(KEY_CHECKED, 0);
    }

    /** Asks GitHub for the latest release, and remembers it. Null when this build is the latest. Blocks. */
    @Nullable
    public static Release check(Context context) throws IOException {
        JSONObject latest = Api.getJson("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest",
                null);
        String version = latest.optString("tag_name", "").replaceFirst("^v", "");
        String url = "";
        JSONArray assets = latest.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && a.optString("name").endsWith(".apk")) {
                url = a.optString("browser_download_url", "");
                break;
            }
        }
        prefs(context).edit()
                .putLong(KEY_CHECKED, System.currentTimeMillis())
                .putString(KEY_VERSION, version)
                .putString(KEY_URL, url)
                .apply();
        return known(context);
    }

    /** Whether version {@code candidate} ("2.10.0") comes after {@code current} ("2.9.1"). */
    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("\\.");
        String[] b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? number(a[i]) : 0;
            int y = i < b.length ? number(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int number(String part) {
        String digits = part.replaceAll("[^0-9].*$", "");
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Downloads {@code release}'s APK into the cache. Blocks. */
    public static File download(Context context, Release release) throws IOException {
        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No room for the update");
        File apk = new File(dir, "aurelia.apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(release.apkUrl).openConnection();
        conn.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
        conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
        // GitHub sends the file from another host: followed, as both are https.
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " for " + release.apkUrl);
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        return apk;
    }

    /**
     * Hands {@code apk} to Android's installer. It answers to {@link InstallReceiver}, which shows the
     * confirmation when Android asks for one. Blocks while the APK is copied in.
     */
    public static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Allowed once this launcher installed the version being replaced: from the second update on.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new java.io.FileInputStream(apk);
                 OutputStream out = session.openWrite("aurelia.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                session.fsync(out);
            }
            Intent done = new Intent(context, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(context, id, done, flags).getIntentSender());
        }
    }

    /** Whether Android lets this launcher install apps; granted once in settings, or by adb. */
    public static boolean canInstall(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
