package com.axuan.vivoislandplus;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public final class DependencyResolverTest {
    interface Contract { }
    static final class Value implements Contract { }
    static class Provider {
        static Provider renamed = new Provider();
        static String unrelated = "decoy";
        Object resolve(Object type) { return new Value(); }
    }
    static class Ambiguous {
        static Ambiguous first, second;
        Object resolve(Object type) { throw new AssertionError("Must reject before invocation"); }
    }
    static class Missing {
        Object resolve(Object type) { throw new AssertionError("Must reject before invocation"); }
    }
    public static void main(String[] args) throws Exception {
        DexIndex index = DexIndex.readApks(Collections.singletonList(new File(args[0])));
        DexIndex.Ref cache = DependencyResolver.discover(index);
        DexIndex.Ref singleton = DependencyResolver.singleton(index, cache);
        System.out.println("cache=" + cache + "\nsingleton=" + singleton);
        DexIndex renamedIndex = ResolverRobustnessTest.renamed(index);
        DexIndex.Ref renamed = DependencyResolver.discover(renamedIndex);
        if (!renamed.owner.startsWith("Ldiscovered/") || renamed.name.equals(cache.name)) throw new AssertionError("Names not renamed");
        if (DependencyResolver.singleton(renamedIndex, renamed).name.equals(singleton.name)) throw new AssertionError("Singleton field not renamed");
        if (args.length > 1 && args[1].equals("plugin")) {
            DexIndex.Ref initializer = DependencyResolver.initializer(index, cache);
            System.out.println("initializer=" + initializer);
            DexIndex.Ref renamedInitializer = DependencyResolver.initializer(renamedIndex, renamed);
            if (renamedInitializer.name.equals(initializer.name)) throw new AssertionError("Initializer name not renamed");
            DexIndex.MethodData init = index.methods.remove(initializer.key());
            rejectInitializer(index, cache); index.methods.put(initializer.key(), init);
            index.methods.put("ambiguous-init", init); rejectInitializer(index, cache); index.methods.remove("ambiguous-init");
        }
        DexIndex.MethodData original = index.methods.remove(cache.key());
        reject(index); index.methods.put(cache.key(), original);
        index.methods.put("ambiguous-copy", original); reject(index); index.methods.remove("ambiguous-copy");
        DexIndex.FieldData field = index.declaredFields.remove(singleton.key());
        reject(index); index.declaredFields.put(singleton.key(), field);
        index.declaredFields.put("ambiguous-field", field); reject(index); index.declaredFields.remove("ambiguous-field");
        Method method = Provider.class.getDeclaredMethod("resolve", Object.class);
        if (!(DependencyAccess.get(method, Contract.class) instanceof Contract)) throw new AssertionError();
        Provider.renamed = null;
        try { DependencyAccess.get(method, Contract.class); throw new AssertionError("Accepted uninitialized provider"); }
        catch (IllegalStateException expected) { }
        Provider.renamed = new Provider();
        for (Class<?> type : new Class<?>[]{Missing.class, Ambiguous.class}) {
            try { DependencyAccess.get(type.getDeclaredMethod("resolve", Object.class), Contract.class); throw new AssertionError("Accepted " + type); }
            catch (NoSuchFieldException expected) { }
        }
        try { DependencyAccess.get(method, Runnable.class); throw new AssertionError("Accepted wrong dependency type"); }
        catch (IllegalStateException expected) { }
        System.out.println("PASS renamed DEX roles/singleton field, missing and ambiguous cache/singleton, uninitialized provider and wrong contract");
    }
    private static void reject(DexIndex index) throws Exception {
        try { DependencyResolver.discover(index); }
        catch (IOException expected) { return; }
        throw new AssertionError("Accepted missing/ambiguous dependency cache");
    }
    private static void rejectInitializer(DexIndex index, DexIndex.Ref cache) throws Exception {
        try { DependencyResolver.initializer(index, cache); }
        catch (IOException expected) { return; }
        throw new AssertionError("Accepted missing/ambiguous dependency initializer");
    }
}
