package dev.linjian.peek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Non-exported, credential-protected boot/update recovery; never overrides a manual stop. */
public final class WakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        AppPrefs.respectSystemStop(context);
        String action = intent.getAction();
        if (WakeRuntime.TIMER.equals(action)) {
            WakeRuntime.timer(context);
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!AppPrefs.serviceRequested(context)
                || !AppPrefs.get(context).getBoolean(AppPrefs.KEY_WAKE_RESTORE, false)) return;
        CompanionService.recover(context, action, false);
    }
}
