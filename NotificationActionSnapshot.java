package com.axuan.vivoislandplus;

import android.app.*;
import android.os.Bundle;
import org.json.*;

/** Capability evidence only: never serialize PendingIntent internals or account extras. */
final class NotificationActionSnapshot {
    private NotificationActionSnapshot() { }

    static JSONObject capture(Notification notification) throws JSONException {
        JSONObject snapshot = new JSONObject();
        Bundle extras = notification.extras;
        snapshot.put("style", extras == null ? "" : extras.getString(Notification.EXTRA_TEMPLATE, ""));
        snapshot.put("category", notification.category);
        snapshot.put("sourceChronometer", extras != null && extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        snapshot.put("sourceShowWhen", extras != null && extras.getBoolean(Notification.EXTRA_SHOW_WHEN));
        snapshot.put("sourceWhenValid", notification.when > 0);
        snapshot.put("screenShareLiveUpdate", extras != null && extras.getBoolean("android.isScreenShareLiveUpdate"));
        snapshot.put("mediaSessionToken", extras != null && extras.getParcelable(Notification.EXTRA_MEDIA_SESSION,
                android.media.session.MediaSession.Token.class) != null);
        snapshot.put("callType", extras == null ? 0 : extras.getInt("android.callType"));
        snapshot.put("videoCall", extras != null && extras.getBoolean("android.callIsVideo"));
        snapshot.put("hangup", extras != null && extras.getParcelable("android.hangUpIntent", PendingIntent.class) != null);
        snapshot.put("answer", extras != null && extras.getParcelable("android.answerIntent", PendingIntent.class) != null);
        snapshot.put("decline", extras != null && extras.getParcelable("android.declineIntent", PendingIntent.class) != null);
        JSONArray actions = new JSONArray();
        int count = notification.actions == null ? 0 : notification.actions.length;
        snapshot.put("actionCount", count);
        snapshot.put("actionsOmitted", Math.max(0, count - 32));
        if (notification.actions != null) for (int i = 0; i < Math.min(count, 32); i++) {
            Notification.Action action = notification.actions[i];
            if (action == null) continue;
            JSONObject item = new JSONObject();
            item.put("index", i);
            String title = action.title == null ? "" : action.title.toString();
            item.put("label", title.length() > 96 ? title.substring(0, 96) : title);
            item.put("icon", action.getIcon() != null);
            item.put("semanticAction", action.getSemanticAction());
            item.put("contextual", action.isContextual());
            item.put("authenticationRequired", action.isAuthenticationRequired());
            item.put("remoteInput", action.getRemoteInputs() != null && action.getRemoteInputs().length > 0);
            boolean freeForm = false, choices = false;
            if (action.getRemoteInputs() != null) for (RemoteInput input : action.getRemoteInputs()) if (input != null) {
                freeForm |= input.getAllowFreeFormInput();
                choices |= input.getChoices() != null && input.getChoices().length > 0;
            }
            item.put("freeFormInput", freeForm); item.put("choiceInput", choices);
            PendingIntent intent = action.actionIntent;
            item.put("pendingIntent", intent != null);
            if (intent != null) item.put("intentType", intent.isActivity() ? "activity"
                    : intent.isBroadcast() ? "broadcast" : intent.isForegroundService() ? "foreground-service"
                    : intent.isService() ? "service" : "unknown");
            actions.put(item);
        }
        snapshot.put("actions", actions);
        return snapshot;
    }
}
