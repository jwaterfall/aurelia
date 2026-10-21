package app.aurelia.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The apps pinned to Home, as changed on the TV. Until someone pins, unpins or moves one, Home shows the theme's
 * dock_apps (config.xml or config.json); the first change saves that list with the change made, and from then on
 * the saved list is what Home shows. A new theme clears it again (Config.syncThemeChoices).
 *
 * The list keeps packages that are not installed: they show again if the app comes back. Moves therefore swap
 * with the neighbour on screen, not with whatever is next in the list.
 */
final class PinnedApps {
    /** The key in Config's launcher_settings preferences. */
    static final String KEY = "pinned_apps";

    private PinnedApps() { }

    /** The list saved on the TV, or null when nothing has been changed there since the theme was applied. */
    static List<String> saved(SharedPreferences choices) {
        String value = choices.getString(KEY, null);
        return value == null ? null : decode(value);
    }

    static void save(Context context, List<String> pinned) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY, encode(pinned)).apply();
    }

    /** Package names cannot hold a comma, so one string keeps the order (a string set would not). */
    static String encode(List<String> pinned) {
        return String.join(",", pinned);
    }

    static List<String> decode(String value) {
        List<String> out = new ArrayList<>();
        for (String pkg : value.split(",")) {
            String p = pkg.trim();
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /** {@code pinned} with {@code pkg} added at the end; unchanged if it is already there. */
    static List<String> pin(List<String> pinned, String pkg) {
        List<String> out = new ArrayList<>(pinned);
        if (!out.contains(pkg)) out.add(pkg);
        return out;
    }

    static List<String> unpin(List<String> pinned, String pkg) {
        List<String> out = new ArrayList<>(pinned);
        out.removeAll(Collections.singleton(pkg));
        return out;
    }

    /**
     * {@code pinned} with {@code pkg} swapped with its neighbour on screen, one place later ({@code by} 1) or
     * earlier (-1). {@code shown} is the pinned row as it shows: only installed apps, in pinned order. Unchanged at
     * either end of the row.
     */
    static List<String> move(List<String> pinned, List<String> shown, String pkg, int by) {
        List<String> out = new ArrayList<>(pinned);
        int at = shown.indexOf(pkg);
        int to = at + by;
        if (at < 0 || to < 0 || to >= shown.size()) return out;
        int a = out.indexOf(pkg);
        int b = out.indexOf(shown.get(to));
        if (a < 0 || b < 0) return out;
        Collections.swap(out, a, b);
        return out;
    }
}
