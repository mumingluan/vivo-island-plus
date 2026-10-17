package com.axuan.vivoislandplus;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import de.robv.android.xposed.*;

/** Opens the existing music-island admission gate; Vivo owns its view and controls. */
final class OfficialMusicCompat {
    private static final AtomicBoolean attempted = new AtomicBoolean();
    private static Method sourceChange;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<MediaSession.Token, Watch> watches = new LinkedHashMap<>();
    private static volatile Set<String> admitted = Collections.emptySet();
    private static Context context;
    private static Object application;
    private static String selected;
    private static boolean selectedPlaying;
    private static String error;
    private static final Set<String> retiring = new HashSet<>();
    private static final Runnable reconcile = new Runnable() { public void run() { sync(); } };

    static void install(final ClassLoader loader) {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                Context attached = (Context) p.args[0];
                if (attached == null || !"com.vivo.musicwidgetmix".equals(attached.getPackageName())
                        || !attempted.compareAndSet(false, true)) return;
                ArrayList<XC_MethodHook.Unhook> hooks = new ArrayList<>();
                try {
                    ApplicationInfo info = attached.getApplicationInfo();
                    ArrayList<File> files = new ArrayList<>(); files.add(new File(info.sourceDir));
                    if (info.splitSourceDirs != null) for (String path : info.splitSourceDirs) files.add(new File(path));
                    MusicResolver.Plan plan = MusicResolver.discover(DexIndex.readApks(files));
                    final Method lists = DexMembers.method(plan.islandList, loader);
                    final Method gate = DexMembers.method(plan.gate, loader);
                    final Method change = DexMembers.method(plan.sourceChange, loader);
                    if (!(p.thisObject instanceof Application) || !change.getDeclaringClass().isInstance(p.thisObject))
                        throw new ReflectiveOperationException("Source dispatch owner is not this application");
                    final Application app = (Application) p.thisObject;
                    Method onCreate = app.getClass().getMethod("onCreate");
                    hooks.add(XposedBridge.hookMethod(lists, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!(p.getResult() instanceof List)) return;
                            ArrayList<Object> result = new ArrayList<>((List<?>) p.getResult());
                            for (String pkg : admitted) if (!result.contains(pkg)) result.add(pkg);
                            p.setResult(result);
                        }
                    }));
                    hooks.add(XposedBridge.hookMethod(gate, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (admitted.contains((String) p.args[1])) p.setResult(true);
                        }
                    }));
                    hooks.add(XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (p.hasThrowable() || p.thisObject != app || application != null) return;
                            application = app; context = app.getApplicationContext(); sourceChange = change;
                            main.post(new Runnable() { public void run() { start(); } });
                        }
                    }));
                    IslandPlus.log("official music semantic plan: " + plan.describe());
                } catch (Throwable t) {
                    for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
                    IslandPlus.log("official music discovery disabled: " + t);
                }
            }
        });
    }

    private static void start() {
        try {
            MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
            manager.addOnActiveSessionsChangedListener(new MediaSessionManager.OnActiveSessionsChangedListener() {
                public void onActiveSessionsChanged(List<MediaController> controllers) { update(controllers); }
            }, null, main);
            update(manager.getActiveSessions(null));
        } catch (Throwable t) { fail(t); }
    }

    private static void update(List<MediaController> controllers) {
        try {
            Set<MediaSession.Token> seen = new HashSet<>();
            if (controllers != null) for (MediaController c : controllers) {
                MediaSession.Token token = c.getSessionToken();
                seen.add(token);
                if (!watches.containsKey(token)) {
                    Watch watch = new Watch(c);
                    watches.put(token, watch);
                    c.registerCallback(watch.callback, main);
                }
            }
            for (MediaSession.Token token : new ArrayList<>(watches.keySet())) if (!seen.contains(token)) {
                Watch old = watches.remove(token);
                old.controller.unregisterCallback(old.callback);
            }
            schedule();
        } catch (Throwable t) { fail(t); }
    }

    private static final class Watch {
        final MediaController controller;
        final MediaController.Callback callback = new MediaController.Callback() {
            public void onPlaybackStateChanged(PlaybackState state) { schedule(); }
            public void onMetadataChanged(MediaMetadata metadata) { schedule(); }
            public void onSessionDestroyed() {
                watches.remove(controller.getSessionToken());
                controller.unregisterCallback(this);
                schedule();
            }
        };
        Watch(MediaController controller) { this.controller = controller; }
    }

    private static void schedule() {
        main.removeCallbacks(reconcile);
        main.postDelayed(reconcile, 200);
    }

    private static boolean eligible(MediaController c) {
        PlaybackState state = c.getPlaybackState();
        MediaMetadata metadata = c.getMetadata();
        if ((c.getFlags() & MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS) == 0
                || state == null || metadata == null) return false;
        CharSequence title = metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null || title.length() == 0) title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        return title != null && title.length() > 0 && (state.getActions()
                & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) != 0;
    }

    private static boolean playing(MediaController c) {
        int state = c.getPlaybackState().getState();
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
    }

    private static void sync() {
        try {
            LinkedHashSet<String> current = new LinkedHashSet<>();
            String next = null;
            for (Watch watch : watches.values()) if (eligible(watch.controller)) {
                String pkg = watch.controller.getPackageName();
                current.add(pkg);
                if (playing(watch.controller) && next == null) next = pkg;
            }
            // Keep the departed source admitted until Vivo's own delayed cancellation runs.
            if (selected != null && selectedPlaying && !current.contains(selected) && retiring.add(selected)) {
                final String old = selected;
                main.postDelayed(new Runnable() { public void run() { retiring.remove(old); schedule(); } }, 10000);
            }
            current.addAll(retiring);
            admitted = Collections.unmodifiableSet(current);
            String source = next != null ? next : selected;
            boolean isPlaying = next != null;
            if (source != null && (!source.equals(selected) || selectedPlaying != isPlaying)) {
                // Dispatch through the discovered stock AudioFeatures entry.
                // The count comes from actual eligible sessions; no private
                // Application field or obfuscated member name is required.
                sourceChange.invoke(application, source, isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_NONE,
                        100, current.size());
                selected = source;
                selectedPlaying = isPlaying;
            }
            error = null;
            audit();
        } catch (Throwable t) { fail(t); }
    }

    private static void fail(Throwable t) {
        error = t.getClass().getSimpleName() + ": " + t.getMessage();
        IslandPlus.log("official music: " + error);
        audit();
    }

    private static void audit() {
        if (context == null) return;
        try {
            JSONObject out = new JSONObject();
            out.put("version", IslandPlus.VERSION); out.put("sourceId", BuildInfo.SOURCE_ID);
            out.put("time", System.currentTimeMillis()); out.put("pid", android.os.Process.myPid());
            out.put("route", "Vivo MusicWidgetMix / BUSINESS_WALKMAN");
            out.put("sessions", watches.size()); out.put("admitted", new JSONArray(admitted));
            out.put("selected", selected == null ? JSONObject.NULL : selected);
            out.put("playing", selectedPlaying); out.put("error", error == null ? JSONObject.NULL : error);
            File file = new File(context.getFilesDir(), "vivo-island-plus-music.json");
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(temp)) {
                stream.write(out.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) throw new IOException("audit rename");
        } catch (Throwable t) { IslandPlus.log("official music audit: " + t); }
    }
}
