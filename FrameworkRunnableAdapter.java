package com.axuan.vivoislandplus;

import de.robv.android.xposed.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapts our callback to the shade API's actual Runnable parameter contract. */
final class FrameworkRunnableAdapter {
    private static final String MARKER = "com.axuan.vivoislandplus.shadeCallback";
    private static final Set<Class<?>> hooked = Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static final Map<Class<?>, Constructor<?>> constructors = new WeakHashMap<>();
    private FrameworkRunnableAdapter() { }

    static void postOnShadeExpanded(Object shade, Runnable continuation) throws ReflectiveOperationException {
        try {
            Method standard = shade.getClass().getMethod("postOnShadeExpanded", Runnable.class);
            standard.invoke(shade, continuation);
            ActionDiagnostics.record("reply.shade.runnable-callback");
            return;
        } catch (NoSuchMethodException specialized) { }
        for (Method method : shade.getClass().getMethods()) {
            if (!"postOnShadeExpanded".equals(method.getName()) || method.getParameterTypes().length != 1) continue;
            Class<?> expected = method.getParameterTypes()[0];
            if (!Runnable.class.isAssignableFrom(expected) || expected.isInterface()
                    || Modifier.isAbstract(expected.getModifiers())) continue;
            final Constructor<?> constructor;
            try {
                String owner = "L" + expected.getName().replace('.', '/') + ";";
                constructor = constructor(expected, owner);
            } catch (IOException invalid) {
                throw new ReflectiveOperationException("Unsafe/unknown shade callback constructor", invalid);
            }
            install(expected);
            Object callback = constructor.newInstance(TypedRunnablePlan.emptyCaptures(constructor.getParameterTypes()));
            // The marker stays for the lifetime of this callback: duplicate runs
            // are swallowed too, so the unrelated stock switch never executes.
            XposedHelpers.setAdditionalInstanceField(callback, MARKER, new Ticket(continuation));
            method.invoke(shade, callback);
            ActionDiagnostics.record("reply.shade.typed-callback");
            return;
        }
        throw new NoSuchMethodException("supported postOnShadeExpanded callback");
    }

    private static synchronized Constructor<?> constructor(Class<?> expected, String owner)
            throws IOException, ReflectiveOperationException {
        Constructor<?> found = constructors.get(expected);
        if (found == null) {
            found = DexMembers.constructor(TypedRunnablePlan.constructor(HostDexIndex.current(), owner), expected.getClassLoader());
            constructors.put(expected, found);
        }
        return found;
    }

    private static synchronized void install(Class<?> expected) {
        if (hooked.contains(expected)) return;
        XposedHelpers.findAndHookMethod(expected, "run", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                Object value = XposedHelpers.getAdditionalInstanceField(p.thisObject, MARKER);
                if (!(value instanceof Ticket)) return;
                p.setResult(null);
                Ticket ticket = (Ticket) value;
                if (ticket.used.compareAndSet(false, true)) ticket.continuation.run();
            }
        });
        hooked.add(expected);
    }

    private static final class Ticket {
        final AtomicBoolean used = new AtomicBoolean();
        final Runnable continuation;
        Ticket(Runnable continuation) { this.continuation = continuation; }
    }
}
