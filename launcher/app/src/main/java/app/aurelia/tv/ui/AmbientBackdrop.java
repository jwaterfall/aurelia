package app.aurelia.tv.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
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
 *
 * Drawing it is not, done the plain way: the wallpaper, two washes while they cross-fade, and the scrim over them
 * are each a layer the size of the screen, and a TV's GPU spends much of every frame painting them one over
 * another. Given a smooth wallpaper and the scrim ({@link #drawBehind}), it mixes all of them itself, on the CPU,
 * into one small picture whenever the wash changes, and the GPU draws only that, stretched over the screen.
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
    /** The size, as a fraction of the screen's, of the picture everything behind the content is mixed into. */
    private static final int MIX_SCALE = 16;

    private static final ColorMatrixColorFilter TONE;

    static {
        ColorMatrix tone = new ColorMatrix();
        tone.setSaturation(1.35f);
        // Darken: artwork such as a white banner must not light up the whole screen.
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.58f, 0.58f, 0.58f, 1f);
        tone.postConcat(dim);
        TONE = new ColorMatrixColorFilter(tone);
    }

    private final ImageView[] layers = new ImageView[2];
    private int front;
    private Object current;
    private boolean animate = true;
    private Runnable pending;

    /** Set once it draws the wallpaper and the scrim itself, mixed with the wash. */
    @Nullable private Mix mix;

    public AmbientBackdrop(Context context) {
        this(context, null);
    }

    public AmbientBackdrop(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        for (int i = 0; i < layers.length; i++) {
            ImageView v = new ImageView(context);
            v.setScaleType(ImageView.ScaleType.FIT_XY);
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
     * Takes over drawing {@code wallpaper} (an image view under this one, showing a bitmap) and {@code scrim} (a
     * view over this one), mixing them with the wash into one picture; both views are hidden. Only for a wallpaper
     * as smooth as the bundled one, which loses nothing kept small: a photo's detail would. True when it took
     * them over.
     */
    public boolean drawBehind(ImageView wallpaper, View scrim) {
        if (mix != null || !(wallpaper.getDrawable() instanceof BitmapDrawable) || scrim.getBackground() == null) {
            return false;
        }
        mix = new Mix(((BitmapDrawable) wallpaper.getDrawable()).getBitmap(), scrim.getBackground());
        wallpaper.setVisibility(GONE);
        scrim.setVisibility(GONE);
        for (ImageView v : layers) removeView(v);
        setWillNotDraw(false);
        if (getWidth() > 0) mix.resize(getWidth(), getHeight());
        return true;
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
        if (mix != null) {
            mix.fade(front, 0f, FADE_OUT_MS);
            if (blurred == null) return;
            front = 1 - front;
            mix.set(front, blurred);
            mix.strengths[front] = 0f;
            mix.fade(front, STRENGTH, FADE_IN_MS);
            return;
        }
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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mix != null && w > 0 && h > 0) mix.resize(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mix != null) mix.draw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mix != null) mix.cancel();
    }

    /** Draws {@code art} into a tiny bitmap, cropping it to the screen's shape, blurs it there, and tones it. */
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
        Blur.box(small, 3, 3);
        // The tone goes into the pixels here, once, rather than through a filter on every frame it is drawn.
        Bitmap toned = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setColorFilter(TONE);
        new Canvas(toned).drawBitmap(small, 0, 0, paint);
        return toned;
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

    /**
     * The wallpaper, the wash going, the wash coming and the scrim, each over the last, mixed into one small
     * picture each time a wash changes or fades a step. Everything in it is a smooth gradient, so stretched back
     * over the screen it looks as the layers did.
     */
    private final class Mix {
        private final Bitmap wallpaper;
        private final Drawable scrim;
        private final Bitmap[] washes = new Bitmap[2];
        /** Each wash at the picture's size, as getPixels gives it; null while it has none. */
        private final int[][] washPixels = new int[2][];
        private final float[] strengths = new float[2];
        private final ValueAnimator[] fades = new ValueAnimator[2];
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Rect src = new Rect();
        private final Rect dst = new Rect();
        private int[] base;
        private int[] scrimPixels;
        private int[] out;
        private Bitmap picture;
        private int w, h;

        Mix(Bitmap wallpaper, Drawable scrim) {
            this.wallpaper = wallpaper;
            this.scrim = scrim;
        }

        /** The wallpaper (cropped to fill, as the image view did) and the scrim, small, for a screen this size. */
        void resize(int width, int height) {
            w = Math.max(1, width / MIX_SCALE);
            h = Math.max(1, height / MIX_SCALE);
            dst.set(0, 0, width, height);
            src.set(0, 0, w, h);
            Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            float scale = Math.max(w / (float) wallpaper.getWidth(), h / (float) wallpaper.getHeight());
            c.translate((w - wallpaper.getWidth() * scale) / 2f, (h - wallpaper.getHeight() * scale) / 2f);
            c.scale(scale, scale);
            c.drawBitmap(wallpaper, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            base = pixels(small);
            small.eraseColor(0);
            scrim.setBounds(0, 0, w, h);
            scrim.draw(new Canvas(small));
            scrimPixels = pixels(small);
            out = new int[w * h];
            picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            for (int i = 0; i < washes.length; i++) if (washes[i] != null) set(i, washes[i]);
            compose();
        }

        /** Wash {@code i} becomes {@code bitmap}, stretched to the picture's size. */
        void set(int i, Bitmap bitmap) {
            washes[i] = bitmap;
            if (picture == null) return;
            washPixels[i] = pixels(Bitmap.createScaledBitmap(bitmap, w, h, true));
        }

        /** Wash {@code i}'s strength to {@code to}, over {@code ms} (at once with animations off). */
        void fade(int i, float to, long ms) {
            if (fades[i] != null) fades[i].cancel();
            if (!animate) {
                strengths[i] = to;
                compose();
                return;
            }
            ValueAnimator a = ValueAnimator.ofFloat(strengths[i], to).setDuration(ms);
            a.addUpdateListener(v -> {
                strengths[i] = (float) v.getAnimatedValue();
                compose();
            });
            fades[i] = a;
            a.start();
        }

        void cancel() {
            for (ValueAnimator a : fades) if (a != null) a.cancel();
        }

        /** Mixes the picture again: the wash coming over the one going, between the wallpaper and the scrim. */
        private void compose() {
            if (picture == null) return;
            int top = front, bottom = 1 - front;
            int[] below = strengths[bottom] > 0f ? washPixels[bottom] : null;
            int[] above = strengths[top] > 0f ? washPixels[top] : null;
            int kb = Math.round(strengths[bottom] * 256), ka = Math.round(strengths[top] * 256);
            for (int p = 0; p < out.length; p++) {
                int c = base[p];
                if (below != null) c = over(c, below[p], kb);
                if (above != null) c = over(c, above[p], ka);
                out[p] = over(c, scrimPixels[p], 256);
            }
            picture.setPixels(out, 0, w, 0, 0, w, h);
            invalidate();
        }

        void draw(Canvas canvas) {
            if (picture != null) canvas.drawBitmap(picture, src, dst, paint);
        }
    }

    /** {@code layer} at {@code k}/256 of its strength over the opaque {@code under}; both as getPixels gives them. */
    private static int over(int under, int layer, int k) {
        int a = ((layer >>> 24) * k) >> 8;
        if (a == 0) return under;
        int keep = 255 - a;
        int r = (((layer >> 16) & 0xFF) * a + ((under >> 16) & 0xFF) * keep) / 255;
        int g = (((layer >> 8) & 0xFF) * a + ((under >> 8) & 0xFF) * keep) / 255;
        int b = ((layer & 0xFF) * a + (under & 0xFF) * keep) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int[] pixels(Bitmap bitmap) {
        int[] px = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(px, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return px;
    }
}
