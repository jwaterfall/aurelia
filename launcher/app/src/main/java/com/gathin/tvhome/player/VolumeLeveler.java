package com.gathin.tvhome.player;

import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.util.Log;

/**
 * Evens out loudness ("night mode"): a gentle compressor lifts quiet dialogue and tames loud scenes, and a limiter
 * stops peaks clipping. Android's own dynamics processing effect on the player's audio session, so it only hears
 * audio the TV decodes; Dolby or DTS passed through to an amplifier never reaches it.
 */
final class VolumeLeveler {

    private static final String TAG = "Player.Leveler";
    /** Enough for 7.1; channels the effect is not configured for would pass through untouched. */
    private static final int CHANNELS = 8;

    private DynamicsProcessing effect;

    /** Attaches to {@code sessionId}, replacing any earlier session, or detaches when {@code on} is false. */
    void apply(int sessionId, boolean on) {
        release();
        // Android 9 brought the effect; before it, nothing to level with.
        if (!on || sessionId == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
                    false, 0, true, 1, false, 0, true).build();
            for (int ch = 0; ch < CHANNELS; ch++) {
                DynamicsProcessing.MbcBand band = new DynamicsProcessing.MbcBand(true, 20_000f,
                        10f, 250f, 3f, -28f, 8f, -90f, 1f, 0f, 9f);
                config.setMbcBandByChannelIndex(ch, 0, band);
                config.setLimiterByChannelIndex(ch, new DynamicsProcessing.Limiter(true, true, 0,
                        1f, 60f, 10f, -2f, 0f));
            }
            effect = new DynamicsProcessing(0, sessionId, config);
            effect.setEnabled(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "Volume levelling is not available", e);
            effect = null;
        }
    }

    void release() {
        if (effect != null) {
            effect.release();
            effect = null;
        }
    }
}
