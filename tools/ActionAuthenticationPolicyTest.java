package com.axuan.vivoislandplus;

/** Regression cases for shared PIs, stale indices and callback/lock races. */
public final class ActionAuthenticationPolicyTest {
    private static int passed;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++;
    }
    private static ActionAuthenticationPolicy.Action a(Object intent, boolean auth) {
        return new ActionAuthenticationPolicy.Action(intent, auth);
    }
    private static ActionAuthenticationPolicy.Result result() {
        return new ActionAuthenticationPolicy.Result("current", 100, 60);
    }
    public static void main(String[] args) {
        ActionAuthenticationPolicy.Action[] actions = {a("shared", false), a(new String("shared"), true),
                null, a("ordinary", false), a("authenticated", true)};
        check(!ActionAuthenticationPolicy.required(0, "shared", actions), "reliable ordinary shared PI index");
        check(ActionAuthenticationPolicy.required(1, "shared", actions), "reliable authenticated shared PI index");
        check(ActionAuthenticationPolicy.required(null, "shared", actions), "missing index uses stricter matching policy");
        check(ActionAuthenticationPolicy.required(-1, "shared", actions), "negative index");
        check(ActionAuthenticationPolicy.required(99, "shared", actions), "out of range index");
        check(ActionAuthenticationPolicy.required(2, "shared", actions), "null indexed action");
        check(ActionAuthenticationPolicy.required(3, "shared", actions), "mismatched indexed PI");
        check(ActionAuthenticationPolicy.required(0, "authenticated", actions), "stale ordinary tag cannot weaken different PI");
        check(!ActionAuthenticationPolicy.required(null, "ordinary", actions), "ordinary unique PI");
        check(!ActionAuthenticationPolicy.required(null, "unknown", actions), "content/custom PI not misclassified");
        check(!ActionAuthenticationPolicy.required(0, null, actions), "null clicked PI");
        check(!ActionAuthenticationPolicy.required(0, "shared", null), "null actions");
        check(!ActionAuthenticationPolicy.required(null, "shared", new ActionAuthenticationPolicy.Action[0]), "empty actions");
        check(!ActionAuthenticationPolicy.required(null, "shared", new ActionAuthenticationPolicy.Action[]{a(null, true)}), "null action PI");
        check(result().accept(159, "current", true, true, false, false), "valid approval before deadline");
        check(!result().accept(160, "current", true, true, false, false), "deadline boundary denies");
        check(!result().accept(1000, "current", true, true, false, false), "expired callback");
        check(!result().accept(101, "old", true, true, false, false), "old host source mismatch");
        check(!result().accept(101, null, true, true, false, false), "old stock host acknowledgement missing");
        check(!result().accept(101, "current", false, true, false, false), "cancelled authentication");
        check(!result().accept(101, "current", true, false, false, false), "invalid original notification generation");
        ActionAuthenticationPolicy.Action[] current = {a("ordinary", false), a("protected", true)};
        check(ActionAuthenticationPolicy.current("k", 7, "protected", 0, 1, "k", 7, 0, current, false), "current source survives detached card");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, 1, "k", 8, 0, current, false), "updated source generation rejected");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, 1, "other", 7, 0, current, false), "different source rejected");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, 1, "k", 7, 10, current, false), "different user rejected");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, 0, "k", 7, 0, current, false), "index and original intent must agree");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, 1, "k", 7, 0, current, true), "suspended source rejected");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, 1, "k", 7, 0, null, false), "removed source actions rejected");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, 1, "k", 7, 0,
                new ActionAuthenticationPolicy.Action[]{a("ordinary", false), a("replacement", true)}, false), "replacement intent rejected");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, 0, "k", 7, 0,
                new ActionAuthenticationPolicy.Action[]{new ActionAuthenticationPolicy.Action("protected", true, true)}, false), "free-form input action cannot be sent without input");
        check(ActionAuthenticationPolicy.current("k", 7, "protected", 0, 0, "k", 7, 0,
                new ActionAuthenticationPolicy.Action[]{new ActionAuthenticationPolicy.Action("protected", true, false)}, false),
                "choice-only action follows native original-intent path after authentication");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, 0, "k", 8, 0,
                new ActionAuthenticationPolicy.Action[]{new ActionAuthenticationPolicy.Action("protected", true, false)}, false),
                "choice-only still requires original notification generation");
        check(ActionAuthenticationPolicy.current("k", 7, "protected", 0, -1, "k", 7, 0, current, false), "missing index uses actual protected match");
        check(!ActionAuthenticationPolicy.current("k", 7, "ordinary", 0, -1, "k", 7, 0, current, false), "ordinary action cannot impersonate protected request");
        check(!ActionAuthenticationPolicy.current("k", 7, "protected", 0, -2, "k", 7, 0, current, false), "invalid index rejected");
        check(!result().accept(101, "current", true, true, true, false), "keyguard still showing");
        check(!result().accept(101, "current", true, true, false, true), "device still locked");
        check(!result().accept(101, "current", true, true, true, true), "both lock signals");
        ActionAuthenticationPolicy.Result once = result();
        check(once.accept(101, "current", true, true, false, false), "first success");
        check(!once.accept(102, "current", true, true, false, false), "duplicate success cannot execute twice");
        once = result();
        check(!once.accept(101, "current", false, true, false, false), "first cancellation");
        check(!once.accept(102, "current", true, true, false, false), "late success after cancellation");
        once = result();
        check(!once.accept(160, "current", true, true, false, false), "first expiry");
        check(!once.accept(150, "current", true, true, false, false), "expired result stays consumed");
        once = result();
        check(!once.accept(101, "old", true, true, false, false), "first mismatch");
        check(!once.accept(102, "current", true, true, false, false), "mismatch cannot be retried as valid");
        check(!new ActionAuthenticationPolicy.Result("current", 100, 0).accept(100, "current", true, true, false, false), "zero TTL");
        check(!new ActionAuthenticationPolicy.Result("current", 100, -1).accept(100, "current", true, true, false, false), "negative TTL");
        check(new ActionAuthenticationPolicy.Result("current", Long.MAX_VALUE - 5, 60)
                .accept(Long.MAX_VALUE - 1, "current", true, true, false, false), "deadline saturates safely");
        check(!new ActionAuthenticationPolicy.Result(null, 100, 60).accept(101, null, true, true, false, false), "unknown build denies");
        System.out.println("PASS " + passed + " offline action authentication checks");
    }
}
