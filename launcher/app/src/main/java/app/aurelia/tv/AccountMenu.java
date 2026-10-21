package app.aurelia.tv;

import android.app.Activity;
import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Discovery;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.jellyfin.QuickConnect;
import app.aurelia.tv.ui.GlassMenu;
import app.aurelia.tv.update.Updates;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Signing in to Jellyfin, in a glass menu under the profile button: pick a server (found on the network, or typed
 * in), then sign in with a Quick Connect code approved from another Jellyfin client. Each step replaces the menu's
 * contents in place; Back goes to the step before. Signed in, the profile button has its own menu (MainActivity).
 */
final class AccountMenu {

    private static final int DISCOVERY_MS = 2500;

    private final Activity activity;
    private final GlassMenu menu;
    private final View anchor;
    private final ViewGroup background;
    private final Account account;
    /** Adds what the menu offers besides signing in: a waiting update. */
    private final Consumer<GlassMenu.Builder> extras;
    /** Adds the menu's foot, the app's icon and version, as the signed-in menu has it, to every step. */
    private final Consumer<GlassMenu.Builder> footer;
    private QuickConnect signIn;
    /** Which step Back returns to; null closes the menu. */
    private Runnable back;
    /** Bumped on every step, so an answer for a step that is no longer showing is dropped. */
    private int step;
    private boolean showing;

    AccountMenu(Activity activity, GlassMenu menu, View anchor, ViewGroup background,
                 Consumer<GlassMenu.Builder> extras, Consumer<GlassMenu.Builder> footer) {
        this.activity = activity;
        this.menu = menu;
        this.anchor = anchor;
        this.background = background;
        this.account = Account.get(activity);
        this.extras = extras;
        this.footer = footer;
    }

    void open() {
        showServers();
    }

    boolean isOpen() {
        return showing && menu.isShowing();
    }

    /** Back: the previous step, or closes the menu from the first one. */
    void back() {
        if (back != null) back.run();
        else close();
    }

    void close() {
        stopSignIn();
        step++;
        if (showing) menu.hide();
        showing = false;
    }

    private void newStep(Runnable onBack) {
        stopSignIn();
        step++;
        back = onBack;
    }

    /** A step's words: its title, a sentence or two, and a status line kept hidden until there is something to say. */
    private View note(String title, @Nullable String body) {
        View v = activity.getLayoutInflater().inflate(R.layout.view_menu_note, null, false);
        ((TextView) v.findViewById(R.id.noteTitle)).setText(title);
        TextView b = v.findViewById(R.id.noteBody);
        b.setText(body);
        b.setVisibility(body == null ? View.GONE : View.VISIBLE);
        return v;
    }

