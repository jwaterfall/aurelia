package app.aurelia.tv.update;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

import app.aurelia.tv.MainActivity;

/**
 * Installing a new version (an update, or adb install) ends the running launcher, and Android starts Home again
 * only when the Home button is pressed, which some remotes do not have. So once the new version is in place, it
 * opens itself, while it is the TV's Home.
 */
public final class UpdatedReceiver extends BroadcastReceiver {

    private static final String TAG = "Updates";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()) || !isHome(context)) return;
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName(context, MainActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not reopen Home after the update", e);
        }
    }

    private static boolean isHome(Context context) {
        ResolveInfo home = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return home != null && home.activityInfo != null
                && context.getPackageName().equals(home.activityInfo.packageName);
    }
}
