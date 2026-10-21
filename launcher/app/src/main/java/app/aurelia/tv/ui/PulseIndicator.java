package app.aurelia.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.aurelia.tv.R;

/**
 * Waiting on something to play: three dots on a small glass pill, one stretched into a pill of its own that
 * travels to the end and back, as the hero's page dots move (HeroDots). It comes in after a moment (so a short
 * wait shows nothing) and goes softly; it draws only while showing.
 */
public class PulseIndicator extends View {

    /** There and back. */
    private static final long PERIOD_MS = 1600;
    /** A wait shorter than this never shows it. */
    private static final long DELAY_MS = 300;
    private static final long FADE_MS = 300;
    private static final int DOTS = 3;

    /** Dark under the glass, so the dots read over a bright picture. */
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PathInterpolator ease = new PathInterpolator(0.45f, 0f, 0.25f, 1f);
    private final float dot;
    private final float pill;
    private final float gap;
    private boolean on;

    public PulseIndicator(Context context) {
        this(context, null);
    }

    public PulseIndicator(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;
        dot = 6 * dp;
        pill = 20 * dp;
        gap = 6 * dp;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(getResources().getDimension(R.dimen.pulse_line));
        rim.setColor(context.getColor(R.color.glass_rim_top));
        base.setColor(0x66000000);
        dim.setColor(0x73FFFFFF);
        lit.setColor(context.getColor(R.color.text_primary));
        setAlpha(0f);
        setVisibility(INVISIBLE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Shows it (after a moment) or hides it (at once, softly). */
    public void show(boolean show) {
        if (show == on) return;
        on = show;
        animate().cancel();
        if (show) {
            setVisibility(VISIBLE);
            animate().alpha(1f).setStartDelay(DELAY_MS).setDuration(FADE_MS).start();
            invalidate();
        } else {
            animate().alpha(0f).setStartDelay(0).setDuration(FADE_MS / 2)
                    .withEndAction(() -> setVisibility(INVISIBLE)).start();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fill.setShader(new LinearGradient(0, 0, 0, h, getContext().getColor(R.color.glass_fill_top),
                getContext().getColor(R.color.glass_fill_bottom), Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        canvas.drawRoundRect(0, 0, w, h, r, r, base);
        canvas.drawRoundRect(0, 0, w, h, r, r, fill);
        canvas.drawRoundRect(0, 0, w, h, r, r, rim);
        // Where the stretched dot is, from the first (0) to the last and back, easing at each end.
        float t = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
        float pos = (DOTS - 1) * ease.getInterpolation(t < 0.5f ? t * 2f : 2f - t * 2f);
        float row = DOTS * dot + (pill - dot) + (DOTS - 1) * gap;
        float x = (w - row) / 2f, top = (h - dot) / 2f, d = dot / 2f;
        for (int i = 0; i < DOTS; i++) {
            float s = Math.max(0f, 1f - Math.abs(i - pos));
            float dw = dot + (pill - dot) * s;
            lit.setAlpha((int) (255 * s));
            canvas.drawRoundRect(x, top, x + dw, top + dot, d, d, dim);
            canvas.drawRoundRect(x, top, x + dw, top + dot, d, d, lit);
            x += dw + gap;
        }
        if (isShown() && getAlpha() > 0f || on) postInvalidateOnAnimation();
    }
}
