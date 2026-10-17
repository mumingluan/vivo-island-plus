package com.axuan.vivoislandplus;
import java.io.IOException;
import java.util.*;

/** Discovers plugin action implementations without anonymous/generated class names. */
final class PluginActionResolver {
    static final class Plan {
        final DexIndex.Ref listener, clickHandler, transport;
        Plan(DexIndex.Ref listener, DexIndex.Ref clickHandler, DexIndex.Ref transport) {
            this.listener=listener; this.clickHandler=clickHandler; this.transport=transport;
        }
        String describe() { return "listener="+listener+"\nclickHandler="+clickHandler+"\ntransport="+transport; }
    }
    static Plan discover(DexIndex index) throws IOException {
        List<DexIndex.Ref> listeners=new ArrayList<>(), clicks=new ArrayList<>();
        Set<String> islandContentOwners=new HashSet<>();
        for(DexIndex.MethodData method:index.methods.values())
            if(method.facts().text("NewSuperXLoader handleContentIntent success, isActivity = ")
                    && method.facts().text("NewSuperXLoader handleContentIntent error = ")) islandContentOwners.add(method.ref.owner);
        for(DexIndex.MethodData method:index.methods.values()) {
            if(method.isStatic())continue;
            if(method.ref.type.equals("(Landroid/app/PendingIntent;I)V")
                    && index.extendsType(method.ref.owner,"Landroid/os/Binder;")
                    && method.facts().strings.contains("pendingIntent")
                    && method.facts().calls.stream().anyMatch(r->r.type.equals("(Ljava/lang/Runnable;)V"))) listeners.add(method.ref);
            if(method.ref.type.equals("(Landroid/service/notification/StatusBarNotification;)Landroid/widget/RemoteViews$InteractionHandler;")
                    && islandContentOwners.contains(method.ref.owner)) clicks.add(method.ref);
        }
        if(listeners.size()!=1||clicks.size()!=1)throw new IOException("Missing/ambiguous plugin action roles: "+listeners+" / "+clicks);
        String owner=index.parents.get(listeners.get(0).owner);
        while(owner!=null&&!owner.equals("Landroid/os/Binder;")) {
            List<DexIndex.Ref> transports=new ArrayList<>();
            for(DexIndex.MethodData method:index.methods.values())
                if(method.ref.owner.equals(owner)&&!method.isStatic()
                        && method.ref.type.equals("(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z")) transports.add(method.ref);
            if(transports.size()>1)throw new IOException("Ambiguous Binder transport");
            if(transports.size()==1)return new Plan(listeners.get(0),clicks.get(0),transports.get(0));
            owner=index.parents.get(owner);
        }
        throw new IOException("No plugin Binder transport override");
    }
}
