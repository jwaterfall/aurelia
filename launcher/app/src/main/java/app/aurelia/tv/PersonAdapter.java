package app.aurelia.tv;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.ui.GridSpacing;
import app.aurelia.tv.ui.Images;

import java.util.ArrayList;
import java.util.List;

/** A film's or show's cast and crew in a row (item_person.xml), on the library's poster columns. */
final class PersonAdapter extends RecyclerView.Adapter<PersonAdapter.Holder> {

    interface Listener {
        void onClicked(Catalog.Person person);
    }

    private static final int PHOTO_PX = 330;

    private final Account account;
    private final int columns;
    private final int gutter;
    private final Listener listener;
    private final List<Catalog.Person> people = new ArrayList<>();

    PersonAdapter(Account account, int columns, int gutter, Listener listener) {
        this.account = account;
        this.columns = columns;
        this.gutter = gutter;
        this.listener = listener;
    }

    void set(List<Catalog.Person> fresh) {
        people.clear();
        people.addAll(fresh);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return people.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_person, parent, false);
        v.getLayoutParams().width = GridSpacing.itemWidth(parent, columns, gutter);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Catalog.Person p = people.get(position);
        h.name.setText(p.name);
        h.role.setText(part(h.itemView, p));
        h.initials.setText(initials(p.name));
        h.initials.setVisibility(View.INVISIBLE);
        h.loading.setVisibility(View.VISIBLE);
        h.itemView.setContentDescription(p.name);
        Images.load(h.photo, Catalog.photo(account, p, PHOTO_PX), Library.auth(account), new Images.Loaded() {
            @Override
            public void onLoaded(Bitmap bitmap) {
                h.loading.setVisibility(View.GONE);
            }

            @Override
            public void onFailed() {
                h.loading.setVisibility(View.GONE);
                h.initials.setVisibility(View.VISIBLE);
            }
        });
        h.itemView.setOnClickListener(v -> listener.onClicked(p));
    }

    /** The character an actor plays; for the crew, their job. */
    private static String part(View v, Catalog.Person p) {
        if (!p.role.isEmpty()) return p.role;
        switch (p.type) {
            case "Director": return v.getContext().getString(R.string.person_director);
            case "Writer": return v.getContext().getString(R.string.person_writer);
            case "Producer": return v.getContext().getString(R.string.person_producer);
            default: return "";
        }
    }

    static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (!word.isEmpty() && out.length() < 2) out.appendCodePoint(word.codePointAt(0));
        }
        return out.toString().toUpperCase();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView photo;
        final View loading;
        final TextView initials;
        final TextView name;
        final TextView role;

        Holder(View v) {
            super(v);
            photo = v.findViewById(R.id.personPhoto);
            loading = v.findViewById(R.id.personLoading);
            initials = v.findViewById(R.id.personInitials);
            name = v.findViewById(R.id.personName);
            role = v.findViewById(R.id.personRole);
        }
    }
}
