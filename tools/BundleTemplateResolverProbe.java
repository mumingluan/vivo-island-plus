package com.axuan.vivoislandplus;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Four original APKs plus renamed references, changed keys/resources and refusal cases. */
public final class BundleTemplateResolverProbe {
    public static void main(String[] args)throws Exception {
        Map<String,Integer> resources=new LinkedHashMap<>();int n=1;
        for(String side:new String[]{"shade","island"})for(String role:BundleTemplateResolver.ROLES)resources.put(side+"."+role,Integer.decode(args[n++]));
        DexIndex index=read(args[0]);BundleTemplateResolver.Plan plan=BundleTemplateResolver.discover(index,resources);
        System.out.println(plan.describe());
        BundleTemplateResolver.Plan renamed=BundleTemplateResolver.discover(RawCodeNames.renamed(read(args[0])),resources);
        if(!renamed.initializer.owner.startsWith("Lchanged/")||renamed.consumer.name.equals(plan.consumer.name)
                ||renamed.shadeMap.name.equals(plan.shadeMap.name)||!renamed.values.equals(plan.values))throw new AssertionError("Name dependence remains");
        DexIndex.MethodData init=index.methods.remove(plan.initializer.key());reject(index,resources);index.methods.put(plan.initializer.key(),init);
        DexIndex.MethodData consumer=index.methods.remove(plan.consumer.key());reject(index,resources);index.methods.put(plan.consumer.key(),consumer);
        DexIndex.MethodData validator=index.methods.remove(plan.validator.key());reject(index,resources);index.methods.put(plan.validator.key(),validator);
        DexIndex.MethodData parser=index.methods.remove(plan.customParser.key());reject(index,resources);index.methods.put(plan.customParser.key(),parser);
        DexIndex.MethodData renderer=index.methods.remove(plan.customRenderer.key());reject(index,resources);index.methods.put(plan.customRenderer.key(),renderer);
        index.methods.put("duplicate-initializer",init);reject(index,resources);index.methods.remove("duplicate-initializer");
        index.methods.put("duplicate-consumer",consumer);reject(index,resources);index.methods.remove("duplicate-consumer");
        Map<String,Integer> wrong=new LinkedHashMap<>(resources);wrong.put("shade.normal",0);reject(index,wrong);
        wrong.put("shade.normal",resources.get("shade.buttons"));reject(index,wrong);
        wrong.put("shade.normal",0x12345678);reject(index,wrong);
        wrong=new LinkedHashMap<>(resources);wrong.put("shade.buttons",0);
        if(resources.get("island.buttons")!=0)reject(index,wrong);
        Map<String,Integer> shiftedResources=new LinkedHashMap<>();
        for(Map.Entry<String,Integer> e:resources.entrySet())shiftedResources.put(e.getKey(),e.getValue()==0?0:e.getValue()+0x20000);
        DexIndex shifted=read(args[0]);
        for(Map.Entry<String,DexIndex.FieldData> e:shifted.declaredFields.entrySet()) {
            DexIndex.FieldData field=e.getValue();
            if(field.initialInteger!=null&&resources.containsValue(field.initialInteger))
                e.setValue(new DexIndex.FieldData(field.ref,field.flags,field.initialInteger+0x20000));
        }
        if(!BundleTemplateResolver.discover(shifted,shiftedResources).values.equals(plan.values))throw new AssertionError("Resource relocation failed");
        mutate(index,init,plan,resources);
        System.out.println("PASS original request maps/consumer/validator/custom renderer, raw class/member renaming, resource relocation, changed request enums with matching validator, missing/ambiguous/aliased/malformed and overbroad-validator rejection");
    }
    private static void mutate(DexIndex index,DexIndex.MethodData init,BundleTemplateResolver.Plan plan,Map<String,Integer> resources)throws Exception {
        Field readerField=DexIndex.MethodData.class.getDeclaredField("reader");readerField.setAccessible(true);
        Object reader=readerField.get(init);Field bytesField=reader.getClass().getDeclaredField("bytes");bytesField.setAccessible(true);
        byte[] bytes=(byte[])bytesField.get(reader),original=bytes.clone();
        try {
            int[] code=init.codeUnits();
            for(int pc:new LinkedHashSet<>(plan.literalPcs.values())) {
                int op=code[pc]&255,position=init.code+16+pc*2;
                // Use distinct negative keys so const/4 still fits and no
                // unchanged positive template can collide with this mutation.
                int value=op==0x12?(short)code[pc]>>12:op==0x13?(short)code[pc+1]:op==0x14?code[pc+1]|code[pc+2]<<16:code[pc+1]<<16;
                int changed=-(value+1);
                if(op==0x12) {if(changed< -8)throw new AssertionError("Fixture const/4 range");put16(bytes,position,(code[pc]&0x0fff)|((changed&15)<<12));}
                else if(op==0x13)put16(bytes,position+2,changed);
                else if(op==0x14) {put16(bytes,position+2,changed);put16(bytes,position+4,changed>>>16);}
                else throw new AssertionError("Unexpected request enum literal opcode");
            }
            clear(init);reject(index,resources); // map and validator must agree
            DexIndex.MethodData validator=index.get(plan.validator);
            replaceValidator(bytes,validator,plan.values.values());
            BundleTemplateResolver.Plan changed=BundleTemplateResolver.discover(index,resources);
            for(String role:BundleTemplateResolver.ROLES)if(changed.value(role)!=-(plan.value(role)+1))throw new AssertionError("Request key not derived");
            System.arraycopy(original,0,bytes,0,bytes.length);clear(init);
            bytes[init.code+16]=0x27;reject(index,resources);
            System.arraycopy(original,0,bytes,0,bytes.length);clear(init);
            put16(bytes,validator.code+16,0x1012);put16(bytes,validator.code+18,0x0f);clear(validator);
            reject(index,resources);
        }finally {System.arraycopy(original,0,bytes,0,bytes.length);clear(init);}
    }
    private static void replaceValidator(byte[] bytes,DexIndex.MethodData method,Collection<Integer> originalValues)throws Exception {
        Set<Integer> values=new LinkedHashSet<>();for(int v:originalValues)values.add(-(v+1));
        int count=method.codeUnits().length,input=method.registerCount()-1,success=values.size()*4+2;
        if(input<1||input>15||count<success+2)throw new AssertionError("Validator fixture code/register bounds");
        int[] code=new int[count];int at=0;
        for(int value:values) {
            code[at++]=0x13;code[at++]=value&65535;
            code[at]=0x32|(input<<8);code[at+1]=success-at;at+=2;
        }
        code[at++]=0x12;code[at++]=0x0f;code[at++]=0x1012;code[at]=0x0f;
        for(int i=0;i<count;++i)put16(bytes,method.code+16+i*2,code[i]);clear(method);
    }
    private static void put16(byte[] bytes,int at,int value){bytes[at]=(byte)value;bytes[at+1]=(byte)(value>>8);}
    private static void clear(DexIndex.MethodData method)throws Exception {Field f=DexIndex.MethodData.class.getDeclaredField("facts");f.setAccessible(true);f.set(method,null);}
    private static DexIndex read(String path)throws Exception{return DexIndex.readApks(Collections.singletonList(new File(path)));}
    private static void reject(DexIndex index,Map<String,Integer> resources)throws Exception {
        try{BundleTemplateResolver.discover(index,resources);}catch(IOException expected){return;}
        throw new AssertionError("Accepted missing/ambiguous/malformed request template contract");
    }
}
