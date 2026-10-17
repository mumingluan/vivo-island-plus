package com.axuan.vivoislandplus;

import java.util.*;

/** Regression cases from framework action ordering, duplicate labels and large fonts. */
public final class CallActionPolicyTest {
    private static int passed;
    private static void check(boolean ok, String scenario) {
        if (!ok) throw new AssertionError(scenario);
        passed++;
    }
    public static void main(String[] args) {
        List<String> duplicate = Arrays.asList("结束", "结束", "静音");
        check(CallActionPolicy.match(1, "结束", duplicate) == 1, "framework tag disambiguates duplicate labels");
        check(CallActionPolicy.match(null, "结束", duplicate) == -1, "ambiguous label cannot choose hangup styling");
        List<String> titles = Arrays.asList("挂断", "解除静音", "停止共享");
        check(CallActionPolicy.match(2, "停止共享", titles) == 2, "reordered framework action");
        check(CallActionPolicy.match(0, "停止共享", titles) == 2, "stale index falls back to unique label");
        check(CallActionPolicy.match(99, "解除静音", titles) == 1, "out of range index");
        check(CallActionPolicy.match(-1, "", titles) == -1, "unlabeled control stays unchanged");
        check(CallActionPolicy.match(null, "未知操作", titles) == -1, "unmatched control stays unchanged");
        check(CallActionPolicy.match(null, "挂断", Arrays.asList(null, "挂断")) == 1, "null source title");
        check(CallActionPolicy.match(1, "\u2066\ufffd\u00a0\u2068解除静音\u2069\u2069", titles) == 1,
                "real Android 16 glued label matches its original action");
        check(CallActionPolicy.match(0, "\u2067\ufffc  \u2068挂断\u2069\u2069", titles) == 0,
                "RTL inline icon keeps framework index");
        check(CallActionPolicy.match(null, "\u2066\ufffd\u00a0结束\u2069", duplicate) == -1,
                "normalized duplicate titles remain ambiguous");
        check(CallActionPolicy.match(1, "\u2066\ufffd\u00a0结束\u2069", duplicate) == 1,
                "normalized duplicates use validated framework tag");
        check(CallActionPolicy.label("a\u200cb\u200dc").equals("a\u200cb\u200dc"), "meaningful joiners preserved");
        check(CallActionPolicy.label("x\ufffdy").equals("x\ufffdy"), "internal replacement character preserved");
        check(!CallActionPolicy.iconAbove(2, 360, 56, 1, 1), "short normal labels use horizontal icons");
        check(CallActionPolicy.iconAbove(3, 360, 56, 1, 1.3f), "three large-font controls stack icons");
        check(CallActionPolicy.iconAbove(3, 320, 90, 1, 1), "long translated labels stack icons");
        check(!CallActionPolicy.iconAbove(0, 320, 90, 1, 1), "empty row");
        check(!CallActionPolicy.iconAbove(3, 0, 90, 1, 1), "unmeasured row");
        int normal = CallActionPolicy.height(3, 54, false);
        int large = CallActionPolicy.height(3, 70.2f, false);
        int huge = CallActionPolicy.height(3, 108, false);
        check(normal >= 48 * 3, "normal touch target");
        check(large > normal && huge > large, "font growth cannot clip to fixed height");
        check(CallActionPolicy.height(3, 70.2f, true) >= large + 26 * 3, "top icon reserves vertical space");
        check(CallActionPolicy.height(Float.NaN, Float.NaN, false) >= 48, "invalid metrics stay bounded");
        int red = CallActionPolicy.hangupColor(0xffe66a60);
        check(CallActionPolicy.contrastWithWhite(red) >= 4.5, "bright vendor red gets legible fallback");
        check(CallActionPolicy.hangupColor(0xffaa1122) == 0xffaa1122, "legible vendor red retained");
        check(CallActionPolicy.hangupColor(0xff2345aa) != 0xff2345aa, "non-red theme cannot disguise hangup");
        check(CallActionPolicy.hangupColor(0x80aa1122) != 0x80aa1122, "translucent destructive fill rejected");
        System.out.println("PASS " + passed + " offline call-action regression checks");
    }
}
