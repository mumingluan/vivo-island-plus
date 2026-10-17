package com.axuan.vivoislandplus;

import java.lang.reflect.*;
import java.util.*;

/** Converts real runtime members to the same typed contract tested against official DEX. */
final class ListenerReflection {
    static final class Plan {
        final Method registration;final Map<String,Method> callbacks;
        Plan(Method registration,Map<String,Method> callbacks){this.registration=registration;this.callbacks=callbacks;}
    }
    private ListenerReflection(){ }
    static Method registration(Class<?> base)throws ReflectiveOperationException {
        Map<String,Method> methods=new LinkedHashMap<>();List<ListenerShape.Member> shape=members(base.getDeclaredMethods(),methods);
        return methods.get(ListenerShape.registration(shape).key());
    }
    static Plan discover(Class<?> base,Class<?> actual)throws ReflectiveOperationException {
        if(base==actual||!base.isAssignableFrom(actual))throw new IllegalArgumentException("Not a concrete platform listener subtype");
        Map<String,Method> methods=new LinkedHashMap<>();List<ListenerShape.Member> b=members(base.getDeclaredMethods(),methods),a=members(actual.getMethods(),methods);
        ListenerShape.Plan shape=ListenerShape.discover(b,a);Map<String,Method> callbacks=new LinkedHashMap<>();
        for(Map.Entry<String,ListenerShape.Member> role:shape.callbacks.entrySet())callbacks.put(role.getKey(),methods.get(role.getValue().key()));
        return new Plan(methods.get(shape.registration.key()),callbacks);
    }
    private static List<ListenerShape.Member> members(Method[] input,Map<String,Method> methods) {
        List<ListenerShape.Member> out=new ArrayList<>();
        for(Method m:input) {
            StringBuilder type=new StringBuilder("(");for(Class<?> p:m.getParameterTypes())type.append(descriptor(p));type.append(')').append(descriptor(m.getReturnType()));
            ListenerShape.Member member=new ListenerShape.Member(descriptor(m.getDeclaringClass()),m.getName(),type.toString(),m.getModifiers());
            out.add(member);methods.put(member.key(),m);
        }
        return out;
    }
    private static String descriptor(Class<?> type) {
        if(type.isArray())return type.getName().replace('.','/');
        if(!type.isPrimitive())return "L"+type.getName().replace('.','/')+";";
        if(type==void.class)return "V";if(type==int.class)return "I";if(type==boolean.class)return "Z";if(type==long.class)return "J";
        if(type==byte.class)return "B";if(type==char.class)return "C";if(type==short.class)return "S";if(type==float.class)return "F";if(type==double.class)return "D";
        throw new IllegalArgumentException("Unknown primitive type");
    }
}
