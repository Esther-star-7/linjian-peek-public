package dev.linjian.peek;

import android.content.Context;
import android.os.Build;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;

import java.net.URLEncoder;

/**
 * Protection for this application's connection credential, not an arbitrary-screen secret detector.
 */
final class SensitiveData {
    static volatile boolean connectionVisible;

    private SensitiveData() {}

    static String redact(Context context, String text) {
        if (text == null) return "";
        String token = AppPrefs.token(context);
        if (!token.isEmpty()) {
            text = text.replace(token, "[REDACTED]");
            try {
                text = text.replace(URLEncoder.encode(token, "UTF-8"), "[REDACTED]");
                text = text.replace(android.net.Uri.encode(token), "[REDACTED]");
                String escaped = org.json.JSONObject.quote(token);
                text = text.replace(escaped.substring(1, escaped.length() - 1), "[REDACTED]");
            } catch (Exception ignored) {
            }
        }
        text = text.replaceAll("(?i)(Bearer\\s+)[^\\s\\\"',;]+", "$1[REDACTED]");
        text =
                text.replaceAll(
                        "(?i)((?:token|api[_-]?key|secret|password|x-auth-token)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"'&,;}]+",
                        "$1[REDACTED]");
        return text.replaceAll("(?i)(https?://)[^/\\s@]+@", "$1[REDACTED]@");
    }

    static void updateWindow(
            android.app.Activity activity, boolean connectionShown, boolean resumed) {
        connectionVisible = connectionShown && resumed;
        int flag = android.view.WindowManager.LayoutParams.FLAG_SECURE;
        boolean secure = (activity.getWindow().getAttributes().flags & flag) != 0;
        if (secure != connectionShown) {
            if (connectionShown) activity.getWindow().addFlags(flag);
            else activity.getWindow().clearFlags(flag);
        }
    }

    static boolean protectedNode(AccessibilityNodeInfo node) {
        return node.isPassword()
                || "dev.linjian.peek:id/tokenInput".equals(node.getViewIdResourceName());
    }

    static void protect(EditText field) {
        field.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Unlike the stock transformation this never briefly draws the last typed character.
        field.setTransformationMethod(
                new PasswordTransformationMethod() {
                    @Override
                    public CharSequence getTransformation(CharSequence source, View view) {
                        return new CharSequence() {
                            @Override
                            public int length() {
                                return source.length();
                            }

                            @Override
                            public char charAt(int index) {
                                if (index < 0 || index >= length())
                                    throw new IndexOutOfBoundsException();
                                return '\u2022';
                            }

                            @Override
                            public CharSequence subSequence(int start, int end) {
                                return toString().subSequence(start, end);
                            }

                            @Override
                            public String toString() {
                                char[] dots = new char[length()];
                                java.util.Arrays.fill(dots, '\u2022');
                                return new String(dots);
                            }
                        };
                    }
                });
        field.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        field.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) {
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            field.setImeOptions(
                    field.getImeOptions()
                            | android.view.inputmethod.EditorInfo
                                    .IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        field.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setText("[Token 已隐藏]");
                        info.setContentDescription(null);
                        info.setPassword(true);
                    }

                    @Override
                    public void onInitializeAccessibilityEvent(
                            View host, AccessibilityEvent event) {
                        super.onInitializeAccessibilityEvent(host, event);
                        scrub(event);
                    }

                    @Override
                    public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
                        scrub(event);
                    }

                    private void scrub(AccessibilityEvent event) {
                        event.getText().clear();
                        event.setBeforeText(null);
                        event.setContentDescription(null);
                        event.setPassword(true);
                    }
                });
    }
}
