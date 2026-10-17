package com.axuan.vivoislandplus;

import android.app.*;
import android.content.*;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import java.io.*;
import org.json.*;

/** Explicit terminal test fixture, protected by the system DUMP permission. No UI. */
public final class ProbeReceiver extends BroadcastReceiver {
    private static MediaSession session;
    private static int actionCount;
    static volatile String lastError = "";
    static String lastCommand = "", lastStyle = "";
    static int lastCallType, lastFlags;
    private static final int ID = 37411;
    public void onReceive(Context context, Intent intent) {
        try {
            String command = intent.getStringExtra("command");
            lastCommand = command; lastError = "";
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if ("call".equals(command)) {
                context.startForegroundService(new Intent(context, CallProbeService.class));
                record(context); reply("call service started"); return;
            }
            if (!"action".equals(command)) context.stopService(new Intent(context, CallProbeService.class));
            if ("cancel".equals(command)) {
                manager.cancel(ID);
                if (session != null) { session.release(); session = null; }
                record(context); reply("cancelled"); return;
            }
            if ("action".equals(command)) {
                actionCount++; record(context); reply("action=" + actionCount); return;
            }
            if ("media-stop".equals(command)) {
                if (session != null) session.setPlaybackState(new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PAUSED, 0, 0).build());
                record(context); reply("paused"); return;
            }
            NotificationChannel channel = new NotificationChannel("bridge_probe", "原子岛终端验证", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null); channel.enableVibration(false); manager.createNotificationChannel(channel);
            Notification.Builder builder = new Notification.Builder(context, "bridge_probe")
                    .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("原子岛 API 验证")
                    .setContentText("终端发出的合成通知").setVisibility(intent.getBooleanExtra("private", false)
                            ? Notification.VISIBILITY_PRIVATE : Notification.VISIBILITY_PUBLIC)
                    .setOngoing(!"ordinary".equals(command)).setDefaults(0).setSound(null).setOnlyAlertOnce(true)
                    .setTimeoutAfter("timeout".equals(command) ? 1500 : 120000);
            int value = intent.getIntExtra("progress", 1);
            if ("media".equals(command)) {
                if (session == null) {
                    session = new MediaSession(context, "VivoIslandPlusApiProbe");
                    session.setCallback(new MediaSession.Callback() {
                        public void onPause() {
                            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 0).build());
                        }
                    }, new Handler(Looper.getMainLooper()));
                }
                session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "媒体会话 API 验证").build());
                session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1).build());
                session.setActive(true); builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()));
            } else if ("clock".equals(command) || "countdown".equals(command)) {
                boolean down = "countdown".equals(command);
                builder.setCategory(Notification.CATEGORY_EVENT).setUsesChronometer(true)
                        .setChronometerCountDown(down).setWhen(System.currentTimeMillis() + (down ? 60000 : -65000));
                Bundle extras = new Bundle(); extras.putBoolean("android.requestPromotedOngoing", true); builder.addExtras(extras);
            } else {
                builder.setCategory(Notification.CATEGORY_PROGRESS);
                if ("progress".equals(command)) builder.setStyle(new Notification.ProgressStyle()
                        .addProgressSegment(new Notification.ProgressStyle.Segment(8)).setProgress(value));
                else builder.setProgress(8, value, false);
                Bundle extras = new Bundle();
                if (!"fallback".equals(command)) extras.putBoolean("android.requestPromotedOngoing", true);
                extras.putCharSequence("android.shortCriticalText", value + "/8"); builder.addExtras(extras);
            }
            if ("actions".equals(command)) for (int i = 0; i < 3; i++) {
                Intent action = new Intent(context, ProbeReceiver.class).putExtra("command", "action");
                action.setIdentifier("action-" + i);
                PendingIntent pi = PendingIntent.getBroadcast(context, i, action, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.addAction(new Notification.Action.Builder(Icon.createWithResource("android", android.R.drawable.ic_menu_manage),
                        "操作 " + (i + 1), pi).build());
            }
            Notification built = builder.build();
            lastStyle = built.extras.getString(Notification.EXTRA_TEMPLATE, "");
            lastCallType = built.extras.getInt("android.callType"); lastFlags = built.flags;
            manager.notify(ID, built); record(context); reply("posted " + command);
        } catch (Throwable t) {
            lastError = t.toString();
            try { record(context); } catch (Throwable ignored) { }
            reply("error: " + t);
        }
    }

    private void reply(String message) { if (isOrderedBroadcast()) setResultData(message); }

    static void record(Context context) throws Exception {
        JSONObject json = new JSONObject(); json.put("actionCount", actionCount);
        json.put("time", System.currentTimeMillis()); json.put("mediaSession", session != null);
        json.put("command", lastCommand); json.put("error", lastError);
        json.put("style", lastStyle); json.put("callType", lastCallType); json.put("flags", lastFlags);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "api-probe.json"))) {
            out.write(json.toString().getBytes("UTF-8"));
        }
    }
}
