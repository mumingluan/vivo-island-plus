package com.axuan.vivoislandplus;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

/** Type-safe equivalent of the stock progress card's action styling. */
final class NativeCardCompat {
    interface AodUpdater { void update(LinearLayout actions) throws Throwable; }

    static int apply(View root, int textColor, AodUpdater aod) throws Throwable {
        if (root == null) return 0;
        View actions = find(root, "notification_action_list_margin_target");
        if (actions instanceof LinearLayout) aod.update((LinearLayout) actions);
        return styleButtons(root, textColor);
    }

    private static int styleButtons(View view, int color) {
        if (view.getVisibility() != View.VISIBLE) return 0;
        int styled = 0;
        // Android 16 can give every action the same action0 ID.
        if (view instanceof Button) {
            for (String name : new String[]{"action0", "action1", "action2"}) {
                int id = view.getResources().getIdentifier(name, "id", "android");
                if (id != 0 && view.getId() == id) {
                    ((Button) view).setTextColor(color); styled++; break;
                }
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            styled += styleButtons(((ViewGroup) view).getChildAt(i), color);
        return styled;
    }

    private static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "android");
        return id == 0 ? null : root.findViewById(id);
    }
}
