package com.gathin.tvhome.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.gathin.tvhome.R;

/**
 * A frosted glass panel floating at the right of the screen, for anything that is not a page of its own: sign-in,
 * account, quick settings. Opening it blurs and dims the screen behind (a snapshot, taken once),
 * so the panel is translucent glass over a frosted screen, the same material as everything on Home.
 * Put one over the whole screen (match_parent, last child of the root) and
 * give it a content view; {@link #setContent} again swaps the content with a short cross-fade, for steps.
 *
 * While it shows, nothing behind it can take focus, and closing it returns focus to where it was.
 */
public class GlassSheet extends FrameLayout {

    private static final long IN_MS = 320;
    private static final long OUT_MS = 200;
    private static final long STEP_MS = 180;

    /** The frosted screen behind the panel: a blurred, dimmed snapshot of it. */
    private final ImageView scrim;
    private final FrameLayout panel;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final int width;
    /** How far it travels in from: its own width plus its margin, so it starts just off the screen. */
    private final int travel;
    private ViewGroup behind;
    private int behindFocusability;
    private View returnFocus;
    private boolean animate = true;
    /** Open, or opening; false as soon as it starts closing. */
    private boolean open;

    public GlassSheet(Context context) {
        this(context, null);
    }

    public GlassSheet(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        width = getResources().getDimensionPixelSize(R.dimen.panel_width);
        scrim = new ImageView(context);
        scrim.setScaleType(ImageView.ScaleType.FIT_XY);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.5f, 0.5f, 0.5f, 1f);
        scrim.setColorFilter(new ColorMatrixColorFilter(dim));
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new FrameLayout(context);
        panel.setBackgroundResource(R.drawable.glass_panel_surface);
        int pad = getResources().getDimensionPixelSize(R.dimen.panel_padding);
        panel.setPadding(pad, pad, pad, pad);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        // No elevation: a shadow under translucent glass shows through it as bands. The glass rim marks the edge.
        // Inset the same on every side, so it reads as a pane hovering over the frosted screen.
        int margin = getResources().getDimensionPixelSize(R.dimen.panel_margin);
        LayoutParams lp = new LayoutParams(width, LayoutParams.MATCH_PARENT, Gravity.END);
        lp.setMargins(margin, margin, margin, margin);
        addView(panel, lp);
        travel = width + margin;
        setVisibility(GONE);
    }

    public void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
    }

    public boolean isShowing() {
        return open;
    }

    /**
     * Opens the panel with {@code content}. {@code background} is the screen behind it, which cannot take focus
     * until the panel closes.
     */
    public void show(View content, ViewGroup background) {
        if (!open) {
            open = true;
            scrim.animate().cancel();
            panel.animate().cancel();
            behind = background;
            returnFocus = background.findFocus();
            behindFocusability = background.getDescendantFocusability();
            background.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            boolean wasClosing = getVisibility() == VISIBLE;
            setVisibility(VISIBLE);
            if (!wasClosing) {
                frost();
                scrim.setAlpha(0f);
                panel.setAlpha(1f);
                panel.setTranslationX(travel);
            }
            // The frost arrives ahead of the panel, so the glass never passes over a sharp screen.
            scrim.animate().alpha(1f).setDuration(time(IN_MS / 2)).setInterpolator(ease).start();
            panel.animate().translationX(0f).setDuration(time(IN_MS)).setInterpolator(ease).start();
            setContent(content);
            // The first step follows the panel in, a beat behind it.
            content.setAlpha(0f);
            content.setTranslationY(getResources().getDimension(R.dimen.space_xs));
            content.animate().alpha(1f).translationY(0f).setStartDelay(time(IN_MS / 3))
                    .setDuration(time(IN_MS)).setInterpolator(ease).start();
            return;
        }
        setContent(content);
    }

    /** Blurs what is on screen now into the layer behind the panel; a plain dim if it cannot be drawn. */
    private void frost() {
        View screen = (View) getParent();
        Bitmap blurred = screen == null ? null : Blur.snapshot(screen, 8, 4);
        if (blurred == null) {
            scrim.setImageDrawable(null);
            scrim.setBackgroundColor(getContext().getColor(R.color.panel_scrim));
            return;
        }
        BitmapDrawable d = new BitmapDrawable(getResources(), blurred);
        d.setFilterBitmap(true);
        scrim.setImageDrawable(d);
        scrim.setBackground(null);
    }

    /** Replaces what the panel shows: the old content fades out as the new one rises into place. */
    public void setContent(View content) {
        View old = panel.getChildCount() > 0 ? panel.getChildAt(0) : null;
        if (old == content) return;
        if (old != null) {
            old.animate().cancel();
            panel.removeView(old);
        }
        // Fade the content without an offscreen layer: one would be cut to the content's bounds and clip the
        // focused row, which lifts a little past them, until the fade ended.
        content.forceHasOverlappingRendering(false);
        panel.addView(content, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (old != null) {
            content.setAlpha(0f);
            content.setTranslationY(getResources().getDimension(R.dimen.space_xs));
            content.animate().alpha(1f).translationY(0f).setDuration(time(STEP_MS)).setInterpolator(ease).start();
        }
        content.post(() -> {
            if (!content.hasFocus()) content.requestFocus(FOCUS_DOWN);
        });
    }

    /** Closes the panel; focus goes back to where it was before it opened. */
    public void hide() {
        if (!open) return;
        open = false;
        if (behind != null) behind.setDescendantFocusability(behindFocusability);
        View focus = returnFocus;
        returnFocus = null;
        behind = null;
        if (focus != null && focus.isAttachedToWindow()) focus.requestFocus();
        scrim.animate().alpha(0f).setDuration(time(OUT_MS)).setInterpolator(ease).start();
        panel.animate().translationX(travel).setDuration(time(OUT_MS)).setInterpolator(ease)
                .withEndAction(() -> {
                    if (open) return;   // reopened while closing
                    setVisibility(GONE);
                    panel.removeAllViews();
                }).start();
    }

    private long time(long ms) {
        return animate ? ms : 0;
    }
}
