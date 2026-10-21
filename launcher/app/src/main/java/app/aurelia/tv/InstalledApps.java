package app.aurelia.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The TV apps installed on the device, as tiles: banner (or icon and name), and how to open them. */
final class InstalledApps {

    static final class App {
        final String pkg;
        final String label;
        final ComponentName component;
        /** Null when the app has no usable banner: its tile shows the icon and name instead. */
        final Drawable banner;
        final Drawable icon;

        App(String pkg, String label, ComponentName component, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.banner = banner;
            this.icon = icon;
        }

        Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN)
                    .setComponent(component)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    private InstalledApps() { }

    /** Every leanback app except this launcher and {@code hidden}, by name. One tile per package. */
    static List<App> load(Context context, Collection<String> hidden) {
        PackageManager pm = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Set<String> seen = new HashSet<>(hidden);
        seen.add(context.getPackageName());
        List<App> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg)) continue;
            String label = Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
            apps.add(new App(pkg, label, new ComponentName(pkg, ri.activityInfo.name),
                    loadBanner(context, pm, ri), ri.loadIcon(pm)));
        }
        apps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    /** The apps of {@code pinned} that are installed, in that order. */
    static List<App> pinned(List<App> apps, List<String> pinned) {
        List<App> out = new ArrayList<>();
        for (String pkg : pinned) {
            for (App a : apps) {
                if (a.pkg.equals(pkg)) {
                    out.add(a);
                    break;
                }
            }
        }
        return out;
    }

    /** All of {@code apps}, the pinned ones first in their pinned order, then the rest by name. */
    static List<App> pinnedFirst(List<App> apps, List<String> pinned) {
        List<App> out = pinned(apps, pinned);
        for (App a : apps) if (!out.contains(a)) out.add(a);
        return out;
    }

    private static Drawable loadBanner(Context context, PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources res = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // At least xhdpi artwork, so the focus zoom does not show a soft upscale.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, context.getResources().getDisplayMetrics().densityDpi);
                banner = res.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        return banner != null && isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight()) ? banner : null;
    }

    /**
     * Some apps declare a small square image as their banner. Cropped to a 16:9 tile it is a blur, so it counts
     * as no banner. A drawable with no intrinsic size (a colour, a shape) still fills the tile.
     */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }
}
