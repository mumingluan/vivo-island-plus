package com.axuan.vivoislandplus;

import java.util.List;

/** Android-independent decisions for stock CallStyle action views. */
public final class CallActionPolicy {
    private CallActionPolicy() { }

    public static int match(Integer frameworkIndex, String label, List<String> titles) {
        // The framework puts an index tag on every action. Several buttons can
        // legitimately share action0 as their view ID or share the same label.
        if (frameworkIndex != null && frameworkIndex >= 0 && frameworkIndex < titles.size()
                && same(label, titles.get(frameworkIndex))) return frameworkIndex;
        int unique = -1;
        for (int i = 0; i < titles.size(); i++) if (same(label, titles.get(i))) {
            if (unique >= 0) return -1;
            unique = i;
        }
        return unique;
    }

    private static boolean same(String left, String right) {
        String normalized = label(left);
        return !normalized.isEmpty() && normalized.equals(label(right));
    }

    /** Framework CallStyle glues an icon and bidi isolates around the real title. */
    public static String label(String value) {
        if (value == null) return "";
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u061c' || c == '\u200e' || c == '\u200f'
                    || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) continue;
            clean.append(c == '\u00a0' ? ' ' : c);
        }
        String text = clean.toString().trim();
        if (!text.isEmpty() && (text.charAt(0) == '\ufffd' || text.charAt(0) == '\ufffc'))
            text = text.substring(1).trim();
        return text;
    }

    public static boolean iconAbove(int count, float rowWidth, float labelWidth, float density, float fontScale) {
        if (count <= 0 || density <= 0 || rowWidth <= 0) return false;
        float slot = (rowWidth - Math.max(0, count - 1) * 8 * density) / count;
        return (count >= 3 && fontScale > 1.15f) || labelWidth + 50 * density > slot;
    }

    public static int height(float density, float fontSpacing, boolean iconAbove) {
        if (!(density > 0) || Float.isInfinite(density)) density = 1;
        if (!(fontSpacing > 0) || Float.isInfinite(fontSpacing)) fontSpacing = 18 * density;
        return (int) Math.ceil(Math.max(48 * density, fontSpacing * 2 + (iconAbove ? 38 : 12) * density));
    }

    public static int hangupColor(int candidate) {
        int red = (candidate >>> 16) & 255, green = (candidate >>> 8) & 255, blue = candidate & 255;
        // Keep the destructive action visibly red even with a vendor theme.
        boolean redHue = red > green * 1.15 && red > blue * 1.15;
        if ((candidate >>> 24) == 255 && redHue && contrastWithWhite(candidate) >= 4.5) return candidate;
        return 0xffc62828;
    }

    public static double contrastWithWhite(int color) {
        double luminance = .2126 * linear((color >>> 16) & 255)
                + .7152 * linear((color >>> 8) & 255) + .0722 * linear(color & 255);
        return 1.05 / (luminance + .05);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}
