package app.aurelia.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.player.PlayerActivity;
import app.aurelia.tv.ui.Placeholder;
import app.aurelia.tv.ui.SectionScrollView;

/**
 * A film's page: its backdrop, what it is, and Play (Resume, with Start over, once started) focused, so OK plays.
 * Back from the player lands here again, with the buttons brought up to date. Down from the buttons scrolls to
 * the rows under it (DetailsMore): the cast and crew, and more like it.
 */
public final class MovieActivity extends Activity {

    private static final String EXTRA_ITEM = "item";

    private Account account;
    private String itemId;
    private DetailsHeader header;
    private ImageView backdrop;
    private View content;
    private View placeholder;
    private boolean focused;
    private View buttons;
    private DetailsMore more;

    public static void start(Context context, String itemId) {
        context.startActivity(new Intent(context, MovieActivity.class).putExtra(EXTRA_ITEM, itemId));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_movie);
        account = Account.get(this);
        itemId = getIntent().getStringExtra(EXTRA_ITEM);
        backdrop = findViewById(R.id.detailsBackdrop);
        content = findViewById(R.id.detailsHeader);
        placeholder = findViewById(R.id.detailsPlaceholder);
        header = new DetailsHeader(this, content);
        header.play.setOnClickListener(v -> PlayerActivity.start(this, itemId));
        header.startOver.setOnClickListener(v -> PlayerActivity.start(this, itemId, true));
        content.setAlpha(0f);
        buttons = content.findViewById(R.id.detailsButtons);
        more = new DetailsMore(this, findViewById(R.id.detailsColumn));
        DetailsMore.fitHero(findViewById(R.id.detailsScroll), findViewById(R.id.detailsHero),
                findViewById(R.id.detailsDim));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && route(event.getKeyCode())) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Down from the buttons into the rows; Up from the first row back to Play. */
    private boolean route(int key) {
        View focus = getCurrentFocus();
        if (focus == null) return false;
        if (more.contains(focus)) return more.route(key, focus, header.play::requestFocus);
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && ShelfStack.isIn(focus, buttons)) {
            more.focusFirst();
            return true;
        }
        return false;
    }

    private void load() {
        Api.async(() -> Catalog.item(account, itemId), new Api.Callback<Catalog.Item>() {
            @Override
            public void onResult(Catalog.Item item) {
                if (isFinishing()) return;
                header.bind(item, null, backdrop);
                more.bind(item);
                boolean started = item.inProgress();
                header.play.setText(started
                        ? getString(R.string.details_resume) + " · " + getString(R.string.details_left,
                                Library.length((item.runtimeMs - item.positionMs) * 10_000L))
                        : getString(R.string.details_play));
                header.startOver.setVisibility(started ? View.VISIBLE : View.GONE);
                Placeholder.reveal(placeholder, content);
                if (!focused) {
                    focused = true;
                    header.play.requestFocus();
                }
            }

            @Override
            public void onError(Exception e) {
                if (isFinishing() || content.getAlpha() > 0f) return;
                String server = account.serverName().isEmpty() ? account.server() : account.serverName();
                Toast.makeText(MovieActivity.this, getString(R.string.details_failed, server), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }
}
