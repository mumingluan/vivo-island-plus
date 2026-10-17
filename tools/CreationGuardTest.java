package com.axuan.vivoislandplus;

import java.util.*;

public final class CreationGuardTest {
    private static int passed;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
    }

    public static void main(String[] args) {
        CreationGuard guard = new CreationGuard();
        check(guard.allow("broken", 0), "first creation");
        check(guard.allow("broken", 1), "one recovery attempt");
        check(!guard.allow("broken", 2), "continuous recreation trips guard");
        check(!guard.allow("broken", 10000), "window includes boundary");
        check(guard.allow("broken", 10001), "old attempt expires");

        guard.clear();
        check(guard.allow("broken", 20000), "new continuous source");
        check(guard.allow("broken", 20001), "new retry");
        Set<String> visible = new HashSet<>(Arrays.asList("private", "broken"));
        check(guard.allow("private", 20000), "first private island");
        // Rapid real privacy/user/ranking transitions must not disable the module.
        for (int i = 1; i <= 20; i++) {
            visible.remove("private");
            guard.retainDesired(visible);
            visible.add("private");
            check(guard.allow("private", 20000 + i), "intentional hide/show " + i);
        }
        check(!guard.allow("broken", 20025), "other source's loop history survives privacy changes");
        guard.remove("broken");
        check(guard.allow("broken", 20026), "actual cancellation starts fresh lifetime");
        guard.retainDesired(Collections.<String>emptySet());
        check(guard.allow("private", 20027), "user/native/ranking suppression clears old lifetime");
        check(guard.allow("private", 20028), "retry after visibility returns");
        check(!guard.allow("private", 20029), "loop protection remains active after normal transitions");
        guard.clear();
        check(guard.allow("private", 20030), "listener reconnect resets creation epoch");
        check(guard.allow("other", 20030), "independent notification key");
        guard.retainDesired(Collections.singleton("other"));
        check(guard.allow("other", 20031), "retain preserves desired key history");
        check(!guard.allow("other", 20032), "remaining desired source still protected");
        System.out.println("PASS " + passed + " offline creation-lifecycle regression checks");
    }
}
