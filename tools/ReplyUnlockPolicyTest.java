package com.axuan.vivoislandplus;

public final class ReplyUnlockPolicyTest {
    private static int passed;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++;
    }
    public static void main(String[] args) {
        ReplyUnlockPolicy normal = new ReplyUnlockPolicy(100);
        check(normal.waiting(), "new request waits for actual unlock");
        check(!normal.allow(10, true, false), "cannot show editor while locked");
        check(!normal.release(10, true, false), "early internal callback cannot open editor");
        check(normal.waiting(), "early callback preserves wait");
        check(normal.release(11, true, true), "actual unlock releases once");
        check(!normal.release(12, true, true), "repeated native callbacks do not queue twice");
        check(normal.allow(12, true, true), "released current source may open");
        check(!normal.allow(13, true, false), "device relocked before queued callback");
        normal.finish();
        check(!normal.allow(14, true, true), "cleanup suppresses later callback");

        ReplyUnlockPolicy cancelled = new ReplyUnlockPolicy(100);
        cancelled.finish(); // native cancellation may precede password bouncer
        check(!cancelled.waiting(), "alternate bouncer cancellation is final");
        check(!cancelled.release(11, true, true), "later ordinary unlock cannot revive cancelled reply");
        check(!cancelled.allow(11, true, true), "cancelled queued work cannot open editor");

        ReplyUnlockPolicy queued = new ReplyUnlockPolicy(100);
        check(queued.release(10, true, true), "unlock event queues opening");
        queued.finish();
        check(!queued.allow(11, true, true), "superseded request cannot open after queue");

        ReplyUnlockPolicy withdrawn = new ReplyUnlockPolicy(100);
        check(!withdrawn.release(10, false, true), "withdrawn or updated source rejected");
        check(!withdrawn.release(11, true, true), "later matching snapshot cannot resurrect invalid source");
        ReplyUnlockPolicy updatedAfterUnlock = new ReplyUnlockPolicy(100);
        check(updatedAfterUnlock.release(10, true, true), "source current at unlock");
        check(!updatedAfterUnlock.allow(11, false, true), "update between unlock and layout rejected");
        check(!updatedAfterUnlock.allow(12, true, true), "invalid generation remains terminal");

        ReplyUnlockPolicy expired = new ReplyUnlockPolicy(100);
        check(!expired.release(100, true, true), "deadline boundary rejects unlock");
        check(!expired.waiting(), "expired request no longer waits");
        ReplyUnlockPolicy expiresAfterQueue = new ReplyUnlockPolicy(100);
        check(expiresAfterQueue.release(99, true, true), "unlock before deadline");
        check(!expiresAfterQueue.allow(100, true, true), "layout after deadline rejected");

        ReplyUnlockPolicy first = new ReplyUnlockPolicy(100);
        ReplyUnlockPolicy replacement = new ReplyUnlockPolicy(200);
        first.finish();
        check(!first.release(20, true, true) && replacement.release(20, true, true), "new click replaces only old interaction");
        System.out.println("PASS " + passed + " offline reply unlock lifecycle checks");
    }
}