    private static void status(View note, @Nullable String text) {
        TextView s = note.findViewById(R.id.noteStatus);
        s.setText(text);
        s.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    /** An option that keeps the menu open, as every step leads to another (or closes it itself). */
    private static GlassMenu.Item choice(String label, @Nullable String detail, Runnable action) {
        return GlassMenu.Item.live(label, detail, 0, (row, title, d) -> action.run());
    }

    /** Whether focus is on the menu's option {@code label}. */
    private boolean focusIsOn(String label) {
        View focus = activity.getCurrentFocus();
        TextView title = focus != null ? focus.findViewById(R.id.rowTitle) : null;
        return title != null && label.contentEquals(title.getText());
    }

    private void show(GlassMenu.Builder m) {
        showing = true;
        footer.accept(m);
        menu.show(anchor, m.width(R.dimen.menu_wide_width), background);
    }

    // ---- choose a server -------------------------------------------------------------------------------

    /** A server that answered: its address, its name and what to say under it. */
    private static final class Found {
        final String address, name, detail;

        Found(String address, String name, String detail) {
            this.address = address;
            this.name = name;
            this.detail = detail;
        }
    }

    /**
     * Servers to sign in to: those that answer Jellyfin's discovery broadcast, and those signed in to before that
     * still answer when asked directly (a server in Docker does, though it never hears the broadcast). Each is
     * listed once, as it answers, above "Enter an address".
     */
    private void showServers() {
        newStep(null);
        View note = note(activity.getString(R.string.jellyfin_connect_title),
                activity.getString(R.string.jellyfin_connect_body));
        status(note, activity.getString(R.string.jellyfin_searching));
        List<Found> found = new ArrayList<>();
        int mine = step;
        Runnable list = () -> {
            if (mine != step) return;
            GlassMenu.Builder m = new GlassMenu.Builder().add(GlassMenu.Item.view(note));
            // The first server to answer takes focus, if it is still on "Enter an address", where the step opened.
            if (found.size() == 1 && focusIsOn(activity.getString(R.string.jellyfin_enter_address))) m.focusFirst();
            for (Found f : found) m.add(choice(f.name, f.detail, () -> showCode(f.address, f.name)));
            m.add(choice(activity.getString(R.string.jellyfin_enter_address), null, () -> showAddress("")));
            extras.accept(m);
            show(m);
        };
        list.run();
        checkForUpdate(list);

        String last = account.server();
        Set<String> listed = new HashSet<>();
        List<Account.KnownServer> known = account.knownServers();
        int[] waiting = {known.size() + 1};
        Runnable done = () -> {
            if (mine != step || --waiting[0] > 0) return;
            int n = listed.size();
            status(note, n == 0 ? activity.getString(R.string.jellyfin_none_found)
                    : n == 1 ? activity.getString(R.string.jellyfin_found_one)
                    : activity.getString(R.string.jellyfin_found_many, n));
        };
        java.util.function.BiConsumer<String, String> add = (address, name) -> {
            if (mine != step || !listed.add(address)) return;
            String detail = address.equals(last) ? activity.getString(R.string.jellyfin_last_used, address) : address;
            found.add(new Found(address, name, detail));
            list.run();
        };
        for (Account.KnownServer k : known) {
            Api.async(() -> Api.getJson(k.address + "/System/Info/Public", null, DISCOVERY_MS),
                    new Api.Callback<JSONObject>() {
                        @Override
                        public void onResult(JSONObject info) {
                            String name = info.optString("ServerName", k.name);
                            add.accept(k.address, name.isEmpty() ? k.address : name);
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
                for (Discovery.Server f : servers) add.accept(f.address, f.name);
                done.run();
            }

            @Override
            public void onError(Exception e) {
                done.run();
            }
        });
    }

    /**
     * Unless GitHub was asked a moment ago, asks it again for a newer version; one it names joins the menu (through
     * {@link #extras}) when {@code relist} runs again.
     */
    private void checkForUpdate(Runnable relist) {
        if (Updates.known(activity) != null || !Updates.dueForMenu(activity)) return;
        Context app = activity.getApplicationContext();
        Api.async(() -> Updates.check(app), new Api.Callback<Updates.Release>() {
            @Override
            public void onResult(Updates.Release release) {
                if (release == null) return;
                activity.findViewById(R.id.updateDot).setVisibility(View.VISIBLE);
                relist.run();
            }

            @Override
            public void onError(Exception e) {
                // Offline, or GitHub busy: the background check tries again later.
            }
        });
    }

    private void showAddress(String typed) {
        newStep(this::showServers);
        View note = note(activity.getString(R.string.jellyfin_address_title),
                activity.getString(R.string.jellyfin_address_body));
        View box = activity.getLayoutInflater().inflate(R.layout.view_menu_field, null, false);
        EditText field = box.findViewById(R.id.menuField);
        field.setHint(R.string.jellyfin_address_hint);
        field.setText(typed);
        field.setSelection(field.length());
        Runnable go = () -> checkAddress(note, field.getText().toString());
        field.setOnEditorActionListener((v, action, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (action != EditorInfo.IME_ACTION_GO && !enter) return false;
            go.run();
            return true;
        });
        show(new GlassMenu.Builder()
                .add(GlassMenu.Item.view(note))
                .add(GlassMenu.Item.view(box))
                .add(choice(activity.getString(R.string.jellyfin_continue), null, go)));
    }

    /** Asks the address for the server's public info, which every Jellyfin server answers without signing in. */
    private void checkAddress(View note, String typed) {
        String address = Api.normalizeAddress(typed);
        if (address.isEmpty()) return;
        status(note, activity.getString(R.string.jellyfin_checking));
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
                if (mine == step) status(note, activity.getString(R.string.jellyfin_address_failed));
            }
        });
    }

    // ---- Quick Connect ---------------------------------------------------------------------------------

    private void showCode(String server, String name) {
        newStep(this::showServers);
        View note = note(activity.getString(R.string.jellyfin_code_title, name),
                activity.getString(R.string.jellyfin_code_body));
        TextView code = note.findViewById(R.id.noteCode);
        code.setText("• • • • • •");
        code.setVisibility(View.VISIBLE);
        status(note, activity.getString(R.string.jellyfin_checking));
        show(new GlassMenu.Builder()
                .add(GlassMenu.Item.view(note))
                .add(choice(activity.getString(R.string.jellyfin_other_server), null, this::showServers)));
        int mine = step;
        signIn = new QuickConnect(account, server, name, new QuickConnect.Listener() {
            @Override
            public void onCode(String c) {
                if (mine != step) return;
                code.setText(c);
                status(note, activity.getString(R.string.jellyfin_waiting));
            }

            @Override
            public void onSignedIn() {
                close();
            }

            @Override
            public void onFailed(QuickConnect.Failure failure) {
                if (mine == step) showFailure(server, name, failure);
            }
        });
        signIn.start();
    }

    private void showFailure(String server, String name, QuickConnect.Failure failure) {
        newStep(this::showServers);
        int message;
        switch (failure) {
            case DISABLED: message = R.string.jellyfin_qc_disabled; break;
            case EXPIRED: message = R.string.jellyfin_qc_expired; break;
            default: message = R.string.jellyfin_qc_unreachable; break;
        }
        show(new GlassMenu.Builder()
                .add(GlassMenu.Item.view(note(activity.getString(R.string.jellyfin_code_title, name),
                        activity.getString(message, name))))
                .add(choice(activity.getString(R.string.jellyfin_try_again), null, () -> showCode(server, name)))
                .add(choice(activity.getString(R.string.jellyfin_other_server), null, this::showServers)));
    }

    private void stopSignIn() {
        if (signIn != null) signIn.cancel();
        signIn = null;
    }

    /** Ends this device's session on the server too, then forgets it here whatever the server says. */
    void signOut() {
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
