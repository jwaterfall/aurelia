package app.aurelia.tv.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A horizontal row that scrolls by whole columns: a card focused off the edge scrolls in until it sits exactly on
 * the first or last column, so the visible cards always line up with the margins and the rows above and below.
 *
 * The stock version counts the gutter after a card as part of it, which left every scrolled row a gutter off.
 */
public class ShelfLayoutManager extends LinearLayoutManager {

    private final PathInterpolator ease = new PathInterpolator(0.25f, 0f, 0f, 1f);
    private boolean animate = true;

    public ShelfLayoutManager(Context context) {
        super(context, HORIZONTAL, false);
    }

    public void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
    }

    /**
     * Keeps a screen's width of cards laid out beyond each edge, so the card a key moves to always exists, even
     * while the row is still scrolling from the key before.
     */
    @Override
    protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
        extra[0] = getWidth();
        extra[1] = getWidth();
    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                 @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
        int start = getPaddingLeft();
        int end = getWidth() - getPaddingRight();
        int dx = 0;
        if (child.getLeft() < start) dx = child.getLeft() - start;
        else if (child.getRight() > end) dx = child.getRight() - end;
        if (dx == 0) return false;
        if (immediate || !animate) parent.scrollBy(dx, 0);
        else parent.smoothScrollBy(dx, 0, ease);
        return true;
    }
}
