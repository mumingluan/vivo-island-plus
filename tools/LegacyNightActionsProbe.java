package com.axuan.vivoislandplus;
import java.io.*;import java.util.*;

/** Compare the retained optional helper with the stock night-mode caller in actual DEX. */
public final class LegacyNightActionsProbe {
    public static void main(String[] args)throws Exception {
        DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
        DexIndex.Ref night=new DexIndex.Ref("Lcom/vivo/notification/template/v2/template/CustomSuperXTemplate;","updateLNForNightMode","()V");
        DexIndex.Ref update=new DexIndex.Ref("Lcom/vivo/systemuiplugin/superx/common/utils/OnGoingNotificationDisplaysUtil;",
                "updateAodDisplayForDefaultForLiveNotification","(Landroid/view/ViewGroup;Z)V");
        DexIndex.MethodData original=index.get(night),helper=index.get(update);
        if(original==null)throw new AssertionError("Stock night template missing");
        boolean called=original.facts().calls.contains(update),present=helper!=null;
        if(called!=present)throw new AssertionError("Optional helper presence and original template call disagree");
        if(present&&((helper.flags&8)!=0||!original.facts().fields.contains(new DexIndex.Ref(update.owner,"INSTANCE",update.owner))))
            throw new AssertionError("Stock helper singleton/frame changed");
        if(!original.facts().calls.contains(new DexIndex.Ref("Landroid/widget/Button;","setTextColor","(I)V"))
                &&!original.facts().calls.contains(new DexIndex.Ref("Landroid/widget/TextView;","setTextColor","(I)V")))
            throw new AssertionError("Stock template no longer performs the retained button styling");
        System.out.println("PASS stock night caller="+night+" legacyHelper="+present+" called="+called+" buttonTextStyling=true");
    }
}
