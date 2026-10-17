package com.axuan.vivoislandplus;

import android.app.*;
import android.content.*;
import android.os.*;
import android.os.Process;
import android.service.notification.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import de.robv.android.xposed.XposedHelpers;

/** Event-driven platform bridge. All state lives on one worker looper. */
final class IslandBridge {
    private final Context context;
    private final NotificationListenerService listener;
    private final NotificationManager manager;
    private final KeyguardManager keyguard;
    private final Handler worker;
    private final SourceTimeouts sourceTimeouts;
    private final SystemUiDisplayState displayState;
    private final Map<String, StatusBarNotification> notifications = new LinkedHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<String> suppressed = new HashSet<>();
    private final ArrayDeque<String> errors = new ArrayDeque<>();
    private final Map<String, Integer> validations = new LinkedHashMap<>();
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final Map<String, NotificationDisplayPolicy.Ranking> eligibility = new HashMap<>();
    private int vendorHideAll;
    private boolean displayContractAttempted;
    private final File auditFile;
    private final File capabilityFile;
    private final File pauseFile;
    private final Set<String> previousNativeIds = new LinkedHashSet<>();
    private final CreationGuard creations = new CreationGuard();
    private final CapabilityHistory capabilities = new CapabilityHistory();
    private final NativeSourceClaims nativeSources = new NativeSourceClaims();
    private boolean started, syncing;
    private NativeIslandAdapter nativeAdapter;
    private long posts, ends, skippedPrivate, skippedUser;
    private boolean probeActionIntegrity;
    private final Runnable syncTask = new Runnable() { public void run() { guardedSync(); } };
    private final Runnable auditTask = new Runnable() { public void run() { writeAudit(); } };
    private final Runnable capabilityAuditTask = new Runnable() { public void run() { writeCapabilities(); } };

    IslandBridge(Context context, NotificationListenerService listener) {
        this.context = context;
        this.listener = listener;
        manager = context.getSystemService(NotificationManager.class);
        keyguard = context.getSystemService(KeyguardManager.class);
        HandlerThread thread = new HandlerThread("VivoIslandPlus", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); worker = new Handler(thread.getLooper());
        displayState = new SystemUiDisplayState(listener.getClass().getClassLoader(), worker, new SystemUiDisplayState.Callback() {
            public void changed() { worker.post(new Runnable() { public void run() { schedule(); } }); }
        });
        sourceTimeouts = new SourceTimeouts(new SourceTimeouts.Queue() {
            public long now() { return SystemClock.uptimeMillis(); }
            public boolean postAtTime(Runnable task, long uptime) { return worker.postAtTime(task, uptime); }
            public void remove(Runnable task) { worker.removeCallbacks(task); }
        }, new SourceTimeouts.Callback() {
            public boolean recheck(String key) {
                event("source timeout recheck " + key);
                reconcile(); guardedSync();
                return notifications.containsKey(key);
            }
        });
        auditFile = new File(context.getFilesDir(), "vivo-island-plus-status.json");
        capabilityFile = new File(context.getFilesDir(), "vivo-island-plus-controls.json");
        pauseFile = new File(context.getFilesDir(), "vivo-island-plus-paused");
        // Read before the first new audit overwrites the previous process's IDs.
        try {
            JSONObject previous = new JSONObject(new String(java.nio.file.Files.readAllBytes(auditFile.toPath()), StandardCharsets.UTF_8));
            JSONArray active = previous.optJSONArray("active");
            if (active != null) for (int i = 0; i < active.length(); i++) {
                JSONObject item = active.getJSONObject(i);
                if (item.optBoolean("native")) rememberPrevious(item.optString("tag"));
            }
            JSONArray pending = previous.optJSONArray("previousNativeIds");
            if (pending != null) for (int i = 0; i < pending.length(); i++) rememberPrevious(pending.optString(i));
        } catch (Throwable ignored) { }
    }

