package app.aurelia.tv.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.aurelia.tv.R;

/**
 * Stands in for something still loading, in its shape: a poster, a line of text, a button. A soft glass block
 * that slowly breathes. Every placeholder takes its phase from the same clock, so a screen of them breathes
 * together rather than flickering.
 */
public class Placeholder extends View {

    private static final long PERIOD_MS = 1600;
    /** The placeholder goes, then what it stood in for comes: never both at once, half over each other. */
    private static final long HIDE_MS = 160;
    private static final long REVEAL_MS = 260;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final int low, high;
    /** Negative for a pill. */
    private final float radius;

    public Placeholder(Context context) {
        this(context, null);
    }

    public Placeholder(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        low = context.getColor(R.color.placeholder_low);
        high = context.getColor(R.color.placeholder_high);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.Placeholder);
        radius = a.getDimension(R.styleable.Placeholder_placeholderRadius, -1f);
        a.recycle();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Fades {@code placeholder} out, then {@code content} in where it was, once (later calls do nothing). One after
     * the other rather than across each other, so two near-identical shapes never show slightly apart.
     */
    public static void reveal(View placeholder, View... content) {
        if (placeholder.getVisibility() != VISIBLE || placeholder.getTag(R.id.revealing) != null) return;
        placeholder.setTag(R.id.revealing, true);
        placeholder.animate().alpha(0f).setDuration(HIDE_MS).withEndAction(() -> {
            placeholder.setVisibility(GONE);
            for (View v : content) v.animate().alpha(1f).setDuration(REVEAL_MS).start();
        }).start();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float t = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
        float wave = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * t);
        paint.setColor(Colors.lerp(low, high, wave));
        bounds.set(0, 0, getWidth(), getHeight());
        float r = radius < 0 ? getHeight() / 2f : radius;
        canvas.drawRoundRect(bounds, r, r, paint);
        if (isShown()) postInvalidateOnAnimation();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (isShown()) invalidate();
    }
}
