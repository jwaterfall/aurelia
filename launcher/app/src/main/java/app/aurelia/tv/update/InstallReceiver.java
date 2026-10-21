package app.aurelia.tv.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * Android's installer answering an update {@link Updates#install} started: when it wants the user to confirm,
 * its confirmation screen is shown; anything else (installed, or failed) is only logged, as the new version
 * opens itself when it is in place.
 */
public final class InstallReceiver extends BroadcastReceiver {

    private static final String TAG = "Updates";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            try {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not ask to confirm the update", e);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Log.w(TAG, "Update not installed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
