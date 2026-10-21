package app.aurelia.tv;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.ui.HeroDots;
import app.aurelia.tv.ui.ImageDisk;
import app.aurelia.tv.ui.Images;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Home's framed hero (view_home_hero.xml), a highlight reel rather than a list: the two things last
 * watched or next up, between the three newest additions to the library. The rows under it hold everything. It
 * steps by itself every few seconds, and with Left and Right while it has focus; OK plays what is being watched or
 * opens what is new, and holding OK on something being watched opens its options. Each step slides the text out
 * and the next in from the side it comes from, while the art crossfades and the dots move along.
 */
final class HomeHero {

    interface Listener {
        /** The hero has focus and shows {@code id}: the backdrop behind Home takes its art. */
        void onShown(String id, Drawable art);
        void onPlay(Library.MediaItem item);
        void onOpen(Catalog.Item item);
        void onOptions(Library.MediaItem item, View hero);
    }

    /** One slide: something being watched ({@code media}), or something new ({@code item}). */
    private static final class Slide {
        final String id;
        @Nullable final Library.MediaItem media;
        final boolean resuming;

        Slide(String id, @Nullable Library.MediaItem media, boolean resuming) {
            this.id = id;
            this.media = media;
            this.resuming = resuming;
        }
    }

    private static final long STEP_MS = 8000;
    private static final int WATCHING = 2;
    private static final int NEW = 3;
    private static final long OUT_MS = 160;
    private static final long IN_MS = 380;
    private static final long ART_MS = 600;
    /** The first slide shows by now even if its art or logo is still on its way. */
    private static final long REVEAL_BY_MS = 1500;

    private final View section;
    private final View card;
    private final View info;
    private final ImageView art;
    private final ImageView artBack;
    private final ImageView logo;
    private final TextView title;
    private final TextView meta;
    private final TextView overview;
    private final ProgressBar progress;
    private final HeroDots dots;
    private final Account account;
    private final Listener listener;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Slide> slides = new ArrayList<>();
    private final Map<String, Catalog.Item> details = new HashMap<>();
    /** Items whose art has been fetched ahead once already: each return to Home asks again, but needs none. */
    private final Set<String> prefetched = new HashSet<>();
    private final Set<String> fetching = new HashSet<>();
    private final float slide;
    private List<Library.MediaItem> resume = new ArrayList<>();
    private List<Library.MediaItem> nextUp = new ArrayList<>();
    private List<Catalog.Item> added = new ArrayList<>();
    private int at;
    /** Nothing shows until the first slide's art, details and logo are all in, so nothing pops in or moves. */
    private boolean revealed;
    private boolean artIn;
    private boolean logoIn;
    private final Runnable reveal = this::reveal;
    private boolean running;
    private boolean animate = true;
    private final Runnable next = () -> {
        step(1);
        schedule();
    };

