package com.gathin.tvhome.jellyfin;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Jellyfin sign-in, kept in the launcher's private storage: the server, the signed-in user, and the access
 * token Quick Connect gave this device. Nothing about a server or user is built into the app.
 *
 * Each install has its own device id, so the TV shows up once under Dashboard > Devices on the server, where its
 * access can be revoked; a revoked token signs the launcher out on its next request.
 */
public final class Account {

    public interface Listener {
        void onAccountChanged();
    }

    /** A server signed in to before. */
    public static final class KnownServer {
        public final String address;
        public final String name;

        KnownServer(String address, String name) {
            this.address = address;
            this.name = name;
        }
    }

    /** Servers remembered at most; the oldest is forgotten first. */
    private static final int MAX_SERVERS = 8;

    private static final String PREFS = "jellyfin_account";
    private static Account instance;

    private final SharedPreferences prefs;
    private final List<Listener> listeners = new ArrayList<>();

    public static synchronized Account get(Context context) {
        if (instance == null) instance = new Account(context.getApplicationContext());
        return instance;
    }

    private Account(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains("deviceId")) prefs.edit().putString("deviceId", UUID.randomUUID().toString()).apply();
    }

    public String deviceId() {
        return prefs.getString("deviceId", "");
    }

    public boolean isSignedIn() {
        return !token().isEmpty() && !server().isEmpty() && !userId().isEmpty();
    }

    /** Base address of the last server used, without a trailing slash; kept after signing out. */
    public String server() {
        return prefs.getString("server", "");
    }

    public String serverName() {
        return prefs.getString("serverName", "");
    }

    public String userId() {
        return prefs.getString("userId", "");
    }

    public String userName() {
        return prefs.getString("userName", "");
    }

    /** Tag of the user's profile picture, empty when they have none. */
    public String imageTag() {
        return prefs.getString("imageTag", "");
    }

    public String token() {
        return prefs.getString("token", "");
    }

    /** Every server signed in to on this TV, the most recent first. */
    public List<KnownServer> knownServers() {
        List<KnownServer> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs.getString("servers", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                out.add(new KnownServer(o.getString("address"), o.optString("name", "")));
            }
        } catch (Exception ignored) {
        }
        // Signed in before servers were remembered as a list.
        if (out.isEmpty() && !server().isEmpty()) out.add(new KnownServer(server(), serverName()));
        return out;
    }

    private String rememberServer(String address, String name) throws org.json.JSONException {
        JSONArray list = new JSONArray();
        list.put(new JSONObject().put("address", address).put("name", name));
        for (KnownServer k : knownServers()) {
            if (list.length() >= MAX_SERVERS) break;
            if (!k.address.equals(address)) list.put(new JSONObject().put("address", k.address).put("name", k.name));
        }
        return list.toString();
    }

    /** Stores the answer of /Users/AuthenticateWithQuickConnect. */
    void signIn(String server, String serverName, JSONObject auth) {
        JSONObject user = auth.optJSONObject("User");
        String servers;
        try {
            servers = rememberServer(server, serverName);
        } catch (org.json.JSONException e) {
            servers = prefs.getString("servers", "[]");
        }
        prefs.edit()
                .putString("servers", servers)
                .putString("server", server)
                .putString("serverName", serverName)
                .putString("token", auth.optString("AccessToken", ""))
                .putString("userId", user != null ? user.optString("Id", "") : "")
                .putString("userName", user != null ? user.optString("Name", "") : "")
                .putString("imageTag", user != null ? user.optString("PrimaryImageTag", "") : "")
                .apply();
        notifyListeners();
    }

    /** Forgets the user and token. The server stays, so signing in again starts from it. */
    public void signOut() {
        if (token().isEmpty()) return;
        prefs.edit().remove("token").remove("userId").remove("userName").remove("imageTag").apply();
        notifyListeners();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onAccountChanged();
    }
}
