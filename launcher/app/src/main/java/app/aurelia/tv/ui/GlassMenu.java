package app.aurelia.tv.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.aurelia.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A compact glass menu that rises from the button that opened it, for picking one of a few options without
 * leaving what is on screen: subtitles in the player, sorting a library. It sits just above the button, lined
 * up with it, and grows upwards; or below it, growing down, when there is more room there (a button near the
 * top of the screen). It scrolls when its options are more than fit.
 *
 * Put one over the whole screen (match_parent, late in the root). While it shows, nothing behind it takes
 * focus; choosing or Back closes it and focus returns to the button.
 */
public class GlassMenu extends FrameLayout {

    /** One line of a menu: an option with a tick when chosen, or a heading over the options below it. */
    public static final class Item {
        final CharSequence label;
        final CharSequence detail;
        final boolean chosen;
        final Runnable action;
        /** Set for an option that stays open and is handed its row (to show progress in it). */
        Live live;
        /** Set for a view shown as it is, such as the profile menu's header. */
        View view;
        /** Drawn where the tick goes, untinted (the update's blue dot). */
        int mark;

        private Item(CharSequence label, CharSequence detail, boolean chosen, Runnable action) {
            this.label = label;
            this.detail = detail;
            this.chosen = chosen;
            this.action = action;
        }

        /** An option that keeps the menu open and is given its row, to change its own text. */
        public static Item live(CharSequence label, @Nullable CharSequence detail, int mark, Live live) {
            Item item = new Item(label, detail, false, () -> { });
            item.live = live;
            item.mark = mark;
            return item;
        }

        /** {@code view} in the menu as it is; it does not take focus. */
        public static Item view(View view) {
            Item item = new Item("", null, false, null);
            item.view = view;
            return item;
        }

        public static Item option(CharSequence label, @Nullable CharSequence detail, boolean chosen, Runnable action) {
            return new Item(label, detail, chosen, action);
        }

        public static Item heading(CharSequence label) {
            return new Item(label, null, false, null);
        }

        /** A thin line between groups of options that need no heading. */
        public static Item divider() {
            return new Item("", null, false, null);
        }
    }

    /** An option that stays open and handles itself. {@code title} and {@code detail} are its row's text. */
    public interface Live {
        void run(View row, TextView title, TextView detail);
    }

    public static final class Builder {
        private final List<Item> items = new ArrayList<>();

        public Builder add(Item item) {
            items.add(item);
            return this;
        }
    }

    private static final long IN_MS = 200;
    private static final long OUT_MS = 140;
    /** The highlight's slide from one option to the next. */
    private static final long SLIDE_MS = 150;

    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final FrameLayout card;
    /** Behind the card's glass, a blurred copy of the part of the screen it covers. */
    private final android.widget.ImageView frost;
    private android.graphics.Bitmap snapshot;
    private boolean frostBehind = true;
    /** Told when the menu opens and closes, so what is behind can hold still and match its frost. */
    private java.util.function.Consumer<Boolean> onOpenChanged;
    private final LinearLayout rows;
    /** The white behind the option with focus, and its slide there. */
    private final View highlight;
    private android.animation.ValueAnimator slide;
    private boolean animate = true;
    private final ScrollView scroll;
    private final int gap;
    private final int margin;
    private final int maxHeight;
    private ViewGroup behind;
    private int behindFocusability;
    private View anchor;
    private boolean open;
    /** Where {@link #place} put the card, as a translation; the open and close animations move relative to it. */
    private float placedY;
    /** Opened below its button, so it grows down and animates from its top edge. */
    private boolean below;

    public GlassMenu(Context context) {
        this(context, null);
    }

    public GlassMenu(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        gap = getResources().getDimensionPixelSize(R.dimen.space_sm);
        margin = getResources().getDimensionPixelSize(R.dimen.screen_margin);
        maxHeight = getResources().getDimensionPixelSize(R.dimen.menu_max_height);
        LayoutInflater.from(context).inflate(R.layout.view_glass_menu, this, true);
        card = findViewById(R.id.menuCard);
        frost = findViewById(R.id.menuFrost);
        float radius = getResources().getDimension(R.dimen.menu_radius);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        scroll = findViewById(R.id.menuScroll);
        rows = findViewById(R.id.menuRows);
        highlight = findViewById(R.id.menuHighlight);
        setClipChildren(false);
        setVisibility(GONE);
    }

