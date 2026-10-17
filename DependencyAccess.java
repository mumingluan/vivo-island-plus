package com.axuan.vivoislandplus;

import java.lang.reflect.*;
import java.util.*;

/** Reads the unique provider singleton without guessing its field name. */
final class DependencyAccess {
    private DependencyAccess() { }
    static Object get(Method cache, Class<?> contract) throws ReflectiveOperationException {
        if (Modifier.isStatic(cache.getModifiers()) || cache.getReturnType() != Object.class
                || !Arrays.equals(cache.getParameterTypes(), new Class<?>[]{Object.class}))
            throw new NoSuchMethodException("Dependency cache contract changed");
        Class<?> owner = cache.getDeclaringClass();
        Field singleton = null;
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !owner.isAssignableFrom(field.getType())) continue;
            if (singleton != null) throw new NoSuchFieldException("Ambiguous dependency singleton");
            singleton = field;
        }
        if (singleton == null) throw new NoSuchFieldException("Missing dependency singleton");
        singleton.setAccessible(true);
        Object provider = singleton.get(null);
        if (provider == null) throw new IllegalStateException("Dependency not initialized");
        cache.setAccessible(true);
        Object value = cache.invoke(provider, contract);
        if (!contract.isInstance(value)) throw new IllegalStateException("Dependency has wrong contract");
        return value;
    }
}
