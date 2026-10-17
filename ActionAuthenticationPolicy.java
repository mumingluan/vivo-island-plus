package com.axuan.vivoislandplus;

/** Pure action identity and one-shot authentication result rules. */
final class ActionAuthenticationPolicy {
    static final class Action {
        final Object intent;
        final boolean authentication;
        final boolean freeFormInput;
        Action(Object intent, boolean authentication) { this(intent, authentication, false); }
        Action(Object intent, boolean authentication, boolean freeFormInput) {
            this.intent = intent; this.authentication = authentication; this.freeFormInput = freeFormInput;
        }
    }

    static boolean current(String key, long postTime, Object intent, int user, int index,
                           String actualKey, long actualPostTime, int actualUser, Action[] actions, boolean suspended) {
        if (key == null || !key.equals(actualKey) || postTime != actualPostTime || user != actualUser
                || intent == null || actions == null || suspended || index < -1) return false;
        if (index >= 0) return index < actions.length && valid(intent, actions[index]);
        for (Action action : actions) if (valid(intent, action)) return true;
        return false;
    }

    private static boolean valid(Object intent, Action action) {
        return action != null && intent.equals(action.intent) && action.authentication && !action.freeFormInput;
    }

    static boolean required(Integer index, Object clickedIntent, Action[] actions) {
        if (clickedIntent == null || actions == null) return false;
        if (index != null && index >= 0 && index < actions.length) {
            Action action = actions[index];
            if (action != null && clickedIntent.equals(action.intent)) return action.authentication;
        }
        // Custom layouts may lack a reliable index. A shared PI must not
        // silently inherit the least restrictive matching action's policy.
        for (Action action : actions)
            if (action != null && action.authentication && clickedIntent.equals(action.intent)) return true;
        return false;
    }

    static final class Result {
        private final String sourceId;
        private final long deadline;
        private boolean finished;
        Result(String sourceId, long now, long timeout) {
            this.sourceId = sourceId;
            deadline = timeout <= 0 ? now : now > Long.MAX_VALUE - timeout ? Long.MAX_VALUE : now + timeout;
        }
        synchronized boolean accept(long now, String respondingSource, boolean approved,
                                    boolean currentSource, boolean keyguardLocked, boolean deviceLocked) {
            if (finished) return false;
            finished = true;
            return now < deadline && approved && currentSource && !keyguardLocked && !deviceLocked
                    && sourceId != null && sourceId.equals(respondingSource);
        }
    }
}
