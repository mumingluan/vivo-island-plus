package com.axuan.vivoislandplus;

import android.app.*;
import android.content.res.Resources;
import android.content.Context;
import java.io.IOException;
import java.util.*;

/** Immutable semantic template plan for the plugin's actual resource/code pair. */
final class PluginTemplateContracts {
    private static final Map<ClassLoader,TemplateResolver.Plan> plans = new WeakHashMap<>();
    private PluginTemplateContracts() { }
    static synchronized TemplateResolver.Plan plan(ClassLoader loader) throws Exception {
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) throw new IOException("Plugin host application not ready");
        TemplateResolver.Plan plan=read(app,loader);
        DexMembers.method(plan.inflater, loader); DexMembers.method(plan.selector, loader);
        return plan;
    }
    static synchronized TemplateResolver.Plan read(Context context)throws Exception {return read(context,context.getClassLoader());}
    private static TemplateResolver.Plan read(Context context,ClassLoader loader)throws Exception {
        TemplateResolver.Plan plan=plans.get(loader);if(plan!=null)return plan;
        String pkg = "com.vivo.systemuiplugin";
        Resources resources = context.getPackageManager().getResourcesForApplication(pkg);
        String[] names = {"vivo_atom_main_island_icon_music", "vivo_atom_main_island_loading", "vivo_atom_main_island_icon_text_right", "vivo_atom_main_island_icon_progress_right"};
        Map<String,Integer> ids = new LinkedHashMap<>();
        for (int i=0;i<names.length;++i) ids.put(TemplateResolver.ROLES.get(i), resources.getIdentifier(names[i],"id",pkg));
        plan = TemplateResolver.discover(PluginDexIndex.current(loader), ids);
        plans.put(loader, plan); return plan;
    }
}
