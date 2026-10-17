package com.axuan.vivoislandplus;
import java.io.*;
import java.util.*;

/** Offline check of the real shade callback contracts in a supplied SystemUI APK. */
public final class ShadeCallbackProbe {
    public static void main(String[] args) throws Exception {
        DexIndex index = DexIndex.readApks(Collections.singletonList(new File(args[0])));
        int count = 0;
        for (DexIndex.MethodData method : new ArrayList<>(index.methods.values())) {
            if (!method.ref.name.equals("postOnShadeExpanded") || !method.ref.type.matches("\\(L[^;]+;\\)V")) continue;
            String owner = method.ref.type.substring(1, method.ref.type.indexOf(')'));
            if (owner.equals("Ljava/lang/Runnable;")) {
                System.out.println("standard " + method.ref); ++count; continue;
            }
            DexIndex.Ref constructor = TypedRunnablePlan.constructor(index, owner);
            System.out.println("specialized " + method.ref + " constructor=" + constructor);
            ++count;
            DexIndex.MethodData original = index.methods.remove(constructor.key());
            try { TypedRunnablePlan.constructor(index, owner); throw new AssertionError("Missing constructor accepted"); }
            catch (IOException expected) { }
            index.methods.put(constructor.key(), original);
            index.methods.put("duplicate-constructor-probe", original);
            try { TypedRunnablePlan.constructor(index, owner); throw new AssertionError("Ambiguous constructor accepted"); }
            catch (IOException expected) { }
            index.methods.remove("duplicate-constructor-probe");
            Set<Integer> opcodes = original.facts().opcodes;
            if (!opcodes.add(0x67)) throw new AssertionError("Original constructor writes static state");
            try { TypedRunnablePlan.constructor(index, owner); throw new AssertionError("Static side effect accepted"); }
            catch (IOException expected) { }
            opcodes.remove(0x67);
            // The matching path has no reference to this class's name. Renaming
            // the owner and its own fields must keep the constructor recognizable.
            DexIndex renamed = ResolverRobustnessTest.renamed(index);
            int position = 0; String renamedOwner = null;
            for (String type : index.parents.keySet()) {
                if (ResolverRobustnessTest.platform(type)) continue;
                if (type.equals(owner)) { renamedOwner = "Ldiscovered/Type" + position + ";"; break; }
                ++position;
            }
            if (renamedOwner == null || !TypedRunnablePlan.constructor(renamed, renamedOwner).owner.equals(renamedOwner))
                throw new AssertionError("Selected constructor could not be resolved after renaming");
        }
        if (count == 0) throw new AssertionError("No shade API found in supplied APK");
        System.out.println("PASS shade API contracts=" + count);
    }
}
