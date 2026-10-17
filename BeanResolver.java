package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Resolves the parcelable island model and its keyed arrival/removal transport. */
final class BeanResolver {
    private static final String SBN = "Landroid/service/notification/StatusBarNotification;";
    private static final String ICON = "Landroid/graphics/drawable/Icon;";
    private static final String TEXT = "Ljava/lang/CharSequence;";
    static final class Plan {
        final String bean, left, right;
        final DexIndex.Ref post, singleton, mainFactory, leftFactory, rightFactory;
        final Map<String,DexIndex.Ref> fields, setters;
        final int removalValue;
        Plan(String bean,String left,String right,DexIndex.Ref post,DexIndex.Ref singleton,
             DexIndex.Ref mainFactory,DexIndex.Ref leftFactory,DexIndex.Ref rightFactory,
             Map<String,DexIndex.Ref> fields,Map<String,DexIndex.Ref> setters,int removalValue) {
            this.bean=bean;this.left=left;this.right=right;this.post=post;this.singleton=singleton;
            this.mainFactory=mainFactory;this.leftFactory=leftFactory;this.rightFactory=rightFactory;
            this.fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            this.setters=Collections.unmodifiableMap(new LinkedHashMap<>(setters));this.removalValue=removalValue;
        }
        String describe(){return "bean="+bean+"\nleft="+left+"\nright="+right+"\npost="+post+
            "\nsingleton="+singleton+"\nfactories="+Arrays.asList(mainFactory,leftFactory,rightFactory)+
            "\nfields="+fields+"\nsetters="+setters+"\nremoval="+removalValue;}
    }
    private static final class Transport {
        final DexIndex.Ref id, operation, post; final int removal;
        Transport(DexIndex.Ref id,DexIndex.Ref operation,DexIndex.Ref post,int removal) {
            this.id=id;this.operation=operation;this.post=post;this.removal=removal;
        }
    }
    private BeanResolver() { }
    static Plan discover(DexIndex index)throws IOException {
        try { return checked(index); }
        catch(IndexOutOfBoundsException|ArithmeticException malformed) {throw new IOException("Malformed island bean contract",malformed);}
    }
    private static Plan checked(DexIndex index)throws IOException {
        List<Plan> plans=new ArrayList<>();
        Set<String> owners=new LinkedHashSet<>();
        for(DexIndex.FieldData field:index.declaredFields.values())
            if(!field.isStatic()&&field.ref.type.equals(SBN)&&parcel(index,field.ref.owner))owners.add(field.ref.owner);
        for(String owner:owners) {
            List<DexIndex.Ref> lefts=new ArrayList<>(),rights=new ArrayList<>();
            for(DexIndex.FieldData field:index.declaredFields.values())if(!field.isStatic()&&field.ref.owner.equals(owner)&&parcel(index,field.ref.type)) {
                if(count(index,field.ref.type,ICON)==1&&count(index,field.ref.type,TEXT)==1
                        &&count(index,field.ref.type,"Landroid/app/PendingIntent;")==0)lefts.add(field.ref);
                if(count(index,field.ref.type,ICON)==1&&count(index,field.ref.type,TEXT)>=2
                        &&count(index,field.ref.type,"Ljava/util/ArrayList;")>=1
                        &&count(index,field.ref.type,"Landroid/app/PendingIntent;")==1)rights.add(field.ref);
            }
            if(lefts.size()!=1||rights.size()!=1)continue;
            List<Transport> transports=new ArrayList<>();
            for(DexIndex.MethodData method:index.methods.values())if(!method.isStatic()
                    &&method.ref.type.equals("("+owner+")V")&&hasMap(method,"put")&&hasMap(method,"remove"))
                transports.add(transport(index,method,owner));
            require(transports.size()==1,"Missing/ambiguous keyed island transport: "+transports.size());
            Transport transport=transports.get(0);
            Map<String,DexIndex.Ref> fields=new LinkedHashMap<>(),setters=new LinkedHashMap<>();
            fields.put("id",transport.id);fields.put("operation",transport.operation);
            fields.put("sbn",field(index,owner,SBN));fields.put("left",lefts.get(0));fields.put("right",rights.get(0));
            fields.put("left.icon",field(index,lefts.get(0).type,ICON));
            fields.put("right.colors",integerListField(index,rights.get(0).type));
            for(Map.Entry<String,DexIndex.Ref> entry:fields.entrySet())setters.put(entry.getKey(),setter(index,entry.getValue()));
            plans.add(new Plan(owner,lefts.get(0).type,rights.get(0).type,transport.post,singleton(index,transport.post.owner),
                    factory(index,owner),factory(index,lefts.get(0).type),factory(index,rights.get(0).type),fields,setters,transport.removal));
        }
        require(plans.size()==1,"Missing/ambiguous parcelable island family: "+plans.size());return plans.get(0);
    }
    private static boolean parcel(DexIndex index,String owner)throws IOException {
        boolean creator=false,writer=false;
        for(DexIndex.FieldData field:index.declaredFields.values())if(field.isStatic()&&field.ref.owner.equals(owner)
                &&field.ref.type.equals("Landroid/os/Parcelable$Creator;"))creator=true;
        for(DexIndex.MethodData method:index.methods.values())if(!method.isStatic()&&method.ref.owner.equals(owner)
                &&method.ref.type.equals("(Landroid/os/Parcel;I)V"))
            for(DexIndex.Ref call:method.facts().calls)if(call.owner.equals("Landroid/os/Parcel;"))writer=true;
        return creator&&writer;
    }
    private static int count(DexIndex index,String owner,String type) {
        int count=0;for(DexIndex.FieldData field:index.declaredFields.values())
            if(!field.isStatic()&&field.ref.owner.equals(owner)&&field.ref.type.equals(type))++count;
        return count;
    }
    private static DexIndex.Ref field(DexIndex index,String owner,String type)throws IOException {
        List<DexIndex.Ref> found=new ArrayList<>();for(DexIndex.FieldData field:index.declaredFields.values())
            if(!field.isStatic()&&field.ref.owner.equals(owner)&&field.ref.type.equals(type))found.add(field.ref);
        require(found.size()==1,"Missing/ambiguous typed island field: "+owner+" / "+type);return found.get(0);
    }
    private static DexIndex.Ref integerListField(DexIndex index,String owner)throws IOException {
        Set<DexIndex.Ref> found=new LinkedHashSet<>();
        for(DexIndex.MethodData writer:index.methods.values())if(!writer.isStatic()&&writer.ref.owner.equals(owner)
                &&writer.ref.type.equals("(Landroid/os/Parcel;I)V")) {
            BeanOrigins flow=new BeanOrigins(writer);
            for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.code[pc]&255)) {
                DexIndex.Ref call=writer.invokedAt(pc);
                if(!call.owner.equals("Landroid/os/Parcel;")||!call.name.equals("writeInt")||!call.type.equals("(I)V"))continue;
                int[] args=flow.arguments(pc);if(args.length!=2||!arg(flow.at(pc,args[0]),1))continue;
                BeanOrigins.Value value=flow.at(pc,args[1]);
                if(value==null||!value.kind.equals("call"))continue;
                DexIndex.Ref unbox=writer.invokedAt(value.index);int[] unboxArgs=flow.arguments(value.index);
                if(!(unbox.owner.equals("Ljava/lang/Integer;")||unbox.owner.equals("Ljava/lang/Number;"))
                        ||!unbox.name.equals("intValue")||!unbox.type.equals("()I")||unboxArgs.length!=1)continue;
                DexIndex.Ref field=listSource(flow,flow.at(value.index,unboxArgs[0]),0);
                if(field!=null&&field.owner.equals(owner)&&field.type.equals("Ljava/util/ArrayList;"))found.add(field);
            }
        }
        require(found.size()==1,"Missing/ambiguous parcelled integer color list: "+found.size());return found.iterator().next();
    }
    private static DexIndex.Ref listSource(BeanOrigins flow,BeanOrigins.Value value,int depth)throws IOException {
        if(value==null||depth>8)return null;
        if(value.kind.equals("field")) {
            int unit=flow.code[value.index],op=unit&255;
            return op>=0x52&&op<=0x58&&arg(flow.at(value.index,unit>>12),0)?flow.method.fieldAt(value.index):null;
        }
        if(!value.kind.equals("call"))return null;
        DexIndex.Ref call=flow.method.invokedAt(value.index);int[] args=flow.arguments(value.index);
        if(args.length>0&&((call.owner.equals("Ljava/util/ArrayList;")&&call.name.equals("get")&&call.type.equals("(I)Ljava/lang/Object;"))
                ||(call.owner.equals("Ljava/util/Iterator;")&&call.name.equals("next")&&call.type.equals("()Ljava/lang/Object;"))
                ||(call.owner.equals("Ljava/util/ArrayList;")&&call.name.equals("iterator")&&call.type.equals("()Ljava/util/Iterator;"))))
            return listSource(flow,flow.at(value.index,args[0]),depth+1);
        return null;
    }
    private static boolean map(DexIndex.Ref call,String operation) {
        return (call.owner.equals("Ljava/util/Map;")||call.owner.equals("Ljava/util/HashMap;")
                ||call.owner.equals("Ljava/util/LinkedHashMap;"))&&call.name.equals(operation)
                &&call.type.equals(operation.equals("put")?"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;":"(Ljava/lang/Object;)Ljava/lang/Object;");
    }
    private static boolean hasMap(DexIndex.MethodData method,String operation)throws IOException {
        for(DexIndex.Ref call:method.facts().calls)if(map(call,operation))return true;return false;
    }
    private static Transport transport(DexIndex index,DexIndex.MethodData method,String owner)throws IOException {
        BeanOrigins flow=new BeanOrigins(method);Set<DexIndex.Ref> ids=new LinkedHashSet<>(),maps=new LinkedHashSet<>();
        Set<String> actions=new HashSet<>();
        for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.code[pc]&255)) {
            DexIndex.Ref call=method.invokedAt(pc);if(!map(call,"put")&&!map(call,"remove"))continue;
            int[] args=flow.arguments(pc);require(args.length==(map(call,"put")?3:2),"Map frame changed");
            BeanOrigins.Value key=flow.at(pc,args[1]),receiver=flow.at(pc,args[0]);
            require(key!=null&&key.kind.equals("call")&&receiver!=null&&receiver.kind.equals("field"),"Map key/storage provenance missing");
            DexIndex.Ref keyCall=method.invokedAt(key.index);int[] keyArgs=flow.arguments(key.index);
            require(keyCall.owner.equals(owner)&&keyCall.type.equals("()Ljava/lang/String;")&&keyArgs.length==1
                    &&arg(flow.at(key.index,keyArgs[0]),1),"Map key is not the submitted bean ID");
            ids.add(getter(index,keyCall));maps.add(method.fieldAt(receiver.index));actions.add(call.name);
            if(map(call,"put"))require(arg(flow.at(pc,args[2]),1),"Map value is not the submitted bean");
        }
        require(ids.size()==1&&maps.size()==1&&actions.size()==2,"Keyed map identity is ambiguous");
        List<Transport> choices=new ArrayList<>();
        for(int pc:flow.states.keySet()) {
            int unit=flow.code[pc],op=unit&255;
            if(op!=0x32&&op!=0x33&&op!=0x38&&op!=0x39)continue;
            BeanOrigins.Value a=flow.at(pc,op<=0x33?(unit>>8)&15:unit>>8);
            BeanOrigins.Value b=op<=0x33?flow.at(pc,unit>>12):new BeanOrigins.Value("constant",0,null);
            if(a!=null&&a.kind.equals("constant")){BeanOrigins.Value swap=a;a=b;b=swap;}
            if(a==null||b==null||!a.kind.equals("call")||!b.kind.equals("constant"))continue;
            DexIndex.Ref tested=method.invokedAt(a.index);int[] testedArgs=flow.arguments(a.index);
            if(!tested.owner.equals(owner)||!tested.type.equals("()I")||testedArgs.length!=1||!arg(flow.at(a.index,testedArgs[0]),1))continue;
            Set<String> equal=firstMapActions(flow,(op==0x32||op==0x38)?pc+(short)flow.code[pc+1]:pc+2,maps.iterator().next());
            Set<String> other=firstMapActions(flow,(op==0x32||op==0x38)?pc+2:pc+(short)flow.code[pc+1],maps.iterator().next());
            if(equal.equals(Collections.singleton("remove"))&&other.equals(Collections.singleton("put")))
                choices.add(new Transport(ids.iterator().next(),getter(index,tested),method.ref,b.index));
        }
        require(choices.size()==1,"Missing/ambiguous map removal discriminator: "+choices.size());return choices.get(0);
    }
    private static Set<String> firstMapActions(BeanOrigins flow,int start,DexIndex.Ref storage)throws IOException {
        Set<String> result=new HashSet<>();Set<Integer> seen=new HashSet<>();ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(start);
        while(!queue.isEmpty()) {
            int pc=queue.removeFirst();if(!seen.add(pc))continue;
            require(flow.states.containsKey(pc),"Unreachable keyed-map branch");
            if(BeanOrigins.invoke(flow.code[pc]&255)) {
                DexIndex.Ref call=flow.method.invokedAt(pc);
                if(map(call,"put")||map(call,"remove")) {
                    int[] args=flow.arguments(pc);BeanOrigins.Value receiver=flow.at(pc,args[0]);
                    if(receiver!=null&&receiver.kind.equals("field")&&flow.method.fieldAt(receiver.index).equals(storage)) {
                        result.add(call.name);continue;
                    }
                }
            }
            queue.addAll(flow.edges.get(pc));
        }
        return result;
    }
    private static boolean arg(BeanOrigins.Value value,int index){return value!=null&&value.kind.equals("arg")&&value.index==index;}
    private static DexIndex.Ref getter(DexIndex index,DexIndex.Ref ref)throws IOException {
        DexIndex.MethodData method=index.get(ref);require(method!=null&&!method.isStatic(),"Missing property getter");
        BeanOrigins flow=new BeanOrigins(method);Set<DexIndex.Ref> fields=new HashSet<>();boolean returned=false;
        require(method.facts().calls.isEmpty(),"Getter has calls");
        for(int pc:flow.states.keySet()) {
            int op=flow.code[pc]&255;
            if(op>=0x59&&op<=0x6d)throw new IOException("Getter writes storage");
            if(op>=0x52&&op<=0x58)require(arg(flow.at(pc,flow.code[pc]>>12),0),"Getter reads another object");
            if(op>=0x0f&&op<=0x11) {
                BeanOrigins.Value value=flow.at(pc,flow.code[pc]>>8);
                require(value!=null&&value.kind.equals("field"),"Getter return provenance missing");
                fields.add(method.fieldAt(value.index));returned=true;
            }
        }
        require(returned&&fields.size()==1,"Missing/ambiguous property getter field");DexIndex.Ref field=fields.iterator().next();
        require(field.owner.equals(ref.owner)&&ref.type.equals("()"+field.type)&&index.declaredFields.containsKey(field.key()),"Getter property type changed");return field;
    }
    private static DexIndex.Ref setter(DexIndex index,DexIndex.Ref field)throws IOException {
        DexIndex.FieldData declaration=index.declaredFields.get(field.key());
        require(declaration!=null&&!declaration.isStatic(),"Missing instance property declaration: "+field);
        List<DexIndex.Ref> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values())if(!method.isStatic()&&!method.ref.name.startsWith("<")
                &&method.ref.owner.equals(field.owner)&&method.ref.type.equals("("+field.type+")V")&&method.facts().fields.equals(Collections.singleton(field))) {
            BeanOrigins flow=new BeanOrigins(method);boolean valid=true;int writes=0,writeAt=-1;
            for(DexIndex.Ref call:method.facts().calls)valid&=call.owner.equals("Lkotlin/jvm/internal/Intrinsics;")&&call.name.equals("checkNotNullParameter");
            for(int pc:flow.states.keySet()) {
                int unit=flow.code[pc],op=unit&255;
                if(op>=0x59&&op<=0x5f) {++writes;writeAt=pc;valid&=method.fieldAt(pc).equals(field)&&arg(flow.at(pc,unit>>12),0)&&arg(flow.at(pc,(unit>>8)&15),1);}
                if(op>=0x67&&op<=0x6d)valid=false;
                if(op>=0x4b&&op<=0x51)valid=false;
            }
            if(valid&&writes==1)for(int pc:flow.states.keySet())if((flow.code[pc]&255)==0x0e)valid&=flow.dominates(writeAt,pc);
            if(valid&&writes==1)found.add(method.ref);
        }
        require(found.size()==1,"Missing/ambiguous direct property setter: "+field+" / "+found.size());return found.get(0);
    }
    static DexIndex.Ref assignedField(DexIndex index,DexIndex.Ref ref)throws IOException {
        DexIndex.MethodData method=index.get(ref);
        require(method!=null&&!method.isStatic()&&method.facts().fields.size()==1,"Missing direct setter declaration");
        DexIndex.Ref field=method.facts().fields.iterator().next();
        require(setter(index,field).equals(ref),"Setter field/argument contract differs");return field;
    }
    static DexIndex.Ref readField(DexIndex index,DexIndex.Ref ref)throws IOException {return getter(index,ref);}
    static DexIndex.Ref staticReadField(DexIndex index,DexIndex.Ref ref)throws IOException {
        DexIndex.MethodData method=index.get(ref);
        require(method!=null&&method.isStatic()&&method.facts().fields.size()==1&&method.facts().calls.isEmpty(),"Missing pure static field accessor");
        DexIndex.Ref field=method.facts().fields.iterator().next();
        require(ref.type.equals("("+field.owner+")"+field.type)&&index.declaredFields.containsKey(field.key()),"Static accessor type differs");
        BeanOrigins flow=new BeanOrigins(method);boolean returned=false;
        for(int pc:flow.states.keySet()) {
            int unit=flow.code[pc],op=unit&255;
            require(!(op>=0x59&&op<=0x6d||op>=0x4b&&op<=0x51),"Static accessor writes storage");
            if(op>=0x52&&op<=0x58)require(method.fieldAt(pc).equals(field)&&arg(flow.at(pc,unit>>12),0),"Static accessor reads another object");
            if(op>=0x0f&&op<=0x11) {
                BeanOrigins.Value value=flow.at(pc,unit>>8);
                require(value!=null&&value.kind.equals("field")&&method.fieldAt(value.index).equals(field),"Static accessor result differs");returned=true;
            }
        }
        require(returned,"Static accessor has no field return");return field;
    }
    static DexIndex.Ref fluentField(DexIndex index,DexIndex.Ref ref)throws IOException {
        DexIndex.MethodData method=index.get(ref);
        if(method==null||method.isStatic()||method.ref.name.startsWith("<")||method.facts().fields.size()!=1||!method.facts().calls.isEmpty())return null;
        DexIndex.Ref field=method.facts().fields.iterator().next();
        if(!field.owner.equals(ref.owner)||!ref.type.equals("("+field.type+")"+ref.owner))return null;
        DexIndex.FieldData declaration=index.declaredFields.get(field.key());if(declaration==null||declaration.isStatic())return null;
        BeanOrigins flow=new BeanOrigins(method);int write=-1;Set<Integer> returns=new HashSet<>();
        for(int pc:flow.states.keySet()) {
            int unit=flow.code[pc],op=unit&255;
            if(op>=0x59&&op<=0x5f) {
                if(write!=-1||!method.fieldAt(pc).equals(field)||!arg(flow.at(pc,unit>>12),0)||!arg(flow.at(pc,(unit>>8)&15),1))return null;write=pc;
            } else if(op==0x11){if(!arg(flow.at(pc,unit>>8),0))return null;returns.add(pc);}
            else if(op>=0x4b&&op<=0x51||op>=0x67&&op<=0x6d)return null;
        }
        if(write<0||returns.isEmpty())return null;for(int pc:returns)if(!flow.dominates(write,pc))return null;return field;
    }
    private static DexIndex.Ref singleton(DexIndex index,String owner)throws IOException {
        List<DexIndex.Ref> fields=new ArrayList<>();
        for(DexIndex.FieldData field:index.declaredFields.values())if(field.isStatic()&&field.ref.owner.equals(owner)&&field.ref.type.equals(owner)) {
            for(DexIndex.MethodData method:index.methods.values())if(method.ref.owner.equals(owner)&&method.ref.name.equals("<clinit>")
                    &&method.facts().fields.contains(field.ref)&&method.facts().opcodes.contains(0x69))fields.add(field.ref);
        }
        require(fields.size()==1,"Missing/ambiguous initialized transport singleton");return fields.get(0);
    }
    private static DexIndex.Ref factory(DexIndex index,String owner)throws IOException {
        List<DexIndex.Ref> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values())if(method.isStatic()&&!method.ref.name.startsWith("<")
                &&method.ref.owner.equals(owner)&&method.ref.type.equals("()"+owner)&&returnsNew(index,method,owner,new HashSet<String>()))found.add(method.ref);
        require(found.size()==1,"Missing/ambiguous default factory: "+owner);return found.get(0);
    }
    private static boolean returnsNew(DexIndex index,DexIndex.MethodData method,String owner,Set<String> path)throws IOException {
        if(path.size()>=4||!path.add(method.ref.key()))return false;
        BeanOrigins flow=new BeanOrigins(method);boolean returned=false;
        for(int pc:flow.states.keySet())if((flow.code[pc]&255)==0x11) {
            BeanOrigins.Value value=flow.at(pc,flow.code[pc]>>8);if(value==null)return false;
            if(value.kind.equals("new")){
                if(!value.text.equals(owner))return false;
                boolean initialized=false;
                for(int constructor:flow.states.keySet())if(BeanOrigins.invoke(flow.code[constructor]&255)) {
                    DexIndex.Ref call=method.invokedAt(constructor);int[] args=flow.arguments(constructor);
                    if(call.owner.equals(owner)&&call.name.equals("<init>")&&args.length>0
                            &&value.equals(flow.at(constructor,args[0]))&&flow.dominates(constructor,pc))initialized=true;
                }
                if(!initialized)return false;
            }
            else if(value.kind.equals("call")) {
                DexIndex.MethodData callee=index.get(method.invokedAt(value.index));
                if(callee==null||!callee.ref.type.equals("()"+owner)||!returnsNew(index,callee,owner,new HashSet<>(path)))return false;
            } else return false;
            returned=true;
        }
        return returned;
    }
    private static void require(boolean condition,String text)throws IOException {if(!condition)throw new IOException(text);}
}
