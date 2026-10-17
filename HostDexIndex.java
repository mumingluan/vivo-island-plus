package com.axuan.vivoislandplus;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.app.AndroidAppHelper;
import java.io.*;
import java.lang.ref.SoftReference;
import java.util.*;

/** Reusable discovery input that can be reclaimed after immutable plans are resolved. */
final class HostDexIndex {
    private static SoftReference<DexIndex> cached = new SoftReference<>(null);
    private HostDexIndex() { }
    static synchronized DexIndex current() throws IOException {
        DexIndex index = cached.get(); if (index != null) return index;
        Application application = AndroidAppHelper.currentApplication();
        if (application == null) throw new IOException("No host application");
        ApplicationInfo info = application.getApplicationInfo();
        List<File> apks = new ArrayList<>(); apks.add(new File(info.sourceDir));
        if (info.splitSourceDirs != null) for (String path : info.splitSourceDirs) apks.add(new File(path));
        index = DexIndex.readApks(apks); cached = new SoftReference<>(index); return index;
    }
}
