package com.axuan.vivoislandplus;

/** Routes by the notification's actual presentation contract, not by app identity. */
public final class NotificationRoutePolicy {
    private NotificationRoutePolicy() { }

    public static String route(String style, boolean ongoing, boolean groupSummary, boolean ownIsland,
            boolean requestedOrPromoted, boolean mediaToken, boolean progressCategory,
            int callType, int progressMax, boolean indeterminate) {
        if (!ongoing || groupSummary || ownIsland) return null;
        if (style == null) style = "";
        if ("android.app.Notification$MediaStyle".equals(style)
                || "android.app.Notification$DecoratedMediaCustomViewStyle".equals(style)) return null;
        boolean call = "android.app.Notification$CallStyle".equals(style) && callType == 2;
        boolean progressStyle = "android.app.Notification$ProgressStyle".equals(style);
        boolean progress = progressCategory && (progressMax > 0 || indeterminate);
        // A MediaSession token is supplemental metadata. CallStyle/ProgressStyle
        // must still enter their own route when an app also attaches that token.
        if (call) return "call";
        if (progress || (requestedOrPromoted && progressStyle)) return "progress";
        // A token alone is not a media presentation contract. Explicit live
        // requests with BigText/default style keep their own notification;
        // actual MediaStyle notifications were left on the official route above.
        if (requestedOrPromoted) return "live";
        return null;
    }
}
