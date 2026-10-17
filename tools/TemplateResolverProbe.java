package com.axuan.vivoislandplus;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Actual four-APK dispatch, renamed raw references, changed IDs and rejection checks. */
public final class TemplateResolverProbe {
    public static void main(String[] args) throws Exception {
        Map<String,Integer> resources=new LinkedHashMap<>();
        for(int i=0;i<TemplateResolver.ROLES.size();++i)resources.put(TemplateResolver.ROLES.get(i),Integer.decode(args[i+1]));
        DexIndex index=read(args[0]);TemplateResolver.Plan plan=TemplateResolver.discover(index,resources);
        System.out.println(plan.describe());
        TemplateResolver.Plan renamed=TemplateResolver.discover(RawCodeNames.renamed(read(args[0])),resources);
        if(!renamed.inflater.owner.startsWith("Lchanged/") || renamed.selector.name.equals(plan.selector.name)
                || !renamed.templates.equals(plan.templates))throw new AssertionError("Raw class/member renaming failed");
        DexIndex.MethodData selector=index.methods.get(plan.selector.key());
        index.methods.remove(plan.selector.key());reject(index,resources);index.methods.put(plan.selector.key(),selector);
        index.methods.put("ambiguous-selector",selector);reject(index,resources);index.methods.remove("ambiguous-selector");
        Map<String,Integer> wrong=new LinkedHashMap<>(resources);wrong.put("wave",0);reject(index,wrong);
        wrong.put("wave",resources.get("text"));reject(index,wrong);
        wrong.put("wave",0x12345678);reject(index,wrong);
        Map<String,Integer> shiftedResources=new LinkedHashMap<>();
        for(String role:TemplateResolver.ROLES)shiftedResources.put(role,resources.get(role)+0x10000);
        DexIndex shifted=read(args[0]);
        for(Map.Entry<String,DexIndex.FieldData> entry:shifted.declaredFields.entrySet()) {
            DexIndex.FieldData field=entry.getValue();
            if(field.initialInteger!=null && resources.containsValue(field.initialInteger))
                entry.setValue(new DexIndex.FieldData(field.ref,field.flags,field.initialInteger+0x10000));
        }
        if(!TemplateResolver.discover(shifted,shiftedResources).templates.equals(plan.templates))throw new AssertionError("Resource ID relocation failed");
        testSwitch(index,selector,plan,resources);
        System.out.println("PASS actual dispatch, raw class/method/field renaming, resource relocation, changed template IDs, missing/ambiguous/resource/payload/opcode rejection");
    }
    private static void testSwitch(DexIndex index,DexIndex.MethodData selector,TemplateResolver.Plan plan,Map<String,Integer> resources)throws Exception {
        Field readerField=DexIndex.MethodData.class.getDeclaredField("reader");readerField.setAccessible(true);
        Object reader=readerField.get(selector);Field bytesField=reader.getClass().getDeclaredField("bytes");bytesField.setAccessible(true);
        byte[] bytes=(byte[])bytesField.get(reader);byte[] original=bytes.clone();int[] code=selector.codeUnits();
        int switchAt=-1;
        for(int at=0;at<code.length;) {
            int op=code[at]&255;if(op==0 && code[at]!=0)break;
            if(op==0x2b||op==0x2c){switchAt=at;break;}at+=DexIndex.width(op);
        }
        if(switchAt<0)throw new AssertionError("No switch fixture");
        int payload=switchAt+(code[switchAt+1]|code[switchAt+2]<<16),kind=code[switchAt]&255;
        try {
            if(kind==0x2b)put(bytes,selector.code+16+(payload+2)*2,(code[payload+2]|code[payload+3]<<16)+1000);
            else for(int i=0;i<code[payload+1];++i)put(bytes,selector.code+16+(payload+2+i*2)*2,(code[payload+2+i*2]|code[payload+3+i*2]<<16)+1000);
            for(int at=0;at<switchAt;at+=DexIndex.width(code[at]&255))if((code[at]&255)==0x13 && (short)code[at+1]>0) {
                int value=(short)code[at+1]+1000,position=selector.code+16+(at+1)*2;
                bytes[position]=(byte)value;bytes[position+1]=(byte)(value>>8);
            }
            Field facts=DexIndex.MethodData.class.getDeclaredField("facts");facts.setAccessible(true);facts.set(selector,null);
            TemplateResolver.Plan changed=TemplateResolver.discover(index,resources);
            for(String role:TemplateResolver.ROLES)if(changed.value(role)!=plan.value(role)+1000)throw new AssertionError("Template ID not derived: "+role+" "+changed.templates);
            System.arraycopy(original,0,bytes,0,bytes.length);facts.set(selector,null);
            bytes[selector.code+16+payload*2]=0;bytes[selector.code+16+payload*2+1]=3;reject(index,resources);
            System.arraycopy(original,0,bytes,0,bytes.length);facts.set(selector,null);
            bytes[selector.code+16]=0x27;reject(index,resources);
        } finally {
            System.arraycopy(original,0,bytes,0,bytes.length);
            Field facts=DexIndex.MethodData.class.getDeclaredField("facts");facts.setAccessible(true);facts.set(selector,null);
        }
    }
    private static DexIndex read(String path)throws Exception{return DexIndex.readApks(Collections.singletonList(new File(path)));}
    private static void put(byte[] bytes,int position,int value){for(int i=0;i<4;++i)bytes[position+i]=(byte)(value>>(8*i));}
    private static void reject(DexIndex index,Map<String,Integer> resources)throws Exception {
        try{TemplateResolver.discover(index,resources);}catch(IOException expected){return;}
        throw new AssertionError("Accepted missing/ambiguous/malformed template contract");
    }
}
