package app.aurelia.tv;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import app.aurelia.tv.ui.GridSpacing;
import app.aurelia.tv.ui.ShelfLayoutManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Rows of cards stacked in a column (view_shelf_section.xml each: a title, a count, a row): search's results, and
 * the cast and the like under a film or show. A section with nothing in it stays gone.
 *
 * Focus moves a card at a time along a row (so a held key never outruns the scrolling), and Up and Down move
 * between the sections showing, landing on the card last focused in each. Up from the first section is the
 * page's to handle.
 */
final class ShelfStack {

    /** One section: its row, and where focus was in it. */
    static final class Section {
        final View view;
        final RecyclerView row;
        private final TextView title;
        private final TextView caption;
        int focus;
        private boolean counted;

        Section(View view) {
            this.view = view;
            row = view.findViewById(R.id.sectionRow);
            View header = view.findViewById(R.id.sectionHeader);
            title = header.findViewById(R.id.sectionTitle);
            caption = header.findViewById(R.id.sectionCaption);
        }

        void setTitle(CharSequence text) {
            title.setText(text);
        }

        /** Says how many there are at the header's far end (search's results). */
        Section counted() {
            counted = true;
            return this;
        }

        /** Shows the section with {@code count} cards (gone with none), back at its start. */
        void show(int count) {
            focus = 0;
            row.scrollToPosition(0);
            caption.setText(counted && count > 0 ? String.valueOf(count) : null);
            view.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        }

        boolean shown() {
            return view.getVisibility() == View.VISIBLE;
        }

        /** Something there to focus: a section of placeholders has nothing. */
        boolean focusable() {
            RecyclerView.Adapter<?> a = row.getAdapter();
            if (!shown() || a == null) return false;
            return a instanceof PosterAdapter ? ((PosterAdapter) a).size() > 0 : a.getItemCount() > 0;
        }
    }

    private final Context context;
    private final ViewGroup column;
    private final int gutter;
    private final List<Section> sections = new ArrayList<>();

    ShelfStack(Context context, ViewGroup column) {
        this.context = context;
        this.column = column;
        gutter = context.getResources().getDimensionPixelSize(R.dimen.gutter);
    }

    int gutter() {
        return gutter;
    }

    /** A new section at the foot of the column, gone until {@link Section#show}. */
    Section add(CharSequence title, RecyclerView.Adapter<?> adapter) {
        View v = LayoutInflater.from(context).inflate(R.layout.view_shelf_section, column, false);
        column.addView(v);
        Section s = new Section(v);
        s.setTitle(title);
        s.row.setLayoutManager(new ShelfLayoutManager(context));
        s.row.addItemDecoration(new GridSpacing(1, gutter));
        s.row.setItemAnimator(null);
        s.row.setAdapter(adapter);
        sections.add(s);
        return s;
    }

    Section add(int title, RecyclerView.Adapter<?> adapter) {
        return add(context.getString(title), adapter);
    }

    boolean contains(View focus) {
        return isIn(focus, column);
    }

    /** The first section with something to focus, or null. */
    Section first() {
        for (Section s : sections) if (s.focusable()) return s;
        return null;
    }

    /** Into the first section; false when there is none. */
    boolean focusFirst() {
        Section s = first();
        if (s == null) return false;
        focusCard(s, s.focus);
        return true;
    }

    /**
     * Moves focus for a key pressed inside the stack. Up from the first section calls {@code exitUp}; false when
     * the key is not the stack's to handle.
     */
    boolean route(int key, View focus, Runnable exitUp) {
        Section in = null;
        for (Section s : sections) if (isIn(focus, s.row)) in = s;
        if (in == null) return false;
        in.focus = positionIn(in.row, focus);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                Section above = neighbour(in, -1);
                if (above != null) focusCard(above, above.focus);
                else exitUp.run();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                Section below = neighbour(in, 1);
                if (below != null) focusCard(below, below.focus);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int to = in.focus + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (to >= 0 && to < in.row.getAdapter().getItemCount()) focusCard(in, to);
                return true;   // the ends of a row stop focus rather than letting it jump rows
            }
            default:
                return false;
        }
    }

    private Section neighbour(Section from, int step) {
        for (int i = sections.indexOf(from) + step; i >= 0 && i < sections.size(); i += step) {
            if (sections.get(i).focusable()) return sections.get(i);
        }
        return null;
    }

    private static void focusCard(Section s, int position) {
        int count = s.row.getAdapter().getItemCount();
        if (count == 0) return;
        int pos = Math.max(0, Math.min(position, count - 1));
        RecyclerView.ViewHolder h = s.row.findViewHolderForAdapterPosition(pos);
        if (h != null) {
            h.itemView.requestFocus();
            return;
        }
        s.row.scrollToPosition(pos);
        s.row.post(() -> {
            RecyclerView.ViewHolder later = s.row.findViewHolderForAdapterPosition(pos);
            if (later != null) later.itemView.requestFocus();
        });
    }

    private static int positionIn(RecyclerView row, View focused) {
        View item = focused == null ? null : row.findContainingItemView(focused);
        return item == null ? 0 : Math.max(0, row.getChildAdapterPosition(item));
    }

    static boolean isIn(View view, View ancestor) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == ancestor) return true;
        }
        return false;
    }
}
