package com.axuan.vivoislandplus;

import java.util.*;

public final class CapabilityHistoryTest {
    private static int passed;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
    }
    public static void main(String[] args) {
        check(CapabilityHistory.accepts(true, false, true, "android.app.Notification$CallStyle", null, false), "CallStyle retained");
        check(CapabilityHistory.accepts(true, false, true, "android.app.Notification$BigTextStyle", null, true), "legacy screen-share producer retained");
        check(CapabilityHistory.accepts(true, false, true, null, "call", false), "legacy call category retained");
        check(CapabilityHistory.accepts(true, false, true, null, null, true), "share marker without style");
        check(!CapabilityHistory.accepts(false, false, true, "android.app.Notification$CallStyle", "call", true), "finished source excluded");
        check(!CapabilityHistory.accepts(true, true, true, "android.app.Notification$CallStyle", "call", true), "summary excluded");
        check(!CapabilityHistory.accepts(true, false, false, "android.app.Notification$CallStyle", "call", true), "other profile excluded");
        check(!CapabilityHistory.accepts(true, false, true, "android.app.Notification$MediaStyle", null, false), "ordinary music excluded");
        check(!CapabilityHistory.accepts(true, false, true, "android.app.Notification$ProgressStyle", "progress", false), "ordinary progress excluded");
        check(!CapabilityHistory.accepts(true, false, true, null, null, false), "unrelated ongoing source excluded");
        check(!CapabilityHistory.accepts(true, false, true, "android.app.Notification$BigTextStyle", null, false), "unclassified text does not broaden inventory");
        check(CapabilityHistory.accepts(true, false, true, "android.app.Notification$MediaStyle", null, true), "explicit share marker is independent of presentation style");
        CapabilityHistory history = new CapabilityHistory();
        check(history.observe("call", "mute", 100, 10), "first source capabilities");
        boolean changed = false;
        for (int i = 0; i < 10000; i++) changed |= history.observe("call", "mute", 101 + i, 11 + i);
        check(!changed && history.snapshot().size() == 1, "identical reposts generate no new history/writes");
        check(history.observe("call", "unmute", 102, 12), "actual action change is preserved");
        check(history.snapshot().get(0).payload.equals("mute") && history.snapshot().get(1).payload.equals("unmute"), "both action states remain available");
        check(history.snapshot().get(1).wallTime == 102 && history.snapshot().get(1).elapsed == 12, "clock correlation preserved");
        check(history.observe("other", "mute", 103, 13), "other sources deduplicate independently");
        List<CapabilityHistory.Sample> saved = history.snapshot();
        boolean immutable = false;
        try { saved.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable && saved.size() == 3, "returned snapshot is immutable");
        history.remove("call");
        check(history.tracked() == 1 && history.snapshot().size() == 3, "removal forgets live state but retains evidence");
        check(history.observe("call", "unmute", 104, 14), "same-key new notification starts new evidence lifetime");
        check(saved.size() == 3, "snapshot copy remains stable");
        history.retainOnly(Collections.singleton("other"));
        check(history.tracked() == 1 && history.snapshot().size() == 4, "snapshot cleanup preserves evidence");
        check(!history.observe("other", "mute", 105, 15), "retained source stays deduplicated");
        history.reconnect();
        check(history.tracked() == 0 && history.snapshot().size() == 4, "reconnect retains historical states");
        check(history.observe("other", "mute", 106, 16), "reconnect can capture current capabilities again");

        CapabilityHistory bounded = new CapabilityHistory();
        for (int i = 0; i < 10000; i++) bounded.observe("source" + i, "payload" + i, i, i);
        check(bounded.snapshot().size() == 32 && bounded.tracked() == 32, "both history and live-source cache are bounded");
        check(bounded.snapshot().get(0).payload.equals("payload9968") && bounded.snapshot().get(31).payload.equals("payload9999"), "oldest evidence evicted first");
        check(!bounded.observe("source9968", "payload9968", 10000, 10000), "recent access deduplicates without history growth");
        bounded.observe("new", "new payload", 10001, 10001);
        check(!bounded.observe("source9968", "payload9968", 10002, 10002), "recently accessed source survives LRU eviction");
        check(bounded.observe("source9969", "payload9969", 10003, 10003), "oldest inactive live-source entry was evicted");
        String maximal = new String(new char[CapabilityHistory.PAYLOAD_LIMIT]).replace('\0', 'x');
        check(bounded.observe("max", maximal, 10004, 10004), "payload limit boundary accepted");
        int count = bounded.snapshot().size(), tracked = bounded.tracked();
        check(!bounded.observe("oversized", maximal + "x", 10005, 10005), "oversized payload rejected");
        check(bounded.snapshot().size() == count && bounded.tracked() == tracked, "oversized record cannot mutate bounded state");
        check(!bounded.observe(null, "valid", 1, 1) && !bounded.observe("valid", null, 1, 1), "null input ignored");
        System.out.println("PASS " + passed + " offline capability-history regression checks");
    }
}
