package com.gathin.tvhome.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * Full-screen, heavily blurred wash of whatever has focus: a show's backdrop, an app's banner. It sits between the
 * wallpaper and the content and cross-fades slowly from one item to the next, so the screen takes on the colour
 * of what you are looking at without competing with it.
 *
 * The blur is cheap: the artwork is drawn into a tiny bitmap, box-blurred there, and stretched back over the
 * screen with bilinear filtering, which smooths it further.
 */
public class AmbientBackdrop extends FrameLayout {

    private static final int SAMPLE_W = 48;
    private static final int SAMPLE_H = 27;
    private static final long FADE_IN_MS = 650;
    private static final long FADE_OUT_MS = 450;
    /** Focus that only passes through an item (holding a direction) does not repaint the screen. */
    private static final long SETTLE_MS = 140;
    /** How strongly the wash shows over the wallpaper. */
    private static final float STRENGTH = 0.8f;

    private final ImageView[] layers = new ImageView[2];
    private int front;
    private Object current;
    private boolean animate = true;
    private Runnable pending;

    public AmbientBackdrop(Context context) {
        this(context, null);
    }

    public AmbientBackdrop(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        ColorMatrix tone = new ColorMatrix();
        tone.setSaturation(1.35f);
        // Darken: artwork such as a white banner must not light up the whole screen.
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.58f, 0.58f, 0.58f, 1f);
        tone.postConcat(dim);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(tone);
        for (int i = 0; i < layers.length; i++) {
            ImageView v = new ImageView(context);
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            v.setColorFilter(filter);
            v.setAlpha(0f);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            layers[i] = v;
        }
    }

    public void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
    }

    /**
     * Washes the screen with {@code art}. {@code key} identifies the item, so focusing the same item again does
     * nothing. A null art clears back to the wallpaper.
     */
    public void show(@Nullable Object key, @Nullable Drawable art) {
        if (pending != null) removeCallbacks(pending);
        pending = () -> {
            pending = null;
            apply(key, art);
        };
        postDelayed(pending, animate ? SETTLE_MS : 0);
    }

    public void clear() {
        show(null, null);
    }

    private void apply(@Nullable Object key, @Nullable Drawable art) {
        if (key != null && key.equals(current)) return;
        Bitmap blurred = art == null ? null : sample(art);
        current = blurred == null ? null : key;
        ImageView out = layers[front];
        out.animate().cancel();
        out.animate().alpha(0f).setDuration(animate ? FADE_OUT_MS : 0).start();
        if (blurred == null) return;
        front = 1 - front;
        ImageView in = layers[front];
        in.animate().cancel();
        BitmapDrawable d = new BitmapDrawable(getResources(), blurred);
        d.setFilterBitmap(true);
        in.setImageDrawable(d);
        in.bringToFront();
        in.setAlpha(0f);
        in.animate().alpha(STRENGTH).setDuration(animate ? FADE_IN_MS : 0).start();
    }

    /** Draws {@code art} into a tiny bitmap, cropping it to the screen's shape, and blurs it there. */
    @Nullable
    private static Bitmap sample(Drawable art) {
        int w = art.getIntrinsicWidth(), h = art.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            w = SAMPLE_W;
            h = SAMPLE_H;
        }
        Bitmap small = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        float scale = Math.max(SAMPLE_W / (float) w, SAMPLE_H / (float) h);
        int dw = Math.round(w * scale), dh = Math.round(h * scale);
        int dx = (SAMPLE_W - dw) / 2, dy = (SAMPLE_H - dh) / 2;
        Drawable copy = art.getConstantState() != null ? art.getConstantState().newDrawable().mutate() : art;
        android.graphics.Rect old = new android.graphics.Rect(copy.getBounds());
        copy.setBounds(dx, dy, dx + dw, dy + dh);
        copy.draw(c);
        if (copy == art) art.setBounds(old);
        weightByColour(small);
        boxBlur(small, 3, 3);
        return small;
    }

    /**
     * Makes grey, white and black pixels mostly transparent, so the wash takes the artwork's colours rather than
     * its background: Netflix's white banner washes the screen red, not grey. Colourless art keeps a faint tint.
     */
    private static void weightByColour(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            float weight = Math.max(0.22f, Math.min(1f, chroma / 90f)) * ((c >>> 24) / 255f);
            px[i] = (Math.round(weight * 255) << 24) | (c & 0xFFFFFF);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** {@code passes} box blurs of {@code radius} pixels, which together approximate a gaussian. */
    private static void boxBlur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            blurLine(px, tmp, w, h, radius, true);
            blurLine(tmp, px, w, h, radius, false);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurLine(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                // Colours are averaged weighted by alpha, so a transparent neighbour does not darken them.
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int c = src[horizontal ? line * w + j : j * w + line];
                    int ca = c >>> 24;
                    a += ca;
                    rr += ((c >> 16) & 0xFF) * ca;
                    g += ((c >> 8) & 0xFF) * ca;
                    b += (c & 0xFF) * ca;
                    n++;
                }
                int out = a == 0 ? 0 : ((a / n) << 24) | ((rr / a) << 16) | ((g / a) << 8) | (b / a);
                dst[horizontal ? line * w + i : i * w + line] = out;
            }
        }
    }
}
