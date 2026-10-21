package app.aurelia.tv.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

import app.aurelia.tv.R;

/**
 * The hero's page dots. The current one stretches into a pill and the rest make room, evenly
 * spaced; moving on, one shrinks back as the next grows, so the row never changes width.
 */
public class HeroDots extends View {

    private static final long MOVE_MS = 380;

    private final PathInterpolator ease = new PathInterpolator(0.25f, 0f, 0f, 1f);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dot;
    private final float pill;
    private final float gap;
    private int count;
    /** Where the pill is, in dots: 2.5 is half way from the third to the fourth. */
    private float pos;
    private ValueAnimator move;
    private boolean animate = true;

    public HeroDots(Context context) {
        this(context, null);
    }

    public HeroDots(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        dot = getResources().getDimension(R.dimen.hero_dot);
        pill = getResources().getDimension(R.dimen.hero_dot_on);
        gap = dot;
        dim.setColor(0x73FFFFFF);
        lit.setColor(0xFFFFFFFF);
    }

    public void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
    }

    public void setCount(int count) {
        this.count = count;
        pos = Math.min(pos, Math.max(0, count - 1));
        requestLayout();
        invalidate();
    }

    public void setCurrent(int index) {
        if (move != null) move.cancel();
        if (!animate || !isLaidOut()) {
            pos = index;
            invalidate();
            return;
        }
        move = ValueAnimator.ofFloat(pos, index);
        move.setDuration(MOVE_MS);
        move.setInterpolator(ease);
        move.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            invalidate();
        });
        move.start();
    }

    /** How far dot {@code i} is stretched towards a pill: 1 when the pill is on it, 0 a whole dot away. */
    private float stretch(int i) {
        return Math.max(0f, 1f - Math.abs(i - pos));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = count == 0 ? 0 : (int) Math.ceil(count * dot + (pill - dot) + (count - 1) * gap);
        setMeasuredDimension(width + getPaddingLeft() + getPaddingRight(),
                (int) Math.ceil(dot) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float x = getPaddingLeft(), top = getPaddingTop(), r = dot / 2f;
        for (int i = 0; i < count; i++) {
            float s = stretch(i);
            float w = dot + (pill - dot) * s;
            lit.setAlpha((int) (255 * s));
            canvas.drawRoundRect(x, top, x + w, top + dot, r, r, dim);
            canvas.drawRoundRect(x, top, x + w, top + dot, r, r, lit);
            x += w + gap;
        }
    }
}
