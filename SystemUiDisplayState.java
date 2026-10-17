package com.axuan.vivoislandplus;

import android.os.*;
import java.lang.reflect.*;
import de.robv.android.xposed.XposedHelpers;

/** Passive StatusBarStateController observer; no polling, settings writes or wake locks. */
final class SystemUiDisplayState {
    interface Callback { void changed(); }
    private final ClassLoader loader;
    private final Callback callback;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile NotificationDisplayPolicy.State state = NotificationDisplayPolicy.State.UNKNOWN;
    private boolean pending, attached, loggedFailure;
    private Object controller, listener;
    private Method dependencyCache;

    SystemUiDisplayState(ClassLoader loader, Handler worker, Callback callback) {
        this.loader = loader; this.worker = worker; this.callback = callback;
    }
    NotificationDisplayPolicy.State state() { return state; }

    synchronized void attach() {
        if (pending || attached) return;
        pending = true;
        // APK decoding and semantic discovery must stay off SystemUI's main looper.
        if (!worker.post(new Runnable() { public void run() {
            try {
                if (dependencyCache == null)
                    dependencyCache = DexMembers.method(DependencyResolver.discover(HostDexIndex.current()), loader);
                if (!main.post(new Runnable() { public void run() { attachOnMain(); } })) clearPending();
            } catch (Throwable failure) {
                publish(NotificationDisplayPolicy.State.UNKNOWN); report(failure); clearPending();
            }
        } })) pending = false;
    }
    private synchronized void clearPending() { pending = false; }

    private void attachOnMain() {
        try {
            Class<?> contract = XposedHelpers.findClass("com.android.systemui.plugins.statusbar.StatusBarStateController", loader);
            controller = DependencyAccess.get(dependencyCache, contract);
            Class<?> callbacks = XposedHelpers.findClass(contract.getName() + "$StateListener", loader);
            listener = Proxy.newProxyInstance(callbacks.getClassLoader(), new Class<?>[]{callbacks}, new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if (method.getDeclaringClass() == Object.class) {
                        if ("equals".equals(name)) return args != null && args.length == 1 && proxy == args[0];
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("toString".equals(name)) return "VivoIslandPlusDisplayState";
                    }
                    if ("onDozingChanged".equals(name)) {
                        if (!state.known || args == null || args.length == 0 || state.dozing != (Boolean) args[0]) refresh();
                    } else if ("onDozeAmountChanged".equals(name)) {
                        // Fade callbacks arrive every frame. Only the exact full-
                        // doze boundary changes the native visual suppression rule.
                        if (!state.known || args == null || args.length == 0
                                || state.fullDoze != (((Number) args[0]).floatValue() == 1f)) refresh();
                    }
                    return defaultValue(method.getReturnType());
                }
            });
            XposedHelpers.callMethod(controller, "addCallback", listener);
            synchronized (this) { attached = true; }
            refresh();
        } catch (Throwable failure) {
            if (controller != null && listener != null) {
                try { XposedHelpers.callMethod(controller, "removeCallback", listener); } catch (Throwable ignored) { }
            }
            controller = null; listener = null;
            publish(NotificationDisplayPolicy.State.UNKNOWN);
            report(failure);
        } finally { synchronized (this) { pending = false; } }
    }

    private void refresh() {
        try {
            boolean dozing = (Boolean) XposedHelpers.callMethod(controller, "isDozing");
            float amount = ((Number) XposedHelpers.callMethod(controller, "getDozeAmount")).floatValue();
            publish(new NotificationDisplayPolicy.State(true, dozing, amount == 1f));
        } catch (Throwable failure) { publish(NotificationDisplayPolicy.State.UNKNOWN); report(failure); }
    }

    private void publish(NotificationDisplayPolicy.State next) {
        if (next.same(state)) return;
        state = next; callback.changed();
    }

    private void report(Throwable failure) {
        if (loggedFailure) return;
        loggedFailure = true;
        IslandPlus.log("display state unavailable: " + failure.getClass().getSimpleName());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == long.class) return 0L;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }
}
