package com.axuan.vivoislandplus;

import java.lang.reflect.*;
import java.util.*;

/** Small typed adapters for stock reply APIs that changed independently of the SDK. */
final class ReplyApiCompat {
    private ReplyApiCompat() { }

    static void showBouncer(Object owner,String reason,boolean scrimmed)throws ReflectiveOperationException {
        List<Method> choices=new ArrayList<>();
        for(Class<?>[] types:new Class<?>[][]{{String.class,boolean.class},{boolean.class,String.class}}) {
            try {
                Method method=owner.getClass().getMethod("showBouncer",types);
                if(Modifier.isStatic(method.getModifiers())||method.getReturnType()!=void.class)
                    throw new ReflectiveOperationException("Stock bouncer API shape changed");
                choices.add(method);
            } catch(NoSuchMethodException absent) { }
        }
        if(choices.size()!=1)throw new ReflectiveOperationException("Missing/ambiguous stock bouncer API");
        Method method=choices.get(0);
        if(method.getParameterTypes()[0]==String.class)method.invoke(owner,reason,scrimmed);
        else method.invoke(owner,scrimmed,reason);
    }

    static void expandGroup(Object groups,Object parent)throws ReflectiveOperationException {
        List<Method> choices=new ArrayList<>();
        for(Method method:groups.getClass().getMethods()) {
            Class<?>[] types=method.getParameterTypes();
            if(method.getName().equals("setGroupExpanded")&&types.length==2&&types[1]==boolean.class
                    &&!types[0].isPrimitive()&&!Modifier.isStatic(method.getModifiers())&&method.getReturnType()==void.class)
                choices.add(method);
        }
        if(choices.size()!=1)throw new ReflectiveOperationException("Missing/ambiguous group expansion API");
        Method expansion=choices.get(0);Class<?> entryType=expansion.getParameterTypes()[0];
        List<Method> getters=new ArrayList<>();
        for(String name:new String[]{"getEntry","getEntryAdapter"}) {
            try {
                Method getter=parent.getClass().getMethod(name);
                if(!Modifier.isStatic(getter.getModifiers())&&getter.getReturnType()==entryType)getters.add(getter);
            } catch(NoSuchMethodException absent) { }
        }
        if(getters.size()!=1)throw new ReflectiveOperationException("Missing/ambiguous parent group entry");
        Object entry=getters.get(0).invoke(parent);
        if(entry==null)throw new ReflectiveOperationException("Parent group entry is unavailable");
        expansion.invoke(groups,entry,true);
    }
}
