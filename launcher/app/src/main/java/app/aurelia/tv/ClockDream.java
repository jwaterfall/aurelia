package app.aurelia.tv;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.Library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Screensaver: the library's backdrops, one after another, each drifting slowly as it cross-fades into the next,
 * with its logo and facts at the bottom left and the clock at the top right, as on Home. Signed out, or with the
 * server out of reach, it is the clock alone over a slow ambient glow, moving every minute.
 *
 * Nothing stays still for long, so nothing burns in: the slides drift, and the clock and caption shift by a few
 * pixels with each one.
 */
public class ClockDream extends DreamService {

    /** How long each slide shows, and how long two cross-fade. */
    private static final long SLIDE_MS = 14_000L;
    private static final long FADE_MS = 2_000L;
    private static final long CAPTION_MS = 700L;
    private static final int SLIDES = 40;
    private static final int BACKDROP_PX = 1280;
    private static final int LOGO_PX = 176;
    /** Each slide starts this much larger and settles back as it drifts. */
    private static final float DRIFT_SCALE = 0.08f;
    /** The clock and caption move up to this far (dp) between slides. */
    private static final float SHIFT_DP = 6f;
    /** With nothing to show: the clock moves every minute. */
    private static final long MOVE_MS = 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private Account account;
    private ImageView[] slides;
    private int front;
    private View scrim;
    private View clock;
    private View caption;
    private ImageView logo;
    private TextView title;
    private TextView facts;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;

    private final List<Catalog.Item> items = new ArrayList<>();
    private int next;
    private boolean showing;
    private boolean ambient;

    private final Runnable advance = this::showNext;

    private final Runnable moveClock = new Runnable() {
        @Override
        public void run() {
            clock.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                placeClock();
                clock.animate().alpha(1f).setDuration(1200).start();
            }).start();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        account = Account.get(this);
        slides = new ImageView[] {findViewById(R.id.dreamSlideA), findViewById(R.id.dreamSlideB)};
        scrim = findViewById(R.id.dreamScrim);
        clock = findViewById(R.id.dreamClockGroup);
        caption = findViewById(R.id.dreamCaption);
        logo = findViewById(R.id.dreamLogo);
        title = findViewById(R.id.dreamTitle);
        facts = findViewById(R.id.dreamFacts);

