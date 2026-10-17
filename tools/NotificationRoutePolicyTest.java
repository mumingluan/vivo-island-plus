package com.axuan.vivoislandplus;

/** Contract regressions: auxiliary media metadata must not hide calls/progress. */
public final class NotificationRoutePolicyTest {
    private static int passed;
    private static void expect(String expected, String style, boolean ongoing, boolean group,
            boolean island, boolean promoted, boolean media, boolean progress, int callType, int max, boolean indeterminate) {
        String actual = NotificationRoutePolicy.route(style, ongoing, group, island, promoted, media, progress, callType, max, indeterminate);
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(style + ": expected " + expected + " got " + actual);
        passed++;
    }
    public static void main(String[] args) {
        String call = "android.app.Notification$CallStyle", progress = "android.app.Notification$ProgressStyle";
        for (boolean token : new boolean[]{false, true}) {
            expect("call", call, true, false, false, false, token, false, 2, 0, false);
            expect("call", call, true, false, false, true, token, false, 2, 0, false);
            expect("progress", progress, true, false, false, true, token, false, 0, 0, false);
            expect("progress", progress, true, false, false, false, token, true, 0, 100, false);
            expect("progress", "", true, false, false, false, token, true, 0, 100, false);
            expect("progress", "", true, false, false, false, token, true, 0, 0, true);
            expect(null, call, false, false, false, true, token, false, 2, 0, false);
            expect(null, call, true, true, false, true, token, false, 2, 0, false);
            expect(null, progress, true, false, true, true, token, true, 0, 100, false);
            expect(null, "android.app.Notification$MediaStyle", true, false, false, true, token, false, 0, 0, false);
            expect(null, "android.app.Notification$DecoratedMediaCustomViewStyle", true, false, false, true, token, false, 0, 0, false);
        }
        expect("live", "", true, false, false, true, false, false, 0, 0, false);
        expect("live", "", true, false, false, true, true, false, 0, 0, false);
        for (boolean token : new boolean[]{false, true}) {
            expect("live", "android.app.Notification$BigTextStyle", true, false, false, true, token, false, 0, 0, false);
            expect(null, "android.app.Notification$BigTextStyle", true, false, false, false, token, false, 0, 0, false);
        }
        expect(null, null, true, false, false, false, false, false, 0, 0, false);
        expect(null, call, true, false, false, false, false, false, 1, 0, false);
        expect(null, call, true, false, false, false, false, false, 3, 0, false);
        expect(null, progress, true, false, false, false, false, false, 0, 100, false);
        System.out.println("PASS " + passed + " offline notification-route regression checks");
    }
}
