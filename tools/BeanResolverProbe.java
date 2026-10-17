package com.axuan.vivoislandplus;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Runs the production model/transport resolver on actual plugin DEX files. */
public final class BeanResolverProbe {
    public static void main(String[] args)throws Exception {
        DexIndex index=read(args[0]);BeanResolver.Plan plan=BeanResolver.discover(index);
        System.out.println(plan.describe());
        Map<String,String> originalNames=new LinkedHashMap<>();
        originalNames.put("id","id");originalNames.put("operation","operation");originalNames.put("sbn","sbn");
        originalNames.put("left","leftIsland");originalNames.put("right","rightIsland");
        originalNames.put("left.icon","leftIcon");originalNames.put("right.colors","waveColor");
        for(String role:originalNames.keySet())if(!plan.fields.get(role).name.equals(originalNames.get(role)))
            throw new AssertionError("Actual original fixture property differs: "+role);
        BeanResolver.Plan renamed=BeanResolver.discover(RawCodeNames.renamed(read(args[0])));
        if(!renamed.bean.startsWith("Lchanged/")||!renamed.left.startsWith("Lchanged/")||!renamed.right.startsWith("Lchanged/")
                ||renamed.post.name.equals(plan.post.name)||renamed.singleton.name.equals(plan.singleton.name)
                ||renamed.mainFactory.name.equals(plan.mainFactory.name))throw new AssertionError("Private name binding remains");
        for(String role:plan.fields.keySet())if(renamed.fields.get(role).name.equals(plan.fields.get(role).name)
                ||renamed.setters.get(role).name.equals(plan.setters.get(role).name))throw new AssertionError("Property name binding remains");
        List<DexIndex.Ref> required=new ArrayList<>(Arrays.asList(plan.post,plan.mainFactory,plan.leftFactory,plan.rightFactory));
        required.addAll(plan.setters.values());
        for(DexIndex.Ref ref:required) {
            DexIndex.MethodData method=index.methods.remove(ref.key());reject(index);index.methods.put(ref.key(),method);
        }
        for(DexIndex.Ref ref:plan.fields.values()) {
            DexIndex.FieldData field=index.declaredFields.remove(ref.key());reject(index);index.declaredFields.put(ref.key(),field);
        }
        DexIndex.FieldData singleton=index.declaredFields.remove(plan.singleton.key());reject(index);index.declaredFields.put(plan.singleton.key(),singleton);
        for(DexIndex.Ref ref:Arrays.asList(plan.post,plan.leftFactory,plan.setters.get("id"))) {
            index.methods.put("extra-ambiguous-role",index.get(ref));reject(index);index.methods.remove("extra-ambiguous-role");
        }
        index.declaredFields.put("ambiguous-sbn-field",index.declaredFields.get(plan.fields.get("sbn").key()));
        reject(index);index.declaredFields.remove("ambiguous-sbn-field");
        mutate(index,plan);
        System.out.println("PASS original parcelable/Map/property/factory contracts, integer-list element provenance, raw class/member renaming, missing/ambiguous roles, changed discriminator, wrong map value, invalid getter/setter/factory and malformed code rejection");
    }
    private static void mutate(DexIndex index,BeanResolver.Plan plan)throws Exception {
        DexIndex.MethodData post=index.get(plan.post);byte[] bytes=bytes(post),original=bytes.clone();
        try {
            int[] code=post.codeUnits();boolean changed=false;
            for(int pc=0;pc<code.length;) {
                int unit=code[pc],op=unit&255,width=DexIndex.width(op);
                if(width<=0||pc+width>code.length)throw new AssertionError("Fixture code width");
                if(op==0x12&&(short)unit>>12==plan.removalValue) {put(bytes,post,pc,(unit&0x0fff)|(7<<12));changed=true;}
                pc+=width;
            }
            if(!changed)throw new AssertionError("Fixture discriminator literal not found");clear(post);
            if(BeanResolver.discover(index).removalValue!=7)throw new AssertionError("Fixed removal discriminator");
        }finally {System.arraycopy(original,0,bytes,0,bytes.length);clear(post);}
        try {
            BeanOrigins flow=new BeanOrigins(post);boolean changed=false;
            for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.code[pc]&255)) {
                DexIndex.Ref call=post.invokedAt(pc);int[] args=flow.arguments(pc);
                if(call.name.equals("put")&&call.owner.equals("Ljava/util/HashMap;")&&args.length==3) {
                    if((flow.code[pc]&255)>=0x74)throw new AssertionError("Fixture Map uses range encoding");
                    put(bytes,post,pc+2,(flow.code[pc+2]&~0xf00)|(args[0]<<8));changed=true;break;
                }
            }
            if(!changed)throw new AssertionError("Fixture Map put missing");clear(post);reject(index);
        }finally {System.arraycopy(original,0,bytes,0,bytes.length);clear(post);}
        for(DexIndex.Ref ref:Arrays.asList(plan.mainFactory,plan.setters.get("id"))) {
            DexIndex.MethodData method=index.get(ref);byte[] target=bytes(method),backup=target.clone();
            try {
                put(target,method,0,0x12);put(target,method,1,ref.equals(plan.mainFactory)?0x11:0x0e);clear(method);reject(index);
            }finally {System.arraycopy(backup,0,target,0,target.length);clear(method);}
        }
        DexIndex.MethodData getter=null;
        for(DexIndex.Ref ref:post.facts().calls)if(ref.owner.equals(plan.bean)&&ref.type.equals("()Ljava/lang/String;")) {
            DexIndex.MethodData candidate=index.get(ref);
            if(candidate!=null&&candidate.facts().fields.equals(Collections.singleton(plan.fields.get("id"))))getter=candidate;
        }
        if(getter==null)throw new AssertionError("Fixture key getter not found");
        byte[] getterBytes=bytes(getter),getterOriginal=getterBytes.clone();
        try {put(getterBytes,getter,0,0x12);put(getterBytes,getter,1,0x11);clear(getter);reject(index);}
        finally {System.arraycopy(getterOriginal,0,getterBytes,0,getterBytes.length);clear(getter);}
        try {put(bytes,post,0,0x73);clear(post);reject(index);}
        finally {System.arraycopy(original,0,bytes,0,bytes.length);clear(post);}
    }
    private static byte[] bytes(DexIndex.MethodData method)throws Exception {
        Field f=DexIndex.MethodData.class.getDeclaredField("reader");f.setAccessible(true);Object reader=f.get(method);
        f=reader.getClass().getDeclaredField("bytes");f.setAccessible(true);return (byte[])f.get(reader);
    }
    private static void put(byte[] bytes,DexIndex.MethodData method,int pc,int word) {int at=method.code+16+pc*2;bytes[at]=(byte)word;bytes[at+1]=(byte)(word>>8);}
    private static void clear(DexIndex.MethodData method)throws Exception {Field f=DexIndex.MethodData.class.getDeclaredField("facts");f.setAccessible(true);f.set(method,null);}
    private static void reject(DexIndex index)throws Exception {
        try{BeanResolver.discover(index);}catch(IOException expected){return;}throw new AssertionError("Accepted invalid model/transport contract");
    }
    private static DexIndex read(String file)throws Exception {return DexIndex.readApks(Collections.singletonList(new File(file)));}
}
