package app.aurelia.tv.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Room above a page's grid for what sits over its top (Home's top bar, a library's sort and filter buttons), as
 * part of what scrolls: the first row starts below them, and they move up with it by {@link #scrolled}, so they
 * scroll away exactly as the posters do. Add it as an item decoration.
 */
public class PageTop extends RecyclerView.ItemDecoration {

    private final int columns;
    private int room;

    public PageTop(int columns, int room) {
        this.columns = columns;
        this.room = room;
    }

    /** Makes the room {@code room} tall, laying {@code grid} out again if that changes it. */
    public void setRoom(RecyclerView grid, int room) {
        if (room == this.room) return;
        this.room = room;
        grid.invalidateItemDecorations();
    }

    @Override
    public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos != RecyclerView.NO_POSITION && pos < columns) out.top = room;
    }

    /**
     * How far {@code grid} has scrolled from its top: exact while its first row is laid out, which is whenever
     * what sits over it could still show; past that, a screen's height, by when it has long gone.
     */
    public static int scrolled(RecyclerView grid) {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (lm == null || lm.getItemCount() == 0) return 0;
        View first = lm.findViewByPosition(0);
        return first == null ? grid.getHeight() : grid.getPaddingTop() - lm.getDecoratedTop(first);
    }
}
