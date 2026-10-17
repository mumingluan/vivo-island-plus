package com.axuan.vivoislandplus;

import android.content.*;
import android.service.notification.*;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;

/** Captures the real Context argument before the system registers its actual listener. */
final class ListenerHooks {
    private static final String TICKET="com.axuan.vivoislandplus.listenerRegistration";
    private static final Map<Object,State> states=new WeakHashMap<>();
    private static final Set<Method> hooked=new HashSet<>();
    private static boolean installed;
    private static final class State {
        final Context context;final ListenerLifecycle lifecycle=new ListenerLifecycle();
        State(Context context){this.context=context;}
    }
    private ListenerHooks(){ }
    static synchronized void install()throws ReflectiveOperationException {
        if(installed)return;
        Method registration=ListenerReflection.registration(NotificationListenerService.class);
        XposedBridge.hookMethod(registration,new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if(!(p.thisObject instanceof NotificationListenerService)||!(p.args[0] instanceof Context)||!(p.args[1] instanceof ComponentName))return;
                Context context=(Context)p.args[0];ComponentName component=(ComponentName)p.args[1];Class<?> actual=p.thisObject.getClass();
                if(!"com.android.systemui".equals(context.getPackageName())||!context.getPackageName().equals(component.getPackageName())
                        ||!component.getClassName().equals(actual.getCanonicalName()))return;
                try {
                    installCallbacks(ListenerReflection.discover(NotificationListenerService.class,actual));
                    State state=new State(context);synchronized(ListenerHooks.class){states.put(p.thisObject,state);}
                    p.setObjectExtra(TICKET,state);
                }catch(Throwable failure){IslandPlus.log("listener registration contract unavailable: "+failure.getClass().getSimpleName()+": "+failure.getMessage());}
            }
            protected void afterHookedMethod(MethodHookParam p) {
                Object ticket=p.getObjectExtra(TICKET);if(!(ticket instanceof State))return;State state=(State)ticket;
                if(p.hasThrowable()) {state.lifecycle.failed();synchronized(ListenerHooks.class){if(states.get(p.thisObject)==state)states.remove(p.thisObject);}return;}
                if(state.lifecycle.registered())connected(p.thisObject,state);
            }
        });
        installed=true;IslandPlus.log("platform listener registration hook ready "+registration.getName());
    }
    private static synchronized void installCallbacks(ListenerReflection.Plan plan) {
        List<XC_MethodHook.Unhook> added=new ArrayList<>();List<Method> methods=new ArrayList<>();
        try {
            for(Map.Entry<String,Method> role:plan.callbacks.entrySet()) {
                final String kind=role.getKey();Method method=role.getValue();if(hooked.contains(method))continue;
                XC_MethodHook.Unhook handle=XposedBridge.hookMethod(method,new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        if(p.hasThrowable())return;State state;
                        synchronized(ListenerHooks.class){state=states.get(p.thisObject);}
                        if(state==null)return;
                        if(kind.equals("connected")){if(state.lifecycle.connected())connected(p.thisObject,state);return;}
                        if(!state.lifecycle.ready())return;IslandBridge bridge=IslandPlus.listenerBridge(p.thisObject);if(bridge==null)return;
                        if(kind.equals("posted"))bridge.posted((StatusBarNotification)p.args[0]);
                        else if(kind.equals("removed"))bridge.removed((StatusBarNotification)p.args[0],(Integer)p.args[2]);
                        else if(kind.equals("ranking"))bridge.rankingChanged();
                        else if(kind.equals("validation"))bridge.validation((StatusBarNotification)p.args[0],(Integer)p.args[1]);
                    }
                });added.add(handle);methods.add(method);
            }
            hooked.addAll(methods);
        }catch(RuntimeException|Error failure){for(XC_MethodHook.Unhook handle:added)handle.unhook();throw failure;}
    }
    private static void connected(Object listener,State state) {
        try{IslandPlus.listenerConnected(state.context,(NotificationListenerService)listener);}
        catch(Throwable failure){IslandPlus.log("listener connect: "+failure);}
    }
}
