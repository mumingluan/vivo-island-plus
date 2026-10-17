package com.axuan.vivoislandplus;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import java.util.ArrayList;
import org.json.JSONObject;
import android.service.notification.StatusBarNotification;

/** Island-only notification transport using the installed plugin's request enums. */
final class IslandPayload {
    static final String MARKER = "com.axuan.vivoislandplus.mirror";
    static final String PREFIX = "notification.superx.";
    static final String CHANNEL = "vivo_island_plus";
    static final int ID = 0x564950;

    static final class Model {
        String key, pkg, type, title = "", text = "", critical = "";
        String nativeSupport = "not-checked";
        int progress, max, visibility = Notification.VISIBILITY_PRIVATE, color;
        boolean indeterminate, media;
        boolean chronometer, countDown;
        long when;
        Icon icon;
        PendingIntent click;
        StatusBarNotification original;
        final ArrayList<Notification.Action> actions = new ArrayList<>();

        String fingerprint() {
            StringBuilder out = new StringBuilder();
            out.append(pkg).append('|').append(type).append('|').append(title).append('|')
                    .append(text).append('|').append(critical).append('|').append(progress)
                    .append('|').append(max).append('|').append(indeterminate).append('|')
                    .append(visibility).append('|').append(color).append('|').append(icon)
                    .append('|').append(chronometer).append('|').append(countDown).append('|').append(when)
                    .append('|').append(click == null ? 0 : click.hashCode());
            if (original != null) out.append('|').append(original.getPostTime());
            for (Notification.Action a : actions) out.append('|').append(a.title).append('|')
                    .append(a.actionIntent == null ? 0 : a.actionIntent.hashCode()).append('|').append(a.getIcon());
            return out.toString();
        }
    }

