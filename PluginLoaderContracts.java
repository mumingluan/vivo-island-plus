package com.axuan.vivoislandplus;

import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;

/** Binds verified host loader contracts on the bridge worker after application startup. */
final class PluginLoaderContracts {
    private static final Map<ClassLoader,PluginLoaderResolver.Plan> plans=new WeakHashMap<>();
    private static final Set<ClassLoader> installed=Collections.newSetFromMap(new WeakHashMap<ClassLoader,Boolean>());
    private PluginLoaderContracts(){ }
    private static synchronized PluginLoaderResolver.Plan plan(final ClassLoader host)throws Exception {
        PluginLoaderResolver.Plan plan=plans.get(host);
        if(plan==null) {
            plan=PluginLoaderResolver.discover(HostDexIndex.current());
            Field cache=DexMembers.field(plan.cache,host);
            Method factory=DexMembers.method(plan.factory,host),lifecycle=DexMembers.method(plan.lifecycle,host);
            if(!Modifier.isStatic(cache.getModifiers())||!Map.class.isAssignableFrom(cache.getType())
                    ||factory.getReturnType()!=ClassLoader.class||Modifier.isStatic(factory.getModifiers())!=plan.staticFactory
                    ||Modifier.isStatic(lifecycle.getModifiers())||lifecycle.getReturnType()!=void.class||lifecycle.getParameterTypes().length!=0
                    ||lifecycle.getDeclaringClass()!=cache.getDeclaringClass())
                throw new ReflectiveOperationException("Host loader runtime contract changed");
            plans.put(host,plan);
        }
        if(!installed.contains(host)) {
            // Keep startup discovery off handleLoadPackage: currentApplication
            // and installed APK paths are available on the bridge worker here.
            Method factory=DexMembers.method(plan.factory,host),lifecycle=DexMembers.method(plan.lifecycle,host);
            XC_MethodHook.Unhook factoryHook=XposedBridge.hookMethod(factory,new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if(p.hasThrowable()||!(p.getResult() instanceof ClassLoader))return;
                    // A fresh loader does not imply the plugin's dependency
                    // container exists. The bridge worker retains its existing
                    // readiness check before touching singletons/renderers.
                    IslandPlus.dependenciesReady();
                }
            });
            try {XposedBridge.hookMethod(lifecycle,new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if(p.hasThrowable())return;
                    IslandPlus.dependenciesReady();
                }
            });}catch(RuntimeException|Error failure){factoryHook.unhook();throw failure;}
            installed.add(host);IslandPlus.log("host loader contract "+plan.describe());
        }
        return plan;
    }
    static List<ClassLoader> loaders(ClassLoader host)throws Exception {plan(host);return snapshot(host);}
    private static List<ClassLoader> snapshot(ClassLoader host)throws Exception {
        PluginLoaderResolver.Plan plan;
        synchronized(PluginLoaderContracts.class){plan=plans.get(host);}
        if(plan==null)throw new IllegalStateException("Host loader plan not bound");
        Object value=DexMembers.field(plan.cache,host).get(null);
        if(!(value instanceof Map))throw new IllegalStateException("Host loader cache not initialized");
        Map<?,?> cache=(Map<?,?>)value;Object[] values;
        synchronized(cache){values=cache.values().toArray();}
        List<ClassLoader> result=new ArrayList<>();
        for(Object item:values) {
            if(!(item instanceof ClassLoader))throw new IllegalStateException("Host loader cache value changed");
            result.add((ClassLoader)item);
        }
        return result;
    }
}
