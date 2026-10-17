package com.axuan.vivoislandplus;

import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;

/** Terminal-only API exercise; no user activity is launched. */
public final class ApiInstrumentation extends Instrumentation {
    private String single;
    public void onCreate(Bundle arguments) { super.onCreate(arguments); single = arguments.getString("case"); start(); }
    public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("framework".equals(single)) {
                result.putString("shapes", NotificationShapeProbe.run(getTargetContext(), this).toString());
                result.putString("result", "completed framework"); finish(0, result); return;
            }
            if (single != null) {
                emit(single, 4, false); Thread.sleep(12000);
                if (!ProbeReceiver.lastError.isEmpty()) throw new IllegalStateException(ProbeReceiver.lastError);
                emit("cancel", 0, false);
                result.putString("result", "completed " + single); finish(0, result); return;
            }
            emit("progress", 2, false); Thread.sleep(4000);
            emit("progress", 5, false); Thread.sleep(4000);
            emit("actions", 6, false); Thread.sleep(4000);
            emit("fallback", 7, false); Thread.sleep(4000);
            emit("ordinary", 1, false); Thread.sleep(4000);
            emit("media", 0, false); Thread.sleep(4000);
            emit("media-stop", 0, false); Thread.sleep(4000);
            emit("call", 0, false); Thread.sleep(4000);
            emit("clock", 0, false); Thread.sleep(4000);
            emit("countdown", 0, false); Thread.sleep(4000);
            emit("progress", 4, true); Thread.sleep(4000);
            emit("timeout", 3, false); Thread.sleep(4000);
            emit("cancel", 0, false);
            result.putString("result", "completed"); finish(0, result);
        } catch (Throwable t) {
            emit("cancel", 0, false); result.putString("error", t.toString()); finish(1, result);
        }
    }
    private void emit(String command, int value, boolean privateContent) {
        if (!"cancel".equals(command) && !ProbeReceiver.lastError.isEmpty())
            throw new IllegalStateException(ProbeReceiver.lastError);
        Context context = getTargetContext();
        Intent intent = new Intent().putExtra("command", command).putExtra("progress", value)
                .putExtra("private", privateContent);
        new ProbeReceiver().onReceive(context, intent);
        if (!ProbeReceiver.lastError.isEmpty()) throw new IllegalStateException(ProbeReceiver.lastError);
        Bundle status = new Bundle(); status.putString("stage", command); status.putInt("progress", value);
        sendStatus(0, status);
    }
}
