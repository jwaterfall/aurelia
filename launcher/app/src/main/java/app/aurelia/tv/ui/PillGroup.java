package app.aurelia.tv.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.aurelia.tv.R;

/**
 * A few icon buttons as one glass pill (the player's side groups): a hairline between each two, and
 * behind the one with focus a white circle that slides from button to button. The hairlines beside the focused
 * one hide, so nothing cuts into the white. Its buttons have no background of their own; hidden ones leave no
 * gap, and one left alone makes a round button.
 */
public class PillGroup extends LinearLayout {

    private static final long SLIDE_MS = 150;

    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairline = new Paint();
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final RectF spot = new RectF();
    private final float line;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (old, now) -> focusMoved(now);
    private View focused;
    /** The highlight's left and right edges; it is as tall as the buttons. */
    private float left, right;
    private ValueAnimator slide;
    private boolean animate = true;

    public PillGroup(Context context) {
        this(context, null);
    }

    public PillGroup(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        white.setColor(context.getColor(R.color.focus));
        hairline.setColor(context.getColor(R.color.pill_hairline));
        line = getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
    }

    /** false: the highlight jumps to each button instead of sliding. */
    public void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
        super.onDetachedFromWindow();
    }

    private void focusMoved(@Nullable View now) {
        View mine = now != null && now.getParent() == this ? now : null;
        if (mine == focused) return;
        if (slide != null) slide.cancel();
        // Coming from outside the pill (or with no animations) it is there at once; along the pill it slides.
        if (mine != null && focused != null && animate) {
            float fromLeft = left, fromRight = right;
            float toLeft = mine.getLeft(), toRight = mine.getRight();
            slide = ValueAnimator.ofFloat(0f, 1f).setDuration(SLIDE_MS);
            slide.setInterpolator(ease);
            slide.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                left = fromLeft + (toLeft - fromLeft) * f;
                right = fromRight + (toRight - fromRight) * f;
                invalidate();
            });
            slide.start();
        } else if (mine != null) {
            left = mine.getLeft();
            right = mine.getRight();
        }
        focused = mine;
        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        View previous = null;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            if (previous != null && previous != focused && child != focused) {
                float x = child.getLeft() - line / 2f;
                float h = child.getHeight() * 0.22f;
                canvas.drawRect(x, child.getTop() + h, x + line, child.getBottom() - h, hairline);
            }
            previous = child;
        }
        if (focused != null) {
            spot.set(left, focused.getTop(), right, focused.getBottom());
            float r = spot.height() / 2f;
            canvas.drawRoundRect(spot, r, r, white);
        }
        super.dispatchDraw(canvas);
    }
}
