package app.aurelia.tv;

import android.view.View;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.ui.GlassMenu;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * A film's or show's menu on a held OK, wherever its poster is (PosterAdapter's, everywhere, and the hero's
 * Recently added slides): mark it watched or not, and make it a favourite or not.
 */
final class ItemOptions {

    private ItemOptions() { }

    /**
     * Opens over {@code poster}, in its screen's menu (GlassMenu.of). {@code show} is handed the item as it now is,
     * to redraw its poster with.
     */
    static void show(Account account, Catalog.Item item, View poster, Consumer<Catalog.Item> show) {
        GlassMenu menu = GlassMenu.of(poster);
        android.content.Context c = poster.getContext();
        GlassMenu.Builder m = new GlassMenu.Builder()
                .add(GlassMenu.Item.heading(item.name))
                .add(GlassMenu.Item.option(c.getString(item.played ? R.string.menu_mark_unwatched : R.string.menu_mark_watched),
                        null, false, () -> mark(account, item, item.withUserData(!item.played, item.favourite), show)))
                .add(GlassMenu.Item.option(c.getString(item.favourite ? R.string.menu_remove_favourite : R.string.menu_add_favourite),
                        null, false, () -> mark(account, item, item.withUserData(item.played, !item.favourite), show)));
        menu.show(poster, m, menu.defaultBackground());
    }

    /**
     * Shows {@code after}'s marks at once and tells the server. Then the server's own copy (a show's count of
     * episodes left), or {@code before} again if the server said no.
     */
    private static void mark(Account account, Catalog.Item before, Catalog.Item after, Consumer<Catalog.Item> show) {
        show.accept(after);
        boolean played = after.played != before.played;
        Api.async(() -> {
            if (played) Catalog.setPlayed(account, after.id, after.played);
            else Catalog.setFavourite(account, after.id, after.favourite);
            try {
                return Catalog.item(account, after.id);
            } catch (IOException e) {
                return after;   // the mark is made; only the fresh copy is missing
            }
        }, new Api.Callback<Catalog.Item>() {
            @Override
            public void onResult(Catalog.Item fresh) {
                show.accept(fresh);
            }

            @Override
            public void onError(Exception e) {
                show.accept(before);
            }
        });
    }
}
