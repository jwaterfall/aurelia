package app.aurelia.tv.ui;

import android.view.View;
import android.view.ViewGroup;

/**
 * Where a page's content scrolls away under the top of the screen (Home's rows under the tabs, a library's
 * posters under its sort and filter buttons, search's results under the field). Two views make it: a band of
 * dark glass behind the top bar, reaching down to the line the content is cut at, with a hairline along that line
 * (drawable/scroll_edge_band.xml); and over the content just below, a long shadow that starts in the band's
 * colour and fades out (drawable/scroll_edge_shadow.xml), so the cut never shows. Both fade in once the page is
 * scrolled, and out again at its top.
 */
public final class ScrollEdge {

    private static final long FADE_MS = 200;

    private final View band;
    private final View shadow;
    private boolean raised;
    private int edge = -1;

    /** {@code band} drawn behind the top bar; {@code shadow} drawn over the page. Both at the top of the screen. */
    public ScrollEdge(View band, View shadow) {
        this.band = band;
        this.shadow = shadow;
        band.setAlpha(0f);
        shadow.setAlpha(0f);
    }

    /** The line, in pixels from the top of the screen, where the page's content is cut off. */
    public void setEdge(int y) {
        if (y == edge || y <= 0) return;
        edge = y;
        ViewGroup.LayoutParams lp = band.getLayoutParams();
        lp.height = y;
        band.setLayoutParams(lp);
        shadow.setTranslationY(y);
    }

    /**
     * The line {@code page} cuts its content off at: its top, or with {@code padded} the foot of its top padding
     * (a scroller clipping to its padding, as Home's does).
     */
    public void setEdge(View page, View root, boolean padded) {
        int[] at = new int[2];
        int[] origin = new int[2];
        page.getLocationInWindow(at);
        root.getLocationInWindow(origin);
        setEdge(at[1] - origin[1] + (padded ? page.getPaddingTop() : 0));
    }

    /** Content scrolled under the edge: the band and shadow show. */
    public void setRaised(boolean up, boolean animate) {
        if (up == raised && animate) return;
        raised = up;
        float to = up ? 1f : 0f;
        for (View v : new View[] {band, shadow}) {
            v.animate().cancel();
            if (animate) v.animate().alpha(to).setDuration(FADE_MS).start();
            else v.setAlpha(to);
        }
    }
}
