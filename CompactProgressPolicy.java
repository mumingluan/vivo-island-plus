package com.axuan.vivoislandplus;

/** Platform progress semantics shared by native and fallback island transports. */
public final class CompactProgressPolicy {
    private CompactProgressPolicy() { }

    static String critical(String explicit, int value, int max, boolean indeterminate) {
        if (explicit != null && !explicit.isEmpty()) return explicit;
        if (indeterminate || max <= 0) return "";
        return Math.max(0, Math.min(value, max)) + "/" + max;
    }

    static int percent(int value, int max) {
        if (max <= 0) return 0;
        return (int) Math.max(0, Math.min(100, (long) value * 100 / max));
    }

    static boolean loading(boolean indeterminate, boolean hasCritical, boolean chronometer) {
        return indeterminate && !hasCritical && !chronometer;
    }
}
