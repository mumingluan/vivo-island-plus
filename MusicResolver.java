package com.axuan.vivoislandplus;
import java.io.*;
import java.util.*;
/** Resolve the official music path by notification protocol and actual call edges. */
final class MusicResolver {
 interface Match { boolean accepts(DexIndex.MethodData method)throws IOException; }
 static final class Plan {
  final DexIndex.Ref sourceChange,gate,islandList,notificationSender;
  Plan(DexIndex.Ref sourceChange,DexIndex.Ref gate,DexIndex.Ref islandList,DexIndex.Ref sender){this.sourceChange=sourceChange;this.gate=gate;this.islandList=islandList;notificationSender=sender;}
  String describe(){return "sourceChange="+sourceChange+"\ngate="+gate+"\nislandList="+islandList+"\nnotificationSender="+notificationSender;}
 }
 static Plan discover(DexIndex index)throws IOException {
  DexIndex.MethodData change=unique(index,"music source dispatch",m -> !m.isStatic()
    && m.ref.type.equals("(Ljava/lang/String;III)V")
    && index.extendsType(m.ref.owner,"Landroid/app/Application;")
    && m.facts().strings.containsAll(Arrays.asList("music_pkg_key","music_play_state","playing_list_size_key"))
    && calls(m,"Landroid/os/Handler;","sendMessageDelayed"));
  DexIndex.MethodData gate=unique(index,"resident music admission",m -> m.isStatic()
    && m.ref.type.equals("(Landroid/content/Context;Ljava/lang/String;)Z")
    && calls(m,"Landroid/text/TextUtils;","isEmpty") && calls(m,"Ljava/lang/String;","startsWith")
    && m.facts().fields.stream().anyMatch(f -> f.owner.equals(change.ref.owner)&&f.type.equals("Ljava/util/List;")));
  DexIndex.MethodData sender=unique(index,"official island notification sender",m -> !m.isStatic()
    && m.facts().strings.containsAll(Arrays.asList("notification.superx.island","island.superx.rightInfo.waveState"))
    && calls(m,"Landroid/app/NotificationManager;","notify") && calls(m,"Ljava/util/List;","contains"));
  Set<DexIndex.Ref> candidates=new LinkedHashSet<>();
  for(DexIndex.Ref r:sender.facts().calls) {
   DexIndex.MethodData target=index.get(r);
   if(target!=null && !target.isStatic() && r.type.equals("()Ljava/util/List;")
     && target.facts().instructions<=8 && target.facts().calls.isEmpty()
     && target.facts().fields.stream().anyMatch(f->f.owner.equals(r.owner)&&f.type.equals("Ljava/util/List;"))) candidates.add(r);
  }
  if(candidates.size()!=1)throw new IOException("Missing/ambiguous island list getter: "+candidates);
  return new Plan(change.ref,gate.ref,candidates.iterator().next(),sender.ref);
 }
 static DexIndex.MethodData unique(DexIndex index,String role,Match match)throws IOException {
  List<DexIndex.MethodData> found=new ArrayList<>();for(DexIndex.MethodData m:index.methods.values())if(match.accepts(m))found.add(m);
  if(found.size()!=1)throw new IOException("Missing/ambiguous "+role+": "+found.size());return found.get(0);
 }
 static boolean calls(DexIndex.MethodData method,String owner,String name)throws IOException {
  for(DexIndex.Ref r:method.facts().calls)if(owner.equals(r.owner)&&name.equals(r.name))return true;return false;
 }
}
