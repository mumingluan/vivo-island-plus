package com.axuan.vivoislandplus;

import android.content.Context;
import android.content.res.Resources;
import android.app.AndroidAppHelper;
import java.util.*;

/** Request enum plan read from the installed plugin's own layouts and DEX. */
final class PluginBundleTemplateContracts {
    private static final Map<ClassLoader,BundleTemplateResolver.Plan> plans=new WeakHashMap<>();
    private PluginBundleTemplateContracts() { }
    static synchronized BundleTemplateResolver.Plan plan(Context context)throws Exception {
        return plan(context,context.getClassLoader());
    }
    static synchronized BundleTemplateResolver.Plan plan(ClassLoader loader)throws Exception {
        Context context=AndroidAppHelper.currentApplication();
        if(context==null)throw new java.io.IOException("Plugin host application not ready");
        return plan(context,loader);
    }
    private static BundleTemplateResolver.Plan plan(Context context,ClassLoader loader)throws Exception {
        BundleTemplateResolver.Plan plan=plans.get(loader);
        if(plan!=null)return plan;
        String pkg="com.vivo.systemuiplugin";
        Resources resources=context.getPackageManager().getResourcesForApplication(pkg);
        String[] names={"vivo_super_x_v2_template_normal","vivo_super_x_v2_template_buttons_info","vivo_super_x_v2_template_custom"};
        Map<String,Integer> ids=new LinkedHashMap<>();
        for(String side:new String[]{"shade","island"})for(int i=0;i<names.length;i++)
            ids.put(side+"."+BundleTemplateResolver.ROLES.get(i),resources.getIdentifier(names[i]+(side.equals("island")?"_island":""),"layout",pkg));
        plan=BundleTemplateResolver.discover(PluginDexIndex.current(loader),ids);
        plans.put(loader,plan);IslandPlus.log("request template contract "+plan.describe());return plan;
    }
}
