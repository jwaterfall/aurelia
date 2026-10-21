package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import com.gathin.tvhome.jellyfin.Account;
import com.gathin.tvhome.jellyfin.Api;
import com.gathin.tvhome.jellyfin.Discovery;
import com.gathin.tvhome.jellyfin.Library;
import com.gathin.tvhome.jellyfin.QuickConnect;
import com.gathin.tvhome.ui.GlassSheet;
import com.gathin.tvhome.ui.PanelStep;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Jellyfin account in a side panel. Signed out: pick a server (found on the network, or typed in), then sign
 * in with a Quick Connect code approved from another Jellyfin client. Signed in: who and where, and sign out.
 */
final class AccountPanel {

    private static final int DISCOVERY_MS = 2500;

    private final Activity activity;
    private final GlassSheet sheet;
    private final ViewGroup background;
    private final Account account;
    private QuickConnect signIn;
    /** Which step Back returns to; null closes the panel. */
    private Runnable back;
    /** Bumped on every step, so an answer for a step that is no longer showing is dropped. */
    private int step;

    AccountPanel(Activity activity, GlassSheet sheet, ViewGroup background) {
        this.activity = activity;
        this.sheet = sheet;
        this.background = background;
        this.account = Account.get(activity);
    }

    void open() {
        if (account.isSignedIn()) showAccount();
        else showServers();
    }

    boolean isOpen() {
        return sheet.isShowing();
    }

    /** Back: the previous step, or closes the panel from the first one. */
    void back() {
        if (back != null) back.run();
        else close();
    }

    void close() {
        stopSignIn();
        step++;
        sheet.hide();
    }

    private PanelStep newStep(Runnable onBack) {
        stopSignIn();
        step++;
        back = onBack;
        return new PanelStep(activity, sheet);
    }

    private void show(PanelStep s) {
        sheet.show(s.view(), background);
    }

    // ---- choose a server -------------------------------------------------------------------------------

    /**
     * Servers to sign in to: those that answer Jellyfin's discovery broadcast, and those signed in to before that
     * still answer when asked directly (a server in Docker does, though it never hears the broadcast). Each is
     * listed once, as it answers, above "Enter an address".
     */
    private void showServers() {
        PanelStep s = newStep(null);
        s.title(activity.getString(R.string.jellyfin_connect_title))
                .body(activity.getString(R.string.jellyfin_connect_body))
                .status(activity.getString(R.string.jellyfin_searching), true);
        View enterAddress = s.row(activity.getString(R.string.jellyfin_enter_address), null, () -> showAddress(""));
        show(s);

        int mine = step;
        String last = account.server();
        Set<String> listed = new HashSet<>();
        List<Account.KnownServer> known = account.knownServers();
        int[] waiting = {known.size() + 1};
        Runnable done = () -> {
            if (mine != step || --waiting[0] > 0) return;
            int n = listed.size();
            s.status(n == 0 ? activity.getString(R.string.jellyfin_none_found)
                    : n == 1 ? activity.getString(R.string.jellyfin_found_one)
                    : activity.getString(R.string.jellyfin_found_many, n), false);
        };
        for (Account.KnownServer k : known) {
            Api.async(() -> Api.getJson(k.address + "/System/Info/Public", null, DISCOVERY_MS),
                    new Api.Callback<JSONObject>() {
                        @Override
                        public void onResult(JSONObject info) {
                            if (mine != step) return;
                            String name = info.optString("ServerName", k.name);
                            addServer(s, listed, enterAddress, k.address, name.isEmpty() ? k.address : name, last);
                            done.run();
                        }

                        @Override
                        public void onError(Exception e) {
                            done.run();
                        }
                    });
        }
        Api.async(() -> Discovery.find(DISCOVERY_MS), new Api.Callback<List<Discovery.Server>>() {
            @Override
            public void onResult(List<Discovery.Server> servers) {
                if (mine != step) return;
                for (Discovery.Server f : servers) addServer(s, listed, enterAddress, f.address, f.name, last);
                done.run();
            }

            @Override
            public void onError(Exception e) {
                done.run();
            }
        });
    }

    /**
     * Lists a server that answered, once, above {@code before}. The first one takes focus if it is still on
     * "Enter an address", where the step opened; a choice already made is left alone.
     */
    private void addServer(PanelStep s, Set<String> listed, View before, String address, String name, String last) {
        if (!listed.add(address)) return;
        String detail = address.equals(last) ? activity.getString(R.string.jellyfin_last_used, address) : address;
        View row = s.row(name, detail, () -> showCode(address, name), before);
        if (listed.size() == 1 && before.isFocused()) row.requestFocus();
    }

