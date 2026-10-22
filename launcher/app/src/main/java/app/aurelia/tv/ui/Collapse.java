package app.aurelia.tv.ui;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import app.aurelia.tv.R;

/**
 * A bar at the top of a column (Home's top bar, a library's sort and filter buttons) sliding up out of the way, the
 * column below rising to take its room, and back down again. It moves its own top margin, so whatever is under it
 * follows without moving itself.
 */
public final class Collapse {

    private static final long MS = 260;
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private Collapse() { }

    /**
     * {@code bar} in place ({@code shown}) or tucked away above its own top. With {@code withParentPadding} its
     * parent's top padding goes too, so the column below reaches the top of the screen. (A negative margin larger
     * than the bar would not do it: LinearLayout gives no room back for one.)
     */
    public static void show(View bar, boolean shown, boolean animate, boolean withParentPadding) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
        View parent = (View) bar.getParent();
        if (bar.getTag(R.id.collapseMargin) == null) {
            bar.setTag(R.id.collapseMargin, new int[] {lp.topMargin, parent.getPaddingTop()});
        }
        int[] rest = (int[]) bar.getTag(R.id.collapseMargin);
        int padding = withParentPadding ? rest[1] : 0;
        float to = shown ? 0f : 1f;
        Object running = bar.getTag(R.id.collapseAnimator);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        float from = 1f - bar.getAlpha();
        if (from == to) return;
        if (!animate || bar.getHeight() == 0) {
            set(bar, rest[0], padding, to);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(from, to).setDuration(MS);
        a.setInterpolator(EASE);
        a.addUpdateListener(v -> set(bar, rest[0], padding, (float) v.getAnimatedValue()));
        bar.setTag(R.id.collapseAnimator, a);
        a.start();
    }

    /** {@code away}: 0 in place, 1 tucked away, between as it slides. */
    private static void set(View bar, int margin, int padding, float away) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
        lp.topMargin = Math.round(margin - (margin + bar.getHeight()) * away);
        bar.setLayoutParams(lp);
        if (padding > 0) {
            View parent = (View) bar.getParent();
            parent.setPadding(parent.getPaddingLeft(), Math.round(padding * (1f - away)), parent.getPaddingRight(),
                    parent.getPaddingBottom());
        }
        // Still there to take focus when sent to (Back to the tabs), which brings it back.
        bar.setAlpha(1f - away);
    }
}
