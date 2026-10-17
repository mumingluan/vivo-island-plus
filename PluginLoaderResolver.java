package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Discovers the host's APK-path loader cache and plugin publication lifecycle. */
final class PluginLoaderResolver {
    private static final String APP = "Landroid/content/pm/ApplicationInfo;";
    private static final String LOADER = "Ljava/lang/ClassLoader;";
    private static final String PATH = "Ldalvik/system/PathClassLoader;";
    static final class Plan {
        final DexIndex.Ref factory, cache, initializer, lifecycle;
        final boolean staticFactory;
        Plan(DexIndex.Ref factory,DexIndex.Ref cache,DexIndex.Ref initializer,DexIndex.Ref lifecycle,boolean staticFactory) {
            this.factory=factory;this.cache=cache;this.initializer=initializer;this.lifecycle=lifecycle;this.staticFactory=staticFactory;
        }
        String describe(){return "factory="+factory+"\ncache="+cache+"\ninitializer="+initializer+"\nlifecycle="+lifecycle;}
    }
    static Plan discover(DexIndex index)throws IOException {
        List<Plan> matches=new ArrayList<>();List<String> rejected=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values()) {
            if(!method.ref.type.endsWith(")"+LOADER)||!method.facts().calls.contains(new DexIndex.Ref(PATH,"<init>","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V")))continue;
            try {
                DexIndex.Ref cache=cache(index,method),init=initializer(index,cache),life=lifecycle(index,cache);
                matches.add(new Plan(method.ref,cache,init,life,method.isStatic()));
            }catch(IOException unsupported){if(rejected.size()<8)rejected.add(method.ref+": "+unsupported.getMessage());}
        }
        require(matches.size()==1,"Missing/ambiguous APK loader cache/lifecycle: "+matches.size()+" "+rejected);return matches.get(0);
    }
    private static DexIndex.Ref cache(DexIndex index,DexIndex.MethodData method)throws IOException {
        BeanOrigins f=new BeanOrigins(method);List<Integer> gets=new ArrayList<>(),puts=new ArrayList<>(),ctors=new ArrayList<>(),paths=new ArrayList<>(),returns=new ArrayList<>();
        for(int pc:f.states.keySet()) {
            int op=f.code[pc]&255;
            if(op==0x11)returns.add(pc);
            if(!BeanOrigins.invoke(op))continue;DexIndex.Ref call=method.invokedAt(pc);
            if(mapCall(call,"get"))gets.add(pc);if(mapCall(call,"put"))puts.add(pc);
            if(call.equals(new DexIndex.Ref(PATH,"<init>","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V")))ctors.add(pc);
            // The platform path producer is identified by typed inputs/output
            // lists and their later use, without its private owner/member name.
            if(call.type.equals("(Landroid/app/ActivityThread;Z"+APP+"Ljava/util/List;Ljava/util/List;)V"))paths.add(pc);
        }
        require(gets.size()==1&&puts.size()==1&&ctors.size()==1&&paths.size()==1&&returns.size()==2,"Unknown loader creation/return shape");
        int get=gets.get(0),put=puts.get(0),ctor=ctors.get(0),path=paths.get(0);
        int[] ga=f.arguments(get),pa=f.arguments(put),ca=f.arguments(ctor),ma=f.arguments(path);
        require(ga.length==2&&pa.length==3&&ca.length==4&&ma.length==5,"Loader call arity changed");
        DexIndex.Ref cache=staticMap(index,f,f.at(get,ga[0]),new HashSet<DexIndex.Ref>());
        require(cache.equals(staticMap(index,f,f.at(put,pa[0]),new HashSet<DexIndex.Ref>())) ,"Loader cache read/write differ");
        String app=identity(f,f.at(path,ma[2]));require(app!=null,"Unknown APK path input");
        require(packageKey(f,f.at(get,ga[1]),app)&&packageKey(f,f.at(put,pa[1]),app),"Loader key is not the same ApplicationInfo package");
        require(isConstant(f.at(path,ma[0]),0)&&isConstant(f.at(path,ma[1]),1),"APK path production mode changed");
        BeanOrigins.Value dex=f.at(path,ma[3]),nativePath=f.at(path,ma[4]),created=f.at(ctor,ca[0]);
        require(isNew(dex,"Ljava/util/ArrayList;")&&isNew(nativePath,"Ljava/util/ArrayList;")&&!dex.equals(nativePath),"APK path output lists overlap");
        require(isNew(created,PATH)&&created.equals(f.at(put,pa[2])),"Cache does not publish the created PathClassLoader");
        require(join(f,f.at(ctor,ca[1]),dex,path)&&join(f,f.at(ctor,ca[2]),nativePath,path),"ClassLoader paths are not the produced APK/native paths");
        BeanOrigins.Value parent=f.at(ctor,ca[3]);require(parent!=null&&parent.kind.equals("new")&&index.extendsType(parent.text,LOADER),"Unknown loader parent/filter");
        boolean parentInitialized=false;
        for(int pc:f.states.keySet())if(BeanOrigins.invoke(f.code[pc]&255)) {
            DexIndex.Ref call=method.invokedAt(pc);int[] a=f.arguments(pc);
            if(call.name.equals("<init>")&&call.owner.equals(parent.text)&&a.length>1&&parent.equals(f.at(pc,a[0]))&&f.dominates(pc,ctor)) {
                for(int i=1;i<a.length;i++) {
                    BeanOrigins.Value value=f.at(pc,a[i]);
                    if(value!=null&&value.kind.equals("field")&&method.fieldAt(value.index).type.equals(LOADER)&&identity(f,value)!=null)parentInitialized=true;
                }
            }
        }
        require(parentInitialized&&f.dominates(path,ctor)&&f.dominates(ctor,put),"Loader parent/path construction not established before publication");
        Integer cachedReturn=null,newReturn=null;
        for(int pc:returns) {
            BeanOrigins.Value v=f.at(pc,f.code[pc]>>8);
            if(isCall(v,get)&&f.dominates(get,pc))cachedReturn=pc;
            else if(created.equals(v)&&f.dominates(put,pc))newReturn=pc;
            else throw new IOException("Loader returns an unrelated value");
        }
        require(cachedReturn!=null&&newReturn!=null,"Loader cache hit/miss return missing");
        boolean guarded=false;
        for(int pc:f.states.keySet()) {
            int u=f.code[pc],op=u&255;if(op!=0x38&&op!=0x39)continue;
            BeanOrigins.Value test=f.at(pc,u>>8);boolean predicate=isCall(test,get);
            if(test!=null&&test.kind.equals("call")) {
                DexIndex.Ref call=method.invokedAt(test.index);int[] a=f.arguments(test.index);
                if(mapCall(call,"containsKey")&&a.length==2)predicate=cache.equals(staticMap(index,f,f.at(test.index,a[0]),new HashSet<DexIndex.Ref>()))&&packageKey(f,f.at(test.index,a[1]),app);
            }
            if(!predicate)continue;int target=pc+(short)f.code[pc+1],fall=pc+2,yes=op==0x39?target:fall,no=op==0x39?fall:target;
            if(reaches(f,yes,cachedReturn)&&!reaches(f,no,cachedReturn)&&reaches(f,no,ctor)&&!reaches(f,yes,ctor))guarded=true;
        }
        require(guarded,"Loader cache hit/miss gate missing or inverted");return cache;
    }
    private static boolean join(BeanOrigins f,BeanOrigins.Value value,BeanOrigins.Value list,int producer)throws IOException {
        if(value==null||!value.kind.equals("call"))return false;
        DexIndex.Ref call=f.method.invokedAt(value.index);int[] a=f.arguments(value.index);
        if(!call.equals(new DexIndex.Ref("Landroid/text/TextUtils;","join","(Ljava/lang/CharSequence;Ljava/lang/Iterable;)Ljava/lang/String;"))||a.length!=2||!list.equals(f.at(value.index,a[1]))||!f.dominates(producer,value.index))return false;
        BeanOrigins.Value sep=f.at(value.index,a[0]);
        return sep!=null&&sep.kind.equals("field")&&(f.code[sep.index]&255)==0x62&&f.method.fieldAt(sep.index).equals(new DexIndex.Ref("Ljava/io/File;","pathSeparator","Ljava/lang/String;"));
    }
    private static DexIndex.Ref staticMap(DexIndex index,BeanOrigins f,BeanOrigins.Value value,Set<DexIndex.Ref> visiting)throws IOException {
        require(value!=null,"Unknown loader Map origin");
        if(value.kind.equals("field")) {
            DexIndex.Ref ref=f.method.fieldAt(value.index);DexIndex.FieldData field=index.declaredFields.get(ref.key());
            require((f.code[value.index]&255)==0x62&&field!=null&&field.isStatic()&&mapType(index,ref.type),"Loader Map is not a declared static cache");return ref;
        }
        require(value.kind.equals("call")&&visiting.size()<4,"Unknown loader Map access");
        DexIndex.Ref ref=f.method.invokedAt(value.index);DexIndex.MethodData getter=index.get(ref);
        require(getter!=null&&BeanOrigins.parameters(ref.type).isEmpty()&&mapType(index,ref.type.substring(ref.type.indexOf(')')+1))&&visiting.add(ref),"Unknown/recursive loader Map accessor");
        BeanOrigins g=new BeanOrigins(getter);List<DexIndex.Ref> found=new ArrayList<>();
        for(int pc:g.states.keySet()) {
            int op=g.code[pc]&255;
            if(op==0x11)found.add(staticMap(index,g,g.at(pc,g.code[pc]>>8),visiting));
            else require(op==0||op>=1&&op<=0x0c||op==0x1f||op==0x62||BeanOrigins.invoke(op),"Loader Map accessor has side effects/control flow");
            if(BeanOrigins.invoke(op)) {
                DexIndex.Ref nested=getter.invokedAt(pc);
                require(index.get(nested)!=null&&BeanOrigins.parameters(nested.type).isEmpty()&&mapType(index,nested.type.substring(nested.type.indexOf(')')+1)),"Unverified Map accessor invocation");
                staticMap(index,g,new BeanOrigins.Value("call",pc,nested.key()),visiting);
            }
        }
        require(found.size()==1,"Loader Map accessor must return exactly once");visiting.remove(ref);return found.get(0);
    }
    private static DexIndex.Ref initializer(DexIndex index,DexIndex.Ref cache)throws IOException {
        List<DexIndex.Ref> found=new ArrayList<>();
        for(DexIndex.MethodData m:index.methods.values())if(m.isStatic()&&m.ref.owner.equals(cache.owner)&&m.ref.name.equals("<clinit>")&&m.ref.type.equals("()V")) {
            BeanOrigins f=new BeanOrigins(m);int writes=0;
            for(int pc:f.states.keySet())if((f.code[pc]&255)==0x69&&m.fieldAt(pc).equals(cache)) {
                BeanOrigins.Value v=f.at(pc,f.code[pc]>>8);require(v!=null&&v.kind.equals("new")&&mapType(index,v.text),"Cache initializer stores an unknown Map");
                boolean constructed=false;
                for(int q:f.states.keySet())if(BeanOrigins.invoke(f.code[q]&255)) {
                    DexIndex.Ref call=m.invokedAt(q);int[] a=f.arguments(q);
                    if(call.owner.equals(v.text)&&call.name.equals("<init>")&&call.type.equals("()V")&&a.length==1&&v.equals(f.at(q,a[0]))&&f.dominates(q,pc))constructed=true;
                }
                require(constructed,"Cache Map is not initialized before storage");writes++;
            }
            require(writes==1,"Missing/ambiguous cache initialization");found.add(m.ref);
        }
        require(found.size()==1,"Missing/ambiguous cache class initializer");return found.get(0);
    }
    private static DexIndex.Ref lifecycle(DexIndex index,DexIndex.Ref cache)throws IOException {
        List<DexIndex.Ref> found=new ArrayList<>();
        for(DexIndex.MethodData m:index.methods.values())if(!m.isStatic()&&m.ref.owner.equals(cache.owner)&&m.ref.type.equals("()V")
                &&m.facts().strings.contains("Loaded plugin; running callbacks")&&m.facts().strings.contains("Load request when already loaded")) {
            BeanOrigins f=new BeanOrigins(m);Set<String> reflectiveCreation=new HashSet<>(),context=new HashSet<>();
            for(int pc:f.states.keySet())if(BeanOrigins.invoke(f.code[pc]&255)) {
                DexIndex.Ref call=m.invokedAt(pc);DexIndex.MethodData creator=index.get(call);if(creator==null||creator.isStatic())continue;
                int[] a=f.arguments(pc);
                if(a.length==2&&isThis(f.at(pc,a[1]))&&creator.facts().calls.contains(new DexIndex.Ref("Ljava/lang/Class;","forName","(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"))
                        &&creator.facts().calls.contains(new DexIndex.Ref("Landroid/content/ComponentName;","getClassName","()Ljava/lang/String;")))reflectiveCreation.add(identity(f,f.at(pc,a[0])));
                String result=call.type.substring(call.type.indexOf(')')+1);
                if(a.length==1&&(index.extendsType(result,"Landroid/content/Context;")||index.extendsType(result,"Landroid/content/ContextWrapper;"))
                        &&creator.facts().calls.contains(new DexIndex.Ref("Landroid/content/Context;","createApplicationContext","("+APP+"I)Landroid/content/Context;")))context.add(identity(f,f.at(pc,a[0])));
            }
            reflectiveCreation.remove(null);reflectiveCreation.retainAll(context);if(reflectiveCreation.size()==1)found.add(m.ref);
        }
        require(found.size()==1,"Missing/ambiguous plugin publication lifecycle");return found.get(0);
    }
    private static boolean packageKey(BeanOrigins f,BeanOrigins.Value v,String app)throws IOException {
        if(v==null||!v.kind.equals("field")||(f.code[v.index]&255)!=0x54||!f.method.fieldAt(v.index).equals(new DexIndex.Ref(APP,"packageName","Ljava/lang/String;")))return false;
        return app.equals(identity(f,f.at(v.index,f.code[v.index]>>12)));
    }
    private static String identity(BeanOrigins f,BeanOrigins.Value v)throws IOException {
        return identity(f,v,0);
    }
    private static String identity(BeanOrigins f,BeanOrigins.Value v,int depth)throws IOException {
        require(depth<8,"Unbounded loader input field chain");
        if(v==null)return null;if(v.kind.equals("arg"))return v.toString();
        if(!v.kind.equals("field"))return null;int op=f.code[v.index]&255;DexIndex.Ref ref=f.method.fieldAt(v.index);
        if(op==0x62)return "static:"+ref;
        if(op!=0x54)return null;String base=identity(f,f.at(v.index,f.code[v.index]>>12),depth+1);return base==null?null:base+"/"+ref;
    }
    private static boolean mapCall(DexIndex.Ref r,String name) {
        String type=name.equals("put")?"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;":name.equals("containsKey")?"(Ljava/lang/Object;)Z":"(Ljava/lang/Object;)Ljava/lang/Object;";
        return r.name.equals(name)&&r.type.equals(type)&&(r.owner.equals("Ljava/util/Map;")||r.owner.equals("Ljava/util/HashMap;")||r.owner.equals("Landroid/util/ArrayMap;"));
    }
    private static boolean mapType(DexIndex i,String t){return t.equals("Ljava/util/Map;")||t.equals("Ljava/util/HashMap;")||t.equals("Landroid/util/ArrayMap;")||i.extendsType(t,"Ljava/util/HashMap;");}
    private static boolean reaches(BeanOrigins f,int start,int target) {
        Set<Integer> seen=new HashSet<>();ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(start);
        while(!queue.isEmpty()){int pc=queue.removeFirst();if(pc==target)return true;if(!seen.add(pc))continue;List<Integer> next=f.edges.get(pc);if(next!=null)queue.addAll(next);}return false;
    }
    private static boolean isNew(BeanOrigins.Value v,String type){return v!=null&&v.kind.equals("new")&&v.text.equals(type);}
    private static boolean isCall(BeanOrigins.Value v,int pc){return v!=null&&v.kind.equals("call")&&v.index==pc;}
    private static boolean isConstant(BeanOrigins.Value v,int n){return v!=null&&v.kind.equals("constant")&&v.index==n;}
    private static boolean isThis(BeanOrigins.Value v){return v!=null&&v.kind.equals("arg")&&v.index==0;}
    private static void require(boolean b,String message)throws IOException {if(!b)throw new IOException(message);}
}
