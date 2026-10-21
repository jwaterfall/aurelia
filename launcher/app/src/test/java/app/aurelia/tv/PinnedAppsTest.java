package app.aurelia.tv;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PinnedAppsTest {
    private static List<String> list(String... pkgs) {
        return Arrays.asList(pkgs);
    }

    @Test public void pinAddsAtTheEndOnce() {
        assertEquals(list("a", "b", "c"), PinnedApps.pin(list("a", "b"), "c"));
        assertEquals(list("a", "b"), PinnedApps.pin(list("a", "b"), "a"));
        assertEquals(list("a"), PinnedApps.pin(Collections.emptyList(), "a"));
    }

    @Test public void unpinRemovesEveryCopy() {
        assertEquals(list("a", "c"), PinnedApps.unpin(list("a", "b", "c", "b"), "b"));
        assertEquals(list("a"), PinnedApps.unpin(list("a"), "z"));
    }

    @Test public void moveSwapsWithTheNeighbourOnScreen() {
        List<String> pinned = list("a", "b", "c");
        assertEquals(list("b", "a", "c"), PinnedApps.move(pinned, pinned, "a", 1));
        assertEquals(list("a", "c", "b"), PinnedApps.move(pinned, pinned, "c", -1));
    }

    @Test public void moveSkipsAppsThatAreNotInstalled() {
        // "gone" is pinned but not installed, so it is not on screen: b and c are neighbours there.
        List<String> pinned = list("a", "b", "gone", "c");
        List<String> shown = list("a", "b", "c");
        assertEquals(list("a", "c", "gone", "b"), PinnedApps.move(pinned, shown, "b", 1));
    }

    @Test public void moveStopsAtTheEnds() {
        List<String> pinned = list("a", "b");
        assertEquals(pinned, PinnedApps.move(pinned, pinned, "a", -1));
        assertEquals(pinned, PinnedApps.move(pinned, pinned, "b", 1));
        assertEquals(pinned, PinnedApps.move(pinned, pinned, "z", 1));
    }

    @Test public void changesLeaveTheGivenListAlone() {
        List<String> pinned = list("a", "b");
        PinnedApps.move(pinned, pinned, "a", 1);
        PinnedApps.unpin(pinned, "a");
        assertEquals(list("a", "b"), pinned);
    }

    @Test public void encodeAndDecodeKeepTheOrder() {
        List<String> pinned = list("com.netflix.ninja", "org.jellyfin.androidtv", "com.google.android.youtube.tv");
        assertEquals(pinned, PinnedApps.decode(PinnedApps.encode(pinned)));
    }

    @Test public void anEmptyListStaysEmpty() {
        // Everything unpinned is a choice too: it must not fall back to the theme's list.
        assertEquals(Collections.emptyList(), PinnedApps.decode(PinnedApps.encode(Collections.emptyList())));
        assertEquals(list("a", "b"), PinnedApps.decode(" a ,,b,a"));
    }
}