    void connected() { worker.post(new Runnable() { public void run() {
        try {
            ensureStarted();
            for (String key : new ArrayList<>(entries.keySet())) end(key);
            notifications.clear();
            creations.clear();
            capabilities.reconnect();
            sourceTimeouts.clear();
            StatusBarNotification[] active = listener.getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) {
                if (own(sbn)) manager.cancel(sbn.getTag(), sbn.getId());
                else { notifications.put(sbn.getKey(), sbn); nativeSources.observed(sbn.getKey(), sbn.getPostTime()); observeTimeout(sbn); observeCapabilities(sbn); }
            }
            nativeSources.retainOnly(notifications.keySet());
            guardedSync();
        } catch (Throwable t) { error("connect", t); }
    }}); }

    void posted(final StatusBarNotification sbn) {
        if (sbn == null || own(sbn)) return;
        worker.post(new Runnable() { public void run() {
            notifications.put(sbn.getKey(), sbn);
            nativeSources.observed(sbn.getKey(), sbn.getPostTime());
            sourceTimeouts.posted(sbn.getKey(), sbn.getPostTime(), sbn.getNotification().getTimeoutAfter());
            observeCapabilities(sbn);
            schedule();
        }});
    }

    void removed(final StatusBarNotification sbn, final int reason) {
        if (sbn == null) return;
        worker.post(new Runnable() { public void run() {
            if (own(sbn)) {
                event("mirror removed reason=" + reason + " id=" + sbn.getId());
                String source = sbn.getNotification().extras.getString(IslandPayload.MARKER + ".source");
                if (source != null && (reason == 1 || reason == 2 || reason == 3)) {
                    suppressed.add(source); entries.remove(source); auditLater();
                }
            } else {
                event("source removed " + sbn.getPackageName() + " reason=" + reason);
                notifications.remove(sbn.getKey()); suppressed.remove(sbn.getKey());
                sourceTimeouts.remove(sbn.getKey());
                capabilities.remove(sbn.getKey());
                nativeSources.removed(sbn.getKey());
                creations.remove(sbn.getKey()); eligibility.remove(sbn.getKey());
                schedule();
            }
        }});
    }

    void rankingChanged() { worker.post(new Runnable() { public void run() { reconcile(); schedule(); } }); }

    void pluginsChanged() { worker.post(new Runnable() { public void run() {
        displayState.attach(); discoverNative(); schedule();
    }}); }

    void nativeSourceClaim(final StatusBarNotification source) {
        if (source == null || own(source)) return;
        worker.post(new Runnable() { public void run() {
            if (nativeSources.claimed(source.getKey(), source.getPostTime())) schedule();
        }});
    }

    private void rememberPrevious(String id) {
        if (id.startsWith("vivo-island-plus:") && previousNativeIds.size() < 64) previousNativeIds.add(id);
    }

    private void discoverNative() {
        NativeIslandAdapter candidate = NativeIslandAdapter.discover(listener.getClass().getClassLoader());
        if (candidate != null) nativeAdapter = candidate;
        if (nativeAdapter == null) return;
        for (String id : new ArrayList<>(previousNativeIds)) {
            try { nativeAdapter.post(null, id, 2); previousNativeIds.remove(id); }
            catch (Throwable t) { error("previous island withdrawal", t); break; }
        }
    }

    private void reconcile() {
        try {
            StatusBarNotification[] active = listener.getActiveNotifications();
            if (active == null) return;
            Set<String> keys = new HashSet<>();
            for (StatusBarNotification sbn : active) {
                if (own(sbn)) continue;
                keys.add(sbn.getKey()); notifications.put(sbn.getKey(), sbn);
                nativeSources.observed(sbn.getKey(), sbn.getPostTime()); observeTimeout(sbn); observeCapabilities(sbn);
            }
            for (String key : new ArrayList<>(notifications.keySet())) if (!keys.contains(key)) {
                notifications.remove(key); eligibility.remove(key); suppressed.remove(key); creations.remove(key);
                event("reconcile removed " + key);
            }
            sourceTimeouts.retainOnly(keys);
            capabilities.retainOnly(keys);
            nativeSources.retainOnly(keys);
        } catch (Throwable t) { error("reconcile", t); }
    }

    private void observeTimeout(StatusBarNotification sbn) {
        sourceTimeouts.observed(sbn.getKey(), sbn.getPostTime(),
                sbn.getNotification().getTimeoutAfter(), System.currentTimeMillis());
    }

    private void observeCapabilities(StatusBarNotification sbn) {
        try {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;
            int user = (Integer) XposedHelpers.callMethod(sbn.getUser(), "getIdentifier");
            if (!CapabilityHistory.accepts(sbn.isOngoing(), (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                    user == (Integer) XposedHelpers.callMethod(context, "getUserId"),
                    extras == null ? null : extras.getString(Notification.EXTRA_TEMPLATE), notification.category,
                    extras != null && extras.getBoolean("android.isScreenShareLiveUpdate"))) {
                capabilities.remove(sbn.getKey());
                return;
            }
            JSONObject snapshot = NotificationActionSnapshot.capture(notification);
            snapshot.put("package", sbn.getPackageName());
            snapshot.put("source", tag(sbn.getKey()));
            snapshot.put("user", user);
            if (capabilities.observe(sbn.getKey(), snapshot.toString(), System.currentTimeMillis(), SystemClock.elapsedRealtime())
                    && !worker.hasCallbacks(capabilityAuditTask)) worker.postDelayed(capabilityAuditTask, 1000);
        } catch (Throwable t) { error("control capability snapshot", t); }
    }

    void validation(final StatusBarNotification sbn, final int result) {
        if (sbn == null || !own(sbn)) return;
        worker.post(new Runnable() { public void run() {
            validations.put(sbn.getTag(), result);
            while (validations.size() > 32) validations.remove(validations.keySet().iterator().next());
            auditLater();
        }});
    }

    private void ensureStarted() {
        if (started) return;
        displayState.attach();
        discoverNative();
        NotificationChannel channel = new NotificationChannel(IslandPayload.CHANNEL,
                "原子岛通知桥接", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false); channel.setShowBadge(false);
        channel.setDescription("Android 持续活动的原子岛卡片");
        manager.createNotificationChannel(channel);
        IntentFilter changes = new IntentFilter();
        changes.addAction(Intent.ACTION_SCREEN_OFF); changes.addAction(Intent.ACTION_SCREEN_ON);
        changes.addAction(Intent.ACTION_USER_PRESENT); changes.addAction("android.intent.action.USER_SWITCHED");
        changes.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        changes.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        context.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { worker.post(new Runnable() { public void run() {
                guardedSync();
                worker.removeCallbacks(syncTask); worker.postDelayed(syncTask, 1000);
            }}); }
        }, changes, null, worker, Context.RECEIVER_EXPORTED);
        context.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, final Intent i) {
                final PendingResult result = goAsync();
                worker.post(new Runnable() { public void run() {
                    try {
                        String command = i.getStringExtra("command");
                        if ("pause".equals(command)) {
                            try (FileOutputStream file = new FileOutputStream(pauseFile)) { file.write(1); }
                        } else if ("resume".equals(command)) {
                            if (pauseFile.exists() && !pauseFile.delete()) throw new IOException("remove pause flag");
                            creations.clear();
                        } else if ("probe-action".equals(command)) {
                            for (Entry e : entries.values()) if ("com.axuan.vivoislandplus".equals(e.model.pkg)
                                    && !e.model.actions.isEmpty()) {
                                Notification mirror = IslandPayload.build(context, e.model, 1, e.sequence);
                                ArrayList<PendingIntent> intents = mirror.extras.getBundle(IslandPayload.PREFIX + "island")
                                        .getBundle("island.superx.infos").getParcelableArrayList(IslandPayload.PREFIX + "infos.btnClickRespList", PendingIntent.class);
                                probeActionIntegrity = intents.size() == e.model.actions.size();
                                for (int index = 0; index < intents.size(); index++) probeActionIntegrity &=
                                        intents.get(index).equals(e.model.actions.get(index).actionIntent);
                                if (!probeActionIntegrity) throw new IllegalStateException("action identity");
                                intents.get(0).send(); break;
                            }
                        }
                        reconcile(); guardedSync(); result.setResultData("ok");
                    } catch (Throwable t) { error("diagnostic", t); result.setResultData(t.toString()); }
                    finally { result.finish(); }
                }});
            }
        }, new IntentFilter("com.axuan.vivoislandplus.DIAGNOSTIC"), "android.permission.DUMP", worker, Context.RECEIVER_EXPORTED);
        started = true;
        IslandPlus.log("bridge ready; event-driven platform APIs; " + IslandPlus.VERSION);
    }

    private void schedule() {
        worker.removeCallbacks(syncTask);
        worker.postDelayed(syncTask, 100); // Coalesce bursts without polling or a wakelock.
    }

    private void guardedSync() {
        if (syncing) return;
        syncing = true;
        try { sync(); }
        catch (Throwable t) { error("sync", t); }
        finally { syncing = false; auditLater(); }
    }

    private int currentUser() {
        return (Integer) XposedHelpers.callStaticMethod(ActivityManager.class, "getCurrentUser");
    }

    private void sync() throws Exception {
        if (pauseFile.exists()) {
            for (String key : new ArrayList<>(entries.keySet())) end(key);
            return;
        }
        // This first adapter only posts for SystemUI's user; no cross-profile reposting.
        int user = currentUser();
        int hostUser = (Integer) XposedHelpers.callMethod(context, "getUserId");
        boolean locked = keyguard.isKeyguardLocked();
        NotificationListenerService.RankingMap rankings = listener.getCurrentRanking();
        Map<String, IslandPayload.Model> desired = new LinkedHashMap<>();
        if (user == hostUser) {
            for (StatusBarNotification sbn : notifications.values()) {
                try {
                    if (((Integer) XposedHelpers.callMethod(sbn.getUser(), "getIdentifier")) != user) { skippedUser++; continue; }
                    if (!permitted(sbn, rankings)) continue;
                    if (nativeSources.covers(sbn.getKey(), sbn.getPostTime())) continue;
                    IslandPayload.Model m = notificationModel(sbn);
                    if (m == null) { creations.remove(sbn.getKey()); continue; }
                    if (locked && m.visibility != Notification.VISIBILITY_PUBLIC) { skippedPrivate++; continue; }
                    // An unrelated native island (including music) from the same
                    // package does not cover this distinct notification key.
                    // notificationModel excludes each source's own Vivo protocol.
                    desired.put(m.key, m);
                } catch (Throwable t) { error("notification", t); }
            }
        }
        creations.retainDesired(desired.keySet());
        for (String key : new ArrayList<>(entries.keySet())) {
            if (!desired.containsKey(key)) { event("withdraw " + key); end(key); }
        }
        for (IslandPayload.Model m : desired.values()) {
            if (suppressed.contains(m.key)) continue;
            Entry e = entries.get(m.key);
            String fingerprint = m.fingerprint();
            if (e != null && fingerprint.equals(e.fingerprint) && e.checkedAdapter == nativeAdapter) continue;
            boolean nativeBackend = nativeAdapter != null && nativeAdapter.supports(m);
            if (e != null && e.nativeBackend != nativeBackend) {
                if (!end(m.key)) continue;
                e = null;
            }
            if (e != null && fingerprint.equals(e.fingerprint)) {
                e.checkedAdapter = nativeAdapter;
                continue;
            }
            boolean create = e == null;
            if (create) {
                if (!creations.allow(m.key, SystemClock.elapsedRealtime())) {
                    try (FileOutputStream out = new FileOutputStream(pauseFile)) {
                        out.write("automatic pause: repeated lifecycle creation".getBytes(StandardCharsets.UTF_8));
                    }
                    event("CIRCUIT BREAKER: lifecycle churn");
                    for (String key : new ArrayList<>(entries.keySet())) end(key);
                    return;
                }
                e = new Entry(tag(m.key));
            }
            e.sequence++;
            event("notify " + (create ? 0 : 1) + " " + e.tag + " " + m.type + (nativeBackend ? " native" : " superx"));
            e.fingerprint = null; // A failed update may have queued an idempotent withdrawal.
            if (nativeBackend) nativeAdapter.post(m, e.tag, create ? 0 : 1);
            else manager.notify(e.tag, IslandPayload.ID, IslandPayload.build(context, m, create ? 0 : 1, e.sequence));
            e.nativeBackend = nativeBackend;
            e.nativeAdapter = nativeBackend ? nativeAdapter : null;
            e.checkedAdapter = nativeAdapter;
            e.model = m; e.fingerprint = fingerprint; entries.put(m.key, e); posts++;
        }
    }

    private boolean permitted(StatusBarNotification sbn, NotificationListenerService.RankingMap map) {
        if (!displayContractAttempted) {
            displayContractAttempted = true;
            try {
                VendorDisplayPlan plan = VendorDisplayPlan.discover(HostDexIndex.current());
                vendorHideAll = plan.unconditionalMask;
                event("display contract " + plan.suppression + " mask=" + vendorHideAll);
            } catch (Throwable failure) { error("display contract", failure); }
        }
        NotificationDisplayPolicy.Ranking facts = eligibility.get(sbn.getKey());
        NotificationListenerService.Ranking rank = new NotificationListenerService.Ranking();
        if (map != null && map.getRanking(sbn.getKey(), rank)) {
            facts = new NotificationDisplayPolicy.Ranking(rank.getImportance(), rank.isSuspended(),
                    rank.matchesInterruptionFilter(), rank.getSuppressedVisualEffects());
            eligibility.put(sbn.getKey(), facts);
        }
        // Omitted keys do not withdraw a live source. Cache ranking facts rather
        // than a final boolean so an AOD transition can reevaluate visual policy.
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        String style = extras == null ? "" : extras.getString(Notification.EXTRA_TEMPLATE, "");
        boolean media = extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                && ("android.app.Notification$MediaStyle".equals(style)
                    || "android.app.Notification$DecoratedMediaCustomViewStyle".equals(style));
        boolean fgsOrUij = (notification.flags & (Notification.FLAG_FOREGROUND_SERVICE | 32768)) != 0;
        return NotificationDisplayPolicy.allowed(facts, displayState.state(), notification.category, fgsOrUij, media, vendorHideAll);
    }

    private IslandPayload.Model notificationModel(StatusBarNotification sbn) {
        Notification n = sbn.getNotification(); Bundle e = n.extras;
        if (e == null) return null;
        String style = e.getString(Notification.EXTRA_TEMPLATE, "");
        boolean request = e.getBoolean("android.requestPromotedOngoing")
                || (n.flags & 262144) != 0;
        String route = NotificationRoutePolicy.route(style, sbn.isOngoing(),
                (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0, e.containsKey(IslandPayload.PREFIX + "operation"),
                request, e.containsKey(Notification.EXTRA_MEDIA_SESSION), "progress".equals(n.category),
                e.getInt("android.callType", 0), e.getInt(Notification.EXTRA_PROGRESS_MAX),
                e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE));
        if (route == null) return null;
        IslandPayload.Model m = base(sbn);
        m.type = route;
        m.progress = e.getInt(Notification.EXTRA_PROGRESS);
        m.max = e.getInt(Notification.EXTRA_PROGRESS_MAX);
        m.indeterminate = e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE);
        m.critical = CompactProgressPolicy.critical(
                IslandPayload.clean(e.getCharSequence("android.shortCriticalText")),
                m.progress, m.max, m.indeterminate);
        addActions(m, n.actions);
        return m;
    }

    private IslandPayload.Model base(StatusBarNotification sbn) {
        Notification n = sbn.getNotification(); Bundle e = n.extras;
        IslandPayload.Model m = new IslandPayload.Model();
        m.key = sbn.getKey(); m.pkg = sbn.getPackageName();
        m.original = sbn;
        m.title = IslandPayload.clean(e.getCharSequence(Notification.EXTRA_TITLE));
        m.text = IslandPayload.clean(e.getCharSequence(Notification.EXTRA_TEXT));
        m.icon = n.getSmallIcon(); m.click = n.contentIntent; m.visibility = n.visibility;
        m.chronometer = (Boolean) XposedHelpers.callMethod(n, "showsChronometer");
        if (m.chronometer) m.when = ((Number) XposedHelpers.callMethod(n, "getWhen")).longValue();
        m.countDown = e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN);
        if (n.color != 0) m.color = n.color | 0xff000000;
        return m;
    }

    private static void addActions(IslandPayload.Model m, Notification.Action[] actions) {
        if (actions == null) return;
        for (Notification.Action action : actions) {
            // The standard shade retains inline reply and authentication-gated actions.
            if (action == null || action.actionIntent == null || action.isAuthenticationRequired()
                    || (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0)) continue;
            if (m.actions.size() == 5) break;
            m.actions.add(action);
        }
    }

    private static boolean own(StatusBarNotification sbn) {
        // Ownership and tag both matter: source applications cannot suppress themselves by copying an extra.
        return "com.android.systemui".equals(sbn.getPackageName()) && (
                sbn.getNotification().extras.getBoolean(IslandPayload.MARKER)
                || (sbn.getTag() != null && sbn.getTag().startsWith("vivo-island-plus:")));
    }

    private boolean end(final String key) {
        final Entry entry = entries.get(key);
        if (entry == null) return true;
        if (entry.withdrawRetryPending) return false;
        try {
            if (entry.nativeBackend && entry.nativeAdapter != null) entry.nativeAdapter.post(null, entry.tag, 2);
            else if (entry.nativeBackend) throw new IOException("native adapter unavailable during withdrawal");
            else {
                manager.notify(entry.tag, IslandPayload.ID, IslandPayload.build(context, entry.model, 2, ++entry.sequence));
                manager.cancel(entry.tag, IslandPayload.ID);
            }
            entries.remove(key); ends++;
            return true;
        } catch (Throwable t) {
            error("end", t);
            // Keep the identity until withdrawal succeeds. Dropping it here
            // would orphan the island when a Binder/looper operation fails.
            if (entry.withdrawFailures < 3) {
                final long delay = new long[]{1000, 3000, 10000}[entry.withdrawFailures++];
                entry.withdrawRetryPending = true;
                worker.postDelayed(new Runnable() { public void run() {
                    entry.withdrawRetryPending = false;
                    if (entries.get(key) == entry) guardedSync();
                }}, delay);
            }
            return false;
        }
    }

    private static final class Entry {
        final String tag;
        int sequence;
        String fingerprint;
        IslandPayload.Model model;
        boolean nativeBackend;
        NativeIslandAdapter nativeAdapter;
        NativeIslandAdapter checkedAdapter;
        boolean withdrawRetryPending;
        int withdrawFailures;
        Entry(String tag) { this.tag = tag; }
    }

    private static String tag(String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder("vivo-island-plus:");
        for (int i = 0; i < 12; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
        return out.toString();
    }

    private void error(String phase, Throwable t) {
        if (errors.size() == 12) errors.removeFirst();
        errors.addLast(phase + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
        IslandPlus.log(phase + ": " + t); auditLater();
    }

    private void auditLater() {
        if (!worker.hasCallbacks(auditTask)) worker.postDelayed(auditTask, 500);
    }

    void actionDiagnosticsChanged() {
        worker.post(new Runnable() { public void run() { auditLater(); } });
    }

    private void event(String text) {
        if (events.size() == 32) events.removeFirst();
        events.addLast(SystemClock.elapsedRealtime() + " " + text);
    }

    private void writeCapabilities() {
        try {
            JSONObject out = new JSONObject();
            out.put("version", IslandPlus.VERSION); out.put("sourceId", BuildInfo.SOURCE_ID);
            out.put("pid", Process.myPid()); out.put("capacity", CapabilityHistory.LIMIT);
            JSONArray history = new JSONArray();
            for (CapabilityHistory.Sample sample : capabilities.snapshot()) {
                JSONObject recorded = new JSONObject();
                recorded.put("time", sample.wallTime); recorded.put("elapsed", sample.elapsed);
                recorded.put("capabilities", new JSONObject(sample.payload)); history.put(recorded);
            }
            out.put("history", history);
            File tmp = new File(capabilityFile.getParentFile(), capabilityFile.getName() + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(tmp)) {
                stream.write(out.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(capabilityFile)) throw new IOException("rename control capabilities");
        } catch (Throwable t) { error("write control capabilities", t); }
    }

    private void writeAudit() {
        try {
            JSONObject out = new JSONObject();
            out.put("version", IslandPlus.VERSION); out.put("time", System.currentTimeMillis());
            out.put("sourceId", BuildInfo.SOURCE_ID);
            out.put("pid", Process.myPid()); out.put("started", started);
            out.put("keyguardLocked", keyguard.isKeyguardLocked());
            NotificationDisplayPolicy.State display = displayState.state();
            out.put("displayStateKnown", display.known); out.put("dozing", display.dozing); out.put("fullDoze", display.fullDoze);
            out.put("paused", pauseFile.exists());
            out.put("contextPackage", context.getPackageName());
            out.put("nativeReady", nativeAdapter != null);
            out.put("nativeSourceClaims", nativeSources.size());
            out.put("previousNativeIds", new JSONArray(previousNativeIds));
            out.put("sourceNotifications", notifications.size()); out.put("mediaRoute", "official-walkman");
            out.put("sourceTimeouts", sourceTimeouts.size()); out.put("queuedSourceTimeouts", sourceTimeouts.pending());
            out.put("controlCapabilitySnapshots", capabilities.snapshot().size());
            out.put("controlCapabilitySources", capabilities.tracked());
            out.put("probeActionIntegrity", probeActionIntegrity);
            out.put("actionDiagnostics", ActionDiagnostics.snapshot());
            out.put("posts", posts); out.put("ends", ends);
            out.put("skippedPrivate", skippedPrivate); out.put("skippedUser", skippedUser);
            JSONArray active = new JSONArray();
            for (Entry e : entries.values()) {
                JSONObject item = new JSONObject();
                item.put("tag", e.tag); item.put("package", e.model.pkg); item.put("type", e.model.type);
                item.put("actions", e.model.actions.size()); item.put("progress", e.model.progress);
                item.put("max", e.model.max); item.put("sequence", e.sequence); active.put(item);
                item.put("chronometer", e.model.chronometer); item.put("countDown", e.model.countDown);
                item.put("native", e.nativeBackend);
                item.put("nativeSupport", e.model.nativeSupport);
                item.put("withdrawFailures", e.withdrawFailures);
                item.put("withdrawRetryPending", e.withdrawRetryPending);
            }
            out.put("active", active); out.put("suppressed", suppressed.size());
            out.put("validation", new JSONObject(validations)); out.put("errors", new JSONArray(errors));
            out.put("events", new JSONArray(events));
            File tmp = new File(auditFile.getParentFile(), auditFile.getName() + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(tmp)) { stream.write(out.toString(2).getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(auditFile)) throw new IOException("rename audit");
        } catch (Throwable t) { IslandPlus.log("audit: " + t.getClass().getSimpleName()); }
    }
}
