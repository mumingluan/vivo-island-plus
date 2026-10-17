package com.axuan.vivoislandplus;

/** Standard action identity and notification-generation checks, without Android. */
public final class RemoteInputActionPolicyTest {
    private static int passed;
    private static void check(boolean value, String scenario) {
        if (!value) throw new AssertionError(scenario);
        passed++;
    }
    private static RemoteInputActionPolicy.Action a(Object pi, boolean input) {
        return new RemoteInputActionPolicy.Action(pi, input);
    }
    public static void main(String[] args) {
        RemoteInputActionPolicy.Action[] actions = {a("shared", false), a(new String("shared"), true),
                null, a("ordinary", false), a("reply", true)};
        check(RemoteInputActionPolicy.select(0, "shared", actions) == -1, "valid ordinary index with shared PI");
        check(RemoteInputActionPolicy.select(1, "shared", actions) == 1, "valid reply index with shared PI");
        check(RemoteInputActionPolicy.select(null, "shared", actions) == -2, "shared PI with absent index is ambiguous");
        check(RemoteInputActionPolicy.select(99, "shared", actions) == -2, "invalid index remains ambiguous");
        check(RemoteInputActionPolicy.select(3, "shared", actions) == -2, "mismatched ordinary index cannot send shared input PI");
        check(RemoteInputActionPolicy.select(2, "shared", actions) == -2, "null indexed action");
        check(RemoteInputActionPolicy.select(-1, "shared", actions) == -2, "negative index");
        check(RemoteInputActionPolicy.select(null, "reply", actions) == 4, "unique PI without index");
        check(RemoteInputActionPolicy.select(0, "reply", actions) == 4, "stale index resolves unique reply PI");
        check(RemoteInputActionPolicy.select(4, "ordinary", actions) == -1, "stale reply index does not capture ordinary PI");
        check(RemoteInputActionPolicy.select(null, "ordinary", actions) == -1, "ordinary action");
        check(RemoteInputActionPolicy.select(4, null, actions) == -1, "null PI");
        check(RemoteInputActionPolicy.select(4, "unknown", actions) == -1, "custom/body PI");
        check(RemoteInputActionPolicy.select(0, "reply", null) == -1, "null action array");
        check(RemoteInputActionPolicy.select(0, "reply", new RemoteInputActionPolicy.Action[0]) == -1, "empty action array");
        check(RemoteInputActionPolicy.select(null, "reply", new RemoteInputActionPolicy.Action[]{a(null, true)}) == -1, "null declared PI");
        check(RemoteInputActionPolicy.select(null, "same", new RemoteInputActionPolicy.Action[]{a("same", true), a("same", true)}) == -2, "two input actions require a reliable index");
        check(RemoteInputActionPolicy.select(null, "same", new RemoteInputActionPolicy.Action[]{a("same", false), a("same", false)}) == -1, "two ordinary actions need no reply interception");
        check(RemoteInputActionPolicy.current("key", 10, "pi", 0, "key", 10, new String("pi"), 0, true, false), "same live source and PI equality");
        check(!RemoteInputActionPolicy.current("key", 10, "pi", 0, "other", 10, "pi", 0, true, false), "different source key");
        check(!RemoteInputActionPolicy.current("key", 10, "pi", 0, "key", 11, "pi", 0, true, false), "new notification generation");
        check(!RemoteInputActionPolicy.current("key", 10, "old", 0, "key", 10, "new", 0, true, false), "replaced action PI");
        check(!RemoteInputActionPolicy.current("key", 10, "pi", 0, "key", 10, "pi", 10, true, false), "other user");
        check(!RemoteInputActionPolicy.current("key", 10, "pi", 10, "key", 10, "pi", 0, true, false), "reverse user mismatch");
        check(!RemoteInputActionPolicy.current("key", 10, "pi", 0, "key", 10, "pi", 0, false, false), "input capability removed");
        check(!RemoteInputActionPolicy.current("key", 10, "pi", 0, "key", 10, "pi", 0, true, true), "suspended source");
        check(!RemoteInputActionPolicy.current(null, 10, "pi", 0, null, 10, "pi", 0, true, false), "missing key");
        check(!RemoteInputActionPolicy.current("key", 10, null, 0, "key", 10, null, 0, true, false), "missing PI");
        System.out.println("PASS " + passed + " offline remote-input action checks");
    }
}
