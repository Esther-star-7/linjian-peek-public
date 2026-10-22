package dev.linjian.peek;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(
        sdk = {34, 35},
        manifest = Config.NONE)
public class SensitiveDataTest {
    private Context context;
    private static final String FIXTURE = "fixture-only-private+/token\"value";

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        AppPrefs.get(context).edit().clear().putString(AppPrefs.KEY_TOKEN, FIXTURE).commit();
        SensitiveData.connectionVisible = false;
    }

    @After
    public void cleanup() {
        SensitiveData.connectionVisible = false;
    }

    @Test
    public void passwordNeverDrawsPlaintextIncludingLastTypedCharacter() {
        EditText field = new EditText(context);
        SensitiveData.protect(field);
        field.setText(FIXTURE);
        assertEquals(
                FIXTURE, field.getText().toString()); // authentication still receives the original
        CharSequence display =
                field.getTransformationMethod().getTransformation(field.getText(), field);
        assertEquals(FIXTURE.length(), display.length());
        assertTrue(display.toString().matches("\\u2022+"));
        field.append("Z");
        assertEquals('\u2022', display.charAt(display.length() - 1));
        assertEquals(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                field.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertFalse(field.isSaveEnabled());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, field.getImportantForAccessibility());
        assertEquals(
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                field.getImportantForAutofill());
    }

    @Test
    public void directAccessibilityNodeAndEventsCannotReadToken() {
        EditText field = new EditText(context);
        SensitiveData.protect(field);
        field.setText(FIXTURE);
        AccessibilityNodeInfo node = field.createAccessibilityNodeInfo();
        assertTrue(node.isPassword());
        assertFalse(String.valueOf(node.getText()).contains(FIXTURE));
        assertEquals("[Token 已隐藏]", node.getText().toString());
        AccessibilityEvent event =
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
        event.getText().add(FIXTURE);
        event.setBeforeText(FIXTURE);
        event.setContentDescription(FIXTURE);
        field.getAccessibilityDelegate().onInitializeAccessibilityEvent(field, event);
        assertTrue(event.getText().isEmpty());
        assertNull(event.getBeforeText());
        assertNull(event.getContentDescription());
    }

    @Test
    public void collectorSkipsPasswordAndOwnTokenButPreservesNormalNodes() {
        ScreenshotService service =
                Robolectric.buildService(ScreenshotService.class).create().get();
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
        root.setText("ordinary button");
        root.setClickable(true);
        AccessibilityNodeInfo password = AccessibilityNodeInfo.obtain();
        password.setText(FIXTURE);
        password.setPassword(true);
        AccessibilityNodeInfo token = AccessibilityNodeInfo.obtain();
        token.setText(FIXTURE);
        token.setViewIdResourceName("dev.linjian.peek:id/tokenInput");
        JSONArray nodes = new JSONArray();
        StringBuilder text = new StringBuilder();
        ClassParameterCollector.collect(service, password, text, nodes);
        ClassParameterCollector.collect(service, token, text, nodes);
        assertEquals(0, nodes.length());
        assertEquals("", text.toString());
        ClassParameterCollector.collect(service, root, text, nodes);
        assertEquals(1, nodes.length());
        assertTrue(text.toString().contains("ordinary button"));
    }

    private static class ClassParameterCollector {
        static void collect(
                ScreenshotService service,
                AccessibilityNodeInfo node,
                StringBuilder text,
                JSONArray nodes) {
            ReflectionHelpers.callInstanceMethod(
                    service,
                    "collect",
                    ReflectionHelpers.ClassParameter.from(AccessibilityNodeInfo.class, node),
                    ReflectionHelpers.ClassParameter.from(StringBuilder.class, text),
                    ReflectionHelpers.ClassParameter.from(JSONArray.class, nodes),
                    ReflectionHelpers.ClassParameter.from(int.class, 0),
                    ReflectionHelpers.ClassParameter.from(int.class, 0));
        }
    }

    @Test
    public void logStorageAndReadbackRedactRawEncodedAndStructuredCredentials() throws Exception {
        DebugState.append(
                context,
                "raw=" + FIXTURE + " encoded=" + java.net.URLEncoder.encode(FIXTURE, "UTF-8"));
        String stored = AppPrefs.get(context).getString("debug_text", "");
        assertFalse(stored.contains(FIXTURE));
        assertFalse(stored.contains(java.net.URLEncoder.encode(FIXTURE, "UTF-8")));
        assertTrue(stored.contains("[REDACTED]"));
        DebugState.append(
                context,
                "Authorization: Bearer unrelated-fixture; token=other-fixture"
                        + " https://user:pass@example.invalid");
        String output = DebugState.get(context);
        assertFalse(output.contains("unrelated-fixture"));
        assertFalse(output.contains("other-fixture"));
        assertFalse(output.contains("user:pass"));
        AppPrefs.get(context).edit().putString("debug_text", FIXTURE).commit();
        assertFalse(DebugState.get(context).contains(FIXTURE));
    }

    @Test
    public void oldDiagnosticsAreClearedOnceAndNewLogsRemainUsable() {
        AppPrefs.get(context)
                .edit()
                .putString("debug_text", "old potentially truncated secret")
                .commit();
        assertFalse(DebugState.get(context).contains("old potentially truncated"));
        DebugState.append(context, "HTTP 403");
        assertTrue(DebugState.get(context).contains("HTTP 403"));
    }

    @Test
    public void errorMessagesNeverEchoServerPayloadOrCredentialUrl() {
        assertEquals(
                "IllegalStateException",
                ScreenshotService.shortMsg(
                        new IllegalStateException("https://user:" + FIXTURE + "@example.invalid")));
    }

    @Test
    public void secureWindowIsLimitedToConnectionSettings() {
        WindowProbe activity = Robolectric.buildActivity(WindowProbe.class).create().get();
        SensitiveData.updateWindow(activity, true, true);
        assertTrue(
                (activity.getWindow().getAttributes().flags
                                & WindowManager.LayoutParams.FLAG_SECURE)
                        != 0);
        assertTrue(SensitiveData.connectionVisible);
        int changes = activity.changes;
        SensitiveData.updateWindow(activity, true, true);
        assertEquals(
                changes,
                activity.changes); // global-layout callbacks must not trigger relayout loops
        SensitiveData.updateWindow(activity, true, false);
        assertFalse(SensitiveData.connectionVisible);
        assertTrue(
                (activity.getWindow().getAttributes().flags
                                & WindowManager.LayoutParams.FLAG_SECURE)
                        != 0);
        SensitiveData.updateWindow(activity, false, true);
        assertEquals(
                0,
                activity.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_SECURE);
    }

    public static class WindowProbe extends Activity {
        int changes;

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
            super.onWindowAttributesChanged(params);
            changes++;
        }
    }

    public static class ScreenshotProbe extends ScreenshotService {
        boolean requested;

        @Override
        public void takeScreenshot(
                int display,
                java.util.concurrent.Executor executor,
                TakeScreenshotCallback callback) {
            requested = true;
        }
    }

    @Test
    public void screenshotRequestIsBlockedOnConnectionPageOnly() {
        ScreenshotProbe service = Robolectric.buildService(ScreenshotProbe.class).create().get();
        SensitiveData.connectionVisible = true;
        service.doScreenshot("http://localhost", FIXTURE);
        assertFalse(service.requested);
        SensitiveData.connectionVisible = false;
        service.doScreenshot("http://localhost", FIXTURE);
        assertTrue(service.requested);
    }

    @Test
    public void failingHttpResponseKeepsStatusButNeverLogsResponseBody() throws Exception {
        java.util.concurrent.CountDownLatch polled = new java.util.concurrent.CountDownLatch(1);
        com.sun.net.httpserver.HttpServer backend =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            ("UNTRUSTED_ECHO_MUST_NOT_BE_LOGGED " + FIXTURE)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(403, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    if (exchange.getRequestURI().getPath().equals("/api/poll")) polled.countDown();
                });
        backend.start();
        org.robolectric.android.controller.ServiceController<CompanionService> service = null;
        try {
            AppPrefs.get(context)
                    .edit()
                    .putString(
                            AppPrefs.KEY_SERVER,
                            "http://127.0.0.1:" + backend.getAddress().getPort())
                    .putBoolean(AppPrefs.KEY_WAKE_ENABLED, true)
                    .putBoolean(AppPrefs.KEY_WAKE_QUIET, false)
                    .commit();
            AppPrefs.saveServiceRequested(context, true);
            service = Robolectric.buildService(CompanionService.class).create();
            service.get().onStartCommand(new android.content.Intent(), 0, 1);
            assertTrue(polled.await(8, java.util.concurrent.TimeUnit.SECONDS));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!DebugState.get(context).contains("HTTP 403") && System.nanoTime() < deadline)
                Thread.sleep(10);
            String log = DebugState.get(context);
            assertTrue(log.contains("HTTP 403"));
            assertFalse(log.contains("UNTRUSTED_ECHO_MUST_NOT_BE_LOGGED"));
            assertFalse(log.contains(FIXTURE));
        } finally {
            if (service != null) service.destroy();
            backend.stop(0);
        }
    }

    @Test
    public void blockedRemoteScreenshotReportsFailureWithoutExposingCredentials() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> report =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer backend =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext(
                "/",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8);
                    if (exchange.getRequestURI().getPath().equals("/api/device/report"))
                        report.set(body);
                    byte[] response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        backend.start();
        try {
            SensitiveData.connectionVisible = true;
            CompanionService.handleCommandBody(
                    context,
                    "{\"command\":{\"id\":\"security-fixture\",\"action\":\"peek\"}}",
                    "http://127.0.0.1:" + backend.getAddress().getPort(),
                    FIXTURE);
            assertNotNull(report.get());
            org.json.JSONObject result = new org.json.JSONObject(report.get());
            assertFalse(result.getBoolean("ok"));
            assertEquals("screenshot_blocked_sensitive_settings", result.getString("result"));
            assertFalse(report.get().contains(FIXTURE));
        } finally {
            backend.stop(0);
        }
    }
}
