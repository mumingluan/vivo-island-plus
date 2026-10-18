package com.axuan.vivoislandplus;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Hooks the platform listener; source applications are never hooked. */
public final class IslandPlus implements IXposedHookLoadPackage {
    public static final String VERSION = BuildInfo.VERSION;
    private static volatile IslandBridge bridge;
    private static volatile NotificationListenerService activeListener;

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if ("com.vivo.musicwidgetmix".equals(lp.packageName)
                && "com.vivo.musicwidgetmix".equals(lp.processName)) {
            OfficialMusicCompat.install(lp.classLoader);
            return;
        }
        if ("com.vivo.systemuiplugin".equals(lp.packageName)) {
            PluginRendererStartup.install(lp.classLoader);
            return;
        }
        if (!"com.android.systemui".equals(lp.packageName)
                || !"com.android.systemui".equals(lp.processName)) return;
        NativeIslandAdapter.installBuilderCompat();
        try { NativeReplyBridge.installCapture(lp.classLoader); }
        catch (Throwable t) { log("native reply capture unavailable: " + t.getClass().getSimpleName()); }
        ListenerHooks.install();
    }

    static void log(String text) { XposedBridge.log("VivoIslandPlus: " + text); }

    static synchronized void listenerConnected(Context context,NotificationListenerService listener) {
        if(bridge==null){activeListener=listener;bridge=new IslandBridge(context,listener);}
        if(activeListener==listener)bridge.connected();
        else log("additional listener instance ignored");
    }
    static IslandBridge listenerBridge(Object listener){return activeListener==listener?bridge:null;}

    static boolean actionDiagnosticsChanged() {
        IslandBridge b = bridge;
        if (b == null) return false;
        b.actionDiagnosticsChanged(); return true;
    }

    static void dependenciesReady() {
        IslandBridge b = bridge;
        if (b != null) b.pluginsChanged();
    }

    static void nativeSourceClaim(StatusBarNotification source) {
        IslandBridge b = bridge;
        if (b != null) b.nativeSourceClaim(source);
    }
}
