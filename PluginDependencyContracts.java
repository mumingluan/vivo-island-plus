package com.axuan.vivoislandplus;

import java.lang.reflect.*;
import java.util.*;

/** Plugin startup readiness, resolved from provider publication rather than private names. */
final class PluginDependencyContracts {
    private static final Map<ClassLoader,Plan> plans = new WeakHashMap<>();
    private static final class Plan {
        final DexIndex.Ref cache, singleton, initializer;
        Plan(DexIndex.Ref cache, DexIndex.Ref singleton, DexIndex.Ref initializer) {
            this.cache = cache; this.singleton = singleton; this.initializer = initializer;
        }
    }
    private PluginDependencyContracts() { }
    private static synchronized Plan plan(ClassLoader loader) throws Exception {
        Plan plan = plans.get(loader); if (plan != null) return plan;
        DexIndex index = PluginDexIndex.current(loader);
        DexIndex.Ref cache = DependencyResolver.discover(index);
        Plan found = new Plan(cache, DependencyResolver.singleton(index, cache), DependencyResolver.initializer(index, cache));
        Field singleton = DexMembers.field(found.singleton, loader);
        Method method = DexMembers.method(found.cache, loader), initializer = DexMembers.method(found.initializer, loader);
        if (!Modifier.isStatic(singleton.getModifiers()) || Modifier.isStatic(initializer.getModifiers())
                || !method.getDeclaringClass().isAssignableFrom(singleton.getType()))
            throw new ReflectiveOperationException("Plugin dependency runtime contract changed");
        plans.put(loader, found); return found;
    }
    static boolean ready(ClassLoader loader) throws Exception { return DexMembers.field(plan(loader).singleton, loader).get(null) != null; }
    static Method initializer(ClassLoader loader) throws Exception { return DexMembers.method(plan(loader).initializer, loader); }
}
