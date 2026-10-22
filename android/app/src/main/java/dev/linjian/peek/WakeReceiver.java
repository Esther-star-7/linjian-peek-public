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
        if (!AppPrefs.serviceRequested(context)) return;
        if (!AppPrefs.get(context).getBoolean(AppPrefs.KEY_WAKE_RESTORE, false)) {
            // Also gates the existing accessibility watchdog, including pre-24 boot-count fallback.
            AppPrefs.get(context).edit().putBoolean(AppPrefs.KEY_RECOVERY_PENDING, true).commit();
            return;
        }
        // A new boot or package replacement is a meaningful opportunity to retry once.
        AppPrefs.get(context)
                .edit()
                .putBoolean(AppPrefs.KEY_RECOVERY_PENDING, false)
                .putLong("service_last_recovery", 0)
                .commit();
        CompanionService.recover(context, action, false);
    }
}
