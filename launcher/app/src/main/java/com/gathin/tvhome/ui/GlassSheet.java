package com.gathin.tvhome.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.gathin.tvhome.R;

/**
 * A frosted panel attached to the right edge of the screen, full height, that slides in over a dimmed screen, for anything that is not a page of its
 * own: sign-in, account, quick settings. Put one over the whole screen (match_parent, last child of the root) and
 * give it a content view; {@link #setContent} again swaps the content with a short cross-fade, for steps.
 *
 * While it shows, nothing behind it can take focus, and closing it returns focus to where it was.
 */
public class GlassSheet extends FrameLayout {

    private static final long IN_MS = 320;
    private static final long OUT_MS = 200;
    private static final long STEP_MS = 180;

    private final View scrim;
    private final FrameLayout panel;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final int width;
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
        scrim = new View(context);
        scrim.setBackgroundColor(context.getColor(R.color.panel_scrim));
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new FrameLayout(context);
        panel.setBackgroundResource(R.drawable.glass_panel_surface);
        int pad = getResources().getDimensionPixelSize(R.dimen.panel_padding);
        // The title sits level with the top bar's controls.
        int top = getResources().getDimensionPixelSize(R.dimen.screen_margin_top);
        panel.setPadding(pad, top + getResources().getDimensionPixelSize(R.dimen.space_xs), pad, pad);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        // A shadow along the edge facing the screen separates it from what it covers.
        panel.setElevation(getResources().getDimension(R.dimen.lift_panel));
        addView(panel, new LayoutParams(width, LayoutParams.MATCH_PARENT, Gravity.END));
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
                scrim.setAlpha(0f);
                panel.setAlpha(1f);
                panel.setTranslationX(width);
            }
            scrim.animate().alpha(1f).setDuration(time(IN_MS)).setInterpolator(ease).start();
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

    /** Replaces what the panel shows: the old content fades out as the new one rises into place. */
    public void setContent(View content) {
        View old = panel.getChildCount() > 0 ? panel.getChildAt(0) : null;
        if (old == content) return;
        if (old != null) {
            old.animate().cancel();
            panel.removeView(old);
        }
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
        panel.animate().translationX(width).setDuration(time(OUT_MS)).setInterpolator(ease)
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
