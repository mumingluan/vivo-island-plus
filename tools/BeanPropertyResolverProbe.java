package com.axuan.vivoislandplus;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Production property inference against an actual original plugin APK. */
public final class BeanPropertyResolverProbe {
    public static void main(String[] args)throws Exception {
        DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
        BeanResolver.Plan core=BeanResolver.discover(index);
        BeanPropertyResolver.Plan plan=BeanPropertyResolver.discover(index,core);System.out.println(plan.describe());
        Map<String,String> expected=new LinkedHashMap<>();
        for(String role:Arrays.asList("forceScreenOn","businessKey","sourcePackageName","templateCard","islandClick","priority","showTime","timeout","landingInfo"))expected.put(role,role);
        expected.put("atomType","atomIslandsType");expected.put("clickResp","clickIslandResp");
        expected.put("left.template","leftTemplate");expected.put("right.template","rightTemplate");expected.put("right.waveState","waveState");expected.put("right.content","rightContent");
        if(plan.fields.size()!=15)throw new AssertionError("Missing original roles");
        for(String role:expected.keySet())if(!plan.fields.get(role).name.equals(expected.get(role)))throw new AssertionError("Wrong original field: "+role);
        DexIndex renamed=RawCodeNames.renamed(read(args[0]));BeanResolver.Plan renamedCore=BeanResolver.discover(renamed);
        BeanPropertyResolver.Plan renamedPlan=BeanPropertyResolver.discover(renamed,renamedCore);
        if(!renamedPlan.producer.owner.startsWith("Lchanged/")||renamedPlan.producer.name.equals(plan.producer.name))throw new AssertionError("Producer name binding");
        for(String role:plan.fields.keySet())if(renamedPlan.fields.get(role).name.equals(plan.fields.get(role).name)
                ||renamedPlan.setters.get(role).name.equals(plan.setters.get(role).name))throw new AssertionError("Property name binding: "+role);
        DexIndex.MethodData producer=index.methods.remove(plan.producer.key());reject(index,core);index.methods.put(plan.producer.key(),producer);
        index.methods.put("ambiguous-producer",producer);reject(index,core);index.methods.remove("ambiguous-producer");
        for(DexIndex.Ref ref:plan.setters.values()) {
            DexIndex.MethodData setter=index.methods.remove(ref.key());reject(index,core);index.methods.put(ref.key(),setter);
        }
        for(DexIndex.Ref ref:plan.fields.values()) {
            DexIndex.FieldData field=index.declaredFields.remove(ref.key());reject(index,core);index.declaredFields.put(ref.key(),field);
        }
        index.methods.put("ambiguous-property-setter",index.get(plan.setters.get("priority")));reject(index,core);index.methods.remove("ambiguous-property-setter");
        mutate(index,core,plan,producer);
        System.out.println("PASS fifteen original protocol/log/value property roles, raw private reference renaming, changed clamp threshold, missing/ambiguous roles, wrong typed scalar source, protocol key/label changes, aliased labels, clock unit/scale/operator changes and malformed code rejection");
    }
    private static void mutate(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan plan,DexIndex.MethodData producer)throws Exception {
        byte[] bytes=bytes(producer),original=bytes.clone();BeanValueFlow flow=new BeanValueFlow(producer);
        try {
            boolean changed=false;
            for(int pc:flow.states.keySet()) {
                int unit=flow.graph.code[pc],op=unit&255;
                if(op==0x12&&(short)unit>>12==5){put(bytes,producer,pc,(unit&0x0fff)|(6<<12));changed=true;}
                else if(op==0x13&&(short)flow.graph.code[pc+1]==5){put(bytes,producer,pc+1,6);changed=true;}
            }
            if(!changed)throw new AssertionError("Original clamp threshold fixture missing");clear(producer);
            if(!BeanPropertyResolver.discover(index,core).fields.equals(plan.fields))throw new AssertionError("Clamping constant binding");
        }finally{restore(bytes,original,producer);}
        // Feed a valid but different integer register to a semantic assignment.
        try {
            int pc=plan.setterPcs.get("templateCard"),unit=flow.graph.code[pc],op=unit&255;
            if(op>=0x74)throw new AssertionError("Original scalar setter uses range encoding");
            int[] args=flow.graph.arguments(pc);int replacement=-1;
            for(int register=0;register<16&&register<flow.registers;register++) {
                Set<Integer> definitions=flow.at(pc,register);Integer definition=flow.single(definitions);
                if(register==args[1]||definition==null||definition<0||!BeanOrigins.invoke(flow.graph.code[definition]&255))continue;
                DexIndex.Ref source=producer.invokedAt(definition);
                if(source.type.endsWith(")I")&&!definitions.equals(flow.at(pc,args[1]))) {replacement=register;break;}
            }
            if(replacement<0)throw new AssertionError("Other original integer value unavailable");
            put(bytes,producer,pc+2,(flow.graph.code[pc+2]&~0xf0)|(replacement<<4));clear(producer);reject(index,core);
        }finally{restore(bytes,original,producer);}
        for(String mode:Arrays.asList("scale","operator"))try {
            boolean changed=false;
            for(int pc:flow.states.keySet()) {
                int unit=flow.graph.code[pc],op=unit&255;
                if(mode.equals("scale")&&op==0x16&&(short)flow.graph.code[pc+1]==1000){put(bytes,producer,pc+1,999);changed=true;break;}
                if(mode.equals("operator")&&(op==0x9b||op==0xbb)){put(bytes,producer,pc,(unit&0xff00)|(op+1));changed=true;break;}
            }
            if(!changed)throw new AssertionError("Timeout fixture "+mode+" missing");clear(producer);reject(index,core);
        }finally{restore(bytes,original,producer);}
        Object reader=reader(producer);Field methodIds=reader.getClass().getDeclaredField("methodIds");methodIds.setAccessible(true);
        DexIndex.Ref[] methods=(DexIndex.Ref[])methodIds.get(reader);int clock=-1;
        for(int i=0;i<methods.length;i++)if(methods[i].owner.equals("Ljava/lang/System;")&&methods[i].name.equals("currentTimeMillis")&&methods[i].type.equals("()J")){clock=i;break;}
        if(clock<0)throw new AssertionError("Clock reference missing");DexIndex.Ref originalClock=methods[clock];
        try{methods[clock]=new DexIndex.Ref(originalClock.owner,"nanoTime",originalClock.type);clear(producer);reject(index,core);}
        finally{methods[clock]=originalClock;clear(producer);}
        Field stringIds=reader.getClass().getDeclaredField("strings");stringIds.setAccessible(true);String[] strings=(String[])stringIds.get(reader);
        changeString(index,core,producer,strings,"island.superx.rightInfo.content","island.superx.rightInfo.otherContent");
        changeString(index,core,producer,strings,", priority = ",", otherPriority = ");
        changeString(index,core,producer,strings,", islandType = ",", priority = ");
        try{put(bytes,producer,0,0x73);clear(producer);reject(index,core);}finally{restore(bytes,original,producer);}
    }
    private static void changeString(DexIndex index,BeanResolver.Plan core,DexIndex.MethodData producer,String[] strings,String old,String value)throws Exception {
        int position=Arrays.asList(strings).indexOf(old);if(position<0)throw new AssertionError("Original string fixture missing: "+old);
        try{strings[position]=value;clear(producer);reject(index,core);}finally{strings[position]=old;clear(producer);}
    }
    private static Object reader(DexIndex.MethodData method)throws Exception{Field f=DexIndex.MethodData.class.getDeclaredField("reader");f.setAccessible(true);return f.get(method);}
    private static byte[] bytes(DexIndex.MethodData method)throws Exception{Object r=reader(method);Field f=r.getClass().getDeclaredField("bytes");f.setAccessible(true);return (byte[])f.get(r);}
    private static void clear(DexIndex.MethodData method)throws Exception{Field f=DexIndex.MethodData.class.getDeclaredField("facts");f.setAccessible(true);f.set(method,null);}
    private static void put(byte[] bytes,DexIndex.MethodData method,int pc,int unit){int at=method.code+16+pc*2;bytes[at]=(byte)unit;bytes[at+1]=(byte)(unit>>8);}
    private static void restore(byte[] bytes,byte[] original,DexIndex.MethodData method)throws Exception{System.arraycopy(original,0,bytes,0,bytes.length);clear(method);}
    private static void reject(DexIndex index,BeanResolver.Plan core)throws Exception {
        try{BeanPropertyResolver.discover(index,core);}catch(IOException expected){return;}throw new AssertionError("Accepted invalid semantic property source");
    }
    private static DexIndex read(String path)throws Exception{return DexIndex.readApks(Collections.singletonList(new File(path)));}
}
