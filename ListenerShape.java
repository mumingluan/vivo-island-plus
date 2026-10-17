package com.axuan.vivoislandplus;

import java.lang.reflect.Modifier;
import java.util.*;

/** Uses platform callback contracts and a live registered listener, never private host names. */
final class ListenerShape {
    static final String CONTEXT="Landroid/content/Context;", COMPONENT="Landroid/content/ComponentName;";
    static final String SBN="Landroid/service/notification/StatusBarNotification;";
    static final String RANKING="Landroid/service/notification/NotificationListenerService$RankingMap;";
    static final String REGISTRATION="("+CONTEXT+COMPONENT+"I)V";
    static final class Member {
        final String owner,name,type;final int flags;
        Member(String owner,String name,String type,int flags){this.owner=owner;this.name=name;this.type=type;this.flags=flags;}
        String key(){return owner+"->"+name+type;}
        boolean callable(){return Modifier.isPublic(flags)&&!Modifier.isStatic(flags)&&!Modifier.isAbstract(flags)&&(flags&0x1000)==0;}
    }
    static final class Plan {
        final Member registration;final Map<String,Member> callbacks;
        Plan(Member registration,Map<String,Member> callbacks){this.registration=registration;this.callbacks=Collections.unmodifiableMap(callbacks);}
    }
    static Member registration(List<Member> base)throws ReflectiveOperationException {
        List<Member> found=new ArrayList<>();for(Member m:base)if(m.callable()&&m.type.equals(REGISTRATION))found.add(m);
        return unique(found,"platform listener registration");
    }
    static Plan discover(List<Member> base,List<Member> actual)throws ReflectiveOperationException {
        Member registration=registration(base);Map<String,Member> roles=new LinkedHashMap<>();
        // These names are Android's virtual dispatch API, not vendor private names.
        callback(roles,"connected",actual,registration.owner,"onListenerConnected","()V");
        callback(roles,"posted",actual,registration.owner,"onNotificationPosted","("+SBN+RANKING+")V");
        callback(roles,"removed",actual,registration.owner,"onNotificationRemoved","("+SBN+RANKING+"I)V");
        callback(roles,"ranking",actual,registration.owner,"onNotificationRankingUpdate","("+RANKING+")V");
        List<Member> extension=new ArrayList<>();
        for(Member m:actual)if(m.callable()&&!m.owner.equals(registration.owner)&&m.type.equals("("+SBN+"I)V")&&!m.name.equals("onNotificationRemoved"))
            for(Member api:base)if(api.callable()&&api.name.equals(m.name)&&api.type.equals(m.type))extension.add(m);
        roles.put("validation",unique(extension,"vendor validation override"));
        return new Plan(registration,roles);
    }
    private static void callback(Map<String,Member> roles,String role,List<Member> actual,String base,String name,String type)throws ReflectiveOperationException {
        List<Member> found=new ArrayList<>();for(Member m:actual)if(m.callable()&&!m.owner.equals(base)&&m.name.equals(name)&&m.type.equals(type))found.add(m);
        roles.put(role,unique(found,role+" callback"));
    }
    private static Member unique(List<Member> found,String role)throws ReflectiveOperationException {
        if(found.size()!=1)throw new NoSuchMethodException("Missing/ambiguous "+role+": "+found.size());return found.get(0);
    }
}
