package app.aurelia.tv.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdatesTest {

    @Test
    public void versionsCompareAsNumbers() {
        assertTrue(Updates.isNewer("2.10.0", "2.9.1"));
        assertTrue(Updates.isNewer("2.5", "2.4.0"));
        assertTrue(Updates.isNewer("3.0.0", "2.99.99"));
    }

    @Test
    public void theSameOrOlderIsNotAnUpdate() {
        assertFalse(Updates.isNewer("2.4.0", "2.4"));
        assertFalse(Updates.isNewer("2.3.9", "2.4.0"));
        assertFalse(Updates.isNewer("", "2.4.0"));
    }

    @Test
    public void suffixesAreIgnored() {
        assertTrue(Updates.isNewer("2.5.0-beta", "2.4.0"));
    }
}
