package app.aurelia.tv;

import android.app.Activity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.ui.Images;

import java.util.ArrayList;
import java.util.List;

/**
 * The top of a film's or show's page (view_details_header.xml): what it is, and its buttons. Watched and
 * favourite change at once and are sent to the server behind; a failure puts them back.
 */
final class DetailsHeader {

    private static final int LOGO_PX = 192;
    private static final int BACKDROP_PX = 1280;

    private final Activity activity;
    private final Account account;
    private final ImageView logo;
    private final TextView title;
    private final TextView facts;
    private final TextView genres;
    private final TextView overview;
    private final TextView credits;
    final TextView play;
    final TextView startOver;
    private final ImageView watched;
    private final ImageView favourite;
    private Catalog.Item item;
    private boolean isWatched;
    private boolean isFavourite;
    private Runnable onWatchedChanged;
    private boolean compact;

    DetailsHeader(Activity activity, View root) {
        this.activity = activity;
        account = Account.get(activity);
        logo = root.findViewById(R.id.detailsLogo);
        title = root.findViewById(R.id.detailsTitle);
        facts = root.findViewById(R.id.detailsFacts);
        genres = root.findViewById(R.id.detailsGenres);
        overview = root.findViewById(R.id.detailsOverview);
        credits = root.findViewById(R.id.detailsCredits);
        play = root.findViewById(R.id.detailsPlay);
        startOver = root.findViewById(R.id.detailsStartOver);
        watched = root.findViewById(R.id.detailsWatched);
        favourite = root.findViewById(R.id.detailsFavourite);
        watched.setOnClickListener(v -> setWatched(!isWatched));
        favourite.setOnClickListener(v -> setFavourite(!isFavourite));
    }

    /**
     * For a page with more under the header (a show's seasons): a smaller logo, two lines about it, and no
     * genres or cast.
     */
    void setCompact(int logoHeight) {
        compact = true;
        logo.setMaxHeight(logoHeight);
        ((View) logo.getParent()).getLayoutParams().height = logoHeight;
        overview.setMaxLines(2);
        genres.setVisibility(View.GONE);
        credits.setVisibility(View.GONE);
    }

    /** Called after Watched changes, so a show's page can mark its episodes. */
    void setOnWatchedChanged(Runnable r) {
        onWatchedChanged = r;
    }

    void bind(Catalog.Item i, String seasonsLabel, ImageView backdrop) {
        boolean first = item == null;
        item = i;
        String logoUrl = Catalog.logo(account, i, LOGO_PX);
        title.setText(i.name);
        title.setVisibility(logoUrl.isEmpty() ? View.VISIBLE : View.INVISIBLE);
        if (first) {
            Images.load(logo, logoUrl, Library.auth(account), bmp -> title.setVisibility(View.INVISIBLE));
            Images.load(backdrop, Catalog.backdrop(account, i, BACKDROP_PX), Library.auth(account));
        }
        facts.setText(Catalog.facts(i, seasonsLabel));
        genres.setText(String.join(" · ", i.genres.subList(0, Math.min(3, i.genres.size()))));
        genres.setVisibility(compact || i.genres.isEmpty() ? View.GONE : View.VISIBLE);
        overview.setText(i.overview);
        overview.setVisibility(i.overview.isEmpty() ? View.GONE : View.VISIBLE);
        List<String> made = new ArrayList<>();
        if (!i.directors.isEmpty()) {
            made.add(activity.getString(R.string.details_directed, String.join(", ", i.directors)));
        }
        if (!i.cast.isEmpty()) made.add(activity.getString(R.string.details_starring, String.join(", ", i.cast)));
        credits.setText(String.join("   ·   ", made));
        credits.setVisibility(compact || made.isEmpty() ? View.GONE : View.VISIBLE);
        isWatched = i.played;
        isFavourite = i.favourite;
        showToggles();
    }

    /**
     * The two toggles say the same way whether they are on: an outline icon on plain glass when off, the icon
     * filled on brighter glass when on, so either reads at a glance with or without focus.
     */
    private void showToggles() {
        watched.setImageResource(isWatched ? R.drawable.ic_watched_on : R.drawable.ic_watched);
        watched.setActivated(isWatched);
        favourite.setImageResource(isFavourite ? R.drawable.ic_heart : R.drawable.ic_heart_outline);
        favourite.setActivated(isFavourite);
    }

    private void setWatched(boolean on) {
        if (item == null) return;
        isWatched = on;
        showToggles();
        String id = item.id;
        Api.async(() -> {
            Catalog.setPlayed(account, id, on);
            return true;
        }, new Api.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok) {
                if (onWatchedChanged != null) onWatchedChanged.run();
            }

            @Override
            public void onError(Exception e) {
                isWatched = !on;
                showToggles();
            }
        });
    }

    private void setFavourite(boolean on) {
        if (item == null) return;
        isFavourite = on;
        showToggles();
        String id = item.id;
        Api.async(() -> {
            Catalog.setFavourite(account, id, on);
            return true;
        }, new Api.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok) {
            }

            @Override
            public void onError(Exception e) {
                isFavourite = !on;
                showToggles();
            }
        });
    }
}
