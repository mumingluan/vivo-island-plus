package com.axuan.vivoislandplus;
import java.io.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.nio.file.*;import java.nio.charset.*;import java.net.*;import javax.tools.*;

/** Feeds real framework/host method shapes to the production runtime-shape resolver. */
public final class ListenerContractProbe {
 static final String BASE="Landroid/service/notification/NotificationListenerService;";
 public static void main(String[] args)throws Exception {
  DexIndex framework=read(args[0]),host=read(args[1]);List<ListenerShape.Member> base=declared(framework,BASE);ListenerShape.registration(base);
  List<String> eligible=new ArrayList<>();ListenerShape.Plan plan=null;List<ListenerShape.Member> actual=null;
  for(String type:host.parents.keySet())if(host.extendsType(type,BASE)) {
   List<ListenerShape.Member> shape=methods(host,framework,type);
   try {ListenerShape.Plan candidate=ListenerShape.discover(base,shape);eligible.add(type);plan=candidate;actual=shape;}catch(ReflectiveOperationException unsupported){ }
  }
  check(eligible.size()==1,"Missing/ambiguous actual host listener shape: "+eligible);
  String owner=eligible.get(0);check(owner.equals("Lcom/android/systemui/statusbar/NotificationListener;"),"Wrong original fixture listener");
  System.out.println("listener="+owner+" registration="+plan.registration.key());
  for(Map.Entry<String,ListenerShape.Member> role:plan.callbacks.entrySet())System.out.println(role.getKey()+"="+role.getValue().key());
  proveRegistration(host,owner,plan.registration);
  List<ListenerShape.Member> renamedBase=relocate(base,plan,"framework"),renamedActual=relocate(actual,plan,"host");
  ListenerShape.Plan moved=ListenerShape.discover(renamedBase,renamedActual);
  check(moved.registration.name.equals("changedRegistration")&&moved.callbacks.get("validation").name.equals("changedExtension"),"Platform/vendor entry name binding");
  for(ListenerShape.Member m:moved.callbacks.values())check(m.owner.startsWith("Lrelocated/"),"Host private class binding");
  for(ListenerShape.Member member:plan.callbacks.values()) {
   List<ListenerShape.Member> absent=new ArrayList<>(actual);absent.remove(member);reject(base,absent);
   List<ListenerShape.Member> duplicate=new ArrayList<>(actual);duplicate.add(member);reject(base,duplicate);
   List<ListenerShape.Member> notOverride=new ArrayList<>(actual);notOverride.remove(member);notOverride.add(new ListenerShape.Member(BASE,member.name,member.type,member.flags));reject(base,notOverride);
   List<ListenerShape.Member> synthetic=new ArrayList<>(actual);synthetic.remove(member);synthetic.add(new ListenerShape.Member(member.owner,member.name,member.type,member.flags|0x1000));reject(base,synthetic);
  }
  List<ListenerShape.Member> duplicateBase=new ArrayList<>(base);duplicateBase.add(plan.registration);reject(duplicateBase,actual);
  List<ListenerShape.Member> missingBase=new ArrayList<>(base);missingBase.remove(plan.registration);reject(missingBase,actual);
  ListenerShape.Member extension=plan.callbacks.get("validation");List<ListenerShape.Member> wrongBase=new ArrayList<>();
  for(ListenerShape.Member m:base)wrongBase.add(m.name.equals(extension.name)&&m.type.equals(extension.type)?new ListenerShape.Member(m.owner,"unimplementedExtension",m.type,m.flags):m);
  reject(wrongBase,actual);
  // Android's two-argument removal API must never become a validation event.
  List<ListenerShape.Member> withRemoved=new ArrayList<>(actual);withRemoved.add(new ListenerShape.Member(owner,"onNotificationRemoved","("+ListenerShape.SBN+"I)V",1));
  check(ListenerShape.discover(base,withRemoved).callbacks.get("validation")==extension,"Removal misclassified as validation");
  lifecycle();reflection(plan,args[2]);
  System.out.println("PASS actual framework/host public shapes, Context/package/runtime-class registration and superclass forwarding, relocated host owners, renamed registration/vendor extension, missing/ambiguous/inherited/synthetic refusal, removal exclusion, real JVM reflection adapter and connection-registration race/failure sequencing");
 }
 static void proveRegistration(DexIndex host,String owner,ListenerShape.Member api)throws Exception {
  List<DexIndex.MethodData> calls=new ArrayList<>();
  for(DexIndex.MethodData m:host.methods.values())if(m.ref.owner.equals(owner)&&!m.isStatic()&&m.ref.type.equals("()V")&&m.facts().calls.stream().anyMatch(r->r.name.equals(api.name)&&r.type.equals(api.type)))calls.add(m);
  check(calls.size()==1,"Actual registration caller missing/ambiguous");DexIndex.MethodData m=calls.get(0);BeanOrigins f=new BeanOrigins(m);int registration=-1;
  for(int pc:f.states.keySet())if(BeanOrigins.invoke(f.code[pc]&255)) {DexIndex.Ref r=m.invokedAt(pc);if(r.name.equals(api.name)&&r.type.equals(api.type)){check(registration<0,"Duplicate registration");registration=pc;}}
  int[] a=f.arguments(registration);check(a.length==4&&arg(f.at(registration,a[0]),0),"Not registering this listener");
  BeanOrigins.Value context=f.at(registration,a[1]),component=f.at(registration,a[2]);
  check(context!=null&&context.kind.equals("field")&&m.fieldAt(context.index).type.equals(ListenerShape.CONTEXT)&&arg(f.at(context.index,f.code[context.index]>>12),0),"Context is not supplied by this listener");
  check(component!=null&&component.kind.equals("new")&&component.text.equals(ListenerShape.COMPONENT),"Registration component origin");
  boolean identity=false;
  for(int pc:f.states.keySet())if(BeanOrigins.invoke(f.code[pc]&255)&&m.invokedAt(pc).equals(new DexIndex.Ref(ListenerShape.COMPONENT,"<init>","(Ljava/lang/String;Ljava/lang/String;)V"))) {
   int[] c=f.arguments(pc);if(!component.equals(f.at(pc,c[0]))||!f.dominates(pc,registration))continue;
   BeanOrigins.Value pkg=f.at(pc,c[1]),klass=f.at(pc,c[2]);
   check(call(f,pkg,new DexIndex.Ref(ListenerShape.CONTEXT,"getPackageName","()Ljava/lang/String;")),"Component package source");
   BeanOrigins.Value pkgContext=f.at(pkg.index,f.arguments(pkg.index)[0]);check(pkgContext!=null&&pkgContext.kind.equals("field")&&m.fieldAt(pkgContext.index).equals(m.fieldAt(context.index)),"Component Context differs");
   check(call(f,klass,new DexIndex.Ref("Ljava/lang/Class;","getCanonicalName","()Ljava/lang/String;")),"Component class name source");
   BeanOrigins.Value type=f.at(klass.index,f.arguments(klass.index)[0]);check(call(f,type,new DexIndex.Ref("Ljava/lang/Object;","getClass","()Ljava/lang/Class;"))&&arg(f.at(type.index,f.arguments(type.index)[0]),0),"Component class is not this runtime class");identity=true;
  }
  check(identity,"Registration component constructor absent");
  DexIndex.Ref ref=m.invokedAt(registration);Set<String> seen=new HashSet<>();
  while(!ref.owner.equals(BASE)) {
   DexIndex.MethodData forwarding=lookup(host,ref);check(forwarding!=null&&seen.add(forwarding.ref.key()),"Registration never reaches platform");
   BeanOrigins g=new BeanOrigins(forwarding);List<DexIndex.Ref> next=new ArrayList<>();
   for(int pc:g.states.keySet())if(BeanOrigins.invoke(g.code[pc]&255)) {DexIndex.Ref r=forwarding.invokedAt(pc);if(r.name.equals(api.name)&&r.type.equals(api.type)) {
    int[] v=g.arguments(pc);check(v.length==4,"Forwarding arity");for(int i=0;i<4;i++)check(arg(g.at(pc,v[i]),i),"Registration changes receiver/Context/component/user");next.add(r);
   }}
   check(next.size()==1,"Registration superclass forwarding ambiguous");ref=next.get(0);
  }
 }
 static List<ListenerShape.Member> relocate(List<ListenerShape.Member> input,ListenerShape.Plan p,String suffix) {
  List<ListenerShape.Member> out=new ArrayList<>();for(ListenerShape.Member m:input) {
   String name=m.name;if(m.name.equals(p.registration.name)&&m.type.equals(p.registration.type))name="changedRegistration";
   ListenerShape.Member v=p.callbacks.get("validation");if(m.name.equals(v.name)&&m.type.equals(v.type))name="changedExtension";
   String owner=m.owner.equals(BASE)?BASE:"Lrelocated/"+suffix+Math.abs(m.owner.hashCode())+";";out.add(new ListenerShape.Member(owner,name,m.type,m.flags));
  }return out;
 }
 static List<ListenerShape.Member> declared(DexIndex x,String owner) {
  List<ListenerShape.Member> out=new ArrayList<>();for(DexIndex.MethodData m:x.methods.values())if(m.ref.owner.equals(owner)&&!m.ref.name.startsWith("<"))out.add(new ListenerShape.Member(m.ref.owner,m.ref.name,m.ref.type,m.flags));return out;
 }
 static List<ListenerShape.Member> methods(DexIndex host,DexIndex framework,String type) {
  Map<String,ListenerShape.Member> found=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
  while(type!=null&&seen.add(type)) {
   DexIndex source=host.parents.containsKey(type)?host:framework;
   for(ListenerShape.Member m:declared(source,type))if((m.flags&1)!=0)found.putIfAbsent(m.name+m.type,m);
   type=source.parents.get(type);
  }return new ArrayList<>(found.values());
 }
 static DexIndex.MethodData lookup(DexIndex x,DexIndex.Ref r){for(String owner=r.owner;owner!=null;owner=x.parents.get(owner)){DexIndex.MethodData m=x.get(new DexIndex.Ref(owner,r.name,r.type));if(m!=null)return m;}return null;}
 static boolean arg(BeanOrigins.Value v,int i){return v!=null&&v.kind.equals("arg")&&v.index==i;}
 static boolean call(BeanOrigins f,BeanOrigins.Value v,DexIndex.Ref r)throws Exception{return v!=null&&v.kind.equals("call")&&f.method.invokedAt(v.index).equals(r);}
 static void lifecycle()throws Exception {
  ListenerLifecycle early=new ListenerLifecycle();check(!early.connected()&&!early.ready()&&early.registered()&&early.ready(),"Early connection lost");
  ListenerLifecycle late=new ListenerLifecycle();check(!late.registered()&&late.connected()&&late.ready()&&late.connected(),"Late/repeated connection lost");
  ListenerLifecycle failed=new ListenerLifecycle();failed.connected();failed.failed();check(!failed.registered()&&!failed.connected()&&!failed.ready(),"Failed registration activated");
  for(int i=0;i<30;i++) {
   ListenerLifecycle concurrent=new ListenerLifecycle();CountDownLatch start=new CountDownLatch(1);AtomicInteger dispatch=new AtomicInteger();
   Thread a=new Thread(()->{try{start.await();if(concurrent.registered())dispatch.incrementAndGet();}catch(InterruptedException e){throw new AssertionError(e);}});
   Thread b=new Thread(()->{try{start.await();if(concurrent.connected())dispatch.incrementAndGet();}catch(InterruptedException e){throw new AssertionError(e);}});
   a.start();b.start();start.countDown();a.join();b.join();check(dispatch.get()==1&&concurrent.ready(),"Registration/connection race duplicate or missed dispatch");
  }
 }
 static void reflection(ListenerShape.Plan plan,String directory)throws Exception {
  Path root=Paths.get(directory);Files.createDirectories(root);List<String> sources=new ArrayList<>();
  fixture(root,sources,"android.content.Context","package android.content; public class Context {}\n");
  fixture(root,sources,"android.content.ComponentName","package android.content; public class ComponentName {}\n");
  fixture(root,sources,"android.service.notification.StatusBarNotification","package android.service.notification; public class StatusBarNotification {}\n");
  StringBuilder methods=new StringBuilder();
  for(Map.Entry<String,ListenerShape.Member> role:plan.callbacks.entrySet()) {
   String name=role.getKey().equals("validation")?"renamedVendorExtension":role.getValue().name;
   methods.append("public void ").append(name).append('(');List<String> types=BeanOrigins.parameters(role.getValue().type);
   for(int i=0;i<types.size();i++){if(i>0)methods.append(',');methods.append(javaType(types.get(i))).append(" p").append(i);}methods.append("){}\n");
  }
  fixture(root,sources,"android.service.notification.NotificationListenerService","package android.service.notification; public class NotificationListenerService { public static class RankingMap {} public void renamedRegistration(android.content.Context c,android.content.ComponentName n,int u) {} "+methods+"}\n");
  fixture(root,sources,"example.RenamedHost","package example; public class RenamedHost extends android.service.notification.NotificationListenerService { private Object unrelatedField; "+methods+"}\n");
  fixture(root,sources,"example.UnimplementedHost","package example; public class UnimplementedHost extends android.service.notification.NotificationListenerService {}\n");
  List<String> compiler=new ArrayList<>(Arrays.asList("-encoding","UTF-8","-d",root.toString()));compiler.addAll(sources);
  check(ToolProvider.getSystemJavaCompiler().run(null,null,null,compiler.toArray(new String[0]))==0,"JVM reflection fixture compile");
  try(URLClassLoader loader=new URLClassLoader(new URL[]{root.toUri().toURL()},null)) {
   Class<?> base=loader.loadClass("android.service.notification.NotificationListenerService"),host=loader.loadClass("example.RenamedHost");
   ListenerReflection.Plan bound=ListenerReflection.discover(base,host);
   check(bound.registration.getName().equals("renamedRegistration")&&bound.callbacks.get("validation").getName().equals("renamedVendorExtension")&&bound.callbacks.size()==5,"Reflection adapter retained private names");
   for(java.lang.reflect.Method m:bound.callbacks.values())check(m.getDeclaringClass()==host,"Wrong reflection declaring type");
   check(ListenerReflection.registration(base).equals(bound.registration),"Registration reflection binding mismatch");
   boolean rejected=false;try{ListenerReflection.discover(base,loader.loadClass("example.UnimplementedHost"));}catch(ReflectiveOperationException expected){rejected=true;}check(rejected,"Inherited default callbacks accepted");
  }
 }
 static String javaType(String descriptor){if(descriptor.equals("I"))return "int";return descriptor.substring(1,descriptor.length()-1).replace('/','.').replace('$','.');}
 static void fixture(Path root,List<String> sources,String name,String text)throws Exception {Path path=root.resolve(name.replace('.','/')+".java");Files.createDirectories(path.getParent());Files.write(path,text.getBytes(StandardCharsets.UTF_8));sources.add(path.toString());}
 static void reject(List<ListenerShape.Member> b,List<ListenerShape.Member> a)throws Exception {try{ListenerShape.discover(b,a);}catch(ReflectiveOperationException expected){return;}throw new AssertionError("Accepted invalid listener shape");}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static DexIndex read(String file)throws Exception{return DexIndex.readApks(Collections.singletonList(new File(file)));}
}
