package com.axuan.vivoislandplus;

import java.lang.reflect.*;

/** Older stock night-mode templates adjust action icons through this optional API. */
final class LegacyNightActions {
    private final Method update;
    private final Field instance;
    private LegacyNightActions(Method update, Field instance) { this.update=update;this.instance=instance; }

    static LegacyNightActions resolve(ClassLoader loader,Class<?> container)throws ReflectiveOperationException {
        try {
            return bind(Class.forName("com.vivo.systemuiplugin.superx.common.utils.OnGoingNotificationDisplaysUtil",false,loader),container);
        } catch(ClassNotFoundException absent) { return new LegacyNightActions(null,null); }
    }

    static LegacyNightActions bind(Class<?> utility,Class<?> container)throws ReflectiveOperationException {
        final Method method;
        try { method=utility.getDeclaredMethod("updateAodDisplayForDefaultForLiveNotification",container,boolean.class); }
        catch(NoSuchMethodException absent) { return new LegacyNightActions(null,null); }
        Field singleton=utility.getDeclaredField("INSTANCE");
        if(Modifier.isStatic(method.getModifiers())||method.getReturnType()!=void.class
                ||!Modifier.isStatic(singleton.getModifiers())||singleton.getType()!=utility)
            throw new ReflectiveOperationException("Legacy action night-mode API shape changed");
        method.setAccessible(true);singleton.setAccessible(true);
        return new LegacyNightActions(method,singleton);
    }

    boolean supported() { return update!=null; }

    void update(Object actions,boolean lightBackground)throws ReflectiveOperationException {
        if(update!=null)update.invoke(instance.get(null),actions,lightBackground);
    }
}