    private void showAddress(String typed) {
        PanelStep s = newStep(this::showServers);
        s.title(activity.getString(R.string.jellyfin_address_title))
                .body(activity.getString(R.string.jellyfin_address_body));
        EditText field = s.field(activity.getString(R.string.jellyfin_address_hint), typed);
        Runnable go = () -> checkAddress(s, field.getText().toString());
        field.setOnEditorActionListener((v, action, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (action != EditorInfo.IME_ACTION_GO && !enter) return false;
            go.run();
            return true;
        });
        s.row(activity.getString(R.string.jellyfin_continue), null, go);
        show(s);
    }

    /** Asks the address for the server's public info, which every Jellyfin server answers without signing in. */
    private void checkAddress(PanelStep s, String typed) {
        String address = Api.normalizeAddress(typed);
        if (address.isEmpty()) return;
        s.status(activity.getString(R.string.jellyfin_checking), true);
        int mine = step;
        Api.async(() -> Api.getJson(address + "/System/Info/Public", null), new Api.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject info) {
                if (mine != step) return;
                String name = info.optString("ServerName", "");
                showCode(address, name.isEmpty() ? address : name);
            }

            @Override
            public void onError(Exception e) {
                if (mine == step) s.status(activity.getString(R.string.jellyfin_address_failed));
            }
        });
    }

    // ---- Quick Connect ---------------------------------------------------------------------------------

    private void showCode(String server, String name) {
        PanelStep s = newStep(this::showServers);
        s.title(activity.getString(R.string.jellyfin_code_title, name))
                .body(activity.getString(R.string.jellyfin_code_body))
                .code("• • • • • •")
                .status(activity.getString(R.string.jellyfin_checking), true);
        s.row(activity.getString(R.string.jellyfin_other_server), null, this::showServers);
        show(s);
        signIn = new QuickConnect(account, server, name, new QuickConnect.Listener() {
            @Override
            public void onCode(String code) {
                s.code(code).status(activity.getString(R.string.jellyfin_waiting), true);
            }

            @Override
            public void onSignedIn() {
                close();
            }

            @Override
            public void onFailed(QuickConnect.Failure failure) {
                showFailure(server, name, failure);
            }
        });
        signIn.start();
    }

    private void showFailure(String server, String name, QuickConnect.Failure failure) {
        PanelStep s = newStep(this::showServers);
        int message;
        switch (failure) {
            case DISABLED: message = R.string.jellyfin_qc_disabled; break;
            case EXPIRED: message = R.string.jellyfin_qc_expired; break;
            default: message = R.string.jellyfin_qc_unreachable; break;
        }
        s.title(activity.getString(R.string.jellyfin_code_title, name))
                .body(activity.getString(message, name));
        s.row(activity.getString(R.string.jellyfin_try_again), null, () -> showCode(server, name));
        s.row(activity.getString(R.string.jellyfin_other_server), null, this::showServers);
        show(s);
    }

    private void stopSignIn() {
        if (signIn != null) signIn.cancel();
        signIn = null;
    }

    // ---- signed in -------------------------------------------------------------------------------------

    private void showAccount() {
        PanelStep s = newStep(null);
        String server = account.serverName().isEmpty() ? account.server() : account.serverName();
        s.avatar(null)
                .title(account.userName())
                .body(activity.getString(R.string.jellyfin_signed_in_to, server));
        if (Library.hasApp(activity)) {
            s.row(activity.getString(R.string.jellyfin_open_app), null, () -> {
                close();
                Library.openApp(activity);
            });
        }
        s.row(activity.getString(R.string.jellyfin_sign_out), null, this::signOut);
        show(s);
        int mine = step;
        Api.async(() -> Library.avatar(account), new Api.Callback<Bitmap>() {
            @Override
            public void onResult(Bitmap picture) {
                if (mine == step && picture != null) s.avatar(picture);
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    /** Ends this device's session on the server too, then forgets it here whatever the server says. */
    private void signOut() {
        String server = account.server();
        String auth = Library.auth(account);
        Api.async(() -> {
            Api.post(server + "/Sessions/Logout", auth);
            return true;
        }, new Api.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok) {
            }

            @Override
            public void onError(Exception e) {
            }
        });
        account.signOut();
        close();
    }
}
