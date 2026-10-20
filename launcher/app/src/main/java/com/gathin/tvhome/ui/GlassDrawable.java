package com.gathin.tvhome.ui;

import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gathin.tvhome.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Frosted glass surface: a fill that is lighter at the top, and a rim that catches light along the top edge and
 * fades out down the sides. Interactive glass fades to solid white while its view has focus.
 *
 * Used from XML as {@code <com.gathin.tvhome.ui.GlassDrawable app:glassInteractive="true" />} (res/drawable/glass_*.xml).
 */
public class GlassDrawable extends Drawable {

    private static final long FOCUS_MS = 180;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF rimRect = new RectF();

    /** Negative: a pill, half the height. */
    private float radius = -1f;
    private boolean interactive;
    private float hairline = 1f;
    private int fillTop, fillBottom, rimTop, rimBottom, focusColor;

    /** 0 at rest, 1 fully white. Animated between the two on focus changes. */
    private float focus;
    private boolean focused;
    private ValueAnimator animator;

    public GlassDrawable() {
        rim.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser, @NonNull AttributeSet attrs,
                        @Nullable Resources.Theme theme) throws IOException, XmlPullParserException {
        super.inflate(r, parser, attrs, theme);
        TypedArray a = theme != null
                ? theme.obtainStyledAttributes(attrs, R.styleable.GlassDrawable, 0, 0)
                : r.obtainAttributes(attrs, R.styleable.GlassDrawable);
        radius = a.getDimension(R.styleable.GlassDrawable_glassRadius, -1f);
        interactive = a.getBoolean(R.styleable.GlassDrawable_glassInteractive, false);
        a.recycle();
        fillTop = r.getColor(R.color.glass_fill_top, theme);
        fillBottom = r.getColor(R.color.glass_fill_bottom, theme);
        rimTop = r.getColor(R.color.glass_rim_top, theme);
        rimBottom = r.getColor(R.color.glass_rim_bottom, theme);
        focusColor = r.getColor(R.color.focus, theme);
        hairline = r.getDimension(R.dimen.hairline);
        rim.setStrokeWidth(hairline);
        updateShaders();
    }

    @Override
    protected void onBoundsChange(@NonNull android.graphics.Rect bounds) {
        rect.set(bounds);
        rimRect.set(rect);
        rimRect.inset(hairline / 2f, hairline / 2f);
        updateShaders();
    }

    private void updateShaders() {
        if (rect.isEmpty()) return;
        int top = Colors.lerp(fillTop, focusColor, focus);
        int bottom = Colors.lerp(fillBottom, focusColor, focus);
        fill.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, top, bottom, Shader.TileMode.CLAMP));
        // The rim is brightest along the top edge and nearly gone by halfway down, like light from above.
        rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{rimTop, rimBottom, rimBottom}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        rim.setAlpha(Math.round(255 * (1f - focus)));
    }

    private float cornerRadius() {
        return radius < 0 ? rect.height() / 2f : radius;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (rect.isEmpty()) return;
        float r = cornerRadius();
        canvas.drawRoundRect(rect, r, r, fill);
        if (focus < 1f) canvas.drawRoundRect(rimRect, r, r, rim);
    }

    @Override
    public boolean isStateful() {
        return interactive;
    }

    @Override
    protected boolean onStateChange(@NonNull int[] state) {
        if (!interactive) return false;
        boolean now = false;
        for (int s : state) if (s == android.R.attr.state_focused) now = true;
        if (now == focused) return false;
        focused = now;
        animateFocus(now ? 1f : 0f);
        return true;
    }

    private void animateFocus(float target) {
        if (animator != null) animator.cancel();
        // Not visible yet (first bind, recycled view): jump, there is nothing to animate from.
        if (!isVisible() || getCallback() == null) {
            focus = target;
            updateShaders();
            invalidateSelf();
            return;
        }
        animator = ValueAnimator.ofFloat(focus, target);
        animator.setDuration(FOCUS_MS);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animator.addUpdateListener(a -> {
            focus = (float) a.getAnimatedValue();
            updateShaders();
            invalidateSelf();
        });
        animator.start();
    }

    @Override
    public void jumpToCurrentState() {
        if (animator != null) animator.cancel();
        focus = focused ? 1f : 0f;
        updateShaders();
        invalidateSelf();
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        if (rect.isEmpty()) return;
        // A shadow only shows while the view is lifted (res/animator), which is while the glass is white.
        outline.setRoundRect(getBounds(), cornerRadius());
        outline.setAlpha(1f);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        rim.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
