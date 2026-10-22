package dev.linjian.peek;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(
        sdk = {34, 35},
        manifest = Config.NONE)
public class WakeLifecycleTest {
    private Context context;
    private ServiceController<CompanionService> controller;

    @Before
    public void setup() {
        // Each test represents a fresh app process, including its once-per-process exit check.
        org.robolectric.util.ReflectionHelpers.setStaticField(AppPrefs.class, "exitChecked", false);
        context = RuntimeEnvironment.getApplication();
        android.provider.Settings.Global.putInt(context.getContentResolver(), "boot_count", 100);
        AppPrefs.get(context).edit().clear().commit();
        context.deleteDatabase("autonomous_wake_v1.db");
        AppPrefs.get(context)
                .edit()
                .putBoolean(AppPrefs.KEY_WAKE_ENABLED, true)
                .putBoolean(AppPrefs.KEY_WAKE_QUIET, false)
                .commit();
        Shadows.shadowOf((Application) context)
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }

    @After
    public void cleanup() {
        if (controller != null) controller.destroy();
    }

    private CompanionService start() {
        AppPrefs.saveServiceRequested(context, true);
        controller = Robolectric.buildService(CompanionService.class).create();
        CompanionService s = controller.get();
        assertEquals(Service.START_STICKY, s.onStartCommand(new Intent(), 0, 1));
        assertTrue(CompanionService.isRunning());
        return s;
    }

