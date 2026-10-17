package com.axuan.vivoislandplus;
import java.util.*;

/** Retains only resolved descriptors for each actual plugin class loader. */
final class PluginActionContracts {
    private static final Map<ClassLoader,PluginActionResolver.Plan> plans=new WeakHashMap<>();
    private PluginActionContracts() { }
    static synchronized PluginActionResolver.Plan plan(ClassLoader loader)throws Exception {
        PluginActionResolver.Plan plan=plans.get(loader);if(plan!=null)return plan;
        plan=PluginActionResolver.discover(PluginDexIndex.current(loader));
        // Validate all referenced runtime members before exposing a partial plan.
        DexMembers.method(plan.listener,loader);DexMembers.method(plan.clickHandler,loader);DexMembers.method(plan.transport,loader);
        plans.put(loader,plan);return plan;
    }
}
