package com.axuan.vivoislandplus;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.Instrumentation;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Resolve plugin contracts after ActivityThread publishes its Application. */
final class PluginRendererStartup {
    private static final String PACKAGE = "com.vivo.systemuiplugin";
    private static boolean installed, attempted;
    private PluginRendererStartup() { }

    static synchronized void install(final ClassLoader loader) {
        if (installed) return;
        // Load-package runs inside Application construction. A failed discovery
        // there leaves compact islands visible without expanded live layouts.
        Application current = AndroidAppHelper.currentApplication();
        if (current != null && PACKAGE.equals(current.getPackageName())) {
            installed = true;
            start(loader, current);
            return;
        }
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class,
                new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam param) {
                start(loader, (Application) param.args[0]);
            }
        });
        installed = true;
    }

    private static synchronized void start(ClassLoader loader, Application app) {
        if (attempted || app == null || !PACKAGE.equals(app.getPackageName())) return;
        // The platform publishes mInitialApplication before calling Instrumentation.
        // Do not scan without it, and do not retry an ambiguous contract in a loop.
        if (AndroidAppHelper.currentApplication() != app) return;
        attempted = true;
        try {
            NativeIslandAdapter.installRenderer(loader);
            IslandPlus.log("plugin renderer startup completed");
        } catch (Throwable failure) {
            IslandPlus.log("plugin renderer startup unavailable: " + failure);
        }
    }
}
