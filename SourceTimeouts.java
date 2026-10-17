package com.axuan.vivoislandplus;

import java.util.*;

/** One cancellable timeout check per notification, confined to the bridge worker. */
final class SourceTimeouts {
    interface Queue {
        long now();
        boolean postAtTime(Runnable task, long uptime);
        void remove(Runnable task);
    }
    interface Callback { boolean recheck(String key); }
    private final Queue queue;
    private final Callback callback;
    private final Map<String, Ticket> tickets = new HashMap<>();

    SourceTimeouts(Queue queue, Callback callback) { this.queue = queue; this.callback = callback; }

    boolean posted(String key, long postTime, long duration) {
        // Explicit reposts rearm even when two posts share the same wall timestamp.
        return arm(key, postTime, duration, duration);
    }

    boolean observed(String key, long postTime, long duration, long wallNow) {
        Ticket current = tickets.get(key);
        if (current != null && current.postTime == postTime && current.duration == duration) return true;
        long expiry = add(postTime, Math.max(0, duration));
        long remaining = expiry <= wallNow ? 1 : subtract(expiry, wallNow);
        return arm(key, postTime, duration, remaining);
    }

    private boolean arm(String key, long postTime, long duration, long remaining) {
        remove(key);
        if (duration <= 0) return true;
        Ticket ticket = new Ticket(key, postTime, duration);
        tickets.put(key, ticket);
        return enqueue(ticket, add(queue.now(), add(Math.max(1, remaining), 100)));
    }

    private boolean enqueue(Ticket ticket, long due) {
        ticket.pending = true;
        if (queue.postAtTime(ticket, due)) return true;
        ticket.pending = false;
        if (tickets.get(ticket.key) == ticket) tickets.remove(ticket.key);
        return false;
    }

    void remove(String key) {
        Ticket old = tickets.remove(key);
        if (old != null && old.pending) queue.remove(old);
    }

    void retainOnly(Set<String> active) {
        for (String key : new ArrayList<>(tickets.keySet())) if (!active.contains(key)) remove(key);
    }

    void clear() { for (String key : new ArrayList<>(tickets.keySet())) remove(key); }
    int size() { return tickets.size(); }
    int pending() {
        int result = 0;
        for (Ticket ticket : tickets.values()) if (ticket.pending) result++;
        return result;
    }

    private final class Ticket implements Runnable {
        final String key;
        final long postTime, duration;
        int checks;
        boolean pending;
        Ticket(String key, long postTime, long duration) {
            this.key = key; this.postTime = postTime; this.duration = duration;
        }
        public void run() {
            // A task already dispatched before removeCallbacks is still harmless.
            if (tickets.get(key) != this || !pending) return;
            pending = false; checks++;
            boolean stillActive = callback.recheck(key);
            if (tickets.get(key) != this) return;
            if (!stillActive) { tickets.remove(key); return; }
            // Account for a delayed system alarm/removal callback, without polling
            // indefinitely or rescheduling on every identical ranking snapshot.
            if (checks < 3) enqueue(this, add(queue.now(), checks == 1 ? 1000 : 5000));
        }
    }

    private static long add(long left, long positive) {
        if (positive > 0 && left > Long.MAX_VALUE - positive) return Long.MAX_VALUE;
        return left + positive;
    }
    private static long subtract(long left, long right) {
        if (right < 0 && left > Long.MAX_VALUE + right) return Long.MAX_VALUE;
        return left - right;
    }
}
