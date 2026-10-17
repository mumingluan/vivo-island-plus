package com.axuan.vivoislandplus;

import java.util.*;

/** Native ownership is scoped to a notification key and its posted generation. */
final class NativeSourceClaims {
    private final Map<String, Long> claims = new LinkedHashMap<>();

    boolean claimed(String key, long postTime) {
        if (key == null) return false;
        Long previous = claims.put(key, postTime);
        while (claims.size() > 128) claims.remove(claims.keySet().iterator().next());
        return previous == null || previous.longValue() != postTime;
    }

    boolean covers(String key, long postTime) {
        Long claimed = claims.get(key);
        return claimed != null && claimed.longValue() == postTime;
    }

    void observed(String key, long postTime) {
        if (!covers(key, postTime)) claims.remove(key);
    }
    void removed(String key) { claims.remove(key); }
    void retainOnly(Set<String> active) { claims.keySet().retainAll(active); }
    int size() { return claims.size(); }
}
