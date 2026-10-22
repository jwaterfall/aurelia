package app.aurelia.tv.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A page's grid (a library's posters, every app): a RecyclerView whose fading edges sit at its own top and bottom,
 * where the screen cuts it, rather than inside its padding, so what scrolls on past an edge fades out right up to
 * it, the same at the top as at the foot.
 */
public class PageGrid extends RecyclerView {

    public PageGrid(Context context) {
        super(context);
    }

    public PageGrid(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

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
}
