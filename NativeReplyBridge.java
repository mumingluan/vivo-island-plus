package com.axuan.vivoislandplus;

import android.app.*;
import android.content.Context;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.*;
import de.robv.android.xposed.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opens the original SystemUI notification reply editor; never sends an empty reply. */
final class NativeReplyBridge {
    private static final String DESCRIPTOR = "com.vivo.island.IslandServiceListener";
    private static final int TRANSACTION_REPLY = 0x564952;
    private static final int TRANSACTION_AUTH = 0x564941;
    private static final long TIMEOUT = 60000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final AtomicInteger queued = new AtomicInteger();
    private static final Set<ClassLoader> transports = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final Map<String, Request> active = new HashMap<>();
    private static final Map<View, Long> recentClicks = new WeakHashMap<>();
    private static volatile Object rowBinder;
    private static WeakReference<Object> observedEditor = new WeakReference<>(null);
    private static long observedUntil;
    private static boolean captureInstalled;

    static synchronized void installCapture(ClassLoader loader) {
        if (captureInstalled) return;
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.systemui.statusbar.policy.RemoteInputView", loader),
                "onDefocus", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (observedEditor.get() != p.thisObject || SystemClock.uptimeMillis() > observedUntil) return;
                observedEditor.clear();
                View view = (View) p.thisObject;
                ActionDiagnostics.record("reply.editor.defocus.shown=" + view.isShown());
                StackTraceElement[] stack = new Throwable().getStackTrace();
                int recorded = 0;
                for (StackTraceElement frame : stack) {
                    String name = frame.getClassName();
                    if (!name.startsWith("com.android.systemui.") && !name.startsWith("com.vivo.")) continue;
                    ActionDiagnostics.record("reply.editor.caller/" + name + "." + frame.getMethodName());
                    if (++recorded >= 8) break;
                }
            }
        });
        Class<?> binder = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.NotificationRowContentBinderImpl", loader);
        XposedBridge.hookAllConstructors(binder, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() == null) {
                    rowBinder = p.thisObject;
                    ActionDiagnostics.record("reply.row-binder-captured");
                }
            }
        });
        XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                loader, "onAttachedToWindow", new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (active.isEmpty()) return;
                for (Request request : new ArrayList<>(active.values())) {
                    if (!request.waitingForRow) continue;
                    try {
                        Object entry = request.entry();
                        if (entry == null) { request.finish(); continue; }
                        if (XposedHelpers.getObjectField(entry, "row") == p.thisObject) {
                            request.waitingForRow = false;
                            ActionDiagnostics.record("reply.host.row-reattached");
                            main.post(weakStep(request, 1));
                        }
                    } catch (Throwable failure) { request.finish(); log("row attach", failure); }
                }
            }
        });
        XposedHelpers.findAndHookMethod("com.android.systemui.keyguard.DismissCallbackRegistry",
                loader, "notifyDismissCancelled", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                final Object registry = p.thisObject;
                Runnable cancel = new Runnable() { public void run() {
                    for (Request request : new ArrayList<>(active.values())) {
                        if (request.lifecycle.waiting() && request.dismissRegistry == registry) {
                            ActionDiagnostics.record("reply.host.unlock-cancelled");
                            request.finish();
                        }
                    }
                }};
                if (Looper.myLooper() == Looper.getMainLooper()) cancel.run();
                else main.post(cancel);
            }
        });
        XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                loader, "executeAfterKeyguardGoneAction", new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (active.isEmpty()) return;
                for (Request request : new ArrayList<>(active.values())) {
                    if (request.lifecycle.waiting() && request.keyguardManager == p.thisObject)
                        main.post(weakStep(request, 0));
                }
            }
        });
        captureInstalled = true;
        ActionDiagnostics.record("reply.capture-installed");
    }

    static synchronized void installTransport(ClassLoader loader) throws Exception {
        if (transports.contains(loader)) return;
        PluginActionResolver.Plan plan = PluginActionContracts.plan(loader);
        final Class<?> target = DexMembers.owner(plan.listener, loader);
        XposedBridge.hookMethod(DexMembers.method(plan.transport, loader),
                new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                final boolean authenticate = (Integer) p.args[0] == TRANSACTION_AUTH;
                if ((!authenticate && (Integer) p.args[0] != TRANSACTION_REPLY) || !target.isInstance(p.thisObject)) return;
                ActionDiagnostics.record(authenticate ? "auth.host.transport" : "reply.host.transport");
                p.setResult(true);
                try {
                    Application context = AndroidAppHelper.currentApplication();
                    if (context == null || !trusted(context, Binder.getCallingUid())) {
                        ActionDiagnostics.record("reply.host.untrusted"); return;
                    }
                    Parcel data = (Parcel) p.args[1];
                    if (data.dataSize() > 65536) return;
                    data.enforceInterface(DESCRIPTOR);
                    if (!BuildInfo.SOURCE_ID.equals(data.readString())) {
                        ActionDiagnostics.record("reply.host.source-mismatch"); return;
                    }
                    final String key = data.readString();
                    final long postTime = data.readLong();
                    final int user = data.readInt(), index = data.readInt();
                    final PendingIntent intent = data.readTypedObject(PendingIntent.CREATOR);
                    final PendingIntent callback = authenticate ? data.readTypedObject(PendingIntent.CREATOR) : null;
                    if (key == null || key.length() > 8192 || index < (authenticate ? -1 : 0) || index >= 1024
                            || (authenticate && callback == null)
                            || intent == null || data.dataAvail() != 0) return;
                    if (queued.incrementAndGet() > 16) { queued.decrementAndGet(); return; }
                    if (!main.post(new Runnable() { public void run() {
                        try {
                            if (authenticate) AuthenticatedActionBridge.authenticate(loader, callback,
                                    new AuthenticatedActionBridge.SourceCheck() {
                                public boolean current() { return authenticationSourceCurrent(key, postTime, user, index, intent); }
                            });
                            else start(key, postTime, user, index, intent);
                        }
                        catch (Throwable failure) { log("start", failure); }
                        finally { queued.decrementAndGet(); }
                    }})) queued.decrementAndGet();
                } catch (Throwable failure) { log("transport", failure); }
            }
        });
        transports.add(loader);
        ActionDiagnostics.record("reply.transport-installed");
    }

    private static boolean trusted(Context context, int uid) {
        for (String name : new String[]{"com.android.systemui", "com.vivo.systemuiplugin"}) {
            try { if (context.getPackageManager().getApplicationInfo(name, 0).uid == uid) return true; }
            catch (Exception ignored) { }
        }
        return false;
    }

    static boolean freeForm(Notification.Action action) {
        if (action == null || action.getRemoteInputs() == null) return false;
        for (RemoteInput input : action.getRemoteInputs()) if (input != null && input.getAllowFreeFormInput()) return true;
        return false;
    }

    static int select(View view, PendingIntent intent, Notification notification) {
        int id = view.getResources().getIdentifier("notification_action_index_tag", "id", "android");
        Object index = id == 0 ? null : view.getTag(id);
        Notification.Action[] source = notification.actions;
        RemoteInputActionPolicy.Action[] actions = new RemoteInputActionPolicy.Action[source == null ? 0 : source.length];
        for (int i = 0; i < actions.length; i++) if (source[i] != null)
            actions[i] = new RemoteInputActionPolicy.Action(source[i].actionIntent, freeForm(source[i]));
        return RemoteInputActionPolicy.select(index instanceof Integer ? (Integer) index : null, intent, actions);
    }

    static void request(ClassLoader loader, StatusBarNotification source, View view, PendingIntent intent, int index) {
        if (Looper.myLooper() != Looper.getMainLooper() || !AuthenticatedActionBridge.current(view, source)) {
            ActionDiagnostics.record("reply.client.rejected-binding"); return;
        }
        ActionDiagnostics.record("reply.client.request");
        long now = SystemClock.uptimeMillis();
        Long last = recentClicks.get(view);
        if (last != null && now - last < 500) return;
        recentClicks.put(view, now);
        Parcel data = Parcel.obtain();
        try {
            Class<?> helper = XposedHelpers.findClass("com.vivo.island.utils.IslandNotifyHelper", loader);
            Object instance = XposedHelpers.callStaticMethod(helper, "getInstance");
            Object listener = XposedHelpers.callMethod(instance, "getIslandServiceListener");
            if (listener == null) throw new IllegalStateException("island listener unavailable");
            IBinder binder = (IBinder) XposedHelpers.callMethod(listener, "asBinder");
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(BuildInfo.SOURCE_ID);
            data.writeString(source.getKey()); data.writeLong(source.getPostTime());
            data.writeInt(userId(source.getUser())); data.writeInt(index); data.writeTypedObject(intent, 0);
            if (!binder.transact(TRANSACTION_REPLY, data, null, IBinder.FLAG_ONEWAY))
                throw new IllegalStateException("reply transport unavailable");
            ActionDiagnostics.record("reply.client.transacted");
        } catch (Throwable failure) { log("request", failure); }
        finally { data.recycle(); }
    }

    static void requestAuthentication(ClassLoader loader, StatusBarNotification source, View view,
                                      PendingIntent intent, PendingIntent callback) throws Throwable {
        if (Looper.myLooper() != Looper.getMainLooper() || !AuthenticatedActionBridge.current(view, source))
            throw new IllegalStateException("authentication source view changed");
        int id = view.getResources().getIdentifier("notification_action_index_tag", "id", "android");
        Object tag = id == 0 ? null : view.getTag(id);
        int index = tag instanceof Integer ? (Integer) tag : -1;
        Notification.Action[] actions = source.getNotification().actions;
        if (index < 0 || actions == null || index >= actions.length || actions[index] == null
                || !intent.equals(actions[index].actionIntent)) index = -1;
        Parcel data = Parcel.obtain();
        try {
            Class<?> helper = XposedHelpers.findClass("com.vivo.island.utils.IslandNotifyHelper", loader);
            Object instance = XposedHelpers.callStaticMethod(helper, "getInstance");
            Object listener = XposedHelpers.callMethod(instance, "getIslandServiceListener");
            if (listener == null) throw new IllegalStateException("island listener unavailable");
            IBinder binder = (IBinder) XposedHelpers.callMethod(listener, "asBinder");
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(BuildInfo.SOURCE_ID);
            data.writeString(source.getKey()); data.writeLong(source.getPostTime());
            data.writeInt(userId(source.getUser())); data.writeInt(index);
            data.writeTypedObject(intent, 0); data.writeTypedObject(callback, 0);
            if (!binder.transact(TRANSACTION_AUTH, data, null, IBinder.FLAG_ONEWAY))
                throw new IllegalStateException("authentication transport unavailable");
        } finally { data.recycle(); }
    }

    private static boolean authenticationSourceCurrent(String key, long postTime, int user, int index, PendingIntent intent) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper() || rowBinder == null) return false;
            Application context = AndroidAppHelper.currentApplication();
            if (context == null || user != (Integer) XposedHelpers.callMethod(context, "getUserId")
                    || user != (Integer) XposedHelpers.callStaticMethod(ActivityManager.class, "getCurrentUser")) return false;
            Object collection = XposedHelpers.getObjectField(rowBinder, "mCollection");
            Object entry = XposedHelpers.callMethod(collection, "getEntry", key);
            if (entry == null) return false;
            StatusBarNotification actual = (StatusBarNotification) XposedHelpers.getObjectField(entry, "mSbn");
            Object ranking = XposedHelpers.getObjectField(entry, "mRanking");
            Notification.Action[] source = actual.getNotification().actions;
            ActionAuthenticationPolicy.Action[] actions = new ActionAuthenticationPolicy.Action[source == null ? 0 : source.length];
            for (int i = 0; i < actions.length; i++) if (source[i] != null)
                actions[i] = new ActionAuthenticationPolicy.Action(source[i].actionIntent,
                        source[i].isAuthenticationRequired(), freeForm(source[i]));
            return ActionAuthenticationPolicy.current(key, postTime, intent, user, index, actual.getKey(), actual.getPostTime(),
                    userId(actual.getUser()), actions, (Boolean) XposedHelpers.callMethod(ranking, "isSuspended"));
        } catch (Throwable failure) { log("authentication source", failure); return false; }
    }

    private static void start(String key, long postTime, int user, int index, PendingIntent intent) {
        ActionDiagnostics.record("reply.host.start");
        String id = key + "|" + postTime + "|" + index;
        if (rowBinder == null || active.containsKey(id) || active.size() >= 8) {
            ActionDiagnostics.record(rowBinder == null ? "reply.host.no-row-binder" : "reply.host.duplicate-or-limit"); return;
        }
        Request request = new Request(id, key, postTime, user, index, intent);
        if (request.entry() == null) { ActionDiagnostics.record("reply.host.invalid-entry"); return; }
        // Like the stock pending remote-input view, the latest click replaces
        // the previous unfinished reply. A later unlock cannot open both.
        for (Request previous : new ArrayList<>(active.values())) previous.finish();
        active.put(id, request);
        if (!main.postDelayed(request.timeout, TIMEOUT)) { active.remove(id); return; }
        request.unlock();
    }

    private static final class Request {
        final String id, key;
        final long postTime, deadline = SystemClock.uptimeMillis() + TIMEOUT;
        final int user, index;
        final PendingIntent intent;
        final Runnable timeout = new Runnable() { public void run() { finish(); } };
        boolean finished, waitingForRow;
        final ReplyUnlockPolicy lifecycle;
        Object keyguardObserver, keyguardState, dismissRegistry, keyguardManager;
        ViewTreeObserver readyTree;
        ViewTreeObserver.OnPreDrawListener readyListener;
        Request(String id, String key, long postTime, int user, int index, PendingIntent intent) {
            this.id = id; this.key = key; this.postTime = postTime; this.user = user; this.index = index; this.intent = intent;
            lifecycle = new ReplyUnlockPolicy(deadline);
        }
        Object manager() { return XposedHelpers.getObjectField(rowBinder, "remoteInputManager"); }
        Object callback() { return XposedHelpers.getObjectField(manager(), "mCallback"); }
        Object entry() {
            if (finished || SystemClock.uptimeMillis() >= deadline || rowBinder == null) return null;
            Object collection = XposedHelpers.getObjectField(rowBinder, "mCollection");
            Object entry = XposedHelpers.callMethod(collection, "getEntry", key);
            if (entry == null) return null;
            StatusBarNotification actual = (StatusBarNotification) XposedHelpers.getObjectField(entry, "mSbn");
            Notification.Action[] actions = actual.getNotification().actions;
            if (actions == null || index >= actions.length || actions[index] == null) return null;
            Application context = AndroidAppHelper.currentApplication();
            if (context == null || user != (Integer) XposedHelpers.callMethod(context, "getUserId")
                    || user != (Integer) XposedHelpers.callStaticMethod(ActivityManager.class, "getCurrentUser")) return null;
            Object ranking = XposedHelpers.getObjectField(entry, "mRanking");
            boolean suspended = (Boolean) XposedHelpers.callMethod(ranking, "isSuspended");
            return RemoteInputActionPolicy.current(key, postTime, intent, user, actual.getKey(), actual.getPostTime(),
                    actions[index].actionIntent, userId(actual.getUser()), freeForm(actions[index]), suspended) ? entry : null;
        }
        boolean unlocked() {
            Application context = AndroidAppHelper.currentApplication();
            if (context == null) return false;
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            return !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
        }
        void unlock() {
            try {
                if (entry() == null) { finish(); return; }
                if (unlocked()) {
                    if (lifecycle.release(SystemClock.uptimeMillis(), true, true)) reveal();
                    else finish();
                    return;
                }
                final Object callback = callback();
                Object bouncer = XposedHelpers.getObjectField(callback, "mStatusBarKeyguardViewManager");
                keyguardManager = bouncer;
                dismissRegistry = XposedHelpers.getObjectField(bouncer, "mDismissCallbackRegistry");
                keyguardState = XposedHelpers.getObjectField(callback, "mKeyguardStateController");
                ClassLoader loader = callback.getClass().getClassLoader();
                Class<?> contract = XposedHelpers.findClass("com.android.systemui.statusbar.policy.KeyguardStateController$Callback", loader);
                final WeakReference<Request> weak = new WeakReference<>(this);
                keyguardObserver = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        Request request = weak.get();
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                            return "VivoIslandPlusReplyObserver";
                        }
                        if (request == null || request.finished) return null;
                        if ("onKeyguardShowingChanged".equals(method.getName())
                                || "onUnlockedChanged".equals(method.getName())
                                || "onKeyguardFadingAwayChanged".equals(method.getName()))
                            main.post(weakStep(request, 0));
                        return null;
                    }
                });
                XposedHelpers.callMethod(keyguardState, "addCallback", keyguardObserver);
                ReplyApiCompat.showBouncer(bouncer, "VivoIslandPlus#nativeReply", false);
                ActionDiagnostics.record("reply.host.native-bouncer");
            } catch (Throwable failure) { finish(); log("unlock", failure); }
        }
        void unlockedEvent() {
            try {
                if (entry() == null) { finish(); return; }
                // vivo reports unlocked before its delayed hideKeyguard() runs.
                // Opening the shade during that transition is later undone.
                // Wait for the native window's completed keyguard state.
                if (keyguardManager != null) {
                    Object window = XposedHelpers.getObjectField(keyguardManager, "mNotificationShadeWindowController");
                    Object state = XposedHelpers.getObjectField(window, "mCurrentState");
                    if (XposedHelpers.getBooleanField(state, "keyguardShowing")
                            || XposedHelpers.getBooleanField(state, "keyguardFadingAway")) return;
                }
                if (lifecycle.release(SystemClock.uptimeMillis(), true, unlocked())) reveal();
            } catch (Throwable failure) { finish(); log("unlock event", failure); }
        }
        void reveal() {
            ActionDiagnostics.record("reply.host.reveal");
            try {
                if (!lifecycle.allow(SystemClock.uptimeMillis(), entry() != null, unlocked())) { finish(); return; }
                Object shade = XposedHelpers.getObjectField(callback(), "mShadeController");
                FrameworkRunnableAdapter.postOnShadeExpanded(shade, weakStep(this, 1));
                XposedHelpers.callMethod(shade, "animateExpandShade");
            } catch (Throwable failure) { finish(); log("shade", failure); }
        }
        void expandRow() {
            ActionDiagnostics.record("reply.host.expand-row");
            try {
                Object entry = entry();
                if (entry == null || !unlocked()) { finish(); return; }
                Object row = XposedHelpers.getObjectField(entry, "row");
                if (row == null) { finish(); return; }
                if (!((View) row).isAttachedToWindow()) {
                    waitingForRow = true;
                    ActionDiagnostics.record("reply.host.wait-row-attach");
                    return;
                }
                Object keyguard = XposedHelpers.getObjectField(callback(), "mKeyguardStateController");
                ActionDiagnostics.record("reply.row.showing=" + XposedHelpers.getBooleanField(keyguard, "mShowing")
                        + ".attached=" + ((View) row).isAttachedToWindow() + ".shown=" + ((View) row).isShown());
                StringBuilder visibility = new StringBuilder("reply.row.ancestors");
                View current = (View) row;
                for (int depth = 0; current != null && depth < 10; depth++) {
                    visibility.append('/').append(current.getClass().getSimpleName()).append(':').append(current.getVisibility());
                    ViewParent parent = current.getParent();
                    current = parent instanceof View ? (View) parent : null;
                }
                ActionDiagnostics.record(visibility.toString());
                if ((Boolean) XposedHelpers.callMethod(row, "isChildInGroup")) {
                    Object parent = XposedHelpers.callMethod(row, "getNotificationParent");
                    if (parent == null) { finish(); return; }
                    Object groups = XposedHelpers.getObjectField(callback(), "mGroupExpansionManager");
                    // 24.7 notifies the view attached to the passed entry, not
                    // the resolved summary. Passing a child expands only that
                    // hidden child; expand the actual summary first.
                    ReplyApiCompat.expandGroup(groups, parent);
                    ActionDiagnostics.record("reply.host.expand-source-group");
                    // The 24.7 stock helper tests the CHILD's mChildrenExpanded
                    // and toggles the group again even after its summary was
                    // expanded. Keep its remaining native expansion steps,
                    // without undoing the summary's expansion.
                    XposedHelpers.callMethod(row, "setUserExpanded", true);
                    XposedHelpers.callMethod(XposedHelpers.callMethod(row, "getPrivateLayout"),
                            "setOnExpandedVisibleListener", weakStep(this, 2));
                    return;
                }
                // The stock callback expands groups and waits for the real
                // expanded content. No synthetic View or click is supplied.
                XposedHelpers.callMethod(callback(), "onMakeExpandedVisibleForRemoteInput", row, null, false, weakStep(this, 2));
            } catch (Throwable failure) { finish(); log("row", failure); }
        }
        void focus() {
            try {
                Object entry = entry();
                if (entry == null || !unlocked()) { finish(); return; }
                Object manager = manager();
                Object state = XposedHelpers.getObjectField(manager, "mStatusBarStateController");
                Object lockscreen = XposedHelpers.getObjectField(manager, "mLockscreenUserManager");
                if (((Number) XposedHelpers.callMethod(state, "getState")).intValue() == 1
                        || (Boolean) XposedHelpers.callMethod(lockscreen, "isLockscreenPublicMode", user)) {
                    finish(); return;
                }
                View row = (View) XposedHelpers.getObjectField(entry, "row");
                Object content = XposedHelpers.callMethod(row, "getPrivateLayout");
                View expanded = (View) XposedHelpers.callMethod(content, "getExpandedChild");
                boolean ready = row.isShown() && expanded != null && expanded.isShown()
                        && !(Boolean) XposedHelpers.callMethod(row, "isGroupExpansionChanging");
                if ((Boolean) XposedHelpers.callMethod(row, "isChildInGroup"))
                    ready &= XposedHelpers.getBooleanField(row, "mChildrenExpanded");
                if (!ready) {
                    if (readyListener == null) {
                        ActionDiagnostics.record("reply.host.wait-visible-layout");
                        readyTree = row.getViewTreeObserver();
                        final WeakReference<Request> weak = new WeakReference<>(this);
                        readyListener = new ViewTreeObserver.OnPreDrawListener() {
                            public boolean onPreDraw() {
                                Request request = weak.get();
                                if (request != null && !request.finished) request.focus();
                                return true;
                            }
                        };
                        readyTree.addOnPreDrawListener(readyListener);
                    }
                    return;
                }
                ActionDiagnostics.record("reply.host.focus");
                boolean focused = activateEntry(entry, false);
                if (!focused) IslandPlus.log("native reply editor unavailable");
                else IslandPlus.log("native reply input activation accepted");
                ActionDiagnostics.record(focused ? "reply.host.editor-accepted" : "reply.host.editor-rejected");
                finish();
            } catch (Throwable failure) { finish(); log("focus", failure); }
        }
        boolean activateEntry(Object entry, boolean includeHidden) {
            Object manager = manager();
            Object row = XposedHelpers.getObjectField(entry, "row");
            Object content = XposedHelpers.callMethod(row, "getPrivateLayout");
            View expanded = (View) XposedHelpers.callMethod(content, "getExpandedChild");
            View button = actionView(expanded, index, includeHidden);
            if (button == null) {
                Object editor = XposedHelpers.callMethod(content, "getExpandedRemoteInput");
                if (editor != null && (Boolean) XposedHelpers.callMethod(editor, "isActive"))
                    button = actionView(expanded, index, true);
            }
            if (button == null || !button.isAttachedToWindow()) {
                ActionDiagnostics.record("reply.host.no-attached-action"); return false;
            }
            StatusBarNotification source = (StatusBarNotification) XposedHelpers.getObjectField(entry, "mSbn");
            Notification.Action action = source.getNotification().actions[index];
            RemoteInput[] inputs = action.getRemoteInputs(); RemoteInput selected = null;
            for (RemoteInput input : inputs) if (input != null && input.getAllowFreeFormInput()) selected = input;
            observedEditor = new WeakReference<>(XposedHelpers.callMethod(content, "getExpandedRemoteInput"));
            observedUntil = SystemClock.uptimeMillis() + TIMEOUT;
            return (Boolean) XposedHelpers.callMethod(manager, "activateRemoteInput",
                    button, inputs, selected, action.actionIntent, null);
        }
        void finish() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(weakStep(this, 3)); return;
            }
            if (finished) return;
            finished = true; main.removeCallbacks(timeout);
            lifecycle.finish(); waitingForRow = false;
            if (readyTree != null && readyTree.isAlive() && readyListener != null)
                readyTree.removeOnPreDrawListener(readyListener);
            readyTree = null; readyListener = null;
            if (keyguardObserver != null) {
                try { XposedHelpers.callMethod(keyguardState, "removeCallback", keyguardObserver); }
                catch (Throwable failure) { log("observer cleanup", failure); }
                keyguardObserver = null; keyguardState = null;
            }
            dismissRegistry = null;
            keyguardManager = null;
            if (active.get(id) == this) active.remove(id);
        }
    }

    private static Runnable weakStep(Request request, final int step) {
        final WeakReference<Request> weak = new WeakReference<>(request);
        return new Runnable() { public void run() {
            if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this); return; }
            Request target = weak.get();
            if (target == null || target.finished) return;
            if (step == 0) target.unlockedEvent();
            else if (step == 1) target.expandRow();
            else if (step == 2) target.focus();
            else target.finish();
        }};
    }

    private static View actionView(View root, int index, boolean includeHidden) {
        if (root == null) return null;
        int tag = root.getResources().getIdentifier("notification_action_index_tag", "id", "android");
        if (tag == 0) return null;
        ArrayDeque<View> queue = new ArrayDeque<>(); queue.add(root);
        int remaining = 1024;
        while (!queue.isEmpty() && remaining-- > 0) {
            View view = queue.removeFirst();
            if (!includeHidden && view.getVisibility() == View.GONE) continue;
            Object value = view.getTag(tag);
            if (value instanceof Integer && (Integer) value == index && view.isEnabled() && view.isClickable()) return view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && queue.size() < 1024; i++) queue.addLast(group.getChildAt(i));
            }
        }
        return null;
    }

    private static int userId(UserHandle user) {
        return ((Number) XposedHelpers.callMethod(user, "getIdentifier")).intValue();
    }

    private static void log(String stage, Throwable failure) {
        ActionDiagnostics.record("reply.error." + stage + "." + failure.getClass().getSimpleName());
        IslandPlus.log("native reply " + stage + ": " + failure.getClass().getSimpleName());
    }
}
