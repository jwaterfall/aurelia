package app.aurelia.tv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.aurelia.tv.BuildConfig;
import app.aurelia.tv.R;

/**
 * One step of a {@link GlassSheet} (layout/panel_step.xml): a title, some explanation, and choices, plus any of
 * a large code, a text field, a status line and an avatar. Parts that are not set stay hidden.
 */
public final class PanelStep {

    private final View root;
    private final ImageView avatar;
    private final TextView title, body, code, status;
    private final View statusRow, spinner;
    private final EditText field;
    private final LinearLayout rows;

    public PanelStep(Context context, ViewGroup parent) {
        root = LayoutInflater.from(context).inflate(R.layout.panel_step, parent, false);
        avatar = root.findViewById(R.id.stepAvatar);
        avatar.setClipToOutline(true);
        title = root.findViewById(R.id.stepTitle);
        body = root.findViewById(R.id.stepBody);
        code = root.findViewById(R.id.stepCode);
        status = root.findViewById(R.id.stepStatus);
        statusRow = root.findViewById(R.id.stepStatusRow);
        spinner = root.findViewById(R.id.stepSpinner);
        field = root.findViewById(R.id.stepField);
        rows = root.findViewById(R.id.stepRows);
    }

    public View view() {
        return root;
    }

    public PanelStep title(CharSequence text) {
        title.setText(text);
        return this;
    }

    /** The launcher's icon, name and version at the foot of the panel. */
    public PanelStep about() {
        View about = root.findViewById(R.id.stepAbout);
        about.findViewById(R.id.stepAboutIcon).setClipToOutline(true);
        TextView version = about.findViewById(R.id.stepAboutVersion);
        version.setText(root.getContext().getString(R.string.about_version, BuildConfig.VERSION_NAME));
        about.setVisibility(View.VISIBLE);
        return this;
    }

    public PanelStep body(CharSequence text) {
        body.setText(text);
        return this;
    }

    /** A round picture above the title; null shows a person icon until the picture arrives. */
    public PanelStep avatar(@Nullable Bitmap picture) {
        if (picture != null) {
            avatar.setPadding(0, 0, 0, 0);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setImageBitmap(picture);
        } else {
            int pad = root.getResources().getDimensionPixelSize(R.dimen.space_md);
            avatar.setPadding(pad, pad, pad, pad);
            avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
            avatar.setImageResource(R.drawable.ic_person);
        }
        avatar.setVisibility(View.VISIBLE);
        return this;
    }

    /** A large code to read out, such as a Quick Connect code. */
    public PanelStep code(CharSequence text) {
        code.setText(text);
        code.setVisibility(View.VISIBLE);
        return this;
    }

    /** One line under everything else, for progress and errors; null hides it. */
    public PanelStep status(@Nullable CharSequence text) {
        return status(text, false);
    }

    /**
     * The status line with a spinner beside it while {@code busy}. Changing what it says, or the spinner, never
     * moves anything else; only removing the line (null) does.
     */
    public PanelStep status(@Nullable CharSequence text, boolean busy) {
        status.setText(text);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusRow.setVisibility(text == null ? View.GONE : View.VISIBLE);
        return this;
    }

    public EditText field(CharSequence hint, CharSequence text) {
        field.setHint(hint);
        field.setText(text);
        field.setSelection(field.getText().length());
        field.setVisibility(View.VISIBLE);
        return field;
    }

    /** Adds a choice under the others; a null action leaves the caller to set one. */
    public View row(CharSequence label, @Nullable CharSequence detail, @Nullable Runnable action) {
        return row(label, detail, action, null);
    }

    /** Adds a choice just above {@code before}, or under the others when it is null. */
    public View row(CharSequence label, @Nullable CharSequence detail, @Nullable Runnable action,
                    @Nullable View before) {
        View row = LayoutInflater.from(root.getContext()).inflate(R.layout.item_panel_row, rows, false);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(label);
        TextView sub = row.findViewById(R.id.rowSubtitle);
        sub.setText(detail);
        sub.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        if (action != null) row.setOnClickListener(v -> action.run());
        int at = before == null ? -1 : rows.indexOfChild(before);
        rows.addView(row, at);
        return row;
    }
}
