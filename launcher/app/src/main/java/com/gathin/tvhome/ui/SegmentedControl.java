package com.gathin.tvhome.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gathin.tvhome.R;

import java.util.ArrayList;

/**
 * tvOS tab bar. Its children are the tabs (TextViews, style Widget.Launcher.Tab).
 *
 * Moving focus onto a tab selects it, so there is never a "hovered" tab next to a "selected" one: one indicator
 * slides to whichever tab is current. It is solid white while the bar has focus, and turns to glass when focus
 * moves down into the page, leaving the current tab marked. Each tab's text takes its colour from how much of
 * the indicator is under it, so the text darkens as the white pill slides beneath it.
 */
public class SegmentedControl extends LinearLayout {

    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    private static final long LEAD_MS = 260;
    private static final long TRAIL_MS = 380;
    private static final long FOCUS_MS = 200;

    private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final PathInterpolator ease = new PathInterpolator(0.25f, 0f, 0f, 1f);
    private final int restFill, focusFill, rimTop, rimBottom, textOnFocus, textSelected, textIdle;
    private final float hairline;

    private int selected;
    private float left, right, top, bottom;
    private float focus;
    private boolean laidOut;
    private boolean animate = true;
    private ValueAnimator leftAnim, rightAnim, focusAnim;
    private OnTabSelectedListener listener;

    public SegmentedControl(Context context) {
        this(context, null);
    }

    public SegmentedControl(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        restFill = context.getColor(R.color.glass_selected);
        focusFill = context.getColor(R.color.focus);
        rimTop = context.getColor(R.color.glass_rim_top);
        rimBottom = context.getColor(R.color.glass_rim_bottom);
        textOnFocus = context.getColor(R.color.on_focus);
        textSelected = context.getColor(R.color.text_primary);
        textIdle = context.getColor(R.color.text_tertiary);
        hairline = getResources().getDimension(R.dimen.hairline);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(hairline);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setOnTabSelectedListener(OnTabSelectedListener l) {
        listener = l;
    }

    /** false: the indicator jumps instead of sliding (config.xml, animations). */
    public void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
    }

    public int getSelectedIndex() {
        return selected;
    }

    /** Moves the indicator to {@code index}; tells the listener only if {@code notify}. */
    public void setSelectedIndex(int index, boolean notify) {
        if (index < 0 || index >= getChildCount()) return;
        boolean changed = index != selected;
        selected = index;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setSelected(i == index);
        slideTo(index);
        if (changed && notify && listener != null) listener.onTabSelected(index);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        wireTabs();
    }

    /** Replaces the tabs with ones labelled {@code labels} (layout/item_tab.xml), the first selected. */
    public void setTabs(java.util.List<? extends CharSequence> labels, int select) {
        removeAllViews();
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(getContext());
        for (CharSequence label : labels) {
            TextView tab = (TextView) inflater.inflate(R.layout.item_tab, this, false);
            tab.setText(label);
            addView(tab);
        }
        selected = Math.max(0, Math.min(select, labels.size() - 1));
        laidOut = false;
        wireTabs();
        requestLayout();
    }

    private void wireTabs() {
        for (int i = 0; i < getChildCount(); i++) {
            final int index = i;
            View tab = getChildAt(i);
            tab.setSelected(i == selected);
            tab.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) setSelectedIndex(index, true);
                post(this::updateFocus);
            });
            tab.setOnClickListener(v -> setSelectedIndex(index, true));
        }
    }

    /** Entering the bar from outside lands on the current tab, not on whichever tab is nearest. */
    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!hasFocus() && selected < getChildCount() && getChildAt(selected).isFocusable()) {
            views.add(getChildAt(selected));
            return;
        }
        super.addFocusables(views, direction, focusableMode);
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        View tab = getChildAt(selected);
        return tab != null && tab.requestFocus(direction, previouslyFocusedRect);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        View tab = getChildAt(selected);
        if (tab == null) return;
        top = tab.getTop();
        bottom = tab.getBottom();
        // First layout, or a resize: place the indicator without animating.
        if (!laidOut || changed) {
            cancel(leftAnim);
            cancel(rightAnim);
            left = tab.getLeft();
            right = tab.getRight();
            laidOut = true;
            updateTextColors();
        }
    }

    private void slideTo(int index) {
        View tab = getChildAt(index);
        if (tab == null || !laidOut) return;
        float toLeft = tab.getLeft(), toRight = tab.getRight();
        if (!animate) {
            left = toLeft;
            right = toRight;
            invalidate();
            updateTextColors();
            return;
        }
        // The edge in the direction of travel leads and the other follows, so the pill stretches a little as it
        // moves and settles back to the tab's width.
        boolean movingRight = toLeft > left;
        leftAnim = animateEdge(leftAnim, left, toLeft, movingRight ? TRAIL_MS : LEAD_MS, v -> left = v);
        rightAnim = animateEdge(rightAnim, right, toRight, movingRight ? LEAD_MS : TRAIL_MS, v -> right = v);
    }

    private interface Setter {
        void set(float v);
    }

    private ValueAnimator animateEdge(ValueAnimator old, float from, float to, long ms, Setter setter) {
        cancel(old);
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.setInterpolator(ease);
        a.addUpdateListener(an -> {
            setter.set((float) an.getAnimatedValue());
            updateTextColors();
            invalidate();
        });
        a.start();
        return a;
    }

    private static void cancel(ValueAnimator a) {
        if (a != null) a.cancel();
    }

    private void updateFocus() {
        float target = hasFocus() ? 1f : 0f;
        if (target == focus && focusAnim == null) return;
        cancel(focusAnim);
        if (!animate) {
            focus = target;
            updateTextColors();
            invalidate();
            return;
        }
        focusAnim = ValueAnimator.ofFloat(focus, target);
        focusAnim.setDuration(FOCUS_MS);
        focusAnim.setInterpolator(ease);
        focusAnim.addUpdateListener(an -> {
            focus = (float) an.getAnimatedValue();
            updateTextColors();
            invalidate();
        });
        focusAnim.start();
    }

    private void updateTextColors() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TextView)) continue;
            float w = child.getWidth();
            float overlap = w <= 0 ? (i == selected ? 1f : 0f)
                    : Math.max(0f, Math.min(right, child.getRight()) - Math.max(left, child.getLeft())) / w;
            int onGlass = Colors.lerp(textIdle, textSelected, overlap);
            ((TextView) child).setTextColor(Colors.lerp(onGlass, textOnFocus, overlap * smoothstep(focus)));
        }
    }

    /**
     * The text flips between light and dark over the middle of the indicator's fade instead of easing through
     * it, which would leave grey text on a grey pill halfway.
     */
    private static float smoothstep(float t) {
        float x = Math.max(0f, Math.min(1f, (t - 0.3f) / 0.4f));
        return x * x * (3f - 2f * x);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (laidOut && right > left) {
            pill.set(left, top, right, bottom);
            float r = pill.height() / 2f;
            indicator.setColor(Colors.lerp(restFill, focusFill, focus));
            canvas.drawRoundRect(pill, r, r, indicator);
            if (focus < 1f) {
                pill.inset(hairline / 2f, hairline / 2f);
                rim.setShader(new LinearGradient(0, pill.top, 0, pill.bottom, rimTop, rimBottom, Shader.TileMode.CLAMP));
                rim.setAlpha(Math.round(255 * (1f - focus)));
                canvas.drawRoundRect(pill, r, r, rim);
            }
        }
        super.dispatchDraw(canvas);
    }
}
