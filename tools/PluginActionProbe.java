package com.axuan.vivoislandplus;
import java.io.*;import java.util.*;
/** Diagnostic inventory of the plugin's action contracts, not a production name allowlist. */
public final class PluginActionProbe {
 public static void main(String[] args)throws Exception {
  DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
  for(DexIndex.MethodData method:index.methods.values())if(!method.isStatic() &&
    (method.ref.type.equals("(Landroid/app/PendingIntent;I)V") ||
     method.ref.type.equals("(Landroid/service/notification/StatusBarNotification;)Landroid/widget/RemoteViews$InteractionHandler;"))) {
   System.out.println(method.ref+" strings="+method.facts().strings+" calls="+method.facts().calls);
  }
 }
}
