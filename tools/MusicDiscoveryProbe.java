package com.axuan.vivoislandplus;
import java.io.*;import java.util.*;
public final class MusicDiscoveryProbe {
 public static void main(String[] args)throws Exception {
  DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
  for(DexIndex.MethodData m:index.methods.values()) {
   if(m.facts().calls.stream().anyMatch(r->r.owner.equals("Lk4/k0;")&&r.name.equals("c")))
    System.out.println("ISLAND-CALLER "+m.ref+" STRINGS="+m.facts().strings+" CALLS="+m.facts().calls);
   if(m.ref.type.equals("(Landroid/content/Context;Ljava/lang/String;)Z")
     &&m.facts().calls.stream().anyMatch(r->r.owner.equals("Ljava/lang/String;")&&r.name.equals("startsWith")))
    System.out.println("GATE "+m.ref+" FIELDS="+m.facts().fields+" STRINGS="+m.facts().strings+" CALLS="+m.facts().calls);
  }
 }
}