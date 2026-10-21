package app.aurelia.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.ui.Images;
import app.aurelia.tv.ui.SectionScrollView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Someone's page, opened from a film's or show's cast: their photo, name, when and where they were born, how much
 * of theirs is here and a few lines about them, over the backdrop of the first of their films or shows; then what
 * of theirs is in the library, films and shows a row each. Choosing one opens
 * its page. Focus starts on their first film (or show); Up from the first row scrolls back up to them.
 */
public final class PersonActivity extends Activity {

    private static final String EXTRA_PERSON = "person";
    private static final int PHOTO_PX = 480;
    private static final int LIMIT = 60;
    private static final int BACKDROP_PX = 1280;

    private Account account;
    private String personId;
    private ShelfStack stack;
    private ShelfStack.Section films, series;
    private SectionScrollView scroll;
    private boolean focused;

    public static void start(Context context, String personId) {
        context.startActivity(new Intent(context, PersonActivity.class).putExtra(EXTRA_PERSON, personId));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_person);
        account = Account.get(this);
        personId = getIntent().getStringExtra(EXTRA_PERSON);
        scroll = findViewById(R.id.personScroll);
        // Rows come up only as far as they must, so the first keeps the person in view above it.
        scroll.setAlignTop(false);
        stack = new ShelfStack(this, findViewById(R.id.personColumn));
        int gutter = stack.gutter();
        films = stack.add(R.string.tab_movies, new PosterAdapter(account, LibraryPage.COLUMNS, gutter, listener()))
                .counted();
        series = stack.add(R.string.tab_shows, new PosterAdapter(account, LibraryPage.COLUMNS, gutter, listener()))
                .counted();
        load();
    }

    private PosterAdapter.Listener listener() {
        return new PosterAdapter.Listener() {
            @Override
            public void onFocused(Catalog.Item item, Drawable art) {
            }

            @Override
            public void onClicked(Catalog.Item item) {
                DetailsMore.open(PersonActivity.this, item);
            }

            @Override
            public void onNearEnd() {
            }
        };
    }

    private void load() {
        Api.async(() -> {
            Catalog.Item person = Catalog.item(account, personId);
            List<Catalog.Item> movies = Catalog.withPerson(account, personId, "Movie", LIMIT);
            List<Catalog.Item> shows = Catalog.withPerson(account, personId, "Series", LIMIT);
            List<Object> out = new ArrayList<>();
            out.add(person);
            out.add(movies);
            out.add(shows);
            return out;
        }, new Api.Callback<List<Object>>() {
            @Override
            @SuppressWarnings("unchecked")
            public void onResult(List<Object> r) {
                if (isFinishing()) return;
                List<Catalog.Item> movies = (List<Catalog.Item>) r.get(1);
                List<Catalog.Item> shows = (List<Catalog.Item>) r.get(2);
                show((Catalog.Item) r.get(0), movies.size() + shows.size());
                showBackdrop(movies, shows);
                ((PosterAdapter) films.row.getAdapter()).set(movies);
                films.show(movies.size());
                ((PosterAdapter) series.row.getAdapter()).set(shows);
                series.show(shows.size());
                if (!focused) {
                    focused = true;
                    scroll.post(stack::focusFirst);
                }
            }

            @Override
            public void onError(Exception e) {
                if (isFinishing()) return;
                String server = account.serverName().isEmpty() ? account.server() : account.serverName();
                Toast.makeText(PersonActivity.this, getString(R.string.details_failed, server), Toast.LENGTH_LONG)
                        .show();
                finish();
            }
        });
    }

    /** The backdrop of the first of their films (or shows) that has one, as a setting for the page. */
    private void showBackdrop(List<Catalog.Item> movies, List<Catalog.Item> shows) {
        List<Catalog.Item> all = new ArrayList<>(movies);
        all.addAll(shows);
        for (Catalog.Item i : all) {
            if (i.backdropTag.isEmpty()) continue;
            Images.load(findViewById(R.id.personBackdrop), Catalog.backdrop(account, i, BACKDROP_PX),
                    Library.auth(account));
            return;
        }
    }

    private void show(Catalog.Item p, int inLibrary) {
        ((TextView) findViewById(R.id.personName)).setText(p.name);
        List<String> facts = new ArrayList<>();
        String born = born(p.born);
        if (!born.isEmpty()) facts.add(getString(R.string.person_born, born));
        if (!p.birthplace.isEmpty()) facts.add(p.birthplace);
        if (inLibrary > 0) {
            facts.add(getResources().getQuantityString(R.plurals.person_in_library, inLibrary, inLibrary));
        }
        TextView factsView = findViewById(R.id.personFacts);
        factsView.setText(String.join(" · ", facts));
        factsView.setVisibility(facts.isEmpty() ? View.GONE : View.VISIBLE);
        TextView overview = findViewById(R.id.personOverview);
        overview.setText(p.overview);
        overview.setVisibility(p.overview.isEmpty() ? View.GONE : View.VISIBLE);

        View loading = findViewById(R.id.personLoading);
        TextView initials = findViewById(R.id.personInitials);
        initials.setText(PersonAdapter.initials(p.name));
        ImageView photo = findViewById(R.id.personPhoto);
        String url = Catalog.poster(account, p, PHOTO_PX);
        Images.load(photo, url, Library.auth(account), new Images.Loaded() {
            @Override
            public void onLoaded(Bitmap bitmap) {
                loading.setVisibility(View.GONE);
            }

            @Override
            public void onFailed() {
                loading.setVisibility(View.GONE);
                initials.setVisibility(View.VISIBLE);
            }
        });
    }

    /** "14 March 1964" in the TV's own way of writing dates, from the server's yyyy-MM-dd. */
    private String born(String iso) {
        if (iso.isEmpty()) return "";
        try {
            java.util.Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso);
            String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "d MMMM yyyy");
            return d == null ? "" : new SimpleDateFormat(pattern, Locale.getDefault()).format(d);
        } catch (ParseException e) {
            return "";
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View focus = getCurrentFocus();
            if (focus != null && stack.contains(focus)
                    && stack.route(event.getKeyCode(), focus, () -> scroll.glideTo(0))) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
