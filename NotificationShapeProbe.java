package com.axuan.vivoislandplus;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.RemoteViews;
import java.io.*;
import org.json.*;

/** Builds fixture notifications in memory; never posts or opens an activity. */
final class NotificationShapeProbe {
    private static Instrumentation instrumentation;

    static JSONObject run(Context context, Instrumentation test) throws Exception {
        instrumentation = test;
        JSONObject report = new JSONObject();
        report.put("version", IslandPlus.VERSION);
        report.put("sourceId", BuildInfo.SOURCE_ID);
        report.put("time", System.currentTimeMillis());
        report.put("fingerprint", Build.FINGERPRINT);
        report.put("sdk", Build.VERSION.SDK_INT);
        JSONArray cases = new JSONArray();
        PendingIntent action = PendingIntent.getBroadcast(context, 412,
                new Intent(context, ProbeReceiver.class).putExtra("command", "action"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        add(context, cases, "plain-no-action", base(context).build());
        add(context, cases, "plain-action", base(context).addAction(android.R.drawable.ic_media_pause, "fixture pause", action).build());
        add(context, cases, "media-no-action", base(context).setStyle(new Notification.MediaStyle()).build());
        add(context, cases, "media-action", base(context).setStyle(new Notification.MediaStyle())
                .addAction(android.R.drawable.ic_media_pause, "fixture pause", action).build());
        add(context, cases, "bigtext-empty", base(context).setStyle(new Notification.BigTextStyle()).build());
        add(context, cases, "bigtext-content", base(context).setStyle(new Notification.BigTextStyle().bigText("fixture expanded text")).build());
        add(context, cases, "call-person-title", new Notification.Builder(context, "bridge_probe")
                .setSmallIcon(android.R.drawable.stat_sys_phone_call).setOngoing(true)
                .setStyle(Notification.CallStyle.forOngoingCall(new Person.Builder().setName("fixture caller").build(), action)).build());
        add(context, cases, "rich-progress", base(context).setStyle(new Notification.ProgressStyle()
                .addProgressSegment(new Notification.ProgressStyle.Segment(10).setColor(0xff0044cc))
                .addProgressSegment(new Notification.ProgressStyle.Segment(20).setColor(0xffcc4400))
                .addProgressPoint(new Notification.ProgressStyle.Point(12).setColor(0xff00cc44))
                .setProgress(15)).build());
        add(context, cases, "clock-up", base(context).setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - 65000).build());
        add(context, cases, "clock-down", base(context).setUsesChronometer(true).setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + 60000).build());
        report.put("cases", cases);
        report.put("clock", clock(context));
        boolean passed = true;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.getJSONObject(i);
            JSONObject inflated = item.optJSONObject("inflatedTypes");
            passed &= !item.has("error") && item.optBoolean("expandedAvailable") && inflated != null
                    && !inflated.has("error") && inflated.optBoolean("compatStylingPassed");
        }
        report.put("passed", passed);
        File output = new File(context.getFilesDir(), "notification-shapes.json");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(report.toString(2).getBytes("UTF-8"));
        }
        if (!passed) throw new IOException("notification layout probe failed; see notification-shapes.json");
        return report;
    }

    private static JSONObject clock(Context context) throws Exception {
        IslandPayload.Model model = new IslandPayload.Model();
        model.title = "fixture clock"; model.chronometer = true;
        model.countDown = true; model.when = System.currentTimeMillis() + 60000;
        long expected = SystemClock.elapsedRealtime() + 60000;
        String content = IslandPayload.compactContent(model);
        JSONObject result = new JSONObject(content);
        if (Math.abs(result.getLong("base") - expected) > 100) throw new IllegalStateException("clock base mismatch");
        // Load only the stock plain data class and JSON parser, never any plugin
        // application/service. This checks compatibility with the actual ROM.
        ClassLoader loader = new dalvik.system.PathClassLoader(
                "/system/priv-app/SystemUIPlugin/SystemUIPlugin.apk", context.getClassLoader());
        Class<?> timerClass = loader.loadClass("com.vivo.systemuiplugin.superx.common.service.ui.TimerFormat");
        Class<?> gsonClass = loader.loadClass("com.google.gson.Gson");
        Object gson = gsonClass.getConstructor().newInstance();
        Object timer = gsonClass.getMethod("fromJson", String.class, Class.class).invoke(gson, content, timerClass);
        boolean valid = (Boolean) timerClass.getMethod("hasValidFormat", boolean.class, boolean.class, boolean.class)
                .invoke(timer, false, false, true);
        if (!valid || !timerClass.getField("countDown").getBoolean(timer)
                || timerClass.getField("base").getLong(timer) != result.getLong("base"))
            throw new IllegalStateException("stock TimerFormat mismatch");
        model.critical = "fixture critical";
        if (!model.critical.equals(IslandPayload.compactContent(model))) throw new IllegalStateException("critical text priority");
        result.put("stockParserVerified", true); result.put("criticalTextPriorityVerified", true);
        return result;
    }

    private static Notification.Builder base(Context context) {
        return new Notification.Builder(context, "bridge_probe")
                .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true)
                .setContentTitle("fixture title").setContentText("fixture text");
    }

    private static void add(Context context, JSONArray cases, String name, Notification notification) throws Exception {
        JSONObject item = new JSONObject();
        item.put("case", name);
        item.put("style", notification.extras.getString(Notification.EXTRA_TEMPLATE, ""));
        item.put("title", String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_TITLE, "")));
        Person person = notification.extras.getParcelable("android.callPerson", Person.class);
        item.put("person", person == null ? "" : String.valueOf(person.getName()));
        item.put("actions", notification.actions == null ? 0 : notification.actions.length);
        item.put("sourceCapabilities", NotificationActionSnapshot.capture(notification));
        item.put("progressMax", notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX));
        item.put("progress", notification.extras.getInt(Notification.EXTRA_PROGRESS));
        item.put("usesChronometer", notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        item.put("countDown", notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN));
        try {
            RemoteViews expanded = Notification.Builder.recoverBuilder(context, notification).createBigContentView();
            item.put("expandedAvailable", expanded != null);
            if (expanded != null) {
                item.put("expandedPackage", expanded.getPackage()); item.put("expandedLayout", expanded.getLayoutId());
                final JSONObject viewTypes = new JSONObject();
                instrumentation.runOnMainSync(new Runnable() { public void run() {
                    try {
                        android.view.View view = expanded.apply(context, new android.widget.FrameLayout(context));
                        for (String name : new String[]{"action0", "action1", "action2", "notification_action_list_margin_target"}) {
                            int id = context.getResources().getIdentifier(name, "id", "android");
                            android.view.View child = id == 0 ? null : view.findViewById(id);
                            viewTypes.put(name, child == null ? "absent" : child.getClass().getName());
                        }
                        final int[] containers = new int[1];
                        int styled = NativeCardCompat.apply(view, 0xff123456, actions -> containers[0]++);
                        viewTypes.put("compatTextButtons", styled);
                        viewTypes.put("compatAodContainers", containers[0]);
                        viewTypes.put("compatStylingPassed", true);
                    } catch (Throwable t) {
                        try { viewTypes.put("error", t.toString()); } catch (Exception ignored) { }
                    }
                }});
                item.put("inflatedTypes", viewTypes);
            }
        } catch (Throwable t) { item.put("error", t.toString()); }
        cases.put(item);
    }
}
