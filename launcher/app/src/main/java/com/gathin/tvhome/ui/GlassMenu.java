package com.gathin.tvhome.ui;

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

import com.gathin.tvhome.R;

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

        private Item(CharSequence label, CharSequence detail, boolean chosen, Runnable action) {
            this.label = label;
            this.detail = detail;
            this.chosen = chosen;
            this.action = action;
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

    public static final class Builder {
        private final List<Item> items = new ArrayList<>();

        public Builder add(Item item) {
            items.add(item);
            return this;
        }
    }

    private static final long IN_MS = 200;
    private static final long OUT_MS = 140;

    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final LinearLayout card;
    private final LinearLayout rows;
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
        scroll = findViewById(R.id.menuScroll);
        rows = findViewById(R.id.menuRows);
        setClipChildren(false);
        setVisibility(GONE);
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
        View focus = null;
        View first = null;
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (Item item : menu.items) {
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
            row.findViewById(R.id.rowCheck).setVisibility(item.chosen ? VISIBLE : INVISIBLE);
            row.setOnClickListener(v -> {
                hide();
                item.action.run();
            });
            rows.addView(row);
            if (first == null) first = row;
            if (item.chosen && focus == null) focus = row;
        }
        View initial = focus != null ? focus : first;

        anchor = from;
        if (!open) {
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
