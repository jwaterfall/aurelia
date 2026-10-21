package app.aurelia.tv.ui;

import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * Logos without the clear margins some come with, so every logo starts at the text's left edge and keeps its true
 * size: on Home's hero and in the player. Worked out once per logo, and kept (a few megabytes at most) for the
 * next screen that shows it.
 */
public final class Logos {

    private static final LruCache<String, Bitmap> trims = new LruCache<String, Bitmap>(8 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private Logos() { }

    /** {@code bitmap}, the logo at {@code url}, cropped to what is drawn in it. */
    public static Bitmap trimmed(String url, Bitmap bitmap) {
        Bitmap known = trims.get(url);
        if (known != null) return known;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] px = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        int left = w, right = -1, top = h, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Nearly clear counts as clear: some logos fade out into a faint haze at their edges.
                if ((px[y * w + x] >>> 24) < 16) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        Bitmap out = right < left || (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) ? bitmap
                : Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1);
        trims.put(url, out);
        return out;
    }
}
