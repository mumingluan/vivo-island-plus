package com.axuan.vivoislandplus;
import java.io.*;import java.lang.reflect.*;import java.util.*;
/** Host-only adversarial discovery checks on the real APK's decoded call graph. */
public final class ResolverRobustnessTest {
 private static final Field FACTS;
 static {try {FACTS=DexIndex.MethodData.class.getDeclaredField("facts");FACTS.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 static boolean platform(String owner){return owner.startsWith("Ljava/")||owner.startsWith("Landroid/")||owner.startsWith("Lorg/json/")||owner.startsWith("Ljavax/");}
 static DexIndex renamed(DexIndex original)throws Exception {
  Map<String,String> types=new LinkedHashMap<>();
  for(String owner:original.parents.keySet())if(!platform(owner))types.put(owner,"Ldiscovered/Type"+types.size()+";");
  Map<String,String> names=new LinkedHashMap<>();
  for(DexIndex.MethodData method:original.methods.values())if(!platform(method.ref.owner)&&!method.ref.name.startsWith("<"))
   names.putIfAbsent(method.ref.name,"member"+names.size());
  for(DexIndex.MethodData method:original.methods.values())for(DexIndex.Ref field:method.facts().fields)
   if(!platform(field.owner))names.putIfAbsent(field.name,"member"+names.size());
  for(DexIndex.FieldData field:original.declaredFields.values())if(!platform(field.ref.owner))
   names.putIfAbsent(field.ref.name,"member"+names.size());
  DexIndex out=new DexIndex();
  for(Map.Entry<String,String> e:original.parents.entrySet())out.parents.put(types.getOrDefault(e.getKey(),e.getKey()),types.getOrDefault(e.getValue(),e.getValue()));
  for(DexIndex.FieldData field:original.declaredFields.values()) {
   DexIndex.Ref ref=rename(field.ref,types,names);out.declaredFields.put(ref.key(),new DexIndex.FieldData(ref,field.flags,field.initialInteger));
  }
  for(DexIndex.MethodData m:original.methods.values()) {
   DexIndex.Ref ref=rename(m.ref,types,names);DexIndex.MethodData copy=new DexIndex.MethodData(ref,m.flags,0,null);
   DexIndex.Facts f=new DexIndex.Facts(),old=m.facts();f.strings.addAll(old.strings);f.constants.addAll(old.constants);f.instructions=old.instructions;f.opcodes.addAll(old.opcodes);
   for(DexIndex.Ref r:old.calls)f.calls.add(rename(r,types,names));
   for(DexIndex.Ref r:old.fields)f.fields.add(rename(r,types,names));FACTS.set(copy,f);out.methods.put(ref.key(),copy);
  }
  return out;
 }
 static DexIndex.Ref rename(DexIndex.Ref r,Map<String,String> types,Map<String,String> names){
  java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("L[^;]+;").matcher(r.type);
  StringBuffer buffer=new StringBuffer();while(matcher.find())matcher.appendReplacement(buffer,java.util.regex.Matcher.quoteReplacement(types.getOrDefault(matcher.group(),matcher.group())));matcher.appendTail(buffer);
  String type=buffer.toString();
  return new DexIndex.Ref(types.getOrDefault(r.owner,r.owner),platform(r.owner)||r.name.startsWith("<")?r.name:names.getOrDefault(r.name,r.name),type);
 }
 static DexIndex copy(DexIndex original){DexIndex out=new DexIndex();out.methods.putAll(original.methods);out.parents.putAll(original.parents);out.declaredFields.putAll(original.declaredFields);return out;}
 static Object discover(String mode,DexIndex index)throws Exception { return MusicResolver.discover(index); }
 static DexIndex.Ref selected(String mode,Object plan) { return ((MusicResolver.Plan)plan).gate; }
 static void rejects(String mode,DexIndex index,String why)throws Exception {
  try {discover(mode,index);}catch(IOException expected){return;}throw new AssertionError("Accepted "+why);
 }
 public static void main(String[] args)throws Exception {
  String mode=args[0];DexIndex original=DexIndex.readApks(Collections.singletonList(new File(args[1])));
  Object baseline=discover(mode,original);DexIndex.Ref target=selected(mode,baseline);
  Object shuffled=discover(mode,renamed(original));DexIndex.Ref changed=selected(mode,shuffled);
  if(!changed.owner.startsWith("Ldiscovered/")||changed.name.equals(target.name))throw new AssertionError("Names were not actually changed");
  DexIndex missing=copy(original);missing.methods.remove(target.key());rejects(mode,missing,"missing required method");
  DexIndex ambiguous=copy(original);ambiguous.methods.put("synthetic-ambiguous-copy",original.get(target));rejects(mode,ambiguous,"ambiguous method");
  System.out.println("PASS baseline + renamed classes/methods/fields + missing + ambiguous: "+mode);
 }
}
