package app.aurelia.tv.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrameRateMatchTest {

    // The Chromecast on a 4K TV, as it lists its modes.
    private static final int[] W = {3840, 3840, 3840, 3840, 3840, 3840, 1920, 1920};
    private static final int[] H = {2160, 2160, 2160, 2160, 2160, 2160, 1080, 1080};
    private static final float[] HZ = {59.94006f, 60.000004f, 50f, 25f, 23.976025f, 24.000002f, 59.94006f, 23.976025f};

    private static int choose(float fps) {
        return FrameRateMatch.choose(W, H, HZ, 3840, 2160, 59.94006f, fps);
    }

    @Test
    public void filmsSwitchToTheirOwnRateAtTheSameSize() {
        assertEquals(4, choose(23.976f));
        assertEquals(5, choose(24f));
    }

    @Test
    public void aRateThatDividesEvenlyStays() {
        assertEquals(-1, choose(29.97f));
        assertEquals(-1, choose(59.94f));
    }

    @Test
    public void theLowestEvenRateWins() {
        // 25 and 50 both show 25fps evenly; 25Hz is the closer match.
        assertEquals(3, choose(25f));
    }

    @Test
    public void unknownOrUnmatchedRatesStay() {
        assertEquals(-1, choose(0f));
        assertEquals(-1, choose(17f));
    }
}
