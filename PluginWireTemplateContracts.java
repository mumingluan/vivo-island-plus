package com.axuan.vivoislandplus;
import android.content.Context;
import java.util.*;

/** Pure DEX/resource input planning; does not initialize or bind OEM model classes. */
final class PluginWireTemplateContracts {
    private static final Map<ClassLoader,BundleWireTemplateResolver.Plan> plans=new WeakHashMap<>();
    private PluginWireTemplateContracts() { }
    static synchronized BundleWireTemplateResolver.Plan plan(Context context)throws Exception {
        ClassLoader loader=context.getClassLoader();BundleWireTemplateResolver.Plan result=plans.get(loader);if(result!=null)return result;
        DexIndex index=PluginDexIndex.current(loader);BeanResolver.Plan core=BeanResolver.discover(index);
        BeanPropertyResolver.Plan properties=BeanPropertyResolver.discover(index,core);
        CapsuleDefaultsResolver.Plan capsule=CapsuleDefaultsResolver.discover(index,core,properties);
        result=BundleWireTemplateResolver.discover(index,core,properties,capsule,PluginTemplateContracts.read(context));
        plans.put(loader,result);IslandPlus.log("Bundle wire template contract "+result.describe());return result;
    }
}
