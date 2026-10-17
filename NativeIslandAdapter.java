package com.axuan.vivoislandplus;

import android.app.*;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.*;
import android.service.notification.*;
import android.view.View;
import android.widget.LinearLayout;
import de.robv.android.xposed.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Bridges the original island renderer and original Android notification. */
final class NativeIslandAdapter {
    static final String MARKER = "com.axuan.vivoislandplus.native";
    private static final Set<ClassLoader> hooked = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final Set<ClassLoader> sourceHooks = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static String lastRender;
    private static boolean builderCompatInstalled;
    private final ClassLoader loader;
    private final PluginBeanContracts beans;
    private final Object proxy;
    private final TemplateResolver.Plan compactTemplates;
    private final CapsuleDefaultsResolver.Plan capsuleDefaults;
    private final BundleTemplateResolver.Plan requestTemplates;
    private final WaveStateResolver.Plan waveState;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MainThreadHandoff.Queue mainQueue = new MainThreadHandoff.Queue() {
        public boolean post(Runnable action) { return main.post(action); }
        public void remove(Runnable action) { main.removeCallbacks(action); }
    };

    static NativeIslandAdapter discover(ClassLoader host) {
        try {
            for (ClassLoader candidate : PluginLoaderContracts.loaders(host)) {
                try {
                    // Class presence is insufficient: touching INSTANCE before the
                    // official dependency container starts permanently poisons <clinit>.
                    if (!PluginDependencyContracts.ready(candidate)) continue;
                    return new NativeIslandAdapter(candidate, PluginBeanContracts.resolve(candidate));
                }
                catch (ClassNotFoundException ignored) { }
            }
        } catch (Throwable t) { IslandPlus.log("native discovery: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
        return null;
    }

    private NativeIslandAdapter(ClassLoader loader, PluginBeanContracts beans) throws Exception {
        this.loader = loader;
        compactTemplates = PluginTemplateContracts.plan(loader);
        capsuleDefaults=CapsuleDefaultsResolver.discover(PluginDexIndex.current(loader),beans.schema,beans.properties);
        DexMembers.method(capsuleDefaults.producer,loader);
        CardTemplateTransportResolver.Plan cardTransport=CardTemplateTransportResolver.discover(PluginDexIndex.current(loader),beans.model);
        DexMembers.method(cardTransport.consumer,loader);DexMembers.method(cardTransport.templateGetter,loader);DexMembers.method(cardTransport.sbnGetter,loader);
        requestTemplates=PluginBundleTemplateContracts.plan(loader);
        waveState=PluginWaveStateContracts.plan(loader,beans.model,compactTemplates);
        this.beans = beans;
        proxy = beans.instance();
        IslandPlus.log("native bean contract " + beans.schema.describe());
        IslandPlus.log("native property contract " + beans.properties.describe());
        IslandPlus.log("native model contract " + beans.model.describe());
        IslandPlus.log("compact template contract " + compactTemplates.describe());
        IslandPlus.log("capsule default contract " + capsuleDefaults.describe());
        IslandPlus.log(cardTransport.describe());
        IslandPlus.log(waveState.describe());
        installRenderer(loader);
        try { AuthenticatedActionBridge.installRenderer(loader); }
        catch (Throwable t) { IslandPlus.log("authenticated action renderer unavailable: " + t.getClass().getSimpleName()); }
        try { AuthenticatedActionBridge.installHost(loader); }
        catch (Throwable t) { IslandPlus.log("authenticated action host unavailable: " + t.getClass().getSimpleName()); }
        try { NativeReplyBridge.installTransport(loader); }
        catch (Throwable t) { IslandPlus.log("native reply transport unavailable: " + t.getClass().getSimpleName()); }
        try { installSourceClaims(loader, beans); }
        catch (Throwable t) { IslandPlus.log("native source hooks unavailable: " + t.getClass().getSimpleName()); }
    }

    private static synchronized void installSourceClaims(ClassLoader loader, final PluginBeanContracts beans) {
        if (sourceHooks.contains(loader)) return;
        // Installed only after discovery proves the dependency provider is ready and the
        // existing proxy is initialized. Never touch INSTANCE during early hooks.
        XposedBridge.hookMethod(beans.post, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() != null || p.args[0] == null) return;
                try {
                    Object bean = p.args[0];
                    int operation = ((Integer) beans.read(bean, "operation")).intValue();
                    if (operation != 0 && operation != 1) return;
                    Object id = beans.read(bean, "id");
                    if (id instanceof String && ((String) id).startsWith("vivo-island-plus:")) return;
                    StatusBarNotification source = (StatusBarNotification) beans.read(bean, "sbn");
                    if (source != null && !marked(source)) IslandPlus.nativeSourceClaim(source);
                    // A native removal does not resurrect a dismissed source.
                    // Ownership expires on a changed generation or real removal.
                } catch (Throwable t) { IslandPlus.log("native source metadata: " + t.getClass().getSimpleName()); }
            }
        });
        sourceHooks.add(loader);
    }

    static synchronized void installRenderer(ClassLoader loader) {
        installBuilderCompat();
        if (hooked.contains(loader)) return;
        Class<?> utils = XposedHelpers.findClassIfExists("com.vivo.notification.template.v2.SuperXTemplateUtils", loader);
        if (utils == null) return;
        final java.lang.reflect.Method initializer;
        try { initializer = PluginDependencyContracts.initializer(loader); }
        catch (Exception failure) { throw new IllegalStateException("Plugin dependency initializer unavailable", failure); }
        XposedHelpers.findAndHookMethod(utils, "isLiveNotification", StatusBarNotification.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                StatusBarNotification sbn = (StatusBarNotification) p.args[0];
                if (marked(sbn)) { p.setResult(true); renderAudit(sbn); }
            }
        });
        Class<?> checks = XposedHelpers.findClass("com.vivo.notification.template.v2.SuperXUtils", loader);
        XposedHelpers.findAndHookMethod(checks, "isSuperXNotification", StatusBarNotification.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (marked((StatusBarNotification) p.args[0])) p.setResult(true);
            }
        });
        installCardCompat(loader);
        try { AuthenticatedActionBridge.installRenderer(loader); }
        catch (Throwable t) { IslandPlus.log("authenticated action renderer unavailable: " + t.getClass().getSimpleName()); }
        XposedBridge.hookMethod(initializer, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() == null) IslandPlus.dependenciesReady();
            }
        });
        hooked.add(loader);
        IslandPlus.log("native renderer hooks ready " + Application.getProcessName());
    }

    static synchronized void installBuilderCompat() {
        if (builderCompatInstalled) return;
        XposedHelpers.findAndHookMethod(Notification.Builder.class, "createBigContentView", new XC_MethodHook() {
            private final ThreadLocal<Boolean> fallingBack = new ThreadLocal<>();
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() != null || p.getResult() != null || Boolean.TRUE.equals(fallingBack.get())) return;
                Notification notification = (Notification) XposedHelpers.getObjectField(p.thisObject, "mN");
                if (notification.extras == null || !notification.extras.getBoolean(MARKER)) return;
                try {
                    fallingBack.set(true);
                    p.setResult(((Notification.Builder) p.thisObject).createContentView());
                } finally { fallingBack.remove(); }
            }
        });
        builderCompatInstalled = true;
    }

    private static void installCardCompat(final ClassLoader loader) {
        Class<?> template = XposedHelpers.findClass("com.vivo.notification.template.v2.template.CustomSuperXTemplate", loader);
        final LegacyNightActions nightActions;
        try { nightActions = LegacyNightActions.resolve(loader, android.view.ViewGroup.class); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Action night-mode contract unavailable", failure); }
        XposedHelpers.findAndHookMethod(template, "applyContent", StatusBarNotification.class, int.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                XposedHelpers.setAdditionalInstanceField(p.thisObject, MARKER,
                        marked((StatusBarNotification) p.args[0]));
                StatusBarNotification source = (StatusBarNotification) p.args[0];
                XposedHelpers.setAdditionalInstanceField(p.thisObject, AuthenticatedActionBridge.BINDING,
                        marked(source) ? source : null);
                XposedHelpers.setAdditionalInstanceField(p.thisObject, MARKER + ".notification",
                        marked(source) ? source.getNotification() : null);
                if (marked(source)) XposedHelpers.setAdditionalInstanceField(p.thisObject, MARKER + ".ambient",
                        XposedHelpers.getIntField(p.thisObject, "mRefreshState") == 1);
            }
        });
        XposedHelpers.findAndHookMethod(template, "updateLNForNightMode", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(p.thisObject, MARKER))) return;
                try {
                    final View root = (View) XposedHelpers.getObjectField(p.thisObject, "mTepRemoteViews");
                    if (root != null) {
                        Notification source = (Notification) XposedHelpers.getAdditionalInstanceField(p.thisObject, MARKER + ".notification");
                        if (isCall(source)) {
                            if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(p.thisObject, MARKER + ".ambient")))
                                CallCardButtons.apply(root, source);
                            p.setResult(null); return;
                        }
                        Class<?> colors = XposedHelpers.findClass("com.vivo.systemuiplugin.superx.R$color", loader);
                        int id = XposedHelpers.getStaticIntField(colors, "vivo_progress_style_action_text_color");
                        NativeCardCompat.apply(root, root.getContext().getColor(id), new NativeCardCompat.AodUpdater() {
                            public void update(LinearLayout actions) {
                                try { nightActions.update(actions, !root.getResources().getConfiguration().isNightModeActive()); }
                                catch (ReflectiveOperationException failure) { throw new IllegalStateException("Action night-mode update failed", failure); }
                            }
                        });
                    }
                } catch (Throwable t) { IslandPlus.log("native card styling: " + t); }
                // The original implementation casts every action to Button.
                p.setResult(null);
            }
        });
        XposedHelpers.findAndHookMethod(template, "updateAppearanceForAod", boolean.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(p.thisObject, MARKER)))
                    XposedHelpers.setAdditionalInstanceField(p.thisObject, MARKER + ".ambient", p.args[0]);
            }
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() == null && Boolean.FALSE.equals(p.args[0])) restoreCallControls(p.thisObject);
            }
        });
        XposedHelpers.findAndHookMethod(template, "updateFullAodAmount", float.class, boolean.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(p.thisObject, MARKER))) return;
                float before = XposedHelpers.getFloatField(p.thisObject, "mProcessAmount");
                float after = (Float) p.args[0];
                p.setObjectExtra(MARKER, before <= .5f && after > .5f);
                if (before > .5f && after <= .5f)
                    XposedHelpers.setAdditionalInstanceField(p.thisObject, MARKER + ".ambient", true);
            }
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() == null && Boolean.TRUE.equals(p.getObjectExtra(MARKER))) {
                    XposedHelpers.setAdditionalInstanceField(p.thisObject, MARKER + ".ambient", false);
                    restoreCallControls(p.thisObject);
                }
            }
        });
    }

    private static boolean isCall(Notification source) {
        return source != null && source.extras != null
                && "android.app.Notification$CallStyle".equals(source.extras.getString(Notification.EXTRA_TEMPLATE));
    }

    private static void restoreCallControls(Object template) {
        if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(template, MARKER))) return;
        Notification source = (Notification) XposedHelpers.getAdditionalInstanceField(template, MARKER + ".notification");
        if (!isCall(source)) return;
        try { CallCardButtons.apply((View) XposedHelpers.getObjectField(template, "mTepRemoteViews"), source); }
        catch (Throwable t) { IslandPlus.log("restore call controls: " + t.getClass().getSimpleName()); }
    }

    private static boolean marked(StatusBarNotification sbn) {
        return sbn != null && sbn.getNotification() != null && sbn.getNotification().extras != null
                && sbn.getNotification().extras.getBoolean(MARKER);
    }

    boolean supports(IslandPayload.Model m) {
        if (m.original == null) return false;
        try {
            // 24.7 supplies expanded layouts even for plain and actionless
            // media notifications. Check the actual renderer input instead of
            // guessing from its style; malformed sources use the safe fallback.
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) { m.nativeSupport = "no-host-application"; return false; }
            Notification.Builder builder = Notification.Builder.recoverBuilder(app, m.original.getNotification());
            if (builder.createBigContentView() != null) { m.nativeSupport = "expanded"; return true; }
            if (builder.createContentView() != null) { m.nativeSupport = "content-fallback"; return true; }
            m.nativeSupport = "no-content"; return false;
        } catch (Throwable t) {
            m.nativeSupport = t.getClass().getSimpleName();
            IslandPlus.log("native content unavailable: " + t.getClass().getSimpleName());
            return false;
        }
    }

    void post(final IslandPayload.Model m, final String id, final int operation) throws Exception {
        MainThreadHandoff.call(mainQueue, new MainThreadHandoff.Operation() {
            public void run() throws Throwable { postOnMain(m, id, operation); }
        }, new Runnable() { public void run() {
            try { postOnMain(null, id, 2); }
            catch (Throwable t) { IslandPlus.log("native timeout withdrawal: " + t); }
        }}, 3000);
    }

    private void postOnMain(IslandPayload.Model m, String id, int operation) throws Exception {
        Object bean = beans.main();
        beans.set(bean, "id", id);
        beans.set(bean, "operation", operation);
        beans.set(bean, "forceScreenOn", false);
        if (operation != 2) {
            StatusBarNotification source = m.original.clone();
            Notification n = source.getNotification();
            n.extras.putBoolean(MARKER, true);
            if (m.media && (n.actions == null || n.actions.length == 0) && !m.actions.isEmpty())
                n.actions = m.actions.toArray(new Notification.Action[0]);
            Object left = beans.left();
            Object right = beans.right();
            beans.set(left, "left.template", capsuleDefaults.integer("left.template"));
            Icon icon = m.icon != null ? m.icon : n.getSmallIcon();
            beans.set(left, "left.icon", icon);
            if (m.media) {
                beans.set(right, "right.template", compactTemplates.value("wave"));
                beans.set(right, "right.waveState", waveState.playing);
                ArrayList<Integer> colors = new ArrayList<>();
                colors.add(m.color == 0 ? 0xffffffff : m.color); colors.add(m.color == 0 ? 0xffffffff : m.color);
                beans.set(right, "right.colors", colors);
            } else if (CompactProgressPolicy.loading(m.indeterminate, !m.critical.isEmpty(), m.chronometer)) {
                beans.set(right, "right.template", compactTemplates.value("loading"));
            } else {
                beans.set(right, "right.template", compactTemplates.value("text"));
                beans.set(right, "right.content", IslandPayload.compactContent(m));
            }
            beans.set(bean, "businessKey", capsuleDefaults.string("businessKey"));
            beans.set(bean, "sourcePackageName", m.pkg);
            beans.set(bean, "left", left);
            beans.set(bean, "right", right);
            beans.set(bean, "sbn", source);
            beans.set(bean, "templateCard", requestTemplates.value("custom"));
            beans.set(bean, "atomType", capsuleDefaults.integer("atomType"));
            beans.set(bean, "scrollRemove", true);
            beans.set(bean, "islandClick", 0);
            beans.set(bean, "clickResp", m.click);
            beans.set(bean, "priority", capsuleDefaults.integer("priority"));
            beans.set(bean, "showTime", Integer.MAX_VALUE);
            beans.set(bean, "timeout", System.currentTimeMillis() + (long) Integer.MAX_VALUE * 1000);
            beans.set(bean, "userId", XposedHelpers.callMethod(source.getUser(), "getIdentifier"));
            beans.set(bean, "keyguardShow", m.visibility == Notification.VISIBILITY_PUBLIC);
            JSONObject landing = new JSONObject();
            landing.put("mType", "app"); landing.put("mPkgName", m.pkg); landing.put("isDeepLink", false);
            beans.set(bean, "landingInfo", landing.toString());
        }
        beans.post.invoke(proxy, bean);
    }

    private static synchronized void renderAudit(StatusBarNotification sbn) {
        try {
            String sample = sbn.getKey() + "|" + sbn.getPostTime();
            if (sample.equals(lastRender)) return;
            lastRender = sample;
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            JSONObject audit = new JSONObject();
            audit.put("version", IslandPlus.VERSION); audit.put("process", Application.getProcessName());
            audit.put("sourceId", BuildInfo.SOURCE_ID);
            audit.put("package", sbn.getPackageName());
            audit.put("style", sbn.getNotification().extras.getString(Notification.EXTRA_TEMPLATE));
            audit.put("actions", sbn.getNotification().actions == null ? 0 : sbn.getNotification().actions.length);
            audit.put("sourceCapabilities", NotificationActionSnapshot.capture(sbn.getNotification()));
            audit.put("time", System.currentTimeMillis());
            String process = Application.getProcessName().replaceAll("[^a-zA-Z0-9]", "_");
            File file = new File(app.getFilesDir(), "vivo-island-plus-renderer-" + process + ".json");
            try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(audit.toString(2).getBytes("UTF-8")); }
        } catch (Throwable ignored) { }
    }
}
