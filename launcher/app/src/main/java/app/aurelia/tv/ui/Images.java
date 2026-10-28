package app.aurelia.tv.ui;

import android.graphics.Bitmap;
import android.util.LruCache;
import android.widget.ImageView;

import app.aurelia.tv.jellyfin.Api;

/**
 * Artwork from the server into image views: kept in memory once fetched (and on disk, {@link ImageDisk}), faded in
 * when it arrives, and never put into a view that has since been reused for something else.
 */
public final class Images {

    public interface Loaded {
        void onLoaded(Bitmap bitmap);

        /** The server had nothing, or could not be reached. */
        default void onFailed() { }
    }

    private static final long FADE_MS = 240;
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 6)) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private Images() { }

    /** Shows {@code url} in {@code view} (nothing for an empty url), then tells {@code loaded}, if given. */
    public static void load(ImageView view, String url, String auth, Loaded loaded) {
        view.animate().cancel();
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            if (loaded != null) loaded.onFailed();
            return;
        }
        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setAlpha(1f);
            if (loaded != null) loaded.onLoaded(cached);
            return;
        }
        view.setImageDrawable(null);
        view.setAlpha(0f);
        Api.image(() -> ImageDisk.get(url, auth), new Api.Callback<Bitmap>() {
            @Override
            public void onResult(Bitmap bmp) {
                if (bmp == null) {
                    onError(null);
                    return;
                }
                cache.put(url, bmp);
                if (!url.equals(view.getTag())) return;
                view.setImageBitmap(bmp);
                view.animate().alpha(1f).setDuration(FADE_MS).start();
                if (loaded != null) loaded.onLoaded(bmp);
            }

            @Override
            public void onError(Exception e) {
                if (loaded != null && url.equals(view.getTag())) loaded.onFailed();
            }
        });
    }

    public static void load(ImageView view, String url, String auth) {
        load(view, url, auth, null);
    }
}
