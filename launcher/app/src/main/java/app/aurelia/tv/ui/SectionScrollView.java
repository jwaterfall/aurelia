package app.aurelia.tv.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

/**
 * A page of sections stacked in one column (its only child, a vertical LinearLayout), scrolled a whole section at
 * a time: when focus moves into a section not wholly on screen, the section's top comes up to the top of the view
 * (search's results, a film's rows), or, {@link #setAlignTop nearest}, it scrolls only as far as shows the whole
 * section (Home's rows). A section already wholly on screen never moves. Either way a
 * section is never left half shown, as the platform's scrolling to the focused card alone would leave it.
 *
 * It glides there on the rows' own curve, not the platform's quick fling, and never past either end, so it always
 * comes to rest exactly at the top, at the foot, or on a section's line.
 *
 * Give it some top padding: it clips what scrolls away to its padding (a scroller with none is not clipped to
 * its own bounds when its parent lets children draw outside theirs, so cards can lift). Home's, which does not
 * clip to its padding, relies on its parent clipping it to its bounds instead.
 */
public class SectionScrollView extends ScrollView {

    private static final long GLIDE_MS = 450;

    private final PathInterpolator ease = new PathInterpolator(0.25f, 0f, 0f, 1f);
    private boolean alignTop = true;
    private boolean animate = true;
    private ValueAnimator glide;
    private int target = -1;

    public SectionScrollView(Context context) {
        this(context, null);
    }

    public SectionScrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setSmoothScrollingEnabled(true);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);
    }

    /** false: jump instead of gliding (config.xml, animations). */
    public void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
    }

    /**
     * Fade the content out over {@code length} wherever it runs on past an edge: at the foot, where the next row
     * peeks in, and at the top once rows have scrolled away above. The same at both.
     */
    public void setEdgeFade(int length) {
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(length);
    }

    // The fades at its own top and foot, where the screen cuts it, not inside its padding (as PageGrid).
    @Override
    protected boolean isPaddingOffsetRequired() {
        return !getClipToPadding();
    }

    @Override
    protected int getTopPaddingOffset() {
        return -getPaddingTop();
    }

    @Override
    protected int getBottomPaddingOffset() {
        return getPaddingBottom();
    }

    /** false: scroll only as far as needed to show the whole section with focus. */
    public void setAlignTop(boolean top) {
        alignTop = top;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        View section = sectionOf(focused);
        if (section == null) return;
        int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
        int from = target >= 0 ? target : getScrollY();
        // The first section takes along whatever leads the page above it (room for Home's top bar).
        int top = leadsPage(section) ? 0 : section.getTop();
        if (alignTop) {
            // Already wholly on screen (the rows under a film, side by side at the foot): stay put.
            if (top >= from && section.getBottom() <= from + viewport) return;
            glideTo(top);
            return;
        }
        int bottom = section.getBottom() + getChildAt(0).getPaddingBottom();
        if (top < from) glideTo(top);
        else if (bottom > from + viewport) glideTo(bottom - viewport);
    }

    /** Nothing above {@code section} can take focus: it is the page's first section. */
    private boolean leadsPage(View section) {
        ViewGroup column = (ViewGroup) getChildAt(0);
        for (int i = 0; i < column.indexOfChild(section); i++) {
            View v = column.getChildAt(i);
            if (v.getVisibility() == VISIBLE && v.hasFocusable()) return false;
        }
        return true;
    }

    /** Scrolls to {@code y}, held between the top and the foot, on the rows' curve. */
    public void glideTo(int y) {
        int max = Math.max(0, getChildAt(0).getHeight() - (getHeight() - getPaddingTop() - getPaddingBottom()));
        int to = Math.max(0, Math.min(y, max));
        if (to == target || (glide == null && to == getScrollY())) return;
        if (glide != null) glide.cancel();
        target = to;
        if (!animate) {
            scrollTo(0, to);
            target = -1;
            return;
        }
        glide = ValueAnimator.ofInt(getScrollY(), to);
        glide.setDuration(GLIDE_MS);
        glide.setInterpolator(ease);
        glide.addUpdateListener(a -> scrollTo(0, (int) a.getAnimatedValue()));
        glide.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (glide == a) {
                    glide = null;
                    target = -1;
                }
            }
        });
        glide.start();
    }

    /** The scrolling is done in {@link #requestChildFocus}, a section at a time. */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    private View sectionOf(View focused) {
        if (getChildCount() == 0) return null;
        ViewGroup column = (ViewGroup) getChildAt(0);
        for (View v = focused; v != null && v.getParent() instanceof View; v = (View) v.getParent()) {
            if (v.getParent() == column) return v;
        }
        return null;
    }
}
