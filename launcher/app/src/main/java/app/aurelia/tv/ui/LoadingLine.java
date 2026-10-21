package app.aurelia.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A thin line under what is opening, with a short white stretch that sweeps along it again and again,
 * for a wait with no telling how long (the player opening a stream). It draws only while shown.
 */
public class LoadingLine extends View {

    private static final long PERIOD_MS = 1400;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PathInterpolator ease = new PathInterpolator(0.45f, 0f, 0.25f, 1f);

    public LoadingLine(Context context) {
        this(context, null);
    }

    public LoadingLine(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        track.setColor(0x33FFFFFF);
        lit.setColor(0xFFFFFFFF);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        canvas.drawRoundRect(0, 0, w, h, r, r, track);
        // A stretch a third as long, coming in from the left and leaving at the right.
        float t = ease.getInterpolation((SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS);
        float len = w / 3f;
        float start = -len + (w + len) * t;
        canvas.save();
        canvas.clipRect(0, 0, w, h);
        canvas.drawRoundRect(Math.max(0, start), 0, Math.min(w, start + len), h, r, r, lit);
        canvas.restore();
        if (isShown()) postInvalidateOnAnimation();
    }
}
