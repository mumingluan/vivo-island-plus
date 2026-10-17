package com.axuan.vivoislandplus;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Bounded, silent foreground service for valid CallStyle API verification. */
public final class CallProbeService extends Service {
    public IBinder onBind(Intent i) { return null; }
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel("bridge_probe", "原子岛终端验证", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null); channel.enableVibration(false); manager.createNotificationChannel(channel);
            Intent command = new Intent(this, ProbeReceiver.class).putExtra("command", "action");
            command.setIdentifier("call-stop");
            PendingIntent stop = PendingIntent.getBroadcast(this, 99, command,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "bridge_probe")
                    .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("通话 API 验证")
                    .setContentText("短时前台服务验证").setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_CALL)
                    .setStyle(Notification.CallStyle.forOngoingCall(new Person.Builder().setName("通话 API 验证").build(), stop)).build();
            startForeground(37411, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            ProbeReceiver.lastStyle = n.extras.getString(Notification.EXTRA_TEMPLATE, "");
            ProbeReceiver.lastCallType = n.extras.getInt("android.callType"); ProbeReceiver.lastFlags = n.flags;
            ProbeReceiver.record(this);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { stopSelf(); }
            }, 15000);
        } catch (Throwable t) {
            ProbeReceiver.lastError = t.toString();
            try { ProbeReceiver.record(this); } catch (Exception ignored) { }
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    public void onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy(); }
}
