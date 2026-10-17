package com.axuan.vivoislandplus;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public final class BeanModelResolverProbe {
    public static void main(String[] args)throws Exception {
        DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
        BeanResolver.Plan core=BeanResolver.discover(index);BeanPropertyResolver.Plan properties=BeanPropertyResolver.discover(index,core);
        BeanModelResolver.Plan plan=BeanModelResolver.discover(index,core,properties);System.out.println(plan.describe());
        if(!plan.fields.get("userId").name.equals("userId")||!plan.fields.get("scrollRemove").name.equals("scrollRemove")
                ||!plan.fields.get("keyguardShow").name.equals("supportKeyguardShow"))throw new AssertionError("Original model role mismatch");
        Set<String> unread=new HashSet<>();for(DexIndex.Ref ref:plan.unreadDto)unread.add(ref.name);
        if(!unread.equals(new HashSet<>(Arrays.asList("notificationSound","notificationTemplate","callBackState"))))throw new AssertionError("Original write-only slots differ");
        DexIndex renamed=RawCodeNames.renamed(read(args[0]));BeanResolver.Plan renamedCore=BeanResolver.discover(renamed);
        BeanModelResolver.Plan renamedPlan=BeanModelResolver.discover(renamed,renamedCore,BeanPropertyResolver.discover(renamed,renamedCore));
        if(!renamedPlan.model.startsWith("Lchanged/")||renamedPlan.consumer.name.equals(plan.consumer.name)||renamedPlan.materializer.name.equals(plan.materializer.name))throw new AssertionError("Private model name dependence");
        for(String role:plan.fields.keySet())if(renamedPlan.fields.get(role).name.equals(plan.fields.get(role).name)
                ||renamedPlan.setters.get(role).name.equals(plan.setters.get(role).name))throw new AssertionError("Tail property name dependence");
        List<DexIndex.Ref> required=new ArrayList<>(Arrays.asList(plan.consumer,plan.constructor,plan.materializer,plan.gesture,plan.keyguard));required.addAll(plan.setters.values());
        for(DexIndex.Ref ref:required){DexIndex.MethodData method=index.methods.remove(ref.key());reject(index,core,properties);index.methods.put(ref.key(),method);}
        for(DexIndex.Ref ref:Arrays.asList(plan.consumer,plan.constructor,plan.gesture,plan.materializer)) {
            index.methods.put("ambiguous-model-role",index.get(ref));reject(index,core,properties);index.methods.remove("ambiguous-model-role");
        }
        for(DexIndex.Ref ref:plan.fields.values()){DexIndex.FieldData field=index.declaredFields.remove(ref.key());reject(index,core,properties);index.declaredFields.put(ref.key(),field);}
        remap(index,core,properties,plan);
        invert(index,core,properties,plan,plan.gesture,plan.modelFields.get("scrollRemove"));
        invert(index,core,properties,plan,plan.keyguard,plan.modelFields.get("keyguardShow"));
        addRead(index,core,properties,plan);
        wrongPublicUser(index,core,properties,plan);
        unknownHandlerInput(index,core,properties,plan);
        padding(index,plan);
        System.out.println("PASS original DTO/builder/model/profile/gesture/keyguard contracts, write-only slots, raw private reference renaming, semantic field remapping, missing/ambiguous roles, reversed permission polarity, added runtime reader, wrong public user source, unknown handler locals and reachable payload rejection");
    }
    private static void remap(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties,BeanModelResolver.Plan plan)throws Exception {
        DexIndex.Ref old=plan.modelFields.get("keyguardShow"),replacement=null,dto=null;
        for(DexIndex.Ref field:plan.unreadModel)if(field.name.equals("notificationSound"))replacement=field;
        for(DexIndex.Ref field:plan.unreadDto)if(field.name.equals("notificationSound"))dto=field;
        if(replacement==null||dto==null)throw new AssertionError("Original sound slot fixture missing");
        DexIndex.MethodData getter=getter(index,old);byte[] bytes=bytes(getter),backup=bytes.clone();
        try {
            replaceRead(index,getter,old,replacement);BeanModelResolver.Plan changed=BeanModelResolver.discover(index,core,properties);
            if(!changed.fields.get("keyguardShow").equals(dto)||changed.unreadDto.contains(dto)||!changed.unreadDto.contains(plan.fields.get("keyguardShow")))
                throw new AssertionError("Semantic model-field remap did not follow actual reader");
        }finally{System.arraycopy(backup,0,bytes,0,bytes.length);clear(getter);}
    }
    private static void invert(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties,BeanModelResolver.Plan plan,DexIndex.Ref methodRef,DexIndex.Ref field)throws Exception {
        DexIndex.MethodData method=index.get(methodRef);BeanValueFlow flow=new BeanValueFlow(method);byte[] bytes=bytes(method),backup=bytes.clone();boolean changed=false;
        try {
            for(int pc:flow.states.keySet()) {
                int unit=flow.graph.code[pc],op=unit&255;if(op!=0x38&&op!=0x39)continue;
                Integer source=flow.single(flow.at(pc,unit>>8));if(source==null||source<0||!BeanOrigins.invoke(flow.graph.code[source]&255))continue;
                DexIndex.MethodData read=index.get(method.invokedAt(source));
                if(read!=null&&read.ref.type.equals("()Z")&&read.facts().fields.equals(Collections.singleton(field))&&read.facts().calls.isEmpty()) {
                    put(bytes,method,pc,(unit&0xff00)|(op==0x38?0x39:0x38));changed=true;break;
                }
            }
            if(!changed)throw new AssertionError("Permission branch fixture missing");clear(method);reject(index,core,properties);
        }finally{System.arraycopy(backup,0,bytes,0,bytes.length);clear(method);}
    }
    private static void addRead(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties,BeanModelResolver.Plan plan)throws Exception {
        DexIndex.Ref target=null;for(DexIndex.Ref field:plan.unreadModel)if(field.type.equals("I"))target=field;
        if(target==null)throw new AssertionError("Write-only integer fixture missing");
        DexIndex.MethodData source=getter(index,plan.modelFields.get("userId"));
        Object originalReader=reader(source);Field bytesField=originalReader.getClass().getDeclaredField("bytes");bytesField.setAccessible(true);
        byte[] cloned=((byte[])bytesField.get(originalReader)).clone();Constructor<?> ctor=originalReader.getClass().getDeclaredConstructor(byte[].class);ctor.setAccessible(true);
        Object newReader=ctor.newInstance((Object)cloned);
        DexIndex.Ref ref=new DexIndex.Ref(plan.model,"extraModelReaderProbe","()I");DexIndex.MethodData extra=source.withRef(ref);
        Field readerField=DexIndex.MethodData.class.getDeclaredField("reader");readerField.setAccessible(true);readerField.set(extra,newReader);
        replaceRead(index,extra,plan.modelFields.get("userId"),target);index.methods.put(ref.key(),extra);
        try{reject(index,core,properties);}finally{index.methods.remove(ref.key());}
    }
    private static void wrongPublicUser(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties,BeanModelResolver.Plan plan)throws Exception {
        DexIndex.MethodData consumer=index.get(plan.consumer);Object reader=reader(consumer);Field table=reader.getClass().getDeclaredField("methodIds");table.setAccessible(true);
        DexIndex.Ref[] refs=(DexIndex.Ref[])table.get(reader);int position=-1;
        for(int i=0;i<refs.length;i++)if(refs[i].owner.equals("Landroid/os/UserHandle;")&&refs[i].name.equals("getIdentifier")&&refs[i].type.equals("()I")){position=i;break;}
        if(position<0)throw new AssertionError("Public profile fixture missing");DexIndex.Ref original=refs[position];
        try{refs[position]=new DexIndex.Ref(original.owner,"hashCode",original.type);clear(consumer);reject(index,core,properties);}
        finally{refs[position]=original;clear(consumer);}
    }
    private static void padding(DexIndex index,BeanModelResolver.Plan plan)throws Exception {
        DexIndex.MethodData method=index.get(plan.consumer);BeanOrigins graph=new BeanOrigins(method);int padding=-1,gotoAt=-1;
        for(int pc:graph.instructionStarts)if(graph.code[pc]==0&&!graph.edges.containsKey(pc)&&pc+1<graph.code.length
                &&(graph.code[pc+1]==0x100||graph.code[pc+1]==0x200||graph.code[pc+1]==0x300))padding=pc;
        if(padding<0)return;
        for(int pc:graph.edges.keySet())if((graph.code[pc]&255)==0x29&&padding-pc>=Short.MIN_VALUE&&padding-pc<=Short.MAX_VALUE){gotoAt=pc;break;}
        if(gotoAt<0)throw new AssertionError("Payload alignment fixture has no goto/16");
        byte[] bytes=bytes(method),backup=bytes.clone();
        try{put(bytes,method,gotoAt+1,padding-gotoAt);try{new BeanOrigins(method);}catch(IOException expected){return;}throw new AssertionError("Reachable padding-to-payload accepted");}
        finally{System.arraycopy(backup,0,bytes,0,bytes.length);clear(method);}
    }
    private static void unknownHandlerInput(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties,BeanModelResolver.Plan plan)throws Exception {
        DexIndex.MethodData method=index.get(plan.consumer);byte[] bytes=bytes(method),backup=bytes.clone();
        int count=(bytes[method.code+6]&255)|(bytes[method.code+7]&255)<<8;
        int units=method.codeUnits().length,tries=method.code+16+units*2+(units%2)*2;boolean rejected=false;
        if(count==0)throw new AssertionError("Original exceptional projection fixture missing");
        for(int i=0;i<count&&!rejected;i++)try {
            int at=tries+i*8,start=(bytes[at]&255)|(bytes[at+1]&255)<<8|(bytes[at+2]&255)<<16|bytes[at+3]<<24;
            int length=(bytes[at+4]&255)|(bytes[at+5]&255)<<8,end=start+length;if(end>65535)continue;
            // An exception before the builder is initialized must not be
            // treated as carrying the ordinary-path builder into a handler.
            Arrays.fill(bytes,at,at+4,(byte)0);bytes[at+4]=(byte)end;bytes[at+5]=(byte)(end>>8);
            try{BeanModelResolver.discover(index,core,properties);}catch(IOException expected){rejected=true;}
        }finally{System.arraycopy(backup,0,bytes,0,bytes.length);clear(method);}
        if(!rejected)throw new AssertionError("Unknown exceptional builder locals were accepted");
    }
    private static DexIndex.MethodData getter(DexIndex index,DexIndex.Ref field)throws Exception {
        List<DexIndex.MethodData> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values())if(method.ref.owner.equals(field.owner)&&method.ref.type.equals("()"+field.type)
                &&method.facts().fields.equals(Collections.singleton(field))&&method.facts().calls.isEmpty())found.add(method);
        if(found.size()!=1)throw new AssertionError("Original getter fixture differs: "+field);return found.get(0);
    }
    private static void replaceRead(DexIndex index,DexIndex.MethodData method,DexIndex.Ref old,DexIndex.Ref target)throws Exception {
        Object reader=reader(method);Field table=reader.getClass().getDeclaredField("fieldIds");table.setAccessible(true);DexIndex.Ref[] refs=(DexIndex.Ref[])table.get(reader);int fieldId=Arrays.asList(refs).indexOf(target);
        if(fieldId<0)throw new AssertionError("Field reference fixture missing");int[] code=method.codeUnits();byte[] bytes=bytes(method);boolean changed=false;
        for(int pc=0;pc<code.length;pc+=DexIndex.width(code[pc]&255))if((code[pc]&255)>=0x52&&(code[pc]&255)<=0x58&&method.fieldAt(pc).equals(old)){put(bytes,method,pc+1,fieldId);changed=true;}
        if(!changed)throw new AssertionError("Field read fixture missing");clear(method);
    }
    private static Object reader(DexIndex.MethodData method)throws Exception{Field f=DexIndex.MethodData.class.getDeclaredField("reader");f.setAccessible(true);return f.get(method);}
    private static byte[] bytes(DexIndex.MethodData method)throws Exception{Object r=reader(method);Field f=r.getClass().getDeclaredField("bytes");f.setAccessible(true);return (byte[])f.get(r);}
    private static void clear(DexIndex.MethodData method)throws Exception{Field f=DexIndex.MethodData.class.getDeclaredField("facts");f.setAccessible(true);f.set(method,null);}
    private static void put(byte[] bytes,DexIndex.MethodData method,int pc,int unit){int at=method.code+16+pc*2;bytes[at]=(byte)unit;bytes[at+1]=(byte)(unit>>8);}
    private static void reject(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties)throws Exception {
        try{BeanModelResolver.discover(index,core,properties);}catch(IOException expected){return;}throw new AssertionError("Accepted invalid model contract");
    }
    private static DexIndex read(String path)throws Exception{return DexIndex.readApks(Collections.singletonList(new File(path)));}
}
