package dev.linjian.peek;

/** Pure policy: no clocks, Android services or network calls. */
public final class WakePolicy {
    private WakePolicy() {}

    public static boolean mayRun(boolean requested, boolean stopped) {
        return requested && !stopped;
    }

    public static boolean quiet(boolean enabled, int startHour, int endHour, int hour) {
        if (!enabled) return false;
        if (startHour == endHour) return true;
        return startHour < endHour
                ? hour >= startHour && hour < endHour
                : hour >= startHour || hour < endHour;
    }

    public static long retryDelay(int attempts) {
        return Math.min(
                6 * 60 * 60_000L, 30_000L * (1L << Math.min(10, Math.max(0, attempts - 1))));
    }
}
