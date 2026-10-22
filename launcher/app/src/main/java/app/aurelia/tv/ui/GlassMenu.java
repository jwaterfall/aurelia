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
 * up with it, and grows upwards; or below it, growing down, where it fits there instead (a button near the top
 * of the screen). Where it fits on neither side (a card nearly the height of the screen), it opens over the card,
 * just inside its top corner. Taller than menu_max_height, it scrolls, fading out where more options continue.
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
        private int width;
        private boolean focusFirst;

        public Builder add(Item item) {
            items.add(item);
            return this;
        }

        /** Focus goes to the first option even when shown again over another that had it. */
        public Builder focusFirst() {
            focusFirst = true;
            return this;
        }

        /** Wider than {@code menu_width}: a dimension, for a menu with sentences in it (signing in). */
        public Builder width(int dimen) {
            width = dimen;
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
    private ViewGroup defaultBackground;
    /** What Back does while it shows; closing it unless told otherwise (a menu of steps goes back a step). */
    private Runnable onBack;
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
        // Grown or shrunk while open (a line of status, a code arriving): placed again, its frost with it.
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (open && card.getAlpha() == 1f && b - t != ob - ot) v.post(this::settle);
        });
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

    /** Open, and opened from {@code button}. */
    public boolean isShowingFrom(View button) {
        return open && anchor == button;
    }

    /**
     * The screen's menu, for something that has none of its own to hand (a poster's hold menu): the one in the
     * layout (R.id.menu), or else one put over the whole screen now, with the screen's own layout behind it.
     */
    public static GlassMenu of(View any) {
        ViewGroup content = any.getRootView().findViewById(android.R.id.content);
        GlassMenu menu = content.findViewById(R.id.menu);
        if (menu != null) return menu;
        menu = new GlassMenu(any.getContext());
        menu.setId(R.id.menu);
        menu.defaultBackground = (ViewGroup) content.getChildAt(0);
        content.addView(menu, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return menu;
    }

    public void setOnBack(@Nullable Runnable action) {
        onBack = action;
    }

    /** Back closes it (or does {@link #setOnBack}'s), on any screen, before the screen itself hears of it. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (open && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                if (onBack != null) onBack.run();
                else hide();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** What cannot take focus while it shows, when the caller does not say (see {@link #of}). */
    public void setDefaultBackground(ViewGroup background) {
        defaultBackground = background;
    }

    public ViewGroup defaultBackground() {
        return defaultBackground;
    }

    public boolean isShowing() {
        return open;
    }

    /**
     * Opens over {@code from} with {@code menu}'s items. {@code background} is the screen behind, which cannot
     * take focus until the menu closes.
     */
    public void show(View from, Builder menu, ViewGroup background) {
        // Shown again while open from the same button: its contents change in place (a step of signing in, or
        // a server that has just answered), keeping focus on the same option where there still is one.
        boolean again = open && from == anchor;
        CharSequence wasFocused = null;
        View had = rows.getFocusedChild();
        if (again && had != null && had.findViewById(R.id.rowTitle) != null) {
            wasFocused = ((TextView) had.findViewById(R.id.rowTitle)).getText().toString();
        }
        rows.removeAllViews();
        if (slide != null) slide.cancel();
        highlight.setVisibility(INVISIBLE);
        ViewGroup.LayoutParams sl = scroll.getLayoutParams();
        sl.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.setLayoutParams(sl);
        ViewGroup.LayoutParams cl = card.getLayoutParams();
        cl.width = getResources().getDimensionPixelSize(menu.width != 0 ? menu.width : R.dimen.menu_width);
        card.setLayoutParams(cl);
        View focus = null;
        View first = null;
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (Item item : menu.items) {
            if (item.view != null) {
                if (item.view.getParent() instanceof ViewGroup) ((ViewGroup) item.view.getParent()).removeView(item.view);
                rows.addView(item.view);
                // One that takes focus (a text field) has its own look for it, so the highlight steps aside.
                for (View field : item.view.getFocusables(FOCUS_DOWN)) {
                    field.setOnFocusChangeListener((v, has) -> {
                        if (has) {
                            if (slide != null) slide.cancel();
                            highlight.setVisibility(INVISIBLE);
                        }
                    });
                    if (first == null) first = field;
                }
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
            // Two lines get room above and below, so the white's rounded ends sit clear of the words, as a single
            // line's pill does.
            if (item.detail != null) {
                int pad = getResources().getDimensionPixelSize(R.dimen.menu_row_two_line_pad);
                row.setPadding(row.getPaddingStart(), pad, row.getPaddingEnd(), pad);
            }
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
            if (!menu.focusFirst && wasFocused != null && wasFocused.equals(item.label.toString())) focus = row;
        }
        View initial = menu.focusFirst || focus == null ? first : focus;

        if (again) {
            card.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    card.removeOnLayoutChangeListener(this);
                    settle();
                    if (initial != null) initial.requestFocus();
                }
            });
            card.requestLayout();
            return;
        }

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

    /** Placed where it now belongs, at once, with no animation. */
    private void settle() {
        if (!open || anchor == null) return;
        place();
        card.setTranslationY(placedY);
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
        // How tall it would be with every option showing, and how tall it may be: menu_max_height, or the screen.
        int content = scroll.getChildAt(0).getHeight();
        int natural = card.getHeight() - scroll.getHeight() + content;
        int want = Math.min(Math.min(maxHeight, natural), getHeight() - 2 * margin);
        int above = anchorTop - gap - margin;
        int under = getHeight() - (anchorTop + anchor.getHeight()) - gap - margin;
        // Beside its button where it fits whole, below first; never squeezed into a gap too small for it (a big card's
        // menu, the hero's), but over the button instead, just inside its top corner, all of it showing.
        boolean over = under < want && above < want;
        below = over || under >= want;
        int scrollHeight = natural > want ? want - (natural - content) : ViewGroup.LayoutParams.WRAP_CONTENT;
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        if (lp.height != scrollHeight) {
            lp.height = scrollHeight;
            scroll.setLayoutParams(lp);
        }
        float x = over ? anchorLeft + gap : anchorLeft - card.getPaddingLeft();
        x = Math.max(margin, Math.min(x, getWidth() - margin - card.getWidth()));
        card.setX(x);
        float y = over ? Math.max(margin, Math.min(anchorTop + gap, getHeight() - margin - want))
                : below ? anchorTop + anchor.getHeight() + gap : anchorTop - gap - want;
        placedY = y - card.getTop();
        frost(x, y, want);
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
