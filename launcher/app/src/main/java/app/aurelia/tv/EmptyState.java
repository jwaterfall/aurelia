package app.aurelia.tv;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * What stands in for content that is not there (view_empty_state.xml): signed out, the server out of reach,
 * nothing yet, nothing matching. One pattern everywhere: an icon, a title, a line about it, and a button.
 */
final class EmptyState {

    private final ImageView icon;
    private final TextView title;
    private final TextView body;
    final TextView action;

    EmptyState(View root) {
        icon = root.findViewById(R.id.emptyIcon);
        title = root.findViewById(R.id.emptyTitle);
        body = root.findViewById(R.id.emptyBody);
        action = root.findViewById(R.id.emptyAction);
    }

    /** Any of {@code body} and {@code button} may be null, and that part is left out. */
    void show(int iconRes, CharSequence heading, CharSequence text, CharSequence button, Runnable onAction) {
        icon.setImageResource(iconRes);
        title.setText(heading);
        body.setText(text);
        body.setVisibility(text == null ? View.GONE : View.VISIBLE);
        action.setText(button);
        action.setVisibility(button == null ? View.GONE : View.VISIBLE);
        action.setOnClickListener(onAction == null ? null : v -> onAction.run());
    }

    boolean hasAction() {
        return action.getVisibility() == View.VISIBLE;
    }
}
