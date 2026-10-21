package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.gathin.tvhome.jellyfin.Account;
import com.gathin.tvhome.jellyfin.Api;
import com.gathin.tvhome.jellyfin.Catalog;
import com.gathin.tvhome.jellyfin.Library;
import com.gathin.tvhome.player.PlayerActivity;

/**
 * A film's page: its backdrop, what it is, and Play (Resume, with Start over, once started) focused, so OK plays.
 * Back from the player lands here again, with the buttons brought up to date.
 */
public final class MovieActivity extends Activity {

    private static final String EXTRA_ITEM = "item";

    private Account account;
    private String itemId;
    private DetailsHeader header;
    private ImageView backdrop;
    private View content;
    private View spinner;
    private boolean focused;

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
        spinner = findViewById(R.id.detailsSpinner);
        header = new DetailsHeader(this, content);
        header.play.setOnClickListener(v -> PlayerActivity.start(this, itemId));
        header.startOver.setOnClickListener(v -> PlayerActivity.start(this, itemId, true));
        content.setAlpha(0f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        Api.async(() -> Catalog.item(account, itemId), new Api.Callback<Catalog.Item>() {
            @Override
            public void onResult(Catalog.Item item) {
                if (isFinishing()) return;
                spinner.setVisibility(View.GONE);
                header.bind(item, null, backdrop);
                boolean started = item.inProgress();
                header.play.setText(started
                        ? getString(R.string.details_resume) + " · " + getString(R.string.details_left,
                                Library.length((item.runtimeMs - item.positionMs) * 10_000L))
                        : getString(R.string.details_play));
                header.startOver.setVisibility(started ? View.VISIBLE : View.GONE);
                if (content.getAlpha() < 1f) content.animate().alpha(1f).setDuration(240).start();
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