    /**
     * False where what is behind cannot be frosted (playing video draws on its own surface, which a
     * snapshot misses): the card sits on a dark fill instead.
     */
    public void setFrostBehind(boolean frost) {
        frostBehind = frost;
    }

    /** {@code listener} hears true as the menu opens and false as it closes. */
    public void setOnOpenChanged(java.util.function.Consumer<Boolean> listener) {
        onOpenChanged = listener;
    }

    /** false: the highlight jumps to each option instead of sliding (config.xml, animations). */
    public void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
    }

    public boolean isShowing() {
        return open;
    }

    /**
     * Opens over {@code from} with {@code menu}'s items. {@code background} is the screen behind, which cannot
     * take focus until the menu closes.
     */
    public void show(View from, Builder menu, ViewGroup background) {
        rows.removeAllViews();
        if (slide != null) slide.cancel();
        highlight.setVisibility(INVISIBLE);
        View focus = null;
        View first = null;
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (Item item : menu.items) {
            if (item.view != null) {
                if (item.view.getParent() instanceof ViewGroup) ((ViewGroup) item.view.getParent()).removeView(item.view);
                rows.addView(item.view);
                continue;
            }
            if (item.action == null && item.label.length() == 0) {
                rows.addView(inflater.inflate(R.layout.item_menu_divider, rows, false));
                continue;
            }
            if (item.action == null) {
                TextView heading = (TextView) inflater.inflate(R.layout.item_menu_heading, rows, false);
                heading.setText(item.label);
                rows.addView(heading);
                continue;
            }
            View row = inflater.inflate(R.layout.item_menu_row, rows, false);
            ((TextView) row.findViewById(R.id.rowTitle)).setText(item.label);
            TextView detail = row.findViewById(R.id.rowSubtitle);
            detail.setText(item.detail);
            detail.setVisibility(item.detail == null ? GONE : VISIBLE);
            android.widget.ImageView check = row.findViewById(R.id.rowCheck);
            check.setVisibility(item.chosen ? VISIBLE : INVISIBLE);
            if (item.mark != 0) {
                check.setImageResource(item.mark);
                check.setImageTintList(null);
                check.setVisibility(VISIBLE);
                int dot = Math.round(9 * getResources().getDisplayMetrics().density);
                check.getLayoutParams().width = dot;
                check.getLayoutParams().height = dot;
            }
            row.setOnClickListener(v -> {
                if (item.live != null) {
                    item.live.run(row, row.findViewById(R.id.rowTitle), detail);
                    return;
                }
                hide();
                item.action.run();
            });
            row.setOnFocusChangeListener((v, has) -> {
                if (has) highlight(v);
            });
            // A row that grows or shrinks with focus (an update's line under it) takes the highlight with it.
            row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (v.hasFocus() && (slide == null || !slide.isRunning()) && (t != ot || b - t != ob - ot)) {
                    placeHighlight(t, b - t);
                }
            });
            rows.addView(row);
            if (first == null) first = row;
            if (item.chosen && focus == null) focus = row;
        }
        View initial = focus != null ? focus : first;

        anchor = from;
        if (!open) {
            // Taken while the menu is still hidden, so it is only what is under it.
            snapshot = frostBehind && getParent() instanceof View ? Blur.snapshot((View) getParent(), 8, 6) : null;
            if (onOpenChanged != null) onOpenChanged.accept(true);
            behind = background;
            behindFocusability = background.getDescendantFocusability();
            background.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        }
        open = true;
        setVisibility(VISIBLE);
        card.animate().cancel();
        card.setAlpha(0f);
        // Placed once it has been measured: above the button, lined up with its far edge.
        card.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                card.removeOnLayoutChangeListener(this);
                place();
                card.setPivotX(Math.max(0f, Math.min(card.getWidth(), anchorCentre() - card.getX())));
                card.setPivotY(below ? 0f : card.getHeight());
                card.setScaleX(0.96f);
                card.setScaleY(0.96f);
                card.setTranslationY(placedY + (below ? -gap : gap));
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(placedY).setDuration(IN_MS)
                        .setInterpolator(ease).start();
                if (initial != null) initial.requestFocus();
            }
        });
        card.requestLayout();
    }

    /**
     * Moves the highlight behind {@code row}: at once onto the first option to take focus, sliding
     * after that. A press mid-slide sets off from where the highlight is, so holding Down never waits on it.
     */
    private void highlight(View row) {
        if (slide != null) slide.cancel();
        float toTop = row.getTop();
        int toHeight = row.getHeight();
        if (highlight.getVisibility() != VISIBLE || !animate) {
            placeHighlight(toTop, toHeight);
            highlight.setVisibility(VISIBLE);
            return;
        }
        float fromTop = highlight.getTranslationY();
        int fromHeight = highlight.getLayoutParams().height;
        slide = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(SLIDE_MS);
        slide.setInterpolator(ease);
        slide.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            placeHighlight(fromTop + (toTop - fromTop) * f, Math.round(fromHeight + (toHeight - fromHeight) * f));
        });
        slide.start();
    }

    private void placeHighlight(float top, int height) {
        highlight.setTranslationY(top);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            highlight.setLayoutParams(lp);
        }
    }

    /**
     * Just above the anchor (or below it, where there is more room), starting where it starts, kept on screen;
     * no taller than {@code menu_max_height}.
     */
    private void place() {
        int[] at = anchorPosition();
        int anchorLeft = at[0];
        int anchorTop = at[1];
        int above = anchorTop - gap - margin;
        int under = getHeight() - (anchorTop + anchor.getHeight()) - gap - margin;
        below = under > above;
        int tallest = Math.min(maxHeight, below ? under : above);
        int height = card.getHeight();
        if (height > tallest) {
            ViewGroup.LayoutParams lp = scroll.getLayoutParams();
            lp.height = tallest - (height - scroll.getHeight());
            scroll.setLayoutParams(lp);
            height = tallest;
        }
        float x = anchorLeft - card.getPaddingLeft();
        x = Math.max(margin, Math.min(x, getWidth() - margin - card.getWidth()));
        card.setX(x);
        float y = below ? anchorTop + anchor.getHeight() + gap : anchorTop - gap - height;
        placedY = y - card.getTop();
        frost(x, y, height);
    }

    /** The snapshot's piece under the card (at x, y, height tall), or a dark fill when there is none. */
    private void frost(float x, float y, int height) {
        if (snapshot == null || getWidth() == 0) {
            frost.setImageDrawable(null);
            frost.setBackgroundColor(getContext().getColor(R.color.menu_solid));
            return;
        }
        frost.setBackground(null);
        float f = (float) snapshot.getWidth() / getWidth();
        int left = Math.max(0, Math.min(snapshot.getWidth() - 1, Math.round(x * f)));
        int top = Math.max(0, Math.min(snapshot.getHeight() - 1, Math.round(y * f)));
        int w = Math.max(1, Math.min(snapshot.getWidth() - left, Math.round(card.getWidth() * f)));
        int h = Math.max(1, Math.min(snapshot.getHeight() - top, Math.round(height * f)));
        frost.setImageBitmap(android.graphics.Bitmap.createBitmap(snapshot, left, top, w, h));
    }

    /** The anchor's top-left corner, in this view's coordinates. */
    private int[] anchorPosition() {
        int[] here = new int[2];
        int[] at = new int[2];
        getLocationInWindow(here);
        anchor.getLocationInWindow(at);
        return new int[] {at[0] - here[0], at[1] - here[1]};
    }

    private float anchorCentre() {
        return anchorPosition()[0] + anchor.getWidth() / 2f;
    }

    public void hide() {
        if (!open) return;
        open = false;
        if (onOpenChanged != null) onOpenChanged.accept(false);
        if (behind != null) behind.setDescendantFocusability(behindFocusability);
        behind = null;
        if (anchor != null && anchor.isAttachedToWindow()) anchor.requestFocus();
        card.animate().alpha(0f).translationY(placedY + (below ? -gap : gap) / 2f).setDuration(OUT_MS).setInterpolator(ease)
                .withEndAction(() -> {
                    if (open) return;
                    setVisibility(GONE);
                    rows.removeAllViews();
                    ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    scroll.setLayoutParams(lp);
                }).start();
    }
}
