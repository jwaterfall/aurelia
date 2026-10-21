package app.aurelia.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.aurelia.tv.R;

/**
 * Waiting on something to play: a small disc of glass that breathes, sending out two rings that widen and fade,
 * half a beat apart, like a jellyfish's pulse. It comes in after a moment (so a short wait shows nothing) and
 * goes softly; it draws only while showing.
 */
public class PulseIndicator extends View {

    private static final long PERIOD_MS = 2000;
    /** A wait shorter than this never shows it. */
    private static final long DELAY_MS = 300;
    private static final long FADE_MS = 300;

    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PathInterpolator out = new PathInterpolator(0.2f, 0.6f, 0.35f, 1f);
    private final float core;
    private boolean on;

    public PulseIndicator(Context context) {
        this(context, null);
    }

    public PulseIndicator(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        core = getResources().getDimension(R.dimen.pulse_core);
        float line = getResources().getDimension(R.dimen.pulse_line);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(line);
        rim.setColor(context.getColor(R.color.glass_rim_top));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(line);
        ring.setColor(context.getColor(R.color.text_primary));
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
        disc.setShader(new RadialGradient(w / 2f, h / 2f - core * 0.4f, core * 1.6f,
                getContext().getColor(R.color.glass_fill_top), getContext().getColor(R.color.glass_fill_bottom),
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float reach = Math.min(cx, cy) - ring.getStrokeWidth();
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < 2; i++) {
            float t = ((now + i * PERIOD_MS / 2) % PERIOD_MS) / (float) PERIOD_MS;
            float grown = out.getInterpolation(t);
            ring.setAlpha(Math.round(140 * (1f - t) * (1f - t)));
            canvas.drawCircle(cx, cy, core + (reach - core) * grown, ring);
        }
        // The disc swells a little as each ring leaves it.
        float beat = (now % (PERIOD_MS / 2)) / (float) (PERIOD_MS / 2);
        float r = core * (1f + 0.08f * (float) Math.sin(Math.PI * beat));
        canvas.drawCircle(cx, cy, r, disc);
        canvas.drawCircle(cx, cy, r, rim);
        if (isShown() && getAlpha() > 0f || on) postInvalidateOnAnimation();
    }
}