    HomeHero(View section, Account account, Listener listener) {
        this.section = section;
        this.account = account;
        this.listener = listener;
        card = section.findViewById(R.id.hero);
        info = section.findViewById(R.id.heroInfo);
        art = section.findViewById(R.id.heroArt);
        artBack = section.findViewById(R.id.heroArtBack);
        logo = section.findViewById(R.id.heroLogo);
        title = section.findViewById(R.id.heroTitle);
        meta = section.findViewById(R.id.heroMeta);
        overview = section.findViewById(R.id.heroOverview);
        progress = section.findViewById(R.id.heroProgress);
        dots = section.findViewById(R.id.heroDots);
        slide = card.getResources().getDimension(R.dimen.space_xl);
        card.setClipToOutline(true);
        info.setAlpha(0f);
        dots.setAlpha(0f);
        card.setOnClickListener(v -> {
            if (slides.isEmpty()) return;
            Slide s = slides.get(at);
            if (s.media != null) listener.onPlay(s.media);
            else if (details.containsKey(s.id)) listener.onOpen(details.get(s.id));
        });
        card.setOnLongClickListener(v -> {
            if (slides.isEmpty() || slides.get(at).media == null) return false;
            listener.onOptions(slides.get(at).media, card);
            return true;
        });
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) shown();
            // While it has focus it waits for Left and Right; it moves on again once focus leaves.
            schedule();
        });
        section.setVisibility(View.GONE);
    }

    void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
        dots.setAnimationsEnabled(enabled);
    }

    View card() {
        return card;
    }

    boolean isShowing() {
        return section.getVisibility() == View.VISIBLE;
    }

    void setWatching(List<Library.MediaItem> resume, List<Library.MediaItem> nextUp) {
        this.resume = new ArrayList<>(resume);
        this.nextUp = new ArrayList<>(nextUp);
        rebuild();
    }

    void setAdded(List<Catalog.Item> added) {
        this.added = new ArrayList<>(added);
        for (Catalog.Item i : added) details.put(i.id, i);
        rebuild();
    }

    /** Watched, new, watched, new, new: it opens on what is most likely wanted next. The one showing stays. */
    private void rebuild() {
        String was = slides.isEmpty() ? null : slides.get(at).id;
        List<Slide> watching = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Library.MediaItem i : resume) if (watching.size() < WATCHING && ids.add(i.id)) watching.add(new Slide(i.id, i, true));
        for (Library.MediaItem i : nextUp) if (watching.size() < WATCHING && ids.add(i.id)) watching.add(new Slide(i.id, i, false));
        List<Slide> fresh = new ArrayList<>();
        for (Catalog.Item i : added) if (fresh.size() < NEW && ids.add(i.id)) fresh.add(new Slide(i.id, null, false));
        slides.clear();
        int w = 0, f = 0;
        for (int turn = 0; w < watching.size() || f < fresh.size(); turn++) {
            boolean takeWatching = (turn == 0 || turn == 2) ? w < watching.size() : f >= fresh.size();
            slides.add(takeWatching ? watching.get(w++) : fresh.get(f++));
        }
        section.setVisibility(slides.isEmpty() ? View.GONE : View.VISIBLE);
        dots.setCount(slides.size() > 1 ? slides.size() : 0);
        dots.setVisibility(slides.size() > 1 ? View.VISIBLE : View.GONE);
        if (slides.isEmpty()) return;
        if (!revealed) {
            handler.removeCallbacks(reveal);
            handler.postDelayed(reveal, REVEAL_BY_MS);
        }
        for (Slide s : slides) {
            fetch(s);
            // Its art ready before it comes round; only for the slides in use, as Recently added has many more.
            Catalog.Item known = details.get(s.id);
            if (known != null) prefetch(known);
        }
        int keep = 0;
        for (int n = 0; n < slides.size(); n++) if (slides.get(n).id.equals(was)) keep = n;
        boolean same = slides.get(keep).id.equals(was);
        at = keep;
        dots.setCurrent(at);
        bind(slides.get(at));
        if (!same) loadArt(slides.get(at), false);
        schedule();
    }

    /** Left or Right on the hero, or the timer: the one before or after, sliding in from that side. */
    void step(int by) {
        if (slides.size() < 2) return;
        at = ((at + by) % slides.size() + slides.size()) % slides.size();
        Slide s = slides.get(at);
        dots.setCurrent(at);
        loadArt(s, true);
        if (!animate) {
            bind(s);
        } else {
            info.animate().cancel();
            info.animate().alpha(0f).translationX(-by * slide).setDuration(OUT_MS).setInterpolator(ease)
                    .withEndAction(() -> {
                        bind(s);
                        info.setTranslationX(by * slide);
                        info.animate().alpha(1f).translationX(0f).setDuration(IN_MS).setInterpolator(ease).start();
                    }).start();
        }
        schedule();
    }

    void start() {
        running = true;
        schedule();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(next);
    }

    private void schedule() {
        handler.removeCallbacks(next);
        if (running && slides.size() > 1 && !card.hasFocus()) handler.postDelayed(next, STEP_MS);
    }

    /** The text for {@code s}: its logo when there is one (else its name), what it is, its overview. */
    private void bind(Slide s) {
        Catalog.Item d = details.get(s.id);
        String kind;
        String line;
        if (s.media != null) {
            kind = card.getResources().getString(s.resuming ? R.string.continue_watching : R.string.next_up);
            line = s.media.subtitle;
            title.setText(s.media.title);
            progress.setVisibility(s.media.progress > 0 ? View.VISIBLE : View.GONE);
            progress.setProgress(s.media.progress);
        } else {
            kind = card.getResources().getString(R.string.recently_added);
            line = d != null ? Catalog.facts(d, null) : "";
            title.setText(d != null ? d.name : "");
            progress.setVisibility(View.GONE);
        }
        meta.setText(line.isEmpty() ? kind : kind + " · " + line);
        overview.setText(d != null ? d.overview : null);
        String logoUrl = d != null ? Catalog.logo(account, d, 160) : "";
        // Cleared first, so the last one's logo cannot arrive late over this one.
        Images.load(logo, "", null);
        logo.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        logoIn = d != null && logoUrl.isEmpty();
        if (logoUrl.isEmpty()) {
            maybeReveal();
            return;
        }
        Images.load(logo, logoUrl, Library.auth(account), new Images.Loaded() {
            @Override
            public void onLoaded(Bitmap bitmap) {
                logo.setImageBitmap(app.aurelia.tv.ui.Logos.trimmed(logoUrl, bitmap));
                logo.setVisibility(View.VISIBLE);
                title.setVisibility(View.GONE);
                logoIn = true;
                maybeReveal();
            }

            @Override
            public void onFailed() {
                logoIn = true;
                maybeReveal();
            }
        });
    }

    /** The backdrop once the details are in (the card's art until then), faded in over the last. */
    private void loadArt(Slide s, boolean fade) {
        Catalog.Item d = details.get(s.id);
        String backdrop = d != null ? Catalog.backdrop(account, d, 1280) : "";
        String url = !backdrop.isEmpty() ? backdrop : s.media != null ? s.media.imageUrl : "";
        artBack.setImageDrawable(art.getDrawable());
        Images.load(art, url, Library.auth(account), bitmap -> {
            if (!revealed) {
                // Held back until the text is ready too; reveal() fades them in together.
                art.animate().cancel();
                art.setAlpha(0f);
                artIn = d != null;
                maybeReveal();
                return;
            }
            if (fade && animate) {
                art.animate().cancel();
                art.setAlpha(0f);
                art.animate().alpha(1f).setDuration(ART_MS).setInterpolator(ease).start();
            }
            shown();
        });
    }

    private void fetch(Slide s) {
        if (details.containsKey(s.id) || !fetching.add(s.id)) return;
        Api.async(() -> Catalog.item(account, s.id), new Api.Callback<Catalog.Item>() {
            @Override
            public void onResult(Catalog.Item item) {
                fetching.remove(s.id);
                details.put(s.id, item);
                prefetch(item);
                // The one showing gets its overview, logo and backdrop as soon as they are known.
                if (!slides.isEmpty() && slides.get(at).id.equals(s.id)) {
                    bind(slides.get(at));
                    loadArt(slides.get(at), true);
                }
            }

            @Override
            public void onError(Exception e) {
                fetching.remove(s.id);
            }
        });
    }

    private void maybeReveal() {
        if (!revealed && artIn && logoIn) reveal();
    }

    /** The first slide, art and text together, faded in as one. */
    private void reveal() {
        if (revealed || slides.isEmpty()) return;
        revealed = true;
        handler.removeCallbacks(reveal);
        for (View v : new View[] {art, info, dots}) {
            v.animate().cancel();
            if (animate) v.animate().alpha(1f).setDuration(ART_MS).setInterpolator(ease).start();
            else v.setAlpha(1f);
        }
        shown();
    }

    /** Each slide's backdrop and logo onto the disk ahead of time, so stepping to it never waits on them. */
    private void prefetch(Catalog.Item item) {
        if (!prefetched.add(item.id)) return;
        String auth = Library.auth(account);
        for (String url : new String[] {Catalog.backdrop(account, item, 1280), Catalog.logo(account, item, 160)}) {
            if (url.isEmpty()) continue;
            Api.async(() -> ImageDisk.get(url, auth), new Api.Callback<Bitmap>() {
                @Override
                public void onResult(Bitmap bitmap) {
                }

                @Override
                public void onError(Exception e) {
                }
            });
        }
    }

    private void shown() {
        if (card.hasFocus() && !slides.isEmpty()) listener.onShown(slides.get(at).id, art.getDrawable());
    }
}
