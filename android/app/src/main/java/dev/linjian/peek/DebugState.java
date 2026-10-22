package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DebugState {
    private static final String PREFS = "linjian_peek";
    private static final String KEY_DEBUG = "debug_text";

    public static synchronized void set(Context ctx, String message) {
        if (ctx == null) return;
        prepare(ctx);
        String line = now() + "  " + SensitiveData.redact(ctx, message);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DEBUG, line)
                .apply();
    }

    public static synchronized void append(Context ctx, String message) {
        if (ctx == null) return;
        prepare(ctx);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(KEY_DEBUG, "");
        String line = now() + "  " + SensitiveData.redact(ctx, message);
        old = SensitiveData.redact(ctx, old);
        String next = old == null || old.isEmpty() ? line : old + "\n" + line;
        String[] lines = next.split("\\n");
        if (lines.length > 12) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 12; i < lines.length; i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            next = sb.toString();
        }
        prefs.edit().putString(KEY_DEBUG, next).apply();
    }

    public static synchronized String get(Context ctx) {
        if (ctx == null) return "";
        prepare(ctx);
        return SensitiveData.redact(ctx, ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEBUG, "等待调试信息…"));
    }

    private static void prepare(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("debug_redaction_v1", false))
            p.edit().putBoolean("debug_redaction_v1", true).remove(KEY_DEBUG).commit();
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
    }
}
