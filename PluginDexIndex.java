package com.axuan.vivoislandplus;

import android.app.*;
import android.content.pm.ApplicationInfo;
import java.io.*;
import java.lang.ref.SoftReference;
import java.util.*;

/** Shares reclaimable plugin DEX input among immutable contract resolvers. */
final class PluginDexIndex {
    private static final Map<ClassLoader,SoftReference<DexIndex>> cached = new WeakHashMap<>();
    private PluginDexIndex() { }
    static synchronized DexIndex current(ClassLoader loader) throws Exception {
        SoftReference<DexIndex> reference = cached.get(loader);
        DexIndex index = reference == null ? null : reference.get();
        if (index != null) return index;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) throw new IOException("Plugin host application not ready");
        ApplicationInfo info = app.getPackageManager().getApplicationInfo("com.vivo.systemuiplugin", 0);
        List<File> files = new ArrayList<>(); files.add(new File(info.sourceDir));
        if (info.splitSourceDirs != null) for (String source : info.splitSourceDirs) files.add(new File(source));
        index = DexIndex.readApks(files); cached.put(loader, new SoftReference<>(index)); return index;
    }
}
