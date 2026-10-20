package com.gathin.tvhome.ui;

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

import com.gathin.tvhome.R;

/**
 * One step of a {@link GlassSheet} (layout/panel_step.xml): a title, some explanation, and choices, plus any of
 * a large code, a text field, a status line and an avatar. Parts that are not set stay hidden.
 */
public final class PanelStep {

    private final View root;
    private final ImageView avatar;
    private final TextView title, body, code, status;
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
        status.setText(text);
        status.setVisibility(text == null ? View.GONE : View.VISIBLE);
        return this;
    }

    public EditText field(CharSequence hint, CharSequence text) {
        field.setHint(hint);
        field.setText(text);
        field.setSelection(field.getText().length());
        field.setVisibility(View.VISIBLE);
        return field;
    }

    /** Adds a choice under the others. */
    public View row(CharSequence label, @Nullable CharSequence detail, Runnable action) {
        View row = LayoutInflater.from(root.getContext()).inflate(R.layout.item_panel_row, rows, false);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(label);
        TextView sub = row.findViewById(R.id.rowSubtitle);
        sub.setText(detail);
        sub.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        row.setOnClickListener(v -> action.run());
        rows.addView(row);
        return row;
    }

    /** Removes the choices; a text field, the first child of the controls, stays. */
    public void clearRows() {
        rows.removeViews(1, rows.getChildCount() - 1);
    }
}
