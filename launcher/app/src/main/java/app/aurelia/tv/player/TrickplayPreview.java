package app.aurelia.tv.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import app.aurelia.tv.R;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Trickplay;

import java.util.HashSet;
import java.util.Set;

/**
 * The frame at the scrub point, over the seek bar, from the server's preview sheets ({@link Trickplay}). Sheets
 * are fetched and decoded in the background and a few kept, since one holds many minutes of frames and scrubbing
 * mostly stays within one or moves to the next; the next one along is fetched ahead. While a sheet is on its way
 * the last frame stays rather than the preview going blank, and before any frame a skeleton stands in.
 */
final class TrickplayPreview {

    /** Decoded sheets kept: the current one, the one fetched ahead and the one just left. Each is several MB. */
    private static final int SHEETS = 3;
    /** Frames wider than this are decoded at half size (or less): the preview is never shown larger. */
    private static final int MAX_DECODED_WIDTH = 480;
    private static final long FADE_MS = 220;

    private final View card;
    private final View frame;
    private final ImageView image;
    private final View skeleton;
    private final TextView time;
    private final View track;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final LruCache<Integer, Bitmap> sheets = new LruCache<>(SHEETS);
    private final Set<Integer> loading = new HashSet<>();
    /** Sheets the server would not give; not asked for again for this item. */
    private final Set<Integer> failed = new HashSet<>();

    private Trickplay info;
    private String server;
    private String auth;
    /** How much smaller than the server's frames the sheets are decoded. */
    private int sample = 1;
    private int shown = -1;
    private int wanted = -1;
    private boolean showing;

    TrickplayPreview(View card, View track) {
        this.card = card;
        this.track = track;
        frame = card.findViewById(R.id.previewFrame);
        image = card.findViewById(R.id.previewImage);
        skeleton = card.findViewById(R.id.previewSkeleton);
        time = card.findViewById(R.id.previewTime);
        image.setClipToOutline(true);
    }

    /** The frames for what is playing now; null for none, which leaves scrubbing as it is without a preview. */
    void setSource(Trickplay t, String server, String auth) {
        if (t != null && info != null && t.itemId.equals(info.itemId) && t.mediaSourceId.equals(info.mediaSourceId)
                && t.width == info.width) {
            return;
        }
        hide();
        info = t;
        this.server = server;
        this.auth = auth;
        sheets.evictAll();
        loading.clear();
        failed.clear();
        shown = -1;
        wanted = -1;
        image.setImageDrawable(null);
        skeleton.setVisibility(View.VISIBLE);
        if (t == null) return;
        sample = 1;
        while (t.width / sample > MAX_DECODED_WIDTH) sample *= 2;
        // Frames of another shape than 16:9 keep the width and take their own height.
        ViewGroup.LayoutParams lp = frame.getLayoutParams();
        lp.height = Math.round(lp.width * (float) t.height / t.width);
        frame.setLayoutParams(lp);
    }

    /** Shows the frame at {@code positionMs}, over {@code fraction} of the way along the bar; nothing without frames. */
    void show(long positionMs, float fraction) {
        if (info == null) return;
        int n = info.thumbnailAt(positionMs);
        boolean forward = wanted < 0 || n >= wanted;
        wanted = n;
        time.setText(PlayerActivity.clock(positionMs));
        place(fraction);
        // A new item's frames can be another shape: placed again once the card has taken it.
        if (card.isLayoutRequested()) card.post(() -> place(fraction));
        draw(n);
        int sheet = info.sheetOf(n);
        load(sheet);
        load(forward ? sheet + 1 : sheet - 1);
        if (!showing) {
            showing = true;
            card.setVisibility(View.VISIBLE);
            card.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(ease).start();
        }
    }

    void hide() {
        if (!showing) return;
        showing = false;
        card.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(ease)
                // Ends early too when a new scrub brings it back, so only hidden if it is still meant to be.
                .withEndAction(() -> {
                    if (!showing) card.setVisibility(View.INVISIBLE);
                }).start();
    }

    /** Centred over the scrub point, but kept within the bar's ends, which sit on the screen margins. */
    private void place(float fraction) {
        View parent = (View) card.getParent();
        int[] at = new int[2];
        int[] origin = new int[2];
        track.getLocationInWindow(at);
        parent.getLocationInWindow(origin);
        float left = at[0] - origin[0];
        float top = at[1] - origin[1];
        float width = card.getWidth();
        float x = left + Math.max(0f, Math.min(1f, fraction)) * track.getWidth() - width / 2f;
        x = Math.max(left, Math.min(x, left + track.getWidth() - width));
        card.setTranslationX(x);
        card.setTranslationY(top - card.getHeight() - card.getResources().getDimension(R.dimen.space_sm));
    }

    /** Frame {@code n}, if its sheet is here; otherwise whatever was showing stays until it arrives. */
    private void draw(int n) {
        if (n == shown) return;
        Bitmap sheet = sheets.get(info.sheetOf(n));
        if (sheet == null) return;
        int x = info.left(n) / sample;
        int y = info.top(n) / sample;
        int w = info.width / sample;
        int h = info.height / sample;
        // The last sheet of an item can stop short of a full grid.
        if (x + w > sheet.getWidth() || y + h > sheet.getHeight()) return;
        image.setImageBitmap(Bitmap.createBitmap(sheet, x, y, w, h));
        skeleton.setVisibility(View.GONE);
        shown = n;
    }

    private void load(int sheet) {
        if (sheet < 0 || sheet >= info.sheetCount() || sheets.get(sheet) != null || loading.contains(sheet)
                || failed.contains(sheet)) {
            return;
        }
        Trickplay from = info;
        String url = from.sheetUrl(server, sheet);
        String header = auth;
        int scale = sample;
        loading.add(sheet);
        Api.async(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            // JPEG has no transparency, so half the bytes a pixel lose nothing that shows.
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inSampleSize = scale;
            return Api.getImage(url, header, o);
        }, new Api.Callback<Bitmap>() {
            @Override
            public void onResult(Bitmap bitmap) {
                if (info != from) return;
                loading.remove(sheet);
                if (bitmap == null) {
                    failed.add(sheet);
                    return;
                }
                sheets.put(sheet, bitmap);
                if (showing && wanted >= 0 && info.sheetOf(wanted) == sheet) draw(wanted);
            }

            @Override
            public void onError(Exception e) {
                if (info != from) return;
                loading.remove(sheet);
                failed.add(sheet);
            }
        });
    }
}
