package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Prove that the runtime model card enum is the notification request enum. */
final class CardTemplateTransportResolver {
    static final class Plan {
        final DexIndex.Ref consumer,templateGetter,sbnGetter;
        final int bundleWritePc;
        Plan(DexIndex.Ref c,DexIndex.Ref t,DexIndex.Ref s,int pc){consumer=c;templateGetter=t;sbnGetter=s;bundleWritePc=pc;}
        String describe(){return "cardTransport="+consumer+" template="+templateGetter+" sbn="+sbnGetter+" write="+bundleWritePc;}
    }
    static Plan discover(DexIndex index,BeanModelResolver.Plan model)throws IOException {
        try{return checked(index,model);}
        catch(IndexOutOfBoundsException|ArithmeticException malformed){throw new IOException("Malformed card template transport",malformed);}
    }
    private static Plan checked(DexIndex index,BeanModelResolver.Plan model)throws IOException {
        if(!model.modelFields.keySet().containsAll(Arrays.asList("templateCard","sbn","priority"))
                ||model.modelFields.containsValue(null)
                ||new HashSet<>(model.modelFields.values()).size()!=model.modelFields.size())
            throw new IOException("Missing/aliased runtime card role fields");
        List<Plan> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values()) {
            if(!method.ref.type.endsWith(")V")||!method.ref.type.contains(model.model)||!method.facts().strings.contains("notification.superx.template"))continue;
            BeanOrigins flow=new BeanOrigins(method);
            for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.code[pc]&255)) {
                DexIndex.Ref call=method.invokedAt(pc);int[] args=flow.arguments(pc);
                if(!(call.owner.equals("Landroid/os/Bundle;")||call.owner.equals("Landroid/os/BaseBundle;"))
                        ||!call.name.equals("putInt")||!call.type.equals("(Ljava/lang/String;I)V")||args.length!=3)continue;
                BeanOrigins.Value key=flow.at(pc,args[1]),value=flow.at(pc,args[2]),bundle=flow.at(pc,args[0]);
                if(!kind(key,"string")||!key.text.equals("notification.superx.template")||!kind(value,"call")||!kind(bundle,"field"))continue;
                DexIndex.Ref template=method.invokedAt(value.index);int[] templateArgs=flow.arguments(value.index);
                if(!template.owner.equals(model.model)||!template.type.equals("()I")||templateArgs.length!=1
                        ||!BeanResolver.readField(index,template).equals(model.modelFields.get("templateCard")))continue;
                BeanOrigins.Value modelObject=flow.at(value.index,templateArgs[0]);
                if(!kind(modelObject,"arg")||!modelObject.text.equals(model.model))continue;
                DexIndex.Ref extras=method.fieldAt(bundle.index);int unit=flow.code[bundle.index];
                if((unit&255)!=0x54||!extras.owner.equals("Landroid/app/Notification;")||!extras.name.equals("extras")||!extras.type.equals("Landroid/os/Bundle;"))continue;
                BeanOrigins.Value notification=flow.at(bundle.index,unit>>12);if(!kind(notification,"call"))continue;
                DexIndex.Ref getNotification=method.invokedAt(notification.index);int[] notificationArgs=flow.arguments(notification.index);
                if(!getNotification.owner.equals("Landroid/service/notification/StatusBarNotification;")||!getNotification.name.equals("getNotification")
                        ||!getNotification.type.equals("()Landroid/app/Notification;")||notificationArgs.length!=1)continue;
                BeanOrigins.Value sbn=flow.at(notification.index,notificationArgs[0]);if(!kind(sbn,"call"))continue;
                DexIndex.Ref sbnGetter=method.invokedAt(sbn.index);int[] sbnArgs=flow.arguments(sbn.index);
                if(!sbnGetter.owner.equals(model.model)||!sbnGetter.type.equals("()Landroid/service/notification/StatusBarNotification;")||sbnArgs.length!=1
                        ||!modelObject.equals(flow.at(sbn.index,sbnArgs[0]))
                        ||!BeanResolver.readField(index,sbnGetter).equals(model.modelFields.get("sbn")))continue;
                found.add(new Plan(method.ref,template,sbnGetter,pc));
            }
        }
        if(found.size()!=1)throw new IOException("Missing/ambiguous runtime card-to-request enum transport: "+found.size());return found.get(0);
    }
    private static boolean kind(BeanOrigins.Value value,String kind){return value!=null&&value.kind.equals(kind);}
}
