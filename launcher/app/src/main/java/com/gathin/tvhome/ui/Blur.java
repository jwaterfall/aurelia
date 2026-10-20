package com.gathin.tvhome.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

/**
 * Blur for small bitmaps, done on the CPU so it works on every Android version this launcher supports. Blur a
 * tiny copy and let the GPU stretch it back over the screen with filtering: the stretch smooths it further.
 */
public final class Blur {
    private Blur() { }

    /**
     * A blurred copy of what {@code view} shows, at 1/{@code factor} of its size; null when it has no size yet.
     * Drawing in software misses elevation shadows, which a blur this strong would lose anyway.
     */
    public static Bitmap snapshot(View view, int factor, int radius) {
        int w = view.getWidth() / factor, h = view.getHeight() / factor;
        if (w <= 0 || h <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(1f / factor, 1f / factor);
        view.draw(c);
        box(bmp, radius, 3);
        return bmp;
    }

    /** {@code passes} box blurs of {@code radius} pixels, which together approximate a gaussian. */
    public static void box(Bitmap bmp, int radius, int passes) {
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
