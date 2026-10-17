package com.axuan.vivoislandplus;

import android.app.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.*;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import java.lang.ref.WeakReference;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Styles existing CallStyle controls; original click listeners remain attached. */
public final class CallCardButtons {
    private CallCardButtons() { }
    private static final Map<View, Boolean> observed = new WeakHashMap<>();

    /** Keep the icon centered on the text font, without ImageSpan changing its baseline. */
    private static final class CenteredIcon extends ReplacementSpan {
        final Drawable icon;
        CenteredIcon(Drawable icon) { this.icon = icon; }
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            if (metrics != null) {
                int extra = Math.max(0, icon.getBounds().height() - (fm.descent - fm.ascent));
                metrics.ascent = fm.ascent - (extra + 1) / 2;
                metrics.descent = fm.descent + extra / 2;
                metrics.top = Math.min(fm.top, metrics.ascent);
                metrics.bottom = Math.max(fm.bottom, metrics.descent);
            }
            return icon.getBounds().width();
        }
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int baseline, int bottom, Paint paint) {
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            float center = baseline + (fm.ascent + fm.descent) / 2f;
            canvas.save(); canvas.translate(x, center - icon.getBounds().height() / 2f);
            icon.draw(canvas); canvas.restore();
        }
    }

    private static int sourceForeground(CharSequence title) {
        if (!(title instanceof Spanned)) return Color.WHITE;
        Spanned text = (Spanned) title;
        ForegroundColorSpan[] spans = text.getSpans(0, text.length(), ForegroundColorSpan.class);
        Integer color = null;
        for (ForegroundColorSpan span : spans) {
            if (text.getSpanStart(span) > 0 || text.getSpanEnd(span) < text.length()) continue;
            if (color != null && color != span.getForegroundColor()) return Color.WHITE;
            color = span.getForegroundColor();
        }
        return color != null && Color.alpha(color) == 255 ? color : Color.WHITE;
    }

    private static void header(View root, Notification source) {
        boolean clock = source.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && source.when > 0;
        boolean time = source.extras.getBoolean(Notification.EXTRA_SHOW_WHEN) && source.when > 0;
        if (!clock) hide(root, "chronometer");
        if (!time && !clock) {
            hide(root, "time"); hide(root, "time_divider"); hide(root, "app_name_divider");
        }
    }
    private static void hide(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "android");
        View view = id == 0 ? null : root.findViewById(id);
        if (view != null) view.setVisibility(View.GONE);
    }

    /** Equal slots centered on the card, within the original row's clipping bounds. */
    public static void align(View root) {
        if (root.getWidth() <= 0) return;
        ArrayList<Button> buttons = new ArrayList<>(); collect(root, buttons);
        if (buttons.isEmpty() || !(buttons.get(0).getParent() instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) buttons.get(0).getParent();
        for (Button button : buttons) if (button.getParent() != row) return;
        int offset = 0;
        for (View current = row; current != root;) {
            offset += current.getLeft() + Math.round(current.getTranslationX());
            if (!(current.getParent() instanceof View)) return;
            current = (View) current.getParent();
        }
        int center = root.getWidth() / 2 - offset;
        int radius = Math.min(center - row.getPaddingLeft(), row.getWidth() - row.getPaddingRight() - center);
        int gap = dp(root, 8), count = buttons.size();
        int available = 2 * radius - gap * (count - 1);
        if (available <= 0) return;
        int start = center - radius;
        boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < count; i++) {
            Button button = buttons.get(i);
            int slot = rtl ? count - 1 - i : i;
            int left = start + available * slot / count + gap * slot;
            int right = start + available * (slot + 1) / count + gap * slot;
            int height = button.getMeasuredHeight();
            if (height <= 0) continue;
            int top = row.getPaddingTop() + (row.getHeight() - row.getPaddingTop() - row.getPaddingBottom() - height) / 2;
            if (button.getMeasuredWidth() != right - left)
                button.measure(View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            if (button.getLeft() != left || button.getRight() != right || button.getTop() != top || button.getHeight() != height)
                button.layout(left, top, right, top + height);
        }
    }
    private static final class LayoutObserver implements View.OnAttachStateChangeListener,
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<View> owner;
        android.view.ViewTreeObserver tree;
        LayoutObserver(View root) { owner = new WeakReference<>(root); }
        public void onGlobalLayout() { View root = owner.get(); if (root != null) align(root); }
        public void onViewAttachedToWindow(View root) {
            tree = root.getViewTreeObserver(); tree.addOnGlobalLayoutListener(this);
        }
        public void onViewDetachedFromWindow(View root) {
            if (tree != null && tree.isAlive()) tree.removeOnGlobalLayoutListener(this);
            tree = null;
        }
    }
    private static void observe(View root) {
        if (observed.containsKey(root)) return;
        observed.put(root, true);
        LayoutObserver observer = new LayoutObserver(root);
        root.addOnAttachStateChangeListener(observer);
        if (root.isAttachedToWindow()) observer.onViewAttachedToWindow(root);
    }

    public static int apply(View root, Notification notification) {
        if (root == null || notification == null || notification.extras == null
                || !"android.app.Notification$CallStyle".equals(notification.extras.getString(Notification.EXTRA_TEMPLATE))) return 0;
        header(root, notification);
        observe(root);
        PendingIntent hangup = notification.extras.getParcelable("android.hangUpIntent", PendingIntent.class);
        Notification.Action[] actions = notification.actions;
        if (actions == null) return 0;
        int count = 0;
        ArrayList<Button> buttons = new ArrayList<>();
        collect(root, buttons);
        ArrayList<String> titles = new ArrayList<>();
        for (Notification.Action action : actions) titles.add(action.title == null ? null : action.title.toString());
        float density = root.getResources().getDisplayMetrics().density;
        float rowWidth = root.getWidth() > 0 ? root.getWidth() : root.getMeasuredWidth();
        if (rowWidth <= 0) rowWidth = (root.getResources().getConfiguration().screenWidthDp - 32) * density;
        float widestLabel = 0;
        android.graphics.Paint labelPaint = new android.graphics.Paint();
        for (Button button : buttons) {
            labelPaint.set(button.getPaint());
            labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, root.getResources().getDisplayMetrics()));
            widestLabel = Math.max(widestLabel, labelPaint.measureText(CallActionPolicy.label(button.getText().toString())));
        }
        boolean iconAbove = CallActionPolicy.iconAbove(buttons.size(), rowWidth, widestLabel,
                density, root.getResources().getConfiguration().fontScale);
        for (int index = 0; index < buttons.size(); index++) {
            Button button = buttons.get(index);
            int tagId = root.getResources().getIdentifier("notification_action_index_tag", "id", "android");
            Object tagged = tagId == 0 ? null : button.getTag(tagId);
            int matched = CallActionPolicy.match(tagged instanceof Integer ? (Integer) tagged : null,
                    button.getText().toString(), titles);
            // An ambiguous custom layout keeps its system styling and listener.
            if (matched < 0) continue;
            Notification.Action action = actions[matched];
            if (action.actionIntent == null) continue;
            boolean destructive = hangup != null && hangup.equals(action.actionIntent);
            int foreground = destructive ? Color.WHITE : sourceForeground(action.title);
            boolean lightSurface = !destructive && CallActionPolicy.contrastWithWhite(foreground) >= 4.5;
            int color = destructive ? declineColor(root) : lightSurface ? 0xffeeeeee : 0xff3c3c3c;
            if (!lightSurface) foreground = Color.WHITE;
            GradientDrawable background = new GradientDrawable();
            int[][] enabledStates = new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]};
            // A re-applied RemoteViews can leave a replacement ripple layer in
            // the empty state even while the Button is enabled/clickable.
            // Keep the semantic fill stable; disabled labels still use their
            // state list and the original View controls whether clicks work.
            background.setColor(color);
            background.setCornerRadius(dp(root, 24));
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE); mask.setCornerRadius(dp(root, 24));
            button.setBackgroundTintList(null);
            button.refreshDrawableState();
            background.setState(button.getDrawableState());
            RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x33ffffff), background, mask);
            button.setBackground(ripple);
            ripple.setState(button.getDrawableState());
            // CallStyle can put foreground-color spans on the destructive label.
            SpannableString label = new SpannableString(action.title);
            for (ForegroundColorSpan span : label.getSpans(0, label.length(), ForegroundColorSpan.class)) label.removeSpan(span);
            button.setTextColor(new ColorStateList(enabledStates, new int[]{lightSurface ? 0xff666666 : 0xffaaaaaa, foreground}));
            button.setAllCaps(false);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setContentDescription(action.title);
            button.setLayerType(View.LAYER_TYPE_NONE, null);
            int height = CallActionPolicy.height(density, button.getPaint().getFontSpacing(), iconAbove);
            button.setMinHeight(height); button.setMinimumHeight(height);
            button.setMinWidth(dp(root, 64)); button.setMinimumWidth(dp(root, 64));
            button.setPadding(dp(root, 12), dp(root, 6), dp(root, 12), dp(root, 6));
            button.setSingleLine(false); button.setMaxLines(2); button.setEllipsize(TextUtils.TruncateAt.END);
            button.setGravity(Gravity.CENTER);
            button.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            button.setLineSpacing(0, 1);
            button.setIncludeFontPadding(false);
            Drawable icon = null;
            try { if (action.getIcon() != null) icon = action.getIcon().loadDrawable(root.getContext()); }
            catch (Throwable ignored) { }
            if (icon == null && destructive) {
                int phone = root.getResources().getIdentifier("ic_call_decline", "drawable", "android");
                if (phone != 0) icon = root.getContext().getDrawable(phone);
            }
            if (icon != null) {
                icon = icon.mutate(); icon.setTint(foreground);
                icon.setBounds(0, 0, dp(root, 18), dp(root, 18));
            }
            // A compound start drawable stays at the edge of a wide Button.
            // Keep the icon and title in one centered text run instead.
            CharSequence rendered = label;
            if (icon != null) {
                SpannableStringBuilder group = new SpannableStringBuilder();
                boolean rtl = root.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                group.append(rtl ? "\u2067" : "\u2066");
                int start = group.length(); group.append("\ufffc");
                group.setSpan(new CenteredIcon(icon), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                group.append(iconAbove ? "\n" : "  ");
                group.append("\u2068").append(label).append("\u2069\u2069");
                rendered = group;
            }
            button.setCompoundDrawablesRelative(null, null, null, null);
            // Reset the native deferred gluer too, so later RTL/layout passes
            // cannot put the original icon/title back over the styled run.
            try {
                button.getClass().getMethod("glueIcon", Icon.class).invoke(button, new Object[]{null});
                button.getClass().getMethod("glueLabel", CharSequence.class).invoke(button, rendered);
            } catch (ReflectiveOperationException ignored) { }
            button.setText(rendered);
            ViewGroup.LayoutParams lp = button.getLayoutParams();
            if (lp != null) {
                lp.height = height;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) lp;
                    margins.setMarginStart(index == 0 ? 0 : dp(root, 4));
                    margins.setMarginEnd(index == buttons.size() - 1 ? 0 : dp(root, 4));
                }
                button.setLayoutParams(lp);
            }
            if (button.getParent() instanceof ViewGroup) {
                ViewGroup row = (ViewGroup) button.getParent();
                row.setPaddingRelative(Math.max(row.getPaddingStart(), dp(root, 16)),
                        Math.max(row.getPaddingTop(), dp(root, 8)),
                        Math.max(row.getPaddingEnd(), dp(root, 16)),
                        Math.max(row.getPaddingBottom(), dp(root, 12)));
                row.setMinimumHeight(height + row.getPaddingTop() + row.getPaddingBottom());
                ViewGroup.LayoutParams rowLp = row.getLayoutParams();
                if (rowLp != null) { rowLp.height = ViewGroup.LayoutParams.WRAP_CONTENT; row.setLayoutParams(rowLp); }
            }
            count++;
        }
        align(root);
        return count;
    }

    private static int declineColor(View root) {
        int id = root.getResources().getIdentifier("call_notification_decline_color", "color", "android");
        try {
            if (id != 0) {
                return CallActionPolicy.hangupColor(root.getContext().getColor(id));
            }
        } catch (Throwable ignored) { }
        return 0xffc62828;
    }
    private static void collect(View view, ArrayList<Button> result) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof Button) {
            for (String name : new String[]{"action0", "action1", "action2"}) {
                int id = view.getResources().getIdentifier(name, "id", "android");
                if (id != 0 && id == view.getId()) { result.add((Button) view); return; }
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            collect(((ViewGroup) view).getChildAt(i), result);
    }
    private static int dp(View root, int value) {
        return Math.round(value * root.getResources().getDisplayMetrics().density);
    }
}
