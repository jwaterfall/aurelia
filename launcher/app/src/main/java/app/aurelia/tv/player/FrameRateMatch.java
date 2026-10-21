package app.aurelia.tv.player;

import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

/**
 * Switches the TV to the video's own frame rate (a 23.976 film at 23.976Hz, not 60Hz), so pans move evenly instead
 * of juddering. Same resolution as now, only the refresh rate changes; nothing when the screen already shows the
 * video's frames evenly (25 at 50Hz, 30 at 60Hz). The TV blanks for a moment as it switches, which it does while the
 * stream opens. The window keeps asking for the mode until the player closes, then the TV goes back.
 */
final class FrameRateMatch {

    /** How far apart two rates can be and still count as the same: 23.976 against 23.976025, not 24. */
    private static final float TOLERANCE = 0.0005f;

    private FrameRateMatch() {
    }

    /** Asks for the mode that suits {@code fps}; a frame rate of 0 (not known) changes nothing. */
    static void apply(Window window, float fps) {
        if (fps <= 0) return;
        Display display = window.getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode[] modes = display.getSupportedModes();
        int[] widths = new int[modes.length];
        int[] heights = new int[modes.length];
        float[] rates = new float[modes.length];
        for (int i = 0; i < modes.length; i++) {
            widths[i] = modes[i].getPhysicalWidth();
            heights[i] = modes[i].getPhysicalHeight();
            rates[i] = modes[i].getRefreshRate();
        }
        int pick = choose(widths, heights, rates, current.getPhysicalWidth(), current.getPhysicalHeight(),
                current.getRefreshRate(), fps);
        if (pick < 0 || modes[pick].getModeId() == current.getModeId()) return;
        WindowManager.LayoutParams params = window.getAttributes();
        params.preferredDisplayModeId = modes[pick].getModeId();
        window.setAttributes(params);
    }

    /**
     * Which of the modes (as their sizes and rates) to show {@code fps} with: -1 to stay as it is, when the current
     * rate already shows each frame a whole number of times, or no mode at this size does. Otherwise the lowest rate
     * that does, so a 24 film plays at 24Hz rather than 48.
     */
    static int choose(int[] widths, int[] heights, float[] rates, int width, int height, float rate, float fps) {
        if (fps <= 0 || isMultiple(rate, fps)) return -1;
        int best = -1;
        for (int i = 0; i < rates.length; i++) {
            if (widths[i] != width || heights[i] != height || !isMultiple(rates[i], fps)) continue;
            if (best < 0 || rates[i] < rates[best]) best = i;
        }
        return best;
    }

    private static boolean isMultiple(float rate, float fps) {
        float times = rate / fps;
        return Math.round(times) >= 1 && Math.abs(times - Math.round(times)) < TOLERANCE;
    }
}
