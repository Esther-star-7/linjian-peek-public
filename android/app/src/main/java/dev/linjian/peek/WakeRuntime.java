package dev.linjian.peek;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.util.Calendar;

/** Event driven adapter owned by CompanionService; no network client or second poll loop. */
public final class WakeRuntime {
    static final String TIMER = "dev.linjian.peek.WAKE_TIMER";
    private static final String CHANNEL = "linjian_autonomous_wake_v1";
    private static final long PERIOD = 15 * 60_000L;
    private static final String NEXT_TIMER = "wake_next_timer";
    private final CompanionService service;
    private BroadcastReceiver receiver;

    public WakeRuntime(CompanionService service) {
        this.service = service;
    }

    public void start() {
        if (receiver != null) return;
        receiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (!AppPrefs.wakeActive(c)) return;
                        String action = i.getAction();
                        long now = System.currentTimeMillis();
                        if (Intent.ACTION_SCREEN_ON.equals(action)
                                || Intent.ACTION_SCREEN_OFF.equals(action)) {
                            String kind =
                                    Intent.ACTION_SCREEN_ON.equals(action)
                                            ? "screen_on"
                                            : "screen_off";
                            enqueue(c, kind, kind + ":" + (now / 30_000), "{}", 20);
                        } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                                    scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                            if (level < 0 || scale <= 0) return;
                            int bucket = (level * 100 / scale) / 10;
                            boolean charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                            String value = bucket + ":" + charging;
                            // Sticky battery snapshots are coalesced; repeated broadcasts do not
                            // fill the queue.
                            if (!value.equals(AppPrefs.get(c).getString("wake_last_battery", ""))) {
                                long id =
                                        enqueue(
                                                c,
                                                "battery",
                                                "battery:" + value + ":" + (now / 60_000),
                                                "{\"percent\":"
                                                        + (level * 100 / scale)
                                                        + ",\"charging\":"
                                                        + charging
                                                        + "}",
                                                bucket <= 2 ? 80 : 30);
                                if (id != -2)
                                    AppPrefs.get(c)
                                            .edit()
                                            .putString("wake_last_battery", value)
                                            .commit();
                            }
                        }
                        dispatch(c);
                    }
                };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33)
            service.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else service.registerReceiver(receiver, f);
        dispatch(service);
    }

    public void stop() {
        if (receiver != null) {
            service.unregisterReceiver(receiver);
            receiver = null;
        }
    }

    public static long enqueue(Context c, String kind, String key, String payload, int priority) {
        if (!AppPrefs.wakeActive(c)) return -1;
        try (WakeQueue q = new WakeQueue(c)) {
            long id = q.enqueue(key, kind, payload, priority, System.currentTimeMillis());
            if (id == -2)
                AppPrefs.get(c).edit().putString("wake_status", "队列已满，拒绝新事件；已接收事件仍保留").commit();
            log(c, "入队 " + kind + " #" + id);
            return id;
        } catch (RuntimeException e) {
            fault(c, e);
            return -2;
        }
    }

    public static void usage(Context c, String pkg) {
        if (!AppPrefs.wakeActive(c) || !AppPrefs.get(c).getBoolean(AppPrefs.KEY_WAKE_USAGE, false))
            return;
        // Called only from the already user-authorized AccessibilityService.
        if (!AppPrefs.isTargetPackage(c, pkg)) return;
        enqueue(
                c,
                "app_usage",
                "usage:" + pkg + ":" + (System.currentTimeMillis() / 60_000),
                "{\"package\":\"" + pkg + "\"}",
                10);
        CompanionService.requestWakeDispatch();
    }

    public static long simulate(Context c) {
        long id = enqueue(c, "simulation", "simulation:" + java.util.UUID.randomUUID(), "{}", 100);
        if (id > 0) CompanionService.requestWakeDispatch();
        return id;
    }

    public static void timer(Context c) {
        if (!AppPrefs.wakeActive(c)) {
            cancel(c);
            return;
        }
        long now = System.currentTimeMillis();
        long due = AppPrefs.get(c).getLong(NEXT_TIMER, 0);
        if (now >= due) {
            long id = enqueue(c, "timer", "timer:" + (now / PERIOD), "{}", 40);
            if (id != -2) AppPrefs.get(c).edit().putLong(NEXT_TIMER, now + PERIOD).commit();
        }
        // An inexact alarm is not a general exemption to start an FGS on Android 12+.
        // Persist even when the process was recreated; only an existing service consumes it.
        if (CompanionService.isRunning()) dispatch(c);
        else schedule(c);
    }

    public static void dispatch(Context c) {
        dispatch(c, System.currentTimeMillis());
    }

    /** One consistent wall-clock snapshot for due-time and quiet-time decisions. */
    static void dispatch(Context c, long nowMs) {
        if (!AppPrefs.wakeActive(c) || !CompanionService.isRunning()) return;
        PowerManager.WakeLock lock = null;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linjian:local-wake");
                lock.acquire(10_000);
            }
            Calendar now = Calendar.getInstance();
            now.setTimeInMillis(nowMs);
            if (WakePolicy.quiet(
                    AppPrefs.get(c).getBoolean(AppPrefs.KEY_WAKE_QUIET, true),
                    23,
                    8,
                    now.get(Calendar.HOUR_OF_DAY))) return;
            try (WakeQueue q = new WakeQueue(c)) {
                // Bound each pass on the service main thread; no model or HTTP work is permitted
                // here.
                for (int n = 0; n < 4 && AppPrefs.wakeActive(c); n++) {
                    WakeQueue.Event e = q.next(nowMs);
                    if (e == null) break;
                    if (notifyEvent(c, e)) {
                        q.delivered(e, nowMs, 60_000);
                        log(c, "本地模拟完成 #" + e.id + " " + e.kind + "；待同步记录保留");
                    } else {
                        q.retry(e, nowMs, "notifications_disabled");
                        log(c, "通知不可用，保留事件 #" + e.id);
                    }
                }
            }
        } catch (RuntimeException e) {
            fault(c, e);
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
            schedule(c);
        }
    }

    private static boolean notifyEvent(Context c, WakeQueue.Event e) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()))
            return false;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel(
                            CHANNEL, "自主唤醒 · 本地模拟", NotificationManager.IMPORTANCE_DEFAULT));
            if (nm.getNotificationChannel(CHANNEL).getImportance()
                    == NotificationManager.IMPORTANCE_NONE) return false;
        }
        PendingIntent open =
                PendingIntent.getActivity(
                        c,
                        0,
                        new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b =
                Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(c, CHANNEL)
                        : new Notification.Builder(c);
        nm.notify(
                "wake:" + e.id,
                1,
                b.setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("自主唤醒 · 模拟事件")
                        .setContentText(e.kind + " · #" + e.id + " · 仅本机处理，未连接 AI")
                        .setContentIntent(open)
                        .setOnlyAlertOnce(true)
                        .setAutoCancel(true)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .build());
        return true;
    }

    public static void schedule(Context c) {
        if (!AppPrefs.wakeActive(c)) {
            cancel(c);
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long due = AppPrefs.get(c).getLong(NEXT_TIMER, 0);
            if (due == 0) {
                due = now + PERIOD;
                AppPrefs.get(c).edit().putLong(NEXT_TIMER, due).commit();
            }
            try (WakeQueue q = new WakeQueue(c)) {
                due = q.nextDue(due);
            }
            Calendar time = Calendar.getInstance();
            if (WakePolicy.quiet(
                    AppPrefs.get(c).getBoolean(AppPrefs.KEY_WAKE_QUIET, true),
                    23,
                    8,
                    time.get(Calendar.HOUR_OF_DAY))) {
                if (time.get(Calendar.HOUR_OF_DAY) >= 23) time.add(Calendar.DAY_OF_YEAR, 1);
                time.set(Calendar.HOUR_OF_DAY, 8);
                time.set(Calendar.MINUTE, 0);
                time.set(Calendar.SECOND, 0);
                time.set(Calendar.MILLISECOND, 0);
                due = Math.max(due, time.getTimeInMillis());
            }
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null)
                am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, Math.max(now + 60_000, due), alarm(c));
        } catch (RuntimeException e) {
            fault(c, e);
        }
    }

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(
                c,
                31001,
                new Intent(c, WakeReceiver.class).setAction(TIMER),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarm(c));
    }

    private static void fault(Context c, RuntimeException e) {
        AppPrefs.get(c)
                .edit()
                .putString("wake_status", "本地事件异常：" + e.getClass().getSimpleName())
                .apply();
        log(c, "本地事件异常：" + e.getClass().getSimpleName());
    }

    static void log(Context c, String message) {
        if (AppPrefs.get(c).getBoolean(AppPrefs.KEY_WAKE_DEBUG, false))
            DebugState.append(c, "自主唤醒：" + message);
    }
}
