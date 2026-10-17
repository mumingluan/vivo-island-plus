package com.axuan.vivoislandplus;

import android.app.*;
import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.RemoteViews;
import de.robv.android.xposed.*;
import java.lang.reflect.*;
import java.util.*;

/** Original action routing and authentication relay; input uses the stock reply editor. */
final class AuthenticatedActionBridge {
    static final String BINDING = NativeIslandAdapter.MARKER + ".actionSource";
    private static final String RESULT = "com.axuan.vivoislandplus.auth.result";
    private static final String SOURCE = "com.axuan.vivoislandplus.auth.source";
    private static final String VERIFIED = "com.axuan.vivoislandplus.auth.sourceCurrent";
    private static final int REQUEST_TYPE = 0x564941;
    private static final long TIMEOUT = 60000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<ClassLoader> hostHooks = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final Set<ClassLoader> renderHooks = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final Map<View, ClientRequest> clients = new WeakHashMap<>();
    private static final Set<PendingIntent> hosts = new HashSet<>();

    static synchronized void installHost(final ClassLoader loader) throws Exception {
        if (hostHooks.contains(loader)) return;
        Method listener = DexMembers.method(PluginActionContracts.plan(loader).listener, loader);
        XposedBridge.hookMethod(listener, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if ((Integer) p.args[1] != REQUEST_TYPE) return;
                // This private request type never carries the application's PI.
                // An old host may send the callback immediately, but cannot
                // produce the matching result/source acknowledgement.
                p.setResult(null);
                final PendingIntent callback = (PendingIntent) p.args[0];
                main.post(new Runnable() { public void run() { authenticate(loader, callback); } });
            }
        });
        hostHooks.add(loader);
    }

    static synchronized void installRenderer(final ClassLoader loader) throws Exception {
        if (renderHooks.contains(loader)) return;
        Method events = DexMembers.method(PluginActionContracts.plan(loader).clickHandler, loader);
        final Class<?> handler = XposedHelpers.findClass("android.widget.RemoteViews$InteractionHandler", loader);
        XposedBridge.hookMethod(events, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                final StatusBarNotification source = (StatusBarNotification) p.args[0];
                if (source == null || source.getNotification().extras == null
                        || !source.getNotification().extras.getBoolean(NativeIslandAdapter.MARKER)) return;
                Notification.Action[] actions = source.getNotification().actions;
                boolean needsHandler = false;
                if (actions != null) for (Notification.Action action : actions)
                    if (action != null && (action.isAuthenticationRequired() || NativeReplyBridge.freeForm(action))) needsHandler = true;
                if (!needsHandler) return;
                final Object original = p.getResult();
                p.setResult(Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[]{handler}, new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                            return "VivoIslandPlusAuthenticatedActions";
                        }
                        if (!"onInteraction".equals(method.getName())) return false;
                        View view = (View) args[0]; PendingIntent intent = (PendingIntent) args[1];
                        ActionDiagnostics.record("actions.clicked");
                        try {
                            int input = NativeReplyBridge.select(view, intent, source.getNotification());
                            if (input != RemoteInputActionPolicy.NONE) {
                                ActionDiagnostics.record(input >= 0 ? "reply.selected" : "reply.ambiguous");
                                if (input >= 0) NativeReplyBridge.request(loader, source, view, intent, input);
                                return true;
                            }
                            if (!required(view, intent, source.getNotification()))
                                return original != null ? method.invoke(original, args) : sendOriginal(view, intent, args[2]);
                            request(loader, source, view, intent, args[2]);
                        } catch (Throwable failure) { log("click", failure); }
                        // Failure must not fall through to an unauthenticated send.
                        return true;
                    }
                }));
            }
        });
        renderHooks.add(loader);
        ActionDiagnostics.record("actions.renderer-installed");
    }

    private static boolean required(View view, PendingIntent intent, Notification notification) {
        int id = view.getResources().getIdentifier("notification_action_index_tag", "id", "android");
        Object index = id == 0 ? null : view.getTag(id);
        Notification.Action[] original = notification.actions;
        ActionAuthenticationPolicy.Action[] actions = new ActionAuthenticationPolicy.Action[original == null ? 0 : original.length];
        for (int i = 0; i < actions.length; i++) if (original[i] != null)
            actions[i] = new ActionAuthenticationPolicy.Action(original[i].actionIntent, original[i].isAuthenticationRequired());
        return ActionAuthenticationPolicy.required(index instanceof Integer ? (Integer) index : null, intent, actions);
    }

    static boolean current(View view, StatusBarNotification source) {
        if (view == null || !view.isAttachedToWindow()) return false;
        for (View node = view; node != null; ) {
            if (XposedHelpers.getAdditionalInstanceField(node, BINDING) == source) return true;
            ViewParent parent = node.getParent(); node = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean sendOriginal(View view, PendingIntent intent, Object response) {
        Object options = XposedHelpers.callMethod(response, "getLaunchOptions", view);
        return (Boolean) XposedHelpers.callStaticMethod(RemoteViews.class, "startPendingIntent", view, intent, options);
    }

    private static void request(ClassLoader loader, StatusBarNotification source, View view, PendingIntent intent, Object response) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("action outside UI thread");
        if (!current(view, source) || clients.containsKey(view) || clients.size() >= 8) {
            ActionDiagnostics.record("auth.client.rejected-binding-or-limit"); return;
        }
        ActionDiagnostics.record("auth.client.request");
        Application context = AndroidAppHelper.currentApplication();
        if (context == null) return;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (!keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked()) {
            sendOriginal(view, intent, response); return;
        }
        ClientRequest request = new ClientRequest(context, source, view, intent, response);
        clients.put(view, request);
        try {
            request.register();
            NativeReplyBridge.requestAuthentication(loader, source, view, intent, request.callback);
        } catch (Throwable failure) { request.finish(null); log("request", failure); }
    }

    private static final class ClientRequest extends BroadcastReceiver {
        final Context context;
        final StatusBarNotification source;
        // Authentication legitimately detaches the card. Keep its launch
        // context until this bounded request finishes, like the stock action.
        final View view;
        final PendingIntent original;
        final Object response;
        final ActionAuthenticationPolicy.Result result = new ActionAuthenticationPolicy.Result(BuildInfo.SOURCE_ID,
                SystemClock.uptimeMillis(), TIMEOUT);
        final Runnable timeout = new Runnable() { public void run() { finish(null); } };
        PendingIntent callback;
        boolean registered, finished;
        ClientRequest(Context context, StatusBarNotification source, View view, PendingIntent original, Object response) {
            this.context = context; this.source = source; this.view = view;
            this.original = original; this.response = response;
        }
        void register() {
            String action = "com.axuan.vivoislandplus.AUTH_" + UUID.randomUUID().toString();
            context.registerReceiver(this, new IntentFilter(action), null, main, Context.RECEIVER_NOT_EXPORTED);
            registered = true;
            // The mutable object is a private callback, never the app operation.
            callback = PendingIntent.getBroadcast(context, 0, new Intent(action).setPackage(context.getPackageName()),
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
            if (!main.postDelayed(timeout, TIMEOUT)) throw new IllegalStateException("callback queue unavailable");
        }
        public void onReceive(Context context, Intent intent) { finish(intent); }
        void finish(Intent acknowledgement) {
            if (finished) return;
            finished = true;
            main.removeCallbacks(timeout);
            View target = view;
            if (clients.get(target) == this) clients.remove(target);
            if (callback != null) { try { callback.cancel(); } catch (Throwable failure) { log("callback cleanup", failure); } }
            if (registered) { try { context.unregisterReceiver(this); } catch (Throwable failure) { log("receiver cleanup", failure); } }
            try {
                KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                ActionDiagnostics.record("auth.client.ack=" + (acknowledgement != null)
                        + ".result=" + (acknowledgement != null && acknowledgement.getBooleanExtra(RESULT, false))
                        + ".current=" + current(target, source)
                        + ".attached=" + (target != null && target.isAttachedToWindow())
                        + ".keyguard=" + keyguard.isKeyguardLocked() + ".device=" + keyguard.isDeviceLocked());
                boolean approved = result.accept(SystemClock.uptimeMillis(),
                        acknowledgement == null ? null : acknowledgement.getStringExtra(SOURCE),
                        acknowledgement != null && acknowledgement.getBooleanExtra(RESULT, false),
                        target != null && acknowledgement != null && acknowledgement.getBooleanExtra(VERIFIED, false),
                        keyguard.isKeyguardLocked(), keyguard.isDeviceLocked());
                if (approved) ActionDiagnostics.record(sendOriginal(target, original, response)
                        ? "auth.client.sent" : "auth.client.send-rejected");
                ActionDiagnostics.record(approved ? "auth.client.approved" : "auth.client.rejected");
            } catch (Throwable failure) { log("result", failure); }
        }
    }

    interface SourceCheck { boolean current(); }

    private static void authenticate(final ClassLoader loader, final PendingIntent callback) {
        authenticate(loader, callback, null);
    }

    static void authenticate(final ClassLoader loader, final PendingIntent callback, final SourceCheck sourceCheck) {
        ActionDiagnostics.record("auth.host.request");
        try {
            Application context = AndroidAppHelper.currentApplication();
            if (context == null || callback == null || !callback.isBroadcast() || hosts.size() >= 8) return;
            String creator = callback.getCreatorPackage();
            if (!"com.android.systemui".equals(creator) && !"com.vivo.systemuiplugin".equals(creator)) return;
            if (context.getPackageManager().getApplicationInfo(creator, 0).uid != callback.getCreatorUid()) return;
            if (!hosts.add(callback)) return;
            final HostRequest request = new HostRequest(context, callback, sourceCheck);
            if (sourceCheck == null || !sourceCheck.current()) {
                ActionDiagnostics.record("auth.host.source-rejected-before-unlock"); request.finish(false); return;
            }
            if (!main.postDelayed(request.timeout, TIMEOUT)) { hosts.remove(callback); return; }
            try {
                Class<?> contract = XposedHelpers.findClass("com.android.systemui.plugins.ActivityStarter", loader);
                Class<?> onDismiss = XposedHelpers.findClass(contract.getName() + "$OnDismissAction", loader);
                Class<?> dependencies = XposedHelpers.findClass("com.android.systemui.plugins.PluginDependency", loader);
                Object starter = XposedHelpers.callStaticMethod(dependencies, "get", contract);
                Object action = Proxy.newProxyInstance(onDismiss.getClassLoader(), new Class<?>[]{onDismiss}, new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onDismiss".equals(method.getName())) request.finish(true);
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                            return "VivoIslandPlusDismissAction";
                        }
                        return method.getReturnType() == boolean.class ? false : null;
                    }
                });
                XposedHelpers.callMethod(starter, "dismissKeyguardThenExecute", action,
                        new Runnable() { public void run() { request.finish(false); } }, true);
            } catch (Throwable failure) { request.finish(false); log("host", failure); }
        } catch (Throwable failure) { log("host validation", failure); }
    }

    private static final class HostRequest {
        final Context context;
        final PendingIntent callback;
        final SourceCheck sourceCheck;
        final Runnable timeout = new Runnable() { public void run() { finish(false); } };
        boolean finished;
        HostRequest(Context context, PendingIntent callback, SourceCheck sourceCheck) {
            this.context = context; this.callback = callback; this.sourceCheck = sourceCheck;
        }
        void finish(final boolean approved) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(new Runnable() { public void run() { finish(approved); } }); return;
            }
            if (finished) return;
            finished = true; hosts.remove(callback); main.removeCallbacks(timeout);
            try {
                KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                boolean sourceCurrent = approved && sourceCheck != null && sourceCheck.current();
                ActionDiagnostics.record(sourceCurrent ? "auth.host.source-current" : "auth.host.source-rejected");
                ActionDiagnostics.record("auth.host.finish=" + approved + ".keyguard="
                        + keyguard.isKeyguardLocked() + ".device=" + keyguard.isDeviceLocked());
                callback.send(context, 0, new Intent().putExtra(SOURCE, BuildInfo.SOURCE_ID)
                        .putExtra(VERIFIED, sourceCurrent)
                        .putExtra(RESULT, approved && sourceCurrent && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked()));
                ActionDiagnostics.record("auth.host.callback-sent");
            } catch (PendingIntent.CanceledException ignored) { ActionDiagnostics.record("auth.host.callback-cancelled"); }
            catch (Throwable failure) { log("host result", failure); }
        }
    }

    private static void log(String stage, Throwable failure) {
        ActionDiagnostics.record("auth.error." + stage + "." + failure.getClass().getSimpleName());
        IslandPlus.log("authenticated action " + stage + ": " + failure.getClass().getSimpleName());
    }
}
