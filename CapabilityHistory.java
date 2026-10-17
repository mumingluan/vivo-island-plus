package com.axuan.vivoislandplus;

import java.util.*;

/** Worker-confined, bounded history of changes to public notification controls. */
final class CapabilityHistory {
    static final int LIMIT = 32;
    static final int PAYLOAD_LIMIT = 32768;
    private final Map<String, String> current = new LinkedHashMap<String, String>(16, .75f, true);
    private final ArrayDeque<Sample> history = new ArrayDeque<>();

    static boolean accepts(boolean ongoing, boolean summary, boolean ownUser,
                           String style, String category, boolean screenShareLiveUpdate) {
        return ongoing && !summary && ownUser && (screenShareLiveUpdate
                || "android.app.Notification$CallStyle".equals(style) || "call".equals(category));
    }

    static final class Sample {
        final long wallTime, elapsed;
        final String payload;
        Sample(long wallTime, long elapsed, String payload) {
            this.wallTime = wallTime; this.elapsed = elapsed; this.payload = payload;
        }
    }

    boolean observe(String key, String payload, long wallTime, long elapsed) {
        if (key == null || payload == null || payload.length() > PAYLOAD_LIMIT) return false;
        if (payload.equals(current.get(key))) return false;
        current.put(key, payload);
        while (current.size() > LIMIT) current.remove(current.keySet().iterator().next());
        if (history.size() == LIMIT) history.removeFirst();
        history.addLast(new Sample(wallTime, elapsed, payload));
        return true;
    }

    void remove(String key) { current.remove(key); }
    void retainOnly(Set<String> keys) { current.keySet().retainAll(keys); }
    void reconnect() { current.clear(); }
    int tracked() { return current.size(); }
    List<Sample> snapshot() { return Collections.unmodifiableList(new ArrayList<>(history)); }
}
