package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Validates a compiler-generated capture constructor, independent of its class/member names. */
final class TypedRunnablePlan {
    private TypedRunnablePlan() { }

    static DexIndex.Ref constructor(DexIndex index, String owner) throws IOException {
        List<DexIndex.Ref> found = new ArrayList<>();
        for (DexIndex.MethodData method : index.methods.values()) {
            if (!method.ref.owner.equals(owner) || !method.ref.name.equals("<init>")) continue;
            DexIndex.Facts facts = method.facts();
            // Constructing the marked callback must not run stock code or write
            // global state. Accept only capture assignments and Object.<init>.
            if (facts.calls.size() != 1 || !facts.calls.contains(
                    new DexIndex.Ref("Ljava/lang/Object;", "<init>", "()V"))) continue;
            if (facts.opcodes.isEmpty() || !facts.strings.isEmpty()) continue;
            boolean safe = true;
            for (int op : facts.opcodes) {
                if (!(op == 0 || (op >= 1 && op <= 9) || op == 0x0e
                        || (op >= 0x12 && op <= 0x19) || (op >= 0x59 && op <= 0x5f)
                        || op == 0x70 || op == 0x76)) safe = false;
            }
            for (DexIndex.Ref field : facts.fields) if (!owner.equals(field.owner)) safe = false;
            if (safe) found.add(method.ref);
        }
        if (found.size() != 1) throw new IOException("Missing/ambiguous safe callback constructor: " + found);
        return found.get(0);
    }

    static Object[] emptyCaptures(Class<?>[] parameters) {
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < values.length; ++i) {
            Class<?> type = parameters[i];
            if (type == boolean.class) values[i] = false;
            else if (type == byte.class) values[i] = (byte) 0;
            else if (type == short.class) values[i] = (short) 0;
            else if (type == char.class) values[i] = (char) 0;
            else if (type == int.class) values[i] = 0;
            else if (type == long.class) values[i] = 0L;
            else if (type == float.class) values[i] = 0f;
            else if (type == double.class) values[i] = 0d;
            else if (type.isPrimitive()) throw new IllegalArgumentException("Unsupported callback parameter: " + type);
        }
        return values;
    }
}
