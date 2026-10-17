package com.axuan.vivoislandplus;
import java.io.IOException;
import java.util.*;

/** Finds the host's visual-suppression rule by its public notification semantics. */
final class VendorDisplayPlan {
    final DexIndex.Ref suppression;
    final int unconditionalMask;
    private VendorDisplayPlan(DexIndex.Ref method, int mask) { suppression = method; unconditionalMask = mask; }

    static VendorDisplayPlan discover(DexIndex index) throws IOException {
        List<DexIndex.MethodData> candidates = new ArrayList<>();
        for (DexIndex.MethodData method : index.methods.values()) {
            if (method.isStatic() || !method.ref.type.equals("(I)Z")) continue;
            DexIndex.Facts facts = reachableFacts(index, method, 0, new HashSet<DexIndex.Ref>());
            if (!facts.strings.containsAll(Arrays.asList("call", "msg", "alarm", "event", "reminder"))) continue;
            if (!calls(facts, "Landroid/service/notification/NotificationListenerService$Ranking;", "getSuppressedVisualEffects")
                    || !calls(facts, "Landroid/app/Notification;", "isFgsOrUij")
                    || !calls(facts, "Landroid/app/Notification;", "isMediaNotification")) continue;
            candidates.add(method);
        }
        if (candidates.size() != 1) throw new IOException("Missing/ambiguous host suppression rule: " + candidates.size());
        DexIndex.MethodData method = candidates.get(0);
        return new VendorDisplayPlan(method.ref, maskFromPrefix(index, method));
    }
    private static DexIndex.Facts reachableFacts(DexIndex index, DexIndex.MethodData method, int depth,
            Set<DexIndex.Ref> seen) throws IOException {
        DexIndex.Facts out = new DexIndex.Facts();
        if (depth > 4 || !seen.add(method.ref)) return out;
        DexIndex.Facts facts = method.facts(); out.strings.addAll(facts.strings); out.calls.addAll(facts.calls);
        for (DexIndex.Ref ref : facts.calls) {
            DexIndex.MethodData target = index.get(ref);
            if (target == null || !ref.owner.equals(method.ref.owner)) continue;
            DexIndex.Facts child = reachableFacts(index, target, depth + 1, seen);
            out.strings.addAll(child.strings); out.calls.addAll(child.calls);
        }
        return out;
    }
    private static boolean effectsGetter(DexIndex index, DexIndex.Ref ref) throws IOException {
        if (!ref.type.equals("()I")) return false;
        if (ref.owner.equals("Landroid/service/notification/NotificationListenerService$Ranking;")
                && ref.name.equals("getSuppressedVisualEffects")) return true;
        DexIndex.MethodData target = index.get(ref);
        return target != null && target.facts().instructions <= 8 && target.facts().calls.size() == 1
                && calls(target.facts(), "Landroid/service/notification/NotificationListenerService$Ranking;", "getSuppressedVisualEffects");
    }
    private static int maskFromPrefix(DexIndex index, DexIndex.MethodData method) throws IOException {
        int[] code = method.codeUnits();
        Set<Integer> effectRegisters = new HashSet<>(); Map<Integer, Integer> maskedRegisters = new HashMap<>();
        boolean effectResult = false;
        Map<Integer, Integer> constants = new HashMap<>();
        for (int at = 0, steps = 0; at < code.length && steps++ < 24;) {
            int unit = code[at], op = unit & 255, width = DexIndex.width(op);
            if (width <= 0 || at + width > code.length) break;
            if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                if (!effectsGetter(index, method.invokedAt(at))) break;
                effectResult = true;
            } else if (op == 0x0a) {
                if (!effectResult) break;
                effectRegisters.add(unit >> 8); effectResult = false;
            } else if (op == 0xd5 || op == 0xdd) {
                int source = op == 0xd5 ? unit >> 12 : code[at + 1] & 255;
                int destination = op == 0xd5 ? (unit >> 8) & 15 : unit >> 8;
                int mask = op == 0xd5 ? (short) code[at + 1] : (byte) (code[at + 1] >> 8);
                if (!effectRegisters.contains(source) || mask <= 256 || (mask & (mask - 1)) != 0) break;
                maskedRegisters.put(destination, mask);
            } else if (op == 0x12) {
                constants.put((unit >> 8) & 15, unit >> 12);
            } else if (op == 0x13) {
                constants.put(unit >> 8, (int) (short) code[at + 1]);
            } else if (op == 0x38 || op == 0x39) {
                Integer mask = maskedRegisters.get(unit >> 8);
                if (mask == null) break;
                int target = at + (short) code[at + 1], fall = at + width;
                int nonzero = op == 0x38 ? fall : target, zero = op == 0x38 ? target : fall;
                if (Boolean.TRUE.equals(constantReturn(code, nonzero, constants))
                        && !Boolean.TRUE.equals(constantReturn(code, zero, constants))) return mask;
                break;
            } else if (op != 0 && op != 0x54) break; // only initial ranking-field reads
            at += width;
        }
        throw new IOException("Unrecognized unconditional visual-suppression prefix: " + method.ref);
    }
    private static Boolean constantReturn(int[] code, int at, Map<Integer, Integer> initial) {
        Map<Integer, Integer> values = new HashMap<>(initial);
        for (int steps = 0; at >= 0 && at < code.length && steps++ < 16;) {
            int unit = code[at], op = unit & 255;
            if (op == 0x28) { at += (byte) (unit >> 8); continue; }
            if (op == 0x29 && at + 1 < code.length) { at += (short) code[at + 1]; continue; }
            if (op == 0x12) { values.put((unit >> 8) & 15, unit >> 12); ++at; continue; }
            if (op == 0x13 && at + 1 < code.length) { values.put(unit >> 8, (int) (short) code[at + 1]); at += 2; continue; }
            if (op == 0x0f) { Integer value = values.get(unit >> 8); return value == null ? null : value == 1; }
            return null;
        }
        return null;
    }
    private static boolean calls(DexIndex.Facts facts, String owner, String name) {
        for (DexIndex.Ref call : facts.calls) if (owner.equals(call.owner) && name.equals(call.name)) return true;
        return false;
    }
}
