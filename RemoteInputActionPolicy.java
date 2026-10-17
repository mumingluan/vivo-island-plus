package com.axuan.vivoislandplus;

/** Identify the original text-input action without guessing between shared PIs. */
final class RemoteInputActionPolicy {
    static final int NONE = -1, AMBIGUOUS = -2;
    static final class Action {
        final Object intent;
        final boolean freeForm;
        Action(Object intent, boolean freeForm) { this.intent = intent; this.freeForm = freeForm; }
    }

    static int select(Integer index, Object clickedIntent, Action[] actions) {
        if (clickedIntent == null || actions == null) return NONE;
        if (index != null && index >= 0 && index < actions.length) {
            Action action = actions[index];
            if (action != null && clickedIntent.equals(action.intent)) return action.freeForm ? index : NONE;
        }
        int match = NONE, count = 0;
        boolean input = false;
        for (int i = 0; i < actions.length; i++) {
            Action action = actions[i];
            if (action == null || !clickedIntent.equals(action.intent)) continue;
            match = i; count++; input |= action.freeForm;
        }
        return !input ? NONE : count == 1 ? match : AMBIGUOUS;
    }

    static boolean current(String requestedKey, long requestedPostTime, Object requestedIntent, int requestedUser,
                           String actualKey, long actualPostTime, Object actualIntent, int actualUser,
                           boolean freeForm, boolean suspended) {
        return requestedKey != null && requestedKey.equals(actualKey) && requestedPostTime == actualPostTime
                && requestedIntent != null && requestedIntent.equals(actualIntent)
                && requestedUser == actualUser && freeForm && !suspended;
    }
}
