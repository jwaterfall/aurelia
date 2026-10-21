package app.aurelia.tv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayerTimeTest {

    @Test
    public void clockReadsLikeAPlayer() {
        assertEquals("0:00", PlayerActivity.clock(0));
        assertEquals("4:05", PlayerActivity.clock(245_000));
        assertEquals("1:02:09", PlayerActivity.clock(3_729_000));
    }

    @Test
    public void holdingAKeyScrubsFurther() {
        assertEquals(10_000, PlayerActivity.scrubStep(0));
        assertTrue(PlayerActivity.scrubStep(10) > PlayerActivity.scrubStep(0));
        assertTrue(PlayerActivity.scrubStep(40) > PlayerActivity.scrubStep(10));
    }
}
