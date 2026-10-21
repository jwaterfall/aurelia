package app.aurelia.tv.jellyfin;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeRowsTest {
    private static Library.MediaItem item(String id) {
        return new Library.MediaItem(id, "", id, "", "", "", 0);
    }

    @Test public void withoutTakesTheCardOutOfEitherRow() {
        Library.HomeRows rows = new Library.HomeRows(Arrays.asList(item("a"), item("b")), Arrays.asList(item("c")));
        Library.HomeRows less = rows.without("b").without("c");
        assertEquals(1, less.resume.size());
        assertEquals("a", less.resume.get(0).id);
        assertTrue(less.nextUp.isEmpty());
        // The rows it came from are left as they were, to put back if the server says no.
        assertEquals(2, rows.resume.size());
    }

    @Test public void onlyContinueWatchingIsResuming() {
        Library.HomeRows rows = new Library.HomeRows(Arrays.asList(item("a")), Arrays.asList(item("c")));
        assertTrue(rows.isResuming("a"));
        assertFalse(rows.isResuming("c"));
    }
}
