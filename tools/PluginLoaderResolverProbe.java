package com.axuan.vivoislandplus;
import java.io.*;import java.lang.reflect.*;import java.util.*;
public final class PluginLoaderResolverProbe {
 public static void main(String[] args)throws Exception {
  DexIndex index=read(args[0]);PluginLoaderResolver.Plan p=PluginLoaderResolver.discover(index);System.out.println(p.describe());
  PluginLoaderResolver.Plan renamed=PluginLoaderResolver.discover(RawCodeNames.renamed(read(args[0])));
  if(!renamed.cache.owner.startsWith("Lchanged/")||renamed.cache.name.equals(p.cache.name)||renamed.factory.name.equals(p.factory.name)||renamed.lifecycle.name.equals(p.lifecycle.name))throw new AssertionError("Private name binding");
  if(!p.cache.name.equals("sClassLoaders")||!p.lifecycle.name.equals("loadPlugin"))throw new AssertionError("Original fixture roles changed");
  for(DexIndex.Ref ref:Arrays.asList(p.factory,p.initializer,p.lifecycle)) {
   DexIndex.MethodData m=index.methods.remove(ref.key());reject(index);index.methods.put(ref.key(),m);
   index.methods.put("duplicate-role",m);reject(index);index.methods.remove("duplicate-role");
  }
  DexIndex.FieldData field=index.declaredFields.remove(p.cache.key());reject(index);index.declaredFields.put(p.cache.key(),field);
  index.declaredFields.put(p.cache.key(),new DexIndex.FieldData(field.ref,field.flags&~8));reject(index);index.declaredFields.put(p.cache.key(),field);
  // Traverse and exercise every actual private Map accessor in the factory,
  // including the Kotlin companion -> static synthetic accessor chain.
  List<DexIndex.MethodData> accessors=new ArrayList<>();
  collectAccessors(index,index.get(p.factory),new HashSet<DexIndex.Ref>(),accessors);
  for(DexIndex.MethodData m:accessors) {
   index.methods.remove(m.ref.key());reject(index);index.methods.put(m.ref.key(),m);
   byte[] b=bytes(m),backup=b.clone();try{word(b,m,0,0x12);word(b,m,1,0x11);reject(index);}finally{restore(b,backup,m);}
  }
  mutate(index,p);
  // A hidden platform path producer can be renamed without changing the
  // ApplicationInfo -> two path lists -> PathClassLoader contract.
  DexIndex moved=read(args[0]);DexIndex.MethodData factory=moved.get(p.factory);boolean changed=false;
  for(DexIndex.Ref r:factory.facts().calls)if(r.type.equals("(Landroid/app/ActivityThread;ZLandroid/content/pm/ApplicationInfo;Ljava/util/List;Ljava/util/List;)V")) {
   rewrite(factory,"methodIds",r,new DexIndex.Ref("Lchanged/PlatformPaths;","produce",r.type));changed=true;break;
  }
  if(!changed||!PluginLoaderResolver.discover(moved).cache.equals(p.cache))throw new AssertionError("Hidden path producer name binding");
  System.out.println("PASS original cache hit/miss/path/publication contracts, raw private reference renaming, renamed path producer, missing/ambiguous/static roles, broken accessor, wrong Map key/value, swapped paths, wrong ApplicationInfo, inverted cache gate and unrelated return rejection; accessor-count="+accessors.size());
 }
 private static void collectAccessors(DexIndex x,DexIndex.MethodData m,Set<DexIndex.Ref> seen,List<DexIndex.MethodData> out)throws Exception {
  for(DexIndex.Ref r:m.facts().calls)if(r.type.equals("()Ljava/util/Map;")&&x.get(r)!=null&&seen.add(r)) {DexIndex.MethodData g=x.get(r);out.add(g);collectAccessors(x,g,seen,out);}
 }
 private static void mutate(DexIndex x,PluginLoaderResolver.Plan p)throws Exception {
  DexIndex.MethodData m=x.get(p.factory);byte[] bytes=bytes(m),original=bytes.clone();BeanOrigins f=new BeanOrigins(m);
  int put=-1,ctor=-1,path=-1,gate=-1,returned=-1;
  for(int pc:f.states.keySet()) {
   int op=f.code[pc]&255;
   if(op==0x38||op==0x39)gate=pc;
   if(op==0x11&&f.at(pc,f.code[pc]>>8)!=null&&f.at(pc,f.code[pc]>>8).kind.equals("new"))returned=pc;
   if(!BeanOrigins.invoke(op))continue;DexIndex.Ref r=m.invokedAt(pc);
   if(r.name.equals("put")&&r.type.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))put=pc;
   if(r.owner.equals("Ldalvik/system/PathClassLoader;")&&r.name.equals("<init>"))ctor=pc;
   if(r.type.equals("(Landroid/app/ActivityThread;ZLandroid/content/pm/ApplicationInfo;Ljava/util/List;Ljava/util/List;)V"))path=pc;
  }
  if(put<0||ctor<0||path<0||gate<0||returned<0)throw new AssertionError("Mutation target missing");
  try {operand(bytes,m,f,put,2,f.arguments(put)[0]);reject(x);}finally{restore(bytes,original,m);}
  try {int[] a=f.arguments(ctor);operand(bytes,m,f,ctor,1,a[2]);operand(bytes,m,f,ctor,2,a[1]);reject(x);}finally{restore(bytes,original,m);}
  try {operand(bytes,m,f,path,2,f.arguments(path)[0]);reject(x);}finally{restore(bytes,original,m);}
  try {word(bytes,m,gate,(f.code[gate]&0xff00)|((f.code[gate]&255)==0x38?0x39:0x38));reject(x);}finally{restore(bytes,original,m);}
  try {word(bytes,m,returned,(f.arguments(put)[0]<<8)|0x11);reject(x);}finally{restore(bytes,original,m);}
  // Replace the public package key reference while retaining all method tags,
  // Map operations and loader creation instructions.
  DexIndex.Ref key=new DexIndex.Ref("Landroid/content/pm/ApplicationInfo;","packageName","Ljava/lang/String;");
  DexIndex.Ref[] table=table(m,"fieldIds"),saved=table.clone();
  rewrite(m,"fieldIds",key,new DexIndex.Ref(key.owner,"processName",key.type));
  try{reject(x);}finally{System.arraycopy(saved,0,table,0,table.length);}
  for(DexIndex.MethodData creator:x.methods.values())if(creator.ref.owner.equals(p.lifecycle.owner)&&creator.ref.equals(p.lifecycle)) {
   // Public reflective creation corroboration is required even if both
   // lifecycle diagnostic strings remain intact.
   for(DexIndex.Ref r:creator.facts().calls)if(x.get(r)!=null&&x.get(r).facts().calls.contains(new DexIndex.Ref("Ljava/lang/Class;","forName","(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"))) {
    DexIndex.MethodData c=x.get(r);DexIndex.Ref originalRef=new DexIndex.Ref("Ljava/lang/Class;","forName","(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    DexIndex.Ref invalid=new DexIndex.Ref(originalRef.owner,"unrelated",originalRef.type);
    rewrite(c,"methodIds",originalRef,invalid);try{reject(x);}finally{rewrite(c,"methodIds",invalid,originalRef);}break;
   }
  }
 }
 private static void operand(byte[] b,DexIndex.MethodData m,BeanOrigins f,int pc,int position,int register)throws Exception {
  if((f.code[pc]&255)>=0x74)throw new AssertionError("Fixture invoke range mutation not implemented");
  if(position==4)word(b,m,pc,(f.code[pc]&~0xf00)|(register<<8));
  else {int at=pc+2,old=(b[m.code+16+at*2]&255)|((b[m.code+17+at*2]&255)<<8);word(b,m,at,(old&~(15<<(position*4)))|(register<<(position*4)));}
  clear(m);
 }
 private static void rewrite(DexIndex.MethodData m,String table,DexIndex.Ref from,DexIndex.Ref to)throws Exception {
  DexIndex.Ref[] refs=table(m,table);boolean changed=false;
  for(int i=0;i<refs.length;i++)if(refs[i].equals(from)){refs[i]=to;changed=true;}
  if(!changed)throw new AssertionError("Reference mutation missing: "+from);
  // Facts are shared by all methods in this DEX and can have been cached by
  // discovery. Clear them through the explicit per-index pass in reject().
 }
 private static DexIndex.Ref[] table(DexIndex.MethodData m,String name)throws Exception {Field f=DexIndex.MethodData.class.getDeclaredField("reader");f.setAccessible(true);Object r=f.get(m);f=r.getClass().getDeclaredField(name);f.setAccessible(true);return(DexIndex.Ref[])f.get(r);}
 private static byte[] bytes(DexIndex.MethodData m)throws Exception {Field f=DexIndex.MethodData.class.getDeclaredField("reader");f.setAccessible(true);Object r=f.get(m);f=r.getClass().getDeclaredField("bytes");f.setAccessible(true);return(byte[])f.get(r);}
 private static void word(byte[] b,DexIndex.MethodData m,int pc,int value)throws Exception {int at=m.code+16+pc*2;b[at]=(byte)value;b[at+1]=(byte)(value>>8);clear(m);}
 private static void clear(DexIndex.MethodData m)throws Exception {Field f=DexIndex.MethodData.class.getDeclaredField("facts");f.setAccessible(true);f.set(m,null);}
 private static void restore(byte[] b,byte[] original,DexIndex.MethodData m)throws Exception {System.arraycopy(original,0,b,0,b.length);clear(m);}
 private static void reject(DexIndex x)throws Exception {for(DexIndex.MethodData m:x.methods.values())clear(m);try{PluginLoaderResolver.discover(x);}catch(IOException expected){return;}throw new AssertionError("Accepted invalid loader contract");}
 private static DexIndex read(String file)throws Exception{return DexIndex.readApks(Collections.singletonList(new File(file)));}
}
