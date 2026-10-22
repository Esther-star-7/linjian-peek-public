package dev.linjian.peek;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class WakeQueueTest {
    private Context context;
    private WakeQueue queue;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("autonomous_wake_v1.db");
        AppPrefs.get(context).edit().clear().commit();
        queue = new WakeQueue(context);
    }

    @After
    public void close() {
        queue.close();
    }

    @Test
    public void duplicateSurvivesReopenAndDelivery() {
        long id = queue.enqueue("screen:1", "screen_off", "{}", 20, 1000);
        assertTrue(id > 0);
        assertEquals(-1, queue.enqueue("screen:1", "screen_off", "{}", 20, 1000));
        queue.close();
        queue = new WakeQueue(context);
        assertEquals(1, queue.pending());
        assertEquals("screen:1", queue.next(1000).key);
        assertEquals("{}", queue.next(1000).payload);
        assertEquals(20, queue.next(1000).priority);
        assertEquals(1000, queue.next(1000).created);
        assertEquals(id, queue.next(1000).id);
        queue.delivered(queue.next(1000), 1000, 60000);
        assertEquals(0, queue.pending());
        assertEquals(1, queue.unsynced());
        assertEquals(-1, queue.enqueue("screen:1", "screen_off", "{}", 20, 2000));
    }

    @Test
    public void readyPriorityDoesNotLoseLowPriorityEvents() {
        long low = queue.enqueue("low", "screen_on", "{}", 10, 1000);
        long high = queue.enqueue("high", "battery", "{}", 80, 1001);
        assertEquals(high, queue.next(1001).id);
        queue.delivered(queue.next(1001), 1001, 60000);
        assertEquals(low, queue.next(1001).id);
    }

    @Test
    public void blockedNotificationRetriesPersistAndRespectDueTime() {
        queue.enqueue("retry", "timer", "{}", 40, 1000);
        queue.retry(queue.next(1000), 1000, "notifications_disabled");
        queue.close();
        queue = new WakeQueue(context);
        assertNull(queue.next(30999));
        WakeQueue.Event event = queue.next(31000);
        assertNotNull(event);
        assertEquals(1, event.attempts);
        queue.retry(event, 31000, "notifications_disabled");
        assertNull(queue.next(90999));
        assertEquals(2, queue.next(91000).attempts);
        assertEquals(1, queue.pending());
        assertEquals(1, queue.unsynced());
    }

    @Test
    public void crashBeforeAckReplaysSameIdAndCooldownSurvivesReopen() {
        long first = queue.enqueue("first", "screen_off", "{}", 20, 1000);
        queue.enqueue("second", "screen_off", "{}", 20, 1001);
        assertEquals(first, queue.next(1001).id);
        queue.close();
        queue = new WakeQueue(context);
        assertEquals(first, queue.next(1001).id); // crash before ack, same notification identity
        queue.delivered(queue.next(1001), 1001, 60000);
        queue.close();
        queue = new WakeQueue(context);
        assertNull(queue.next(61000));
        assertNotNull(queue.next(61001));
        assertEquals(61001, queue.nextDue(100000));
    }

    @Test
    public void offlineOutboxIsRetainedAfterLocalDelivery() {
        queue.enqueue("offline", "simulation", "{}", 100, 1000);
        queue.delivered(queue.next(1000), 1000, 0);
        queue.close();
        queue = new WakeQueue(context);
        assertEquals(0, queue.pending());
        assertEquals(1, queue.unsynced());
        try (Cursor c =
                queue.getReadableDatabase().rawQuery("SELECT state,sync_state FROM events", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("delivered", c.getString(0));
            assertEquals("pending", c.getString(1));
        }
    }

    @Test
    public void fullQueueRejectsNewEventsWithoutEviction() {
        queue.getWritableDatabase().beginTransaction();
        try {
            for (int i = 0; i < WakeQueue.CAPACITY; i++)
                queue.getWritableDatabase()
                        .execSQL(
                                "INSERT INTO events(dedupe,kind,payload,priority,created,due)"
                                        + " VALUES(?, 'timer', '{}', 40, 1000, 1000)",
                                new Object[] {"key" + i});
            queue.getWritableDatabase().setTransactionSuccessful();
        } finally {
            queue.getWritableDatabase().endTransaction();
        }
        assertEquals(-2, queue.enqueue("overflow", "timer", "{}", 40, 1000));
        assertEquals(WakeQueue.CAPACITY, queue.pending());
        assertEquals(-1, queue.enqueue("key1", "timer", "{}", 40, 1000));
    }

    @Test
    public void manualStopWinsOverRecoveryUntilExplicitStart() {
        assertFalse(AppPrefs.serviceRequested(context));
        assertTrue(AppPrefs.saveServiceRequested(context, true));
        assertTrue(AppPrefs.serviceRequested(context));
        AppPrefs.get(context)
                .edit()
                .putBoolean(AppPrefs.KEY_WAKE_ENABLED, true)
                .putBoolean(AppPrefs.KEY_WAKE_RESTORE, true)
                .commit();
        assertTrue(AppPrefs.wakeActive(context));
        assertTrue(AppPrefs.saveServiceRequested(context, false));
        assertFalse(AppPrefs.serviceRequested(context));
        assertFalse(AppPrefs.wakeActive(context));
        assertTrue(AppPrefs.get(context).getBoolean(AppPrefs.KEY_WAKE_RESTORE, false));
        assertTrue(AppPrefs.saveServiceRequested(context, true));
        assertTrue(AppPrefs.wakeActive(context));
    }

    @Test
    public void legacyMigrationRequiresExplicitNonStoppedState() {
        assertFalse(AppPrefs.serviceRequested(context));
        AppPrefs.get(context).edit().putBoolean("user_stopped", false).commit();
        assertTrue(AppPrefs.serviceRequested(context));
        AppPrefs.get(context).edit().putBoolean("user_stopped", true).commit();
        assertFalse(AppPrefs.serviceRequested(context));
    }

    @Test
    public void quietWindowAndRetryBounds() {
        assertTrue(WakePolicy.quiet(true, 23, 8, 23));
        assertTrue(WakePolicy.quiet(true, 23, 8, 7));
        assertFalse(WakePolicy.quiet(true, 23, 8, 8));
        assertFalse(WakePolicy.quiet(true, 23, 8, 22));
        assertFalse(WakePolicy.quiet(false, 23, 8, 2));
        assertTrue(WakePolicy.quiet(true, 9, 17, 12));
        assertFalse(WakePolicy.quiet(true, 9, 17, 18));
        assertEquals(30000, WakePolicy.retryDelay(1));
        assertEquals(60000, WakePolicy.retryDelay(2));
        assertEquals(21600000, WakePolicy.retryDelay(100));
    }
}
