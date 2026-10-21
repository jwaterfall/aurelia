package app.aurelia.tv.jellyfin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class TrickplayTest {

    private static final String SOURCE = "3f2b6c0a9d8e4f1a8b7c6d5e4f3a2b1c";

    private static JSONObject info(int width, int height, int tiles, int count, long interval) throws Exception {
        return new JSONObject().put("Width", width).put("Height", height).put("TileWidth", tiles)
                .put("TileHeight", tiles).put("ThumbnailCount", count).put("Interval", interval)
                .put("Bandwidth", 12345);
    }

    private static JSONObject item(String source, JSONObject byWidth) throws Exception {
        return new JSONObject().put("Id", "item").put("Trickplay", new JSONObject().put(source, byWidth));
    }

    private static Trickplay standard(String itemId) throws Exception {
        return Trickplay.parse(item(SOURCE, new JSONObject().put("320", info(320, 180, 10, 540, 10_000))), itemId,
                SOURCE, 320);
    }

    @Test
    public void noFieldMeansNoPreview() throws Exception {
        assertNull(Trickplay.parse(new JSONObject().put("Id", "item"), "item", SOURCE, 320));
        assertNull(Trickplay.parse(new JSONObject().put("Trickplay", new JSONObject()), "item", SOURCE, 320));
        assertNull(Trickplay.parse(null, "item", SOURCE, 320));
    }

    @Test
    public void readsTheServersInfo() throws Exception {
        Trickplay t = standard("item");
        assertNotNull(t);
        assertEquals(320, t.width);
        assertEquals(180, t.height);
        assertEquals(100, t.perSheet());
        assertEquals(6, t.sheetCount());
        assertEquals(10_000, t.intervalMs);
    }

    @Test
    public void picksTheWidthNearestTheOneWanted() throws Exception {
        JSONObject widths = new JSONObject().put("160", info(160, 90, 10, 540, 10_000))
                .put("320", info(320, 180, 10, 540, 10_000)).put("640", info(640, 360, 10, 540, 10_000));
        assertEquals(320, Trickplay.parse(item(SOURCE, widths), "item", SOURCE, 320).width);
        assertEquals(640, Trickplay.parse(item(SOURCE, widths), "item", SOURCE, 600).width);
        // Halfway between: the larger, which shrinks without blurring.
        assertEquals(320, Trickplay.parse(item(SOURCE, widths), "item", SOURCE, 240).width);
    }

    @Test
    public void picksThePlayingSourceEvenWrittenWithDashes() throws Exception {
        JSONObject all = new JSONObject()
                .put("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", new JSONObject().put("320", info(320, 180, 10, 10, 10_000)))
                .put(SOURCE, new JSONObject().put("320", info(320, 136, 10, 99, 5_000)));
        Trickplay t = Trickplay.parse(new JSONObject().put("Trickplay", all), "item",
                "3F2B6C0A-9D8E-4F1A-8B7C-6D5E4F3A2B1C", 320);
        assertEquals(SOURCE, t.mediaSourceId);
        assertEquals(136, t.height);
        assertEquals(99, t.thumbnailCount);
    }

    @Test
    public void anotherSourceFallsBackToTheOneWithFrames() throws Exception {
        Trickplay t = Trickplay.parse(item(SOURCE, new JSONObject().put("320", info(320, 180, 10, 540, 10_000))),
                "item", "somethingelse", 320);
        assertNotNull(t);
        assertEquals(SOURCE, t.mediaSourceId);
    }

    @Test
    public void incompleteInfoIsIgnored() throws Exception {
        assertNull(Trickplay.parse(item(SOURCE, new JSONObject().put("320", info(320, 180, 10, 540, 0))),
                "item", SOURCE, 320));
        assertNull(Trickplay.parse(item(SOURCE, new JSONObject().put("320", info(320, 180, 0, 540, 10_000))),
                "item", SOURCE, 320));
    }

    @Test
    public void framesAreCountedByInterval() throws Exception {
        Trickplay t = standard("item");
        assertEquals(0, t.thumbnailAt(0));
        assertEquals(0, t.thumbnailAt(9_999));
        assertEquals(1, t.thumbnailAt(10_000));
        assertEquals(0, t.thumbnailAt(-5_000));
        // Past the last frame (the credits run on after it): the last one.
        assertEquals(539, t.thumbnailAt(10_000_000));
    }

    @Test
    public void framesSitInRowsOnTheirSheet() throws Exception {
        Trickplay t = standard("item");
        assertEquals(0, t.sheetOf(99));
        assertEquals(1, t.sheetOf(100));
        assertEquals(5, t.sheetOf(539));
        // Frame 123: the second sheet, 23rd on it, so row 2 and column 3.
        assertEquals(1, t.sheetOf(123));
        assertEquals(3 * 320, t.left(123));
        assertEquals(2 * 180, t.top(123));
        assertEquals(0, t.left(100));
        assertEquals(0, t.top(100));
        assertEquals(9 * 320, t.left(99));
        assertEquals(9 * 180, t.top(99));
    }

    @Test
    public void wideGridsWrapByTheirOwnWidth() throws Exception {
        JSONObject wide = new JSONObject().put("Width", 320).put("Height", 180).put("TileWidth", 5)
                .put("TileHeight", 2).put("ThumbnailCount", 30).put("Interval", 1000);
        Trickplay t = Trickplay.parse(item(SOURCE, new JSONObject().put("320", wide)), "item", SOURCE, 320);
        assertEquals(10, t.perSheet());
        assertEquals(3, t.sheetCount());
        assertEquals(2, t.sheetOf(27));
        assertEquals(2 * 320, t.left(27));
        assertEquals(180, t.top(27));
    }

    @Test
    public void sheetAddress() throws Exception {
        assertEquals("http://tv:8096/Videos/abc/Trickplay/320/4.jpg?MediaSourceId=" + SOURCE,
                standard("abc").sheetUrl("http://tv:8096", 4));
    }
}
