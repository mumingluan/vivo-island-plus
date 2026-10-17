package com.axuan.vivoislandplus;

/** Progress contract regressions, independent of Android views. */
public final class CompactProgressPolicyTest {
    private static int passed;
    private static void expect(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " got " + actual);
        passed++;
    }

    public static void main(String[] args) {
        // Unknown progress must not invent a fraction, even with a stale total.
        expect("", CompactProgressPolicy.critical("", 0, 100, true));
        expect("", CompactProgressPolicy.critical(null, 50, 100, true));
        expect("", CompactProgressPolicy.critical("", 50, 0, false));
        expect("", CompactProgressPolicy.critical("", 50, -1, false));
        expect("正在连接", CompactProgressPolicy.critical("正在连接", 0, 100, true));
        expect("7 min", CompactProgressPolicy.critical("7 min", 50, 100, false));
        expect("25/100", CompactProgressPolicy.critical("", 25, 100, false));
        expect("0/100", CompactProgressPolicy.critical("", -20, 100, false));
        expect("100/100", CompactProgressPolicy.critical("", 120, 100, false));
        expect("2147483647/2147483647", CompactProgressPolicy.critical("", Integer.MAX_VALUE, Integer.MAX_VALUE, false));
        expect(0, CompactProgressPolicy.percent(1, 0));
        expect(0, CompactProgressPolicy.percent(1, -1));
        expect(0, CompactProgressPolicy.percent(-1, 100));
        expect(0, CompactProgressPolicy.percent(Integer.MIN_VALUE, Integer.MAX_VALUE));
        expect(25, CompactProgressPolicy.percent(25, 100));
        expect(33, CompactProgressPolicy.percent(1, 3));
        expect(100, CompactProgressPolicy.percent(120, 100));
        expect(100, CompactProgressPolicy.percent(Integer.MAX_VALUE, Integer.MAX_VALUE));
        expect(99, CompactProgressPolicy.percent(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
        for (boolean indeterminate : new boolean[]{false, true})
            for (boolean critical : new boolean[]{false, true})
                for (boolean clock : new boolean[]{false, true})
                    expect(indeterminate && !critical && !clock,
                            CompactProgressPolicy.loading(indeterminate, critical, clock));
        System.out.println("PASS " + passed + " offline compact-progress regression checks");
    }
}
