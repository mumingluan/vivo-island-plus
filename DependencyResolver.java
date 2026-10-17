package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Finds the lazy dependency cache from its creation and map-access behavior. */
final class DependencyResolver {
    private DependencyResolver() { }
    static DexIndex.Ref discover(DexIndex index) throws IOException {
        List<DexIndex.MethodData> creators = new ArrayList<>();
        for (DexIndex.MethodData method : index.methods.values()) {
            if (!method.isStatic() && method.ref.type.equals("(Ljava/lang/Object;)Ljava/lang/Object;")
                    && method.facts().text("Unsupported dependency ")
                    && method.facts().text("providers known.")) creators.add(method);
        }
        if (creators.size() != 1) throw new IOException("Missing/ambiguous dependency creator: " + creators.size());
        DexIndex.Ref creator = creators.get(0).ref;
        List<DexIndex.Ref> caches = new ArrayList<>();
        for (DexIndex.MethodData method : index.methods.values()) {
            if (method.isStatic() || !method.ref.owner.equals(creator.owner)
                    || !method.ref.type.equals(creator.type)) continue;
            DexIndex.Facts facts = method.facts();
            if (facts.calls.contains(creator) && mapCall(facts, "get") && mapCall(facts, "put")) caches.add(method.ref);
        }
        if (caches.size() != 1) throw new IOException("Missing/ambiguous dependency cache: " + caches.size());
        singleton(index, caches.get(0));
        return caches.get(0);
    }
    static DexIndex.Ref singleton(DexIndex index, DexIndex.Ref cache) throws IOException {
        List<DexIndex.Ref> fields = new ArrayList<>();
        for (DexIndex.FieldData field : index.declaredFields.values())
            if (field.isStatic() && field.ref.owner.equals(cache.owner)
                    && index.extendsType(field.ref.type, cache.owner)) fields.add(field.ref);
        if (fields.size() != 1) throw new IOException("Missing/ambiguous dependency singleton: " + fields.size());
        return fields.get(0);
    }
    static DexIndex.Ref initializer(DexIndex index, DexIndex.Ref cache) throws IOException {
        DexIndex.Ref singleton = singleton(index, cache);
        Set<DexIndex.Ref> publishers = new HashSet<>();
        for (DexIndex.MethodData method : index.methods.values())
            if (method.isStatic() && method.ref.owner.equals(cache.owner)
                    && method.ref.type.equals("(" + cache.owner + ")V")
                    && method.facts().fields.contains(singleton) && method.facts().opcodes.contains(0x69))
                publishers.add(method.ref);
        List<DexIndex.Ref> starters = new ArrayList<>();
        for (DexIndex.MethodData method : index.methods.values()) {
            if (method.isStatic() || !method.ref.owner.equals(cache.owner)
                    || !method.ref.type.equals("()V") || method.ref.name.startsWith("<")) continue;
            DexIndex.Facts facts = method.facts();
            boolean publishes = facts.fields.contains(singleton) && facts.opcodes.contains(0x69);
            for (DexIndex.Ref call : facts.calls) publishes |= publishers.contains(call);
            if (publishes && mapCall(facts, "put")) starters.add(method.ref);
        }
        if (starters.size() != 1) throw new IOException("Missing/ambiguous dependency initializer: " + starters.size());
        return starters.get(0);
    }
    private static boolean mapCall(DexIndex.Facts facts, String name) {
        for (DexIndex.Ref call : facts.calls)
            if (call.name.equals(name)
                    && call.type.equals(name.equals("get") ? "(Ljava/lang/Object;)Ljava/lang/Object;"
                            : "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                    && (call.owner.equals("Landroid/util/ArrayMap;")
                    || call.owner.equals("Landroid/util/SimpleArrayMap;")
                    || call.owner.equals("Ljava/util/Map;") || call.owner.equals("Ljava/util/HashMap;"))) return true;
        return false;
    }
}
