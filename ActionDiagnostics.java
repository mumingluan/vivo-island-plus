package com.axuan.vivoislandplus;

import android.os.SystemClock;
import android.os.*;
import android.app.Application;
import android.app.AndroidAppHelper;
import java.io.*;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Bounded stage names only: no notification text, input, keys or PI contents. */
final class ActionDiagnostics {
    private static final ArrayDeque<JSONObject> stages = new ArrayDeque<>();
    private static final AtomicBoolean pending = new AtomicBoolean();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private ActionDiagnostics() { }
    static void record(String stage) {
        synchronized (stages) {
            try {
                JSONObject event = new JSONObject();
                event.put("stage", stage); event.put("elapsed", SystemClock.elapsedRealtime());
                if (stages.size() == 32) stages.removeFirst();
                stages.addLast(event);
            } catch (JSONException ignored) { return; }
        }
        if (!IslandPlus.actionDiagnosticsChanged()) flushRenderer();
    }
    static JSONArray snapshot() {
        synchronized (stages) { return new JSONArray(stages); }
    }

    private static void flushRenderer() {
        if (!pending.compareAndSet(false, true)) return;
        if (!main.postDelayed(new Runnable() { public void run() {
            if (IslandPlus.actionDiagnosticsChanged()) { pending.set(false); return; }
            // Renderer and host can have separate module class loaders. Use
            // the shared platform executor; no polling or extra worker thread.
            AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() { public void run() {
                File tmp = null;
                try {
                    Application app = AndroidAppHelper.currentApplication();
                    if (app == null) return;
                    JSONObject report = new JSONObject();
                    report.put("sourceId", BuildInfo.SOURCE_ID); report.put("version", IslandPlus.VERSION);
                    report.put("pid", android.os.Process.myPid()); report.put("stages", snapshot());
                    File target = new File(app.getFilesDir(), "vivo-island-plus-actions-renderer.json");
                    tmp = new File(app.getFilesDir(), target.getName() + "." + System.identityHashCode(ActionDiagnostics.class) + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(report.toString(2).getBytes("UTF-8")); }
                    if (!tmp.renameTo(target)) throw new IOException("rename action diagnostics");
                } catch (Exception ignored) { }
                finally { if (tmp != null && tmp.exists()) tmp.delete(); pending.set(false); }
            }});
        }}, 500)) pending.set(false);
    }
}
