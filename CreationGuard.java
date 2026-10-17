package com.axuan.vivoislandplus;

import java.util.*;

/** Worker-confined protection against repeated creation of continuously desired islands. */
final class CreationGuard {
    private final Map<String, ArrayDeque<Long>> attempts = new HashMap<>();

    boolean allow(String key, long elapsed) {
        ArrayDeque<Long> times = attempts.get(key);
        if (times == null) { times = new ArrayDeque<>(); attempts.put(key, times); }
        while (!times.isEmpty() && elapsed - times.peekFirst() > 10000) times.removeFirst();
        if (times.size() >= 2) return false;
        times.addLast(elapsed);
        return true;
    }

    void remove(String key) { attempts.remove(key); }
    void clear() { attempts.clear(); }

    void retainDesired(Set<String> desired) {
        // Privacy/user/ranking/native suppression is an intentional withdrawal,
        // not an uncontrolled loop. A later show starts a new visibility lifetime.
        attempts.keySet().retainAll(desired);
    }
}
