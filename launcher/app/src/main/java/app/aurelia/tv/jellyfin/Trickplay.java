package app.aurelia.tv.jellyfin;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

/**
 * The server's preview frames for an item (Jellyfin 10.9 and later), shown above the seek bar while scrubbing.
 * The server cuts a frame every {@link #intervalMs} and packs them into sheets, a grid of
 * {@link #tileWidth} by {@link #tileHeight} frames each, row by row. This works out which sheet and which part of
 * it shows a given moment; loading and drawing it is the player's.
 */
public final class Trickplay {

    /** The frame width to aim for: about the size the preview shows at, so it is neither soft nor wasteful. */
    public static final int PREFERRED_WIDTH = 320;

    public final String itemId;
    public final String mediaSourceId;
    /** One frame's size, in pixels. */
    public final int width;
    public final int height;
    /** Frames across and down a sheet. */
    public final int tileWidth;
    public final int tileHeight;
    public final int thumbnailCount;
    public final long intervalMs;

    Trickplay(String itemId, String mediaSourceId, int width, int height, int tileWidth, int tileHeight,
              int thumbnailCount, long intervalMs) {
        this.itemId = itemId;
        this.mediaSourceId = mediaSourceId;
        this.width = width;
        this.height = height;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.thumbnailCount = thumbnailCount;
        this.intervalMs = intervalMs;
    }

    /**
     * The frames for {@code mediaSourceId} out of an item's "Trickplay" field, at the width nearest
     * {@code preferredWidth}; null when the item has none (an older server, or the frames not made yet). The field
     * is keyed by media source and then by width: {"<source id>": {"320": {"Width": 320, ...}}}. A source missing
     * from it falls back to the first, since a file with several versions usually has frames for one.
     */
    public static Trickplay parse(JSONObject item, String itemId, String mediaSourceId, int preferredWidth) {
        JSONObject all = item == null ? null : item.optJSONObject("Trickplay");
        if (all == null || all.length() == 0) return null;
        String sourceKey = null;
        for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (sourceKey == null) sourceKey = key;
            if (sameId(key, mediaSourceId)) {
                sourceKey = key;
                break;
            }
        }
        JSONObject byWidth = all.optJSONObject(sourceKey);
        if (byWidth == null) return null;

        Trickplay best = null;
        for (Iterator<String> keys = byWidth.keys(); keys.hasNext(); ) {
            Trickplay t = fromInfo(itemId, sourceKey, byWidth.optJSONObject(keys.next()));
            if (t == null) continue;
            if (best == null || closer(t.width, best.width, preferredWidth)) best = t;
        }
        return best;
    }

    private static Trickplay fromInfo(String itemId, String sourceId, JSONObject info) {
        if (info == null) return null;
        int width = info.optInt("Width", 0);
        int height = info.optInt("Height", 0);
        int tileWidth = info.optInt("TileWidth", 0);
        int tileHeight = info.optInt("TileHeight", 0);
        int count = info.optInt("ThumbnailCount", 0);
        long interval = info.optLong("Interval", 0);
        if (width <= 0 || height <= 0 || tileWidth <= 0 || tileHeight <= 0 || count <= 0 || interval <= 0) return null;
        return new Trickplay(itemId, sourceId, width, height, tileWidth, tileHeight, count, interval);
    }

    /** Nearer the preferred width; on a tie the larger, which shrinks cleanly where the smaller would blur. */
    private static boolean closer(int candidate, int current, int preferred) {
        int a = Math.abs(candidate - preferred);
        int b = Math.abs(current - preferred);
        return a < b || (a == b && candidate > current);
    }

    /** Ids compared as the server writes them either way: with or without dashes, in either case. */
    static boolean sameId(String a, String b) {
        if (a == null || b == null) return false;
        return a.replace("-", "").equalsIgnoreCase(b.replace("-", ""));
    }

    /** Frames on one sheet. */
    public int perSheet() {
        return tileWidth * tileHeight;
    }

    public int sheetCount() {
        return (thumbnailCount + perSheet() - 1) / perSheet();
    }

    /** The frame showing {@code positionMs}: the last one cut at or before it, and the last there is after the end. */
    public int thumbnailAt(long positionMs) {
        long n = Math.max(0, positionMs) / intervalMs;
        return (int) Math.min(n, thumbnailCount - 1);
    }

    public int sheetOf(int thumbnail) {
        return thumbnail / perSheet();
    }

    /** Where {@code thumbnail} starts on its sheet, in pixels. */
    public int left(int thumbnail) {
        return thumbnail % perSheet() % tileWidth * width;
    }

    public int top(int thumbnail) {
        return thumbnail % perSheet() / tileWidth * height;
    }

    /** A sheet's address on {@code server}; it needs the same Authorization header as any image. */
    public String sheetUrl(String server, int sheet) {
        return String.format(Locale.ROOT, "%s/Videos/%s/Trickplay/%d/%d.jpg?MediaSourceId=%s", server, itemId, width,
                sheet, mediaSourceId);
    }
}