    @Test
    public void manualStopBlocksStickyBootUpdateAndTimer() {
        CompanionService service = start();
        assertEquals(
                Service.START_NOT_STICKY,
                service.onStartCommand(new Intent().setAction("STOP"), 0, 2));
        assertFalse(AppPrefs.serviceRequested(context));
        assertFalse(CompanionService.isRunning());
        AppPrefs.get(context).edit().putBoolean(AppPrefs.KEY_WAKE_RESTORE, true).commit();
        WakeReceiver receiver = new WakeReceiver();
        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        receiver.onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        receiver.onReceive(context, new Intent(WakeRuntime.TIMER));
        assertNull(Shadows.shadowOf((Application) context).getNextStartedService());
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 3));
    }

    @Test
    public void bootRecoveryRequiresOptInAndPreviousStart() {
        AppPrefs.saveServiceRequested(context, true);
        new WakeReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(Shadows.shadowOf((Application) context).getNextStartedService());
        AppPrefs.get(context).edit().putBoolean(AppPrefs.KEY_WAKE_RESTORE, true).commit();
        new WakeReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent start = Shadows.shadowOf((Application) context).getNextStartedService();
        assertNotNull(start);
        assertEquals(CompanionService.class.getName(), start.getComponent().getClassName());
    }

    @Test
    public void timerWithoutServicePersistsButDoesNotBypassBackgroundStartLimits() {
        AppPrefs.saveServiceRequested(context, true);
        new WakeReceiver().onReceive(context, new Intent(WakeRuntime.TIMER));
        try (WakeQueue q = new WakeQueue(context)) {
            assertEquals(1, q.pending());
        }
        assertNull(Shadows.shadowOf((Application) context).getNextStartedService());
    }

    @Test
    public void simulationCreatesLocalNotificationAndKeepsOutbox() {
        CompanionService service = start();
        WakeRuntime.simulate(service);
        WakeRuntime.dispatch(service);
        try (WakeQueue q = new WakeQueue(context)) {
            assertEquals(0, q.pending());
            assertTrue(q.unsynced() > 0);
        }
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        assertTrue(Shadows.shadowOf(nm).getAllNotifications().size() > 0);
        int count = Shadows.shadowOf(nm).getAllNotifications().size();
        WakeRuntime.dispatch(service);
        assertEquals(count, Shadows.shadowOf(nm).getAllNotifications().size());
    }

    @Test
    public void disabledMasterDoesNotConsumeOrDiscardQueue() {
        CompanionService service = start();
        WakeRuntime.simulate(service);
        AppPrefs.get(context).edit().putBoolean(AppPrefs.KEY_WAKE_ENABLED, false).commit();
        WakeRuntime.dispatch(service);
        try (WakeQueue q = new WakeQueue(context)) {
            assertTrue(q.pending() > 0);
        }
        assertTrue(
                CompanionService
                        .isRunning()); // existing remote service is independent of master switch
    }

    @Test
    public void deniedNotificationPermissionKeepsEventForRetry() {
        CompanionService service = start();
        Shadows.shadowOf((Application) context)
                .denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        WakeRuntime.simulate(service);
        WakeRuntime.dispatch(service);
        try (WakeQueue q = new WakeQueue(context)) {
            assertTrue(q.pending() > 0);
            assertTrue(q.unsynced() > 0);
            assertNull(q.next(System.currentTimeMillis()));
        }
    }

    @Test
    public void systemTimeoutDoesNotClearUserIntentOrTriggerRecoveryLoop() {
        CompanionService service = start();
        service.onTimeout(1, 0);
        assertTrue(AppPrefs.serviceRequested(context));
        assertTrue(AppPrefs.get(context).getBoolean(AppPrefs.KEY_RECOVERY_PENDING, false));
        assertFalse(CompanionService.recover(context, "watchdog", false));
        assertNull(Shadows.shadowOf((Application) context).getNextStartedService());
    }

    @Test
    public void screenBroadcastsDeduplicateAndPauseWithMasterSwitch() {
        CompanionService service = start();
        service.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        service.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        try (WakeQueue q = new WakeQueue(context);
                android.database.Cursor c =
                        q.getReadableDatabase()
                                .rawQuery(
                                        "SELECT COUNT(*) FROM events WHERE kind='screen_off'",
                                        null)) {
            c.moveToFirst();
            assertEquals(1, c.getInt(0));
        }
        AppPrefs.get(context).edit().putBoolean(AppPrefs.KEY_WAKE_ENABLED, false).commit();
        service.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        try (WakeQueue q = new WakeQueue(context);
                android.database.Cursor c =
                        q.getReadableDatabase()
                                .rawQuery(
                                        "SELECT COUNT(*) FROM events WHERE kind='screen_on'",
                                        null)) {
            c.moveToFirst();
            assertEquals(0, c.getInt(0));
        }
    }

    @Test
    public void remoteNoopStillPollsAndReportsAgainstLocalFixture() throws Exception {
        java.util.concurrent.CountDownLatch reported = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean authenticated =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        com.sun.net.httpserver.HttpServer backend =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    exchange.getRequestBody().readAllBytes();
                    String response = "{}";
                    if (path.equals("/api/poll")) {
                        authenticated.set(
                                "fixture-only"
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("X-Auth-Token")));
                        response = "{\"command\":{\"id\":\"fixture-command\",\"action\":\"noop\"}}";
                    }
                    byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    if (path.equals("/api/device/report")) reported.countDown();
                });
        backend.start();
        try {
            AppPrefs.get(context)
                    .edit()
                    .putString(
                            AppPrefs.KEY_SERVER,
                            "http://127.0.0.1:" + backend.getAddress().getPort())
                    .putString(AppPrefs.KEY_TOKEN, "fixture-only")
                    .commit();
            start();
            assertTrue(
                    "existing poll/report path must remain functional",
                    reported.await(8, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(authenticated.get());
        } finally {
            backend.stop(0);
        }
    }

    private void recordExit(int reason, long timestamp) {
        android.app.ApplicationExitInfo exit = new android.app.ApplicationExitInfo();
        exit.setReason(reason);
        exit.setTimestamp(timestamp);
        exit.setPackageName(context.getPackageName());
        Shadows.shadowOf(
                        (android.app.ActivityManager)
                                context.getSystemService(Context.ACTIVITY_SERVICE))
                .addApplicationExitInfo(exit);
    }

    @Test
    public void taskManagerUserStopPreventsWatchdogResurrection() {
        AppPrefs.saveServiceRequested(context, true);
        recordExit(
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED,
                System.currentTimeMillis() + 1000);
        assertFalse(CompanionService.recover(context, "watchdog", false));
        assertFalse(AppPrefs.serviceRequested(context));
        assertNull(Shadows.shadowOf((Application) context).getNextStartedService());
    }

    @Test
    public void oldUserStopDoesNotOverrideNewManualStart() {
        recordExit(
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED,
                System.currentTimeMillis() - 1);
        AppPrefs.saveServiceRequested(context, true);
        assertTrue(CompanionService.recover(context, "manual_start", true));
        assertTrue(AppPrefs.serviceRequested(context));
    }

    @Test
    public void processCrashPreservesRequestedState() {
        AppPrefs.saveServiceRequested(context, true);
        recordExit(android.app.ApplicationExitInfo.REASON_CRASH, System.currentTimeMillis() + 1000);
        AppPrefs.respectSystemStop(context);
        assertTrue(AppPrefs.serviceRequested(context));
    }

    @Test
    public void quietTimeDefersSimulationUntilMorning() {
        CompanionService service = start();
        java.util.Calendar at = java.util.Calendar.getInstance();
        if (at.get(java.util.Calendar.HOUR_OF_DAY) >= 23) at.add(java.util.Calendar.DAY_OF_YEAR, 1);
        at.set(java.util.Calendar.HOUR_OF_DAY, 23);
        at.set(java.util.Calendar.MINUTE, 0);
        at.set(java.util.Calendar.SECOND, 0);
        AppPrefs.get(context).edit().putBoolean(AppPrefs.KEY_WAKE_QUIET, true).commit();
        long id = WakeRuntime.simulate(service);
        WakeRuntime.dispatch(service, at.getTimeInMillis());
        try (WakeQueue q = new WakeQueue(context);
                android.database.Cursor c =
                        q.getReadableDatabase()
                                .rawQuery(
                                        "SELECT state FROM events WHERE _id=?",
                                        new String[] {"" + id})) {
            assertTrue(c.moveToFirst());
            assertEquals("pending", c.getString(0));
        }
        at.add(java.util.Calendar.HOUR_OF_DAY, 9);
        WakeRuntime.dispatch(service, at.getTimeInMillis());
        try (WakeQueue q = new WakeQueue(context);
                android.database.Cursor c =
                        q.getReadableDatabase()
                                .rawQuery(
                                        "SELECT state,sync_state FROM events WHERE _id=?",
                                        new String[] {"" + id})) {
            assertTrue(c.moveToFirst());
            assertEquals("delivered", c.getString(0));
            assertEquals("pending", c.getString(1));
        }
    }

    @Test
    public void watchdogCanRecoverWithinSameBootWithoutBootOptIn() {
        AppPrefs.saveServiceRequested(context, true);
        assertTrue(CompanionService.recover(context, "watchdog", false));
        assertNotNull(Shadows.shadowOf((Application) context).getNextStartedService());
    }

    @Test
    public void watchdogCannotBypassDisabledBootRecoveryBeforeBootBroadcast() {
        AppPrefs.saveServiceRequested(context, true);
        android.provider.Settings.Global.putInt(context.getContentResolver(), "boot_count", 101);
        assertFalse(CompanionService.recover(context, "watchdog", false));
        assertTrue(AppPrefs.serviceRequested(context));
        assertNull(Shadows.shadowOf((Application) context).getNextStartedService());
    }

    @Test
    public void updateRecoveryGateAlsoAppliesToWatchdog() {
        AppPrefs.saveServiceRequested(context, true);
        AppPrefs.get(context).edit().putLong("service_package_updated_at", -123).commit();
        assertFalse(CompanionService.recover(context, "watchdog", false));
        assertTrue(CompanionService.recover(context, "activity_visible", true));
    }

    @Test
    public void enabledRecoveryAllowsNewBoot() {
        AppPrefs.saveServiceRequested(context, true);
        AppPrefs.get(context).edit().putBoolean(AppPrefs.KEY_WAKE_RESTORE, true).commit();
        android.provider.Settings.Global.putInt(context.getContentResolver(), "boot_count", 101);
        new WakeReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNotNull(Shadows.shadowOf((Application) context).getNextStartedService());
    }

    @Test
    public void stickyRestartAlsoRespectsDisabledBootRecovery() {
        AppPrefs.saveServiceRequested(context, true);
        android.provider.Settings.Global.putInt(context.getContentResolver(), "boot_count", 101);
        controller = Robolectric.buildService(CompanionService.class).create();
        assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1));
        assertFalse(CompanionService.isRunning());
    }
}