        View glowView = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowView.setBackground(glow);
        glowView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        showing = true;
        clock.setAlpha(0f);
        clock.animate().alpha(1f).setDuration(1200).start();
        if (account.isSignedIn()) loadSlides();
        else startAmbient();
    }

    @Override
    public void onDreamingStopped() {
        stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void stop() {
        showing = false;
        handler.removeCallbacksAndMessages(null);
        for (ImageView s : slides) s.animate().cancel();
    }

    // ---- slides --------------------------------------------------------------------------------------

    private void loadSlides() {
        Api.async(() -> Catalog.showcase(account, SLIDES), new Api.Callback<List<Catalog.Item>>() {
            @Override
            public void onResult(List<Catalog.Item> list) {
                if (!showing) return;
                items.clear();
                for (Catalog.Item i : list) if (!i.backdropTag.isEmpty()) items.add(i);
                next = 0;
                if (items.isEmpty()) startAmbient();
                else showNext();
            }

            @Override
            public void onError(Exception e) {
                if (showing) startAmbient();
            }
        });
    }

    /** Fetches the next slide's backdrop and, once it is here, fades it in over the last. */
    private void showNext() {
        if (!showing) return;
        if (next >= items.size()) {
            // Round again, in a new order.
            Collections.shuffle(items, random);
            next = 0;
        }
        Catalog.Item item = items.get(next++);
        String auth = Library.auth(account);
        String backdropUrl = Catalog.backdrop(account, item, BACKDROP_PX);
        String logoUrl = Catalog.logo(account, item, LOGO_PX);
        Api.async(() -> new Bitmap[] {Api.getImage(backdropUrl, auth),
                        logoUrl.isEmpty() ? null : Api.getImage(logoUrl, auth)},
                new Api.Callback<Bitmap[]>() {
                    @Override
                    public void onResult(Bitmap[] art) {
                        if (!showing) return;
                        if (art[0] == null) {
                            handler.post(advance);
                            return;
                        }
                        reveal(item, art[0], art[1]);
                        handler.postDelayed(advance, SLIDE_MS);
                    }

                    @Override
                    public void onError(Exception e) {
                        // One that will not load is skipped; with none loading, the clock alone.
                        if (!showing) return;
                        if (ambient || items.size() <= 1) startAmbient();
                        else handler.postDelayed(advance, FADE_MS);
                    }
                });
    }

    private void reveal(Catalog.Item item, Bitmap backdrop, Bitmap logoArt) {
        if (ambient) stopAmbient();
        ImageView incoming = slides[1 - front];
        ImageView outgoing = slides[front];
        front = 1 - front;

        incoming.setImageBitmap(backdrop);
        incoming.bringToFront();
        scrim.bringToFront();
        clock.bringToFront();
        caption.bringToFront();
        incoming.setAlpha(0f);
        // Drift: from a little larger, towards one side, back to rest over the slide's whole time.
        float width = getResources().getDisplayMetrics().widthPixels;
        float dx = (random.nextBoolean() ? 1 : -1) * width * DRIFT_SCALE / 3f;
        incoming.setScaleX(1f + DRIFT_SCALE);
        incoming.setScaleY(1f + DRIFT_SCALE);
        incoming.setTranslationX(dx);
        // The fade on its own animator: a view's animate() runs all its properties over one duration.
        ObjectAnimator fade = ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f);
        fade.setDuration(FADE_MS);
        fade.setInterpolator(new DecelerateInterpolator());
        fade.start();
        incoming.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(SLIDE_MS + FADE_MS)
                .setInterpolator(new LinearInterpolator()).start();
        // The last slide keeps drifting under the new one until it is covered, then lets its picture go.
        handler.postDelayed(() -> {
            if (!showing) return;
            outgoing.animate().cancel();
            outgoing.setAlpha(0f);
            outgoing.setImageDrawable(null);
        }, FADE_MS);
        if (scrim.getAlpha() < 1f) scrim.animate().alpha(1f).setDuration(FADE_MS).start();

        // The caption leaves as the new slide arrives, and comes back up with its name.
        caption.animate().alpha(0f).setDuration(CAPTION_MS).withEndAction(() -> {
            setCaption(item, logoArt);
            shift(caption);
            shift(clock);
            caption.setTranslationY(caption.getTranslationY() + dp(12));
            caption.animate().alpha(1f).translationYBy(-dp(12)).setDuration(CAPTION_MS * 2)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }).start();
    }

    private void setCaption(Catalog.Item item, Bitmap logoArt) {
        logo.setImageBitmap(logoArt);
        logo.setVisibility(logoArt == null ? View.GONE : View.VISIBLE);
        title.setText(item.name);
        title.setVisibility(logoArt == null ? View.VISIBLE : View.GONE);
        String seasons = item.isSeries() && item.childCount > 0
                ? getResources().getQuantityString(R.plurals.seasons, item.childCount, item.childCount) : null;
        facts.setText(Catalog.facts(item, seasons));
    }

    /** A few pixels off its place, a new way each slide. */
    private void shift(View v) {
        v.setTranslationX((random.nextFloat() * 2 - 1) * dp(SHIFT_DP));
        v.setTranslationY((random.nextFloat() * 2 - 1) * dp(SHIFT_DP));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // ---- the clock alone -----------------------------------------------------------------------------

    private void startAmbient() {
        if (ambient) return;
        ambient = true;
        caption.animate().alpha(0f).setDuration(CAPTION_MS).start();
        scrim.animate().alpha(0f).setDuration(FADE_MS).start();
        for (ImageView s : slides) s.animate().alpha(0f).setDuration(FADE_MS).start();
        clock.post(this::placeClock);
        handler.postDelayed(moveClock, MOVE_MS);
    }

    private void stopAmbient() {
        ambient = false;
        handler.removeCallbacks(moveClock);
        clock.animate().translationX(0f).translationY(0f).setDuration(FADE_MS).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) clock.getParent();
        int maxX = Math.max(0, parent.getWidth() - clock.getWidth());
        int maxY = Math.max(0, parent.getHeight() - clock.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        clock.setTranslationX(x - clock.getLeft());
        clock.setTranslationY(y - clock.getTop());
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + clock.getWidth() / 2f) / parent.getWidth(),
                    (y + clock.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
