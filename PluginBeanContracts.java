package com.axuan.vivoislandplus;

import java.lang.reflect.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Exact runtime binding of the DEX-proven model/transport contract. */
final class PluginBeanContracts {
    private static final Map<ClassLoader,WeakReference<PluginBeanContracts>> plans=new WeakHashMap<>();
    final BeanResolver.Plan schema;
    final BeanPropertyResolver.Plan properties;
    final BeanModelResolver.Plan model;
    final Method post;
    private final Field singleton;
    private final Method mainFactory,leftFactory,rightFactory;
    private final Map<String,Field> fields=new LinkedHashMap<>();
    private final Map<String,Method> setters=new LinkedHashMap<>();
    private PluginBeanContracts(BeanResolver.Plan schema,BeanPropertyResolver.Plan properties,BeanModelResolver.Plan model,ClassLoader loader)throws Exception {
        this.schema=schema;
        this.properties=properties;
        this.model=model;
        Class<?> bean=DexMembers.type(schema.bean,loader),left=DexMembers.type(schema.left,loader),right=DexMembers.type(schema.right,loader);
        if(!android.os.Parcelable.class.isAssignableFrom(bean)||!android.os.Parcelable.class.isAssignableFrom(left)
                ||!android.os.Parcelable.class.isAssignableFrom(right))throw new ReflectiveOperationException("Island model no longer implements Parcelable");
        post=DexMembers.method(schema.post,loader);singleton=DexMembers.field(schema.singleton,loader);
        if(Modifier.isStatic(post.getModifiers())||!Modifier.isStatic(singleton.getModifiers())
                ||singleton.getType()!=post.getDeclaringClass()||post.getReturnType()!=void.class
                ||!Arrays.equals(post.getParameterTypes(),new Class<?>[]{bean}))throw new ReflectiveOperationException("Island transport runtime shape changed");
        mainFactory=factory(schema.mainFactory,bean,loader);leftFactory=factory(schema.leftFactory,left,loader);rightFactory=factory(schema.rightFactory,right,loader);
        Class<?> runtimeModel=DexMembers.type(model.model,loader);
        Constructor<?> runtimeConstructor=DexMembers.constructor(model.constructor,loader);
        Method materializer=DexMembers.method(model.materializer,loader);
        if(runtimeConstructor.getDeclaringClass()!=runtimeModel||materializer.getReturnType()!=runtimeModel
                ||Modifier.isStatic(materializer.getModifiers())||materializer.getParameterTypes().length!=0
                ||!Arrays.equals(runtimeConstructor.getParameterTypes(),new Class<?>[]{materializer.getDeclaringClass()}))
            throw new ReflectiveOperationException("Island runtime model shape changed");
        for(DexIndex.Ref ref:model.unreadModel) {
            Field field=DexMembers.field(ref,loader);
            if(field.getDeclaringClass()!=runtimeModel||Modifier.isStatic(field.getModifiers()))
                throw new ReflectiveOperationException("Write-only model slot runtime shape changed");
        }
        Map<String,DexIndex.Ref> fieldRefs=new LinkedHashMap<>(schema.fields),setterRefs=new LinkedHashMap<>(schema.setters);
        for(String role:properties.fields.keySet()) {
            if(fieldRefs.containsKey(role)||fieldRefs.containsValue(properties.fields.get(role)))
                throw new ReflectiveOperationException("Island semantic property aliases a core field");
            fieldRefs.put(role,properties.fields.get(role));setterRefs.put(role,properties.setters.get(role));
        }
        for(String role:model.fields.keySet()) {
            if(fieldRefs.containsKey(role)||fieldRefs.containsValue(model.fields.get(role)))throw new ReflectiveOperationException("Runtime property aliases another role");
            fieldRefs.put(role,model.fields.get(role));setterRefs.put(role,model.setters.get(role));
        }
        for(String role:fieldRefs.keySet()) {
            Field field=DexMembers.field(fieldRefs.get(role),loader);
            Method setter=DexMembers.method(setterRefs.get(role),loader);
            if(Modifier.isStatic(field.getModifiers())||Modifier.isStatic(setter.getModifiers())
                    ||setter.getDeclaringClass()!=field.getDeclaringClass()||setter.getReturnType()!=void.class
                    ||!Arrays.equals(setter.getParameterTypes(),new Class<?>[]{field.getType()}))
                throw new ReflectiveOperationException("Island property runtime shape changed: "+role);
            fields.put(role,field);setters.put(role,setter);
        }
    }
    static synchronized PluginBeanContracts resolve(ClassLoader loader)throws Exception {
        WeakReference<PluginBeanContracts> reference=plans.get(loader);
        PluginBeanContracts result=reference==null?null:reference.get();
        if(result==null) {
            DexIndex index=PluginDexIndex.current(loader);BeanResolver.Plan core=BeanResolver.discover(index);
            BeanPropertyResolver.Plan properties=BeanPropertyResolver.discover(index,core);
            result=new PluginBeanContracts(core,properties,BeanModelResolver.discover(index,core,properties),loader);plans.put(loader,new WeakReference<>(result));
        }
        return result;
    }
    private static Method factory(DexIndex.Ref ref,Class<?> type,ClassLoader loader)throws Exception {
        Method method=DexMembers.method(ref,loader);
        if(!Modifier.isStatic(method.getModifiers())||method.getReturnType()!=type||method.getParameterTypes().length!=0)
            throw new ReflectiveOperationException("Island factory runtime shape changed");
        return method;
    }
    Object instance()throws Exception {
        Object value=singleton.get(null);if(value==null)throw new IllegalStateException("Island transport is not ready");return value;
    }
    Object main()throws Exception{return mainFactory.invoke(null);}
    Object left()throws Exception{return leftFactory.invoke(null);}
    Object right()throws Exception{return rightFactory.invoke(null);}
    Object read(Object object,String role)throws Exception{return fields.get(role).get(object);}
    void set(Object object,String role,Object value)throws Exception{setters.get(role).invoke(object,value);}
}
