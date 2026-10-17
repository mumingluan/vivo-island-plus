package com.axuan.vivoislandplus;
import java.io.*;import java.util.*;
/** Real plugin APK discovery, renamed decoded graph, and role failure checks. */
public final class PluginActionResolverTest {
 public static void main(String[] args)throws Exception {
  DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
  PluginActionResolver.Plan plan=PluginActionResolver.discover(index);
  System.out.println(plan.describe());
  PluginActionResolver.Plan renamed=PluginActionResolver.discover(ResolverRobustnessTest.renamed(index));
  for(DexIndex.Ref ref:Arrays.asList(renamed.listener,renamed.clickHandler,renamed.transport))
   if(!ref.owner.startsWith("Ldiscovered/"))throw new AssertionError("Owner was not renamed: "+ref);
  for(DexIndex.Ref ref:Arrays.asList(plan.listener,plan.clickHandler,plan.transport)) {
   DexIndex.MethodData saved=index.methods.remove(ref.key());rejects(index,"missing "+ref);index.methods.put(ref.key(),saved);
   index.methods.put("ambiguous-probe",saved);rejects(index,"ambiguous "+ref);index.methods.remove("ambiguous-probe");
  }
  System.out.println("PASS plugin action roles + renamed classes/methods/fields + missing + ambiguous");
 }
 static void rejects(DexIndex index,String reason)throws Exception {try{PluginActionResolver.discover(index);}catch(IOException expected){return;}throw new AssertionError("Accepted "+reason);}
}
