package app.aurelia.tv;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.ui.SectionScrollView;

import java.util.List;

/**
 * The rows under a film's or show's page: the rest of its collection, for a film in one ("The Matrix
 * Collection"); who is in it and who made it; then more like it. Choosing someone opens their page; choosing a
 * film or show opens its own. Each row stays gone until there is something in it.
 */
final class DetailsMore {

    private static final int SIMILAR = 14;

    private final Activity activity;
    private final Account account;
    private final ShelfStack stack;
    private final ShelfStack.Section collection;
    private final ShelfStack.Section cast;
    private final ShelfStack.Section similar;
    private String loadedFor;

    DetailsMore(Activity activity, ViewGroup column) {
        this.activity = activity;
        account = Account.get(activity);
        stack = new ShelfStack(activity, column);
        int gutter = stack.gutter();
        collection = stack.add("", new PosterAdapter(account, LibraryPage.COLUMNS, gutter, opener()));
        cast = stack.add(R.string.details_cast, new PersonAdapter(account, LibraryPage.COLUMNS, gutter,
                person -> PersonActivity.start(activity, person.id)));
        similar = stack.add(R.string.details_more_like_this,
                new PosterAdapter(account, LibraryPage.COLUMNS, gutter, opener()));
    }

    /** Choosing a poster opens its page. */
    private PosterAdapter.Listener opener() {
        return new PosterAdapter.Listener() {
            @Override
            public void onFocused(Catalog.Item item, Drawable art) {
            }

            @Override
            public void onClicked(Catalog.Item item) {
                open(activity, item);
            }

            @Override
            public void onNearEnd() {
            }
        };
    }

    /**
     * Makes {@code hero} (the page's top: what it is, its buttons) the height of {@code scroll} less a peek of the
     * first row, and {@code dim} darken the backdrop as the rows scroll up over it.
     */
    static void fitHero(SectionScrollView scroll, View hero, View dim) {
        int peek = scroll.getResources().getDimensionPixelSize(R.dimen.details_peek);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int height = (b - t) - scroll.getPaddingTop() - peek;
            if (height > 0 && hero.getLayoutParams().height != height) {
                hero.getLayoutParams().height = height;
                hero.requestLayout();
            }
        });
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            float h = Math.max(1, hero.getHeight());
            dim.setAlpha(Math.min(1f, y / (h * 0.6f)));
        });
    }

    /** A film's or show's page for {@code item}. */
    static void open(Activity activity, Catalog.Item item) {
        if (item.isSeries()) ShowActivity.start(activity, item.id);
        else MovieActivity.start(activity, item.id);
    }

    /** Fills the rows for {@code item}; only once for each item (they do not change on coming back). */
    void bind(Catalog.Item item) {
        if (item.id.equals(loadedFor)) return;
        loadedFor = item.id;
        ((PersonAdapter) cast.row.getAdapter()).set(item.people);
        cast.show(item.people.size());
        String id = item.id;
        if (!item.isSeries()) {
            Api.async(() -> {
                Catalog.Item set = Catalog.collectionOf(account, id);
                return set == null ? null : new Object[] {set, Catalog.collection(account, set.id)};
            }, new Api.Callback<Object[]>() {
                @Override
                @SuppressWarnings("unchecked")
                public void onResult(Object[] r) {
                    if (r == null || activity.isFinishing()) return;
                    List<Catalog.Item> films = (List<Catalog.Item>) r[1];
                    collection.setTitle(((Catalog.Item) r[0]).name);
                    ((PosterAdapter) collection.row.getAdapter()).set(films);
                    // A collection of one is not worth a row.
                    collection.show(films.size() > 1 ? films.size() : 0);
                }

                @Override
                public void onError(Exception e) {
                }
            });
        }
        Api.async(() -> Catalog.similar(account, id, SIMILAR), new Api.Callback<List<Catalog.Item>>() {
            @Override
            public void onResult(List<Catalog.Item> items) {
                if (activity.isFinishing()) return;
                ((PosterAdapter) similar.row.getAdapter()).set(items);
                similar.show(items.size());
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    boolean contains(View focus) {
        return stack.contains(focus);
    }

    boolean focusFirst() {
        return stack.focusFirst();
    }

    boolean route(int key, View focus, Runnable exitUp) {
        return stack.route(key, focus, exitUp);
    }
}
