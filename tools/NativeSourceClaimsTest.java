package com.axuan.vivoislandplus;

import java.util.*;

public final class NativeSourceClaimsTest {
    private static int passed;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
    }
    public static void main(String[] args) {
        String call = "0|sample.app|1|null|10001", progress = "0|sample.app|2|null|10001";
        NativeSourceClaims claims = new NativeSourceClaims();
        check(claims.claimed(call, 1000), "new native source claim");
        check(claims.covers(call, 1000), "exact source and generation covered");
        check(!claims.covers(progress, 1000), "same app's independent progress is not covered");
        check(!claims.covers("10|sample.app|1|null|1010001", 1000), "other user cannot be covered by owner claim");
        check(!claims.covers(call, 1001), "other generation not covered");
        boolean changed = false;
        for (int i = 0; i < 10000; i++) changed |= claims.claimed(call, 1000);
        check(!changed && claims.size() == 1, "repeated native submissions do not schedule new reconciliations");
        claims.observed(call, 1000);
        check(claims.covers(call, 1000), "claim arriving before listener post is preserved");
        claims.observed(call, 1001);
        check(!claims.covers(call, 1001) && claims.size() == 0, "new unclaimed post releases old coverage");
        check(claims.claimed(call, 1001), "new native generation can claim source");
        claims.observed(call, 1001);
        check(claims.covers(call, 1001), "same generation stays native across ranking snapshots");
        claims.claimed(progress, 1002);
        claims.removed(call);
        check(!claims.covers(call, 1001) && claims.covers(progress, 1002), "source removal affects only its own notification");
        claims.observed(call, 1001);
        check(!claims.covers(call, 1001), "same-key same-time repost after real removal starts unclaimed");
        claims.claimed(call, 1003);
        claims.retainOnly(Collections.singleton(progress));
        check(claims.size() == 1 && claims.covers(progress, 1002), "active snapshot prunes only missing source");
        claims.claimed(call, 1000);
        check(!claims.covers(call, 1004), "late stale native metadata cannot cover newer source");
        claims.observed(call, 1004);
        check(claims.size() == 1, "stale generation cleared on observation");
        claims.retainOnly(Collections.<String>emptySet());
        check(claims.size() == 0, "empty source snapshot cleans all claims");
        check(!claims.claimed(null, 1), "missing source cannot claim");
        for (int i = 0; i < 10000; i++) claims.claimed("source" + i, i);
        check(claims.size() == 128, "source metadata is bounded");
        check(!claims.covers("source0", 0) && claims.covers("source9999", 9999), "oldest claims evict before newest");
        System.out.println("PASS " + passed + " offline native-source ownership regression checks");
    }
}
