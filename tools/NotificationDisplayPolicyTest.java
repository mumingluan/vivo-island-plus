package com.axuan.vivoislandplus;

public final class NotificationDisplayPolicyTest {
    private static int passed;
    private static final NotificationDisplayPolicy.State AWAKE = new NotificationDisplayPolicy.State(true, false, false);
    private static final NotificationDisplayPolicy.State DOZING = new NotificationDisplayPolicy.State(true, true, false);
    private static final NotificationDisplayPolicy.State FULL = new NotificationDisplayPolicy.State(true, false, true);
    private static void expect(boolean wanted, int importance, boolean suspended, boolean interruption,
            int effects, NotificationDisplayPolicy.State state, String category, boolean fgs, boolean media) {
        boolean actual = NotificationDisplayPolicy.allowed(
                new NotificationDisplayPolicy.Ranking(importance, suspended, interruption, effects), state, category, fgs, media, 512);
        if (actual != wanted) throw new AssertionError("effects=" + effects + " category=" + category
                + " state=" + state.dozing + "/" + state.fullDoze + " wanted=" + wanted);
        passed++;
    }
    public static void main(String[] args) {
        // Muted interruption is not, by itself, a visual suppression instruction.
        expect(true, 2, false, false, 0, AWAKE, "progress", false, false);
        expect(true, 2, false, false, 0, DOZING, "progress", false, false);
        expect(true, 2, false, false, 0, AWAKE, "call", true, false);
        expect(true, 2, false, false, 4 | 8 | 16 | 64, AWAKE, "call", true, false);
        expect(false, 2, false, true, 32, AWAKE, "progress", false, false);
        expect(false, 2, false, true, 256, AWAKE, "progress", false, false);
        expect(true, 2, false, false, 128, AWAKE, "progress", false, false);
        expect(false, 2, false, true, 128, DOZING, "progress", false, false);
        expect(true, 2, false, false, 256, DOZING, "progress", false, false);
        expect(false, 2, false, true, 128, FULL, "progress", false, false);
        expect(false, 2, false, true, 256, FULL, "progress", false, false);
        // Foreground-service/UIJ and actual MediaStyle exemptions follow 24.7.
        expect(true, 2, false, false, 32 | 128 | 256, AWAKE, "progress", true, false);
        expect(true, 2, false, false, 128, DOZING, "progress", true, false);
        expect(true, 2, false, false, 32 | 128 | 256, DOZING, "transport", false, true);
        for (String category : new String[]{"call", "msg", "alarm", "event", "reminder"}) {
            expect(false, 2, false, true, 32, AWAKE, category, true, true);
            expect(false, 2, false, true, 128, DOZING, category, true, true);
        }
        for (NotificationDisplayPolicy.State state : new NotificationDisplayPolicy.State[]{AWAKE, DOZING, FULL, NotificationDisplayPolicy.State.UNKNOWN}) {
            expect(false, 2, false, true, 512, state, "progress", true, true);
            expect(false, 5, true, true, 0, state, "progress", true, true);
        }
        for (int importance : new int[]{-1000, 0, 1}) expect(false, importance, false, true, 0, AWAKE, "call", true, false);
        expect(false, 2, false, false, 0, NotificationDisplayPolicy.State.UNKNOWN, "progress", true, false);
        expect(true, 2, false, true, 0, NotificationDisplayPolicy.State.UNKNOWN, "progress", false, false);
        expect(false, 2, false, true, 128, NotificationDisplayPolicy.State.UNKNOWN, "progress", false, false);
        expect(false, 2, false, true, 256, NotificationDisplayPolicy.State.UNKNOWN, "call", true, false);
        expect(true, 2, false, true, 256, NotificationDisplayPolicy.State.UNKNOWN, null, true, false);
        if (NotificationDisplayPolicy.allowed(null, AWAKE, "progress", false, false, 512)) throw new AssertionError("unknown ranking allowed");
        passed++;
        // Reevaluate the same cached facts when the display phase changes.
        NotificationDisplayPolicy.Ranking cached = new NotificationDisplayPolicy.Ranking(2, false, false, 128);
        if (!NotificationDisplayPolicy.allowed(cached, AWAKE, "progress", false, false, 512)
                || NotificationDisplayPolicy.allowed(cached, DOZING, "progress", false, false, 512))
            throw new AssertionError("cached ranking must not cache a phase-dependent verdict");
        passed++;
        for (int mask : new int[]{512, 1024, 2048, 4096}) {
            NotificationDisplayPolicy.Ranking suppressed = new NotificationDisplayPolicy.Ranking(3, false, true, mask);
            if (NotificationDisplayPolicy.allowed(suppressed, AWAKE, "progress", true, true, mask))
                throw new AssertionError("discovered mask ignored: " + mask);
            if (!NotificationDisplayPolicy.allowed(new NotificationDisplayPolicy.Ranking(3, false, true, 0), AWAKE, "progress", true, true, mask))
                throw new AssertionError("unrelated effects suppressed: " + mask);
            passed += 2;
        }
        if (NotificationDisplayPolicy.allowed(cached, AWAKE, "progress", false, false, 0)
                || NotificationDisplayPolicy.allowed(cached, AWAKE, "progress", false, false, 513))
            throw new AssertionError("unverified mask accepted");
        passed++;
        if (!AWAKE.same(new NotificationDisplayPolicy.State(true, false, false)) || AWAKE.same(DOZING)
                || AWAKE.same(NotificationDisplayPolicy.State.UNKNOWN)) throw new AssertionError("state change deduplication");
        passed++;
        System.out.println("PASS " + passed + " offline display-policy regression checks");
    }
}
