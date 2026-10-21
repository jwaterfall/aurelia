package app.aurelia.tv.ui;

/** Colour arithmetic shared by the components. */
public final class Colors {
    private Colors() { }

    /** Mixes two ARGB colours, channel by channel: 0 gives {@code from}, 1 gives {@code to}. */
    public static int lerp(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int a = mix(from >>> 24, to >>> 24, t);
        int r = mix((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = mix((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = mix(from & 0xFF, to & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }
}
