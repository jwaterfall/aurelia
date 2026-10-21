package app.aurelia.tv.jellyfin;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Signs in with Jellyfin Quick Connect: the server gives this device a short code, the user approves it from any
 * signed-in Jellyfin client (phone, browser), and the device exchanges the approval for its own access token.
 *
 * One attempt per instance; {@link #cancel} stops it, and nothing is reported afterwards.
 */
public final class QuickConnect {

    public enum Failure {
        /** The server has Quick Connect switched off (Dashboard > General). */
        DISABLED,
        /** The server did not answer. */
        UNREACHABLE,
        /** Nobody approved the code before it ran out. */
        EXPIRED
    }

    public interface Listener {
        void onCode(String code);
        void onSignedIn();
        void onFailed(Failure failure);
    }

    private static final String TAG = "Jellyfin.QuickConnect";
    private static final long POLL_MS = 2500;
    /** Jellyfin forgets a code after about ten minutes; stop a little before. */
    private static final long LIFETIME_MS = 9 * 60_000L;

    private final Account account;
    private final String server;
    private final String serverName;
    private final Listener listener;
    private volatile boolean cancelled;

    public QuickConnect(Account account, String server, String serverName, Listener listener) {
        this.account = account;
        this.server = server;
        this.serverName = serverName;
        this.listener = listener;
    }

    public void start() {
        Api.inBackground(this::run);
    }

    public void cancel() {
        cancelled = true;
    }

    private void run() {
        String auth = Api.authHeader(account.deviceId(), null);
        try {
            if (!enabled(auth)) {
                report(() -> listener.onFailed(Failure.DISABLED));
                return;
            }
            JSONObject started = initiate(auth);
            String secret = started.optString("Secret", "");
            String code = started.optString("Code", "");
            if (secret.isEmpty() || code.isEmpty()) throw new IOException("No Quick Connect code from " + server);
            report(() -> listener.onCode(code));

            String query = "?secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8.name());
            long end = System.currentTimeMillis() + LIFETIME_MS;
            while (!cancelled && System.currentTimeMillis() < end) {
                Thread.sleep(POLL_MS);
                if (cancelled) return;
                JSONObject state = Api.getJson(server + "/QuickConnect/Connect" + query, auth);
                if (state.optBoolean("Authenticated", false)) {
                    JSONObject body = new JSONObject().put("Secret", secret);
                    JSONObject signedIn = Api.postJson(server + "/Users/AuthenticateWithQuickConnect", auth, body);
                    if (cancelled) return;
                    Api.onMain(() -> {
                        if (cancelled) return;
                        account.signIn(server, serverName, signedIn);
                        listener.onSignedIn();
                    });
                    return;
                }
            }
            report(() -> listener.onFailed(Failure.EXPIRED));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "Quick Connect failed", e);
            report(() -> listener.onFailed(e instanceof Api.HttpError && ((Api.HttpError) e).code == 401
                    ? Failure.DISABLED : Failure.UNREACHABLE));
        }
    }

    /** The server answers a bare true or false. */
    private boolean enabled(String auth) throws IOException {
        return Api.getText(server + "/QuickConnect/Enabled", auth).trim().equalsIgnoreCase("true");
    }

    /** POST on current servers; GET on 10.7 and older. */
    private JSONObject initiate(String auth) throws IOException {
        String url = server + "/QuickConnect/Initiate";
        try {
            return Api.postJson(url, auth, null);
        } catch (Api.HttpError e) {
            if (e.code == 404 || e.code == 405) return Api.getJson(url, auth);
            throw e;
        }
    }

    private void report(Runnable r) {
        if (cancelled) return;
        Api.onMain(() -> {
            if (!cancelled) r.run();
        });
    }
}
