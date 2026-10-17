package com.axuan.vivoislandplus;

/** One pending reply interaction; cancellation wins over queued UI callbacks. */
final class ReplyUnlockPolicy {
    private final long deadline;
    private boolean waiting = true, finished;

    ReplyUnlockPolicy(long deadline) {
        this.deadline = deadline;
    }

    boolean waiting() { return waiting && !finished; }

    boolean release(long now, boolean sourceCurrent, boolean unlocked) {
        if (!waiting()) return false;
        if (now >= deadline || !sourceCurrent) { finish(); return false; }
        if (!unlocked) return false;
        waiting = false;
        return true;
    }

    boolean allow(long now, boolean sourceCurrent, boolean unlocked) {
        if (finished) return false;
        if (now >= deadline || !sourceCurrent) { finish(); return false; }
        return !waiting && unlocked;
    }

    void finish() { finished = true; waiting = false; }
}
