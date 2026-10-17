package com.axuan.vivoislandplus;
import java.io.*;import java.util.*;

/** Actual stock call sites corroborate the typed compatibility adapters. */
public final class ReplyApiProbe {
    public static void main(String[] args)throws Exception {
        DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
        String bouncer="Lcom/android/systemui/statusbar/phone/StatusBarKeyguardViewManager;";
        String row="Lcom/android/systemui/statusbar/notification/row/ExpandableNotificationRow;";
        String groups="Lcom/android/systemui/statusbar/notification/collection/render/GroupExpansionManagerImpl;";
        String entry="Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;";
        DexIndex.Ref callback=new DexIndex.Ref("Lcom/android/systemui/statusbar/phone/StatusBarRemoteInputCallback;","onMakeExpandedVisibleForRemoteInput","("+row+"Landroid/view/View;ZLjava/lang/Runnable;)V");
        DexIndex.MethodData caller=index.get(callback);if(caller==null)throw new AssertionError("Native reply callback missing");
        Set<DexIndex.Ref> replyCalls=new LinkedHashSet<>();Set<DexIndex.Ref> seen=new HashSet<>();ArrayDeque<DexIndex.Ref> work=new ArrayDeque<>();work.add(callback);
        while(!work.isEmpty()) {
            DexIndex.Ref next=work.removeFirst();if(!seen.add(next))continue;
            if(seen.size()>64)throw new AssertionError("Native reply callback graph unbounded");
            DexIndex.MethodData method=index.get(next);if(method==null)continue;
            for(DexIndex.Ref call:method.facts().calls){replyCalls.add(call);if(call.owner.equals(callback.owner))work.add(call);}
        }
        List<DexIndex.Ref> bouncers=new ArrayList<>(),expanders=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values()) {
            DexIndex.Ref ref=method.ref;
            if(ref.owner.equals(bouncer)&&ref.name.equals("showBouncer")&&Arrays.asList("(Ljava/lang/String;Z)V","(ZLjava/lang/String;)V").contains(ref.type))bouncers.add(ref);
            if(ref.owner.equals(groups)&&ref.name.equals("setGroupExpanded")&&ref.type.endsWith(";Z)V"))expanders.add(ref);
        }
        if(bouncers.size()!=1||!replyCalls.contains(bouncers.get(0)))throw new AssertionError("Bouncer adapter disagrees with actual native reply callback path");
        if(expanders.size()!=1)throw new AssertionError("Group API missing/ambiguous");
        String proto=expanders.get(0).type,parameter=proto.substring(1,proto.length()-3);List<DexIndex.Ref> getters=new ArrayList<>();
        for(String name:Arrays.asList("getEntry","getEntryAdapter")) {
            DexIndex.Ref ref=new DexIndex.Ref(row,name,"()"+parameter);if(index.get(ref)!=null)getters.add(ref);
        }
        if(getters.size()!=1)throw new AssertionError("Group adapter cannot select exactly one parent getter");
        Set<DexIndex.Ref> rowCalls=new LinkedHashSet<>();boolean typed=false;
        for(DexIndex.MethodData method:index.methods.values())if(method.ref.owner.equals(row))rowCalls.addAll(method.facts().calls);
        for(DexIndex.Ref call:rowCalls)if(call.name.equals("isGroupExpanded")&&call.type.equals("("+parameter+")Z"))typed=true;
        if(!typed)throw new AssertionError("Native row/group parameter disagrees with adapter");
        if(parameter.equals(entry)) {
            DexIndex.MethodData legacy=index.get(new DexIndex.Ref(row,"getEntryLegacy","()"+entry));
            if(legacy==null||!legacy.facts().fields.equals(index.get(getters.get(0)).facts().fields)
                    ||legacy.facts().fields.size()!=1)throw new AssertionError("Legacy/current row getters disagree");
        } else if(!rowCalls.contains(getters.get(0)))throw new AssertionError("Native row no longer reads the selected adapter getter");
        System.out.println("PASS nativeReply="+bouncers.get(0)+" group="+expanders.get(0)+" parentGetter="+getters.get(0));
    }
}
