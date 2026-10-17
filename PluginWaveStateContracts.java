package com.axuan.vivoislandplus;
import android.content.Context;
import java.util.*;

/** Immutable wave-state plan; the Context path performs only DEX/resource analysis. */
final class PluginWaveStateContracts {
    private static final Map<ClassLoader,WaveStateResolver.Plan> plans=new WeakHashMap<>();
    private PluginWaveStateContracts() { }
    static synchronized WaveStateResolver.Plan plan(ClassLoader loader,BeanModelResolver.Plan model,TemplateResolver.Plan templates)throws Exception {
        WaveStateResolver.Plan result=plans.get(loader);
        if(result==null){result=WaveStateResolver.discover(PluginDexIndex.current(loader),model,templates);plans.put(loader,result);}
        DexMembers.method(result.stateSetter,loader);DexMembers.method(result.gate,loader);
        return result;
    }
    static synchronized WaveStateResolver.Plan plan(Context context)throws Exception {
        ClassLoader loader=context.getClassLoader();WaveStateResolver.Plan result=plans.get(loader);if(result!=null)return result;
        DexIndex index=PluginDexIndex.current(loader);BeanResolver.Plan core=BeanResolver.discover(index);
        BeanPropertyResolver.Plan properties=BeanPropertyResolver.discover(index,core);
        result=WaveStateResolver.discover(index,BeanModelResolver.discover(index,core,properties),PluginTemplateContracts.read(context));
        plans.put(loader,result);return result;
    }
}
