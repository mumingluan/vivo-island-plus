package com.axuan.vivoislandplus;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class SourceTimeoutsTest {
    private static int passed;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); passed++; }
    private static final class Queue implements SourceTimeouts.Queue {
        long uptime = 10000;
        boolean accept = true;
        final Map<Runnable, Long> jobs = new LinkedHashMap<>();
        public long now() { return uptime; }
        public boolean postAtTime(Runnable task, long when) { if (accept) jobs.put(task, when); return accept; }
        public void remove(Runnable task) { jobs.remove(task); }
        Runnable first() { return jobs.keySet().iterator().next(); }
        long due() { return jobs.get(first()); }
        void next() { Runnable task = first(); uptime = jobs.remove(task); task.run(); }
    }
    public static void main(String[] args) {
        Queue q = new Queue(); AtomicInteger calls = new AtomicInteger();
        SourceTimeouts tracker = new SourceTimeouts(q, key -> { calls.incrementAndGet(); return false; });
        tracker.posted("a", 1000, 5000);
        Runnable old = q.first(); long first = q.due();
        check(first == 15100, "timeout waits for duration and alarm grace");
        q.uptime += 1000; tracker.posted("a", 1000, 5000);
        check(q.jobs.size() == 1 && tracker.pending() == 1, "same-timestamp repost replaces old task");
        check(q.due() == first + 1000, "explicit repost rearms duration");
        old.run(); check(calls.get() == 0, "already dispatched cancelled task cannot reconcile new source");
        for (int i = 0; i < 10000; i++) tracker.posted("a", 1000 + i, 5000);
        check(q.jobs.size() == 1 && tracker.size() == 1, "ten thousand updates keep one timer");
        tracker.posted("a", 12000, 0);
        check(q.jobs.isEmpty() && tracker.size() == 0, "removing timeout cancels old job");
        tracker.observed("a", 20000, 5000, 22000);
        long observedDue = q.due();
        check(observedDue == q.uptime + 3100, "reconnect preserves remaining timeout");
        q.uptime += 100;
        tracker.observed("a", 20000, 5000, -100000);
        check(q.due() == observedDue, "identical ranking snapshot and wall-clock change do not renew timer");
        q.next();
        check(calls.get() == 1 && tracker.size() == 0 && q.jobs.isEmpty(), "gone source is reconciled once");
        tracker.posted("a", 30000, 5000); tracker.posted("b", 30000, 5000);
        tracker.retainOnly(Collections.singleton("b"));
        check(tracker.size() == 1 && q.jobs.size() == 1, "snapshot removes only missing source timers");
        tracker.clear(); check(q.jobs.isEmpty() && tracker.size() == 0, "reconnection clears all old jobs");

        Queue retryQueue = new Queue(); AtomicInteger retries = new AtomicInteger();
        SourceTimeouts delayed = new SourceTimeouts(retryQueue, key -> { retries.incrementAndGet(); return true; });
        delayed.posted("late", 50000, 1);
        retryQueue.next(); long second = retryQueue.due();
        check(second == retryQueue.uptime + 1000, "delayed system removal gets first bounded recheck");
        delayed.observed("late", 50000, 1, 999999);
        check(retryQueue.jobs.size() == 1 && retryQueue.due() == second, "snapshot cannot duplicate pending retry");
        retryQueue.next(); check(retryQueue.due() == retryQueue.uptime + 5000, "last bounded recheck");
        retryQueue.next();
        check(retries.get() == 3 && retryQueue.jobs.isEmpty() && delayed.pending() == 0, "still active source never causes unbounded polling");
        for (int i = 0; i < 1000; i++) delayed.observed("late", 50000, 1, 999999 + i);
        check(retryQueue.jobs.isEmpty() && delayed.size() == 1, "exhausted generation stays dormant across snapshots");
        delayed.posted("late", 50000, 1);
        check(retryQueue.jobs.size() == 1, "new explicit post can rearm exhausted timestamp");
        delayed.remove("late"); check(retryQueue.jobs.isEmpty() && delayed.size() == 0, "source removal cancels retry");

        Queue overflow = new Queue();
        SourceTimeouts huge = new SourceTimeouts(overflow, key -> false);
        huge.posted("huge", Long.MAX_VALUE, Long.MAX_VALUE);
        check(overflow.due() == Long.MAX_VALUE, "huge duration cannot overflow to immediate execution");
        huge.observed("other", Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MIN_VALUE);
        check(overflow.jobs.values().stream().allMatch(time -> time == Long.MAX_VALUE), "snapshot overflow saturates");
        huge.clear(); overflow.accept = false;
        check(!huge.posted("refused", 1, 2) && huge.size() == 0, "stopped queue leaves no phantom pending record");

        Queue changed = new Queue(); final SourceTimeouts[] replacing = new SourceTimeouts[1];
        replacing[0] = new SourceTimeouts(changed, key -> { replacing[0].posted(key, 2, 10000); return true; });
        replacing[0].posted("race", 1, 1); changed.next();
        check(changed.jobs.size() == 1 && changed.due() == changed.uptime + 10100, "replacement during callback owns the new deadline");
        System.out.println("PASS " + passed + " offline source-timeout regression checks");
    }
}
