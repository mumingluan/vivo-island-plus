package com.axuan.vivoislandplus;

/** Public visual effects plus the unconditional mask discovered in the host. */
final class NotificationDisplayPolicy {
    static final int STATUS_BAR = 32, AMBIENT = 128, NOTIFICATION_LIST = 256;

    static final class Ranking {
        final int importance, effects;
        final boolean suspended, interruptionAllowed;
        Ranking(int importance, boolean suspended, boolean interruptionAllowed, int effects) {
            this.importance = importance; this.suspended = suspended;
            this.interruptionAllowed = interruptionAllowed; this.effects = effects;
        }
    }

    static final class State {
        static final State UNKNOWN = new State(false, false, false);
        final boolean known, dozing, fullDoze;
        State(boolean known, boolean dozing, boolean fullDoze) {
            this.known = known; this.dozing = dozing; this.fullDoze = fullDoze;
        }
        boolean same(State other) {
            return known == other.known && dozing == other.dozing && fullDoze == other.fullDoze;
        }
    }

    static boolean allowed(Ranking ranking, State state, String category, boolean fgsOrUij, boolean media, int vendorHideAll) {
        if (vendorHideAll <= 0 || (vendorHideAll & (vendorHideAll - 1)) != 0) return false;
        if (ranking == null || ranking.importance <= 1 || ranking.suspended) return false;
        if (suppresses(ranking.effects, STATUS_BAR, category, fgsOrUij, media, vendorHideAll)) return false;
        if (!state.known) {
            // Preserve the conservative prior gate until the real UI phase is known.
            return ranking.interruptionAllowed
                    && !suppresses(ranking.effects, AMBIENT | NOTIFICATION_LIST, category, fgsOrUij, media, vendorHideAll);
        }
        if ((state.dozing || state.fullDoze) && suppresses(ranking.effects, AMBIENT, category, fgsOrUij, media, vendorHideAll)) return false;
        return state.dozing || !suppresses(ranking.effects, NOTIFICATION_LIST, category, fgsOrUij, media, vendorHideAll);
    }

    private static boolean suppresses(int effects, int mask, String category, boolean fgsOrUij, boolean media, int vendorHideAll) {
        if ((effects & vendorHideAll) != 0) return true;
        boolean policyCategory = "call".equals(category) || "msg".equals(category) || "alarm".equals(category)
                || "event".equals(category) || "reminder".equals(category);
        return (policyCategory || !(fgsOrUij || media)) && (effects & mask) != 0;
    }
}