    static Notification build(Context context, Model model, int operation, int sequence) throws Exception {
        Icon icon = model.icon != null ? model.icon : Icon.createWithResource("android", android.R.drawable.stat_notify_sync);
        Bundle extras = new Bundle();
        extras.putBoolean(MARKER, true);
        extras.putString(MARKER + ".source", model.key);
        extras.putString(MARKER + ".package", model.pkg);
        extras.putBoolean(PREFIX + "islandNotify", true);
        extras.putBoolean(PREFIX + "showNotify", false);
        extras.putBoolean(PREFIX + "sound", false);
        extras.putBoolean(PREFIX + "dismissWhenKill", false);
        extras.putInt(PREFIX + "operation", operation);
        extras.putInt(PREFIX + "changedRecord", sequence);
        extras.putInt(PREFIX + "keepDuration", 0);
        extras.putString(PREFIX + "scene", "VivoIslandPlus");
        extras.putParcelable(PREFIX + "clickResp", model.click);
        int percent = CompactProgressPolicy.percent(model.progress, model.max);
        BundleTemplateResolver.Plan requests=PluginBundleTemplateContracts.plan(context);
        int template = requests.value(!model.actions.isEmpty() ? "buttons" : "normal");
        extras.putInt(PREFIX + "template", template);
        Bundle base = new Bundle();
        base.putCharSequence(PREFIX + "baseInfos.title", model.title);
        base.putCharSequence(PREFIX + "baseInfos.content", model.text);
        base.putParcelable(PREFIX + "baseInfos.icon", icon);
        base.putBoolean(PREFIX + "baseInfos.iconRoundCorner", true);
        base.putInt(PREFIX + "baseInfos.subInfo", model.actions.isEmpty()
                ? model.indeterminate ? 6 : model.max > 0 ? 5 : 0 : 0);
        if (model.max > 0) {
            base.putInt(PREFIX + "baseInfos.subProgress", percent);
            base.putInt(PREFIX + "baseInfos.progressState", 0);
            base.putCharSequence(PREFIX + "baseInfos.progressContent", model.critical.isEmpty() ? percent + "%" : model.critical);
            base.putInt(PREFIX + "baseInfos.subProgressColor", model.color != 0 ? model.color
                    : themeColor(context, "vivo_super_x_progress_bar_default_color", context.getColor(android.R.color.system_accent1_400)));
            base.putInt(PREFIX + "baseInfos.subProgressBkgColor",
                    themeColor(context, "vivo_super_x_widget_ring_progress_default_bkg_color", 0x33ffffff));
        }
        extras.putBundle(PREFIX + "baseInfos", base);
        Bundle infos = new Bundle();
        if (!model.actions.isEmpty()) {
            ArrayList<Icon> icons = new ArrayList<>();
            ArrayList<String> labels = new ArrayList<>();
            ArrayList<PendingIntent> intents = new ArrayList<>();
            ArrayList<Integer> textColors = new ArrayList<>(), bgColors = new ArrayList<>();
            for (Notification.Action action : model.actions) {
                if (icons.size() == 5) break;
                icons.add(action.getIcon() == null ? icon : action.getIcon());
                labels.add(clean(action.title)); intents.add(action.actionIntent);
                textColors.add(0xffffffff); bgColors.add(0xff454545);
            }
            infos.putInt(PREFIX + "infos.btnType", icons.size() <= 3 ? 1 : 2);
            infos.putParcelableArrayList(PREFIX + "infos.btnIconList", icons);
            infos.putStringArrayList(PREFIX + "infos.btnTextList", labels);
            infos.putParcelableArrayList(PREFIX + "infos.btnClickRespList", intents);
            infos.putIntegerArrayList(PREFIX + "infos.btnTextColorList", textColors);
            infos.putIntegerArrayList(PREFIX + "infos.btnColorList", bgColors);
        }
        extras.putBundle(PREFIX + "infos", infos);
        Bundle capsule = new Bundle();
        capsule.putInt(PREFIX + "capsule.state", 1);
        capsule.putCharSequence(PREFIX + "capsule.content", compactContent(model));
        capsule.putParcelable(PREFIX + "capsule.icon", icon);
        extras.putBundle(PREFIX + "capsule", capsule);
        Bundle island = new Bundle(), left = new Bundle(), right = new Bundle();
        BundleWireTemplateResolver.Plan wire=PluginWireTemplateContracts.plan(context);
        island.putInt("island.superx.leftTemplate", wire.value("left.icon"));
        island.putInt("island.superx.template", template);
        island.putInt("island.superx.showTime", -1);
        island.putString("island.superx.scene", "VivoIslandPlus");
        island.putString("island.superx.landingPkg", model.pkg);
        island.putInt("island.superx.islandClick", 0);
        island.putParcelable("island.superx.clickResp", model.click);
        JSONObject landing = new JSONObject();
        landing.put("mType", "app"); landing.put("mPkgName", model.pkg); landing.put("isDeepLink", false);
        island.putString("island.superx.landingInfo", landing.toString());
        left.putParcelable("island.superx.leftInfo.icon", icon);
        if (model.media) {
            island.putInt("island.superx.rightTemplate", wire.value("wave"));
            right.putInt("island.superx.rightInfo.waveState", PluginWaveStateContracts.plan(context).playing);
            ArrayList<Integer> colors = new ArrayList<>(); colors.add(model.color == 0 ? 0xffffffff : model.color);
            colors.add(model.color == 0 ? 0xffffffff : model.color);
            right.putIntegerArrayList("island.superx.rightInfo.waveColor", colors);
        } else if (!model.critical.isEmpty() || model.chronometer) {
            island.putInt("island.superx.rightTemplate", wire.value("text"));
            right.putCharSequence("island.superx.rightInfo.content", compactContent(model));
        } else if (model.indeterminate) {
            island.putInt("island.superx.rightTemplate", wire.value("loading"));
            if (model.color != 0) right.putInt("island.superx.rightInfo.loadingColor", model.color);
        } else if (model.max > 0) {
            island.putInt("island.superx.rightTemplate", wire.value("progress"));
            right.putInt("island.superx.rightInfo.progressValue", percent);
            right.putInt("island.superx.rightInfo.progressState", 0);
            if (model.color != 0) right.putInt("island.superx.rightInfo.progressColor", model.color);
        } else {
            island.putInt("island.superx.rightTemplate", wire.value("text"));
            right.putCharSequence("island.superx.rightInfo.content", model.title);
        }
        island.putBundle("island.superx.leftInfo", left);
        island.putBundle("island.superx.rightInfo", right);
        // Island-only cards read these bundles, rather than the shade bundles.
        island.putBundle("island.superx.baseInfos", base);
        island.putBundle("island.superx.infos", infos);
        extras.putBundle(PREFIX + "island", island);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(icon).setContentTitle(model.title).setContentText(model.text)
                .setContentIntent(model.click).setVisibility(model.visibility).setOngoing(true)
                .setOnlyAlertOnce(true).setDefaults(0).setSound(null).setShowWhen(false).addExtras(extras);
        for (Notification.Action action : model.actions) builder.addAction(action);
        if(!model.actions.isEmpty()&&requests.customButtons) {
            // Older plugins have no buttons-layout registration. Feed their
            // verified official custom renderer the platform's actual action view.
            android.widget.RemoteViews content=builder.createBigContentView();
            if(content==null)throw new IllegalStateException("Platform action notification has no expanded content");
            extras.putParcelable(PREFIX+"customTemplate",content);
            island.putParcelable("island.superx.customTemplate",content);
            extras.putBundle(PREFIX+"island",island);builder.addExtras(extras);
        }
        return builder.build();
    }

    static String clean(CharSequence value) {
        if (value == null) return "";
        String string = value.toString();
        return string.length() <= 512 ? string : string.substring(0, 512);
    }

    static String compactContent(Model model) throws Exception {
        if (!model.critical.isEmpty()) return model.critical;
        if (!model.chronometer) return model.title;
        JSONObject clock = new JSONObject();
        // Match Notification.Builder's wall-clock -> elapsed-time conversion.
        // The stock IslandTextView ticks only while shown; no bridge polling.
        clock.put("base", model.when + (android.os.SystemClock.elapsedRealtime() - System.currentTimeMillis()));
        clock.put("countDown", model.countDown); clock.put("started", true);
        clock.put("minUnit", "M"); clock.put("elapsedFormatHMS", "%d:%02d:%02d");
        clock.put("elapsedFormatMS", "%02d:%02d"); clock.put("elapsedFormatS", "%02d");
        return clock.toString();
    }

    private static int themeColor(Context context, String name, int fallback) {
        try {
            Context plugin = context.createPackageContext("com.vivo.systemuiplugin", Context.CONTEXT_IGNORE_SECURITY);
            for (String pkg : new String[]{"com.vivo.systemuiplugin.superx", "com.vivo.systemuiplugin"}) {
                int id = plugin.getResources().getIdentifier(name, "color", pkg);
                if (id != 0) return plugin.getColor(id);
            }
        } catch (Throwable ignored) { }
        return fallback;
    }
}
