package com.axuan.vivoislandplus;
import java.io.*;
import java.util.*;
import java.lang.reflect.*;

public final class WireTemplateProbe {
    static BundleWireTemplateResolver.Plan discover(DexIndex index,Map<String,Integer> resources)throws Exception {
        BeanResolver.Plan core=BeanResolver.discover(index);BeanPropertyResolver.Plan properties=BeanPropertyResolver.discover(index,core);
        return BundleWireTemplateResolver.discover(index,core,properties,CapsuleDefaultsResolver.discover(index,core,properties),TemplateResolver.discover(index,resources));
    }
    static void reject(DexIndex index,Map<String,Integer> resources,String why)throws Exception {
        try{discover(index,resources);}catch(IOException expected){return;}throw new AssertionError("Accepted "+why);
    }
    static void expect(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        Map<String,Integer> resources=new LinkedHashMap<>();for(int i=0;i<TemplateResolver.ROLES.size();i++)resources.put(TemplateResolver.ROLES.get(i),Integer.decode(args[i+1]));
        DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
        BundleWireTemplateResolver.Plan plan=discover(index,resources);System.out.println(plan.describe());
        BundleWireTemplateResolver.Plan renamed=discover(RawCodeNames.renamed(DexIndex.readApks(Collections.singletonList(new File(args[0])))),resources);
        expect(renamed.producer.owner.startsWith("Lchanged/")&&!renamed.producer.name.equals(plan.producer.name)&&renamed.values.equals(plan.values),"Private names were binding");
        DexIndex.MethodData method=index.get(plan.producer);
        index.methods.remove(plan.producer.key());reject(index,resources,"missing producer");index.methods.put(plan.producer.key(),method);
        index.methods.put("ambiguous-wire-producer",method);reject(index,resources,"ambiguous producer");index.methods.remove("ambiguous-wire-producer");
        CapsuleDefaultsProbe.text(index,method,"island.superx.leftTemplate","changed.leftTemplate",()->reject(index,resources,"wrong left wire key"));
        CapsuleDefaultsProbe.text(index,method,"island.superx.rightTemplate","changed.rightTemplate",()->reject(index,resources,"wrong right wire key"));
        BeanValueFlow flow=new BeanValueFlow(method);boolean branch=false;
        for(int pc:flow.states.keySet()) {
            int unit=flow.graph.code[pc],op=unit&255;if(op!=0x32&&op!=0x33)continue;
            Integer a=flow.single(flow.at(pc,(unit>>8)&15)),b=flow.single(flow.at(pc,unit>>12));
            if(a==null||b==null||a<0||b<0)continue;
            if(a!=plan.rightRead&&b!=plan.rightRead)continue;
            int literal=a==plan.rightRead?b:a,literalOp=flow.graph.code[literal]&255;
            if(literalOp<0x12||literalOp>0x15)continue;
            CapsuleDefaultsProbe.units(index,method,pc,new int[]{(unit&~255)|(op==0x32?0x33:0x32)},()->reject(index,resources,"reversed template conversion gate"));
            branch=true;break;
        }
        expect(branch,"Actual conversion gate was not mutated");
        TemplateResolver.Plan templates=TemplateResolver.discover(index,resources);
        DexIndex.MethodData selector=index.get(templates.selector);int[] code=selector.codeUnits();int switchPc=-1;
        for(int pc=0;pc<code.length;pc+=DexIndex.width(code[pc]&255))if((code[pc]&255)==0x2b||(code[pc]&255)==0x2c){switchPc=pc;break;}
        expect(switchPc>=0,"Missing renderer switch");
        byte[] bytes=(byte[])CapsuleDefaultsProbe.table(selector,"bytes"),saved=bytes.clone();
        try {
            int payload=switchPc+BeanOrigins.integer(code,switchPc+1),count=code[payload+1];
            if((code[switchPc]&255)==0x2b)put(bytes,selector,payload+2,BeanOrigins.integer(code,payload+2)+1000);
            else for(int i=0;i<count;i++)put(bytes,selector,payload+2+i*2,BeanOrigins.integer(code,payload+2+i*2)+1000);
            for(int pc=0;pc<switchPc;pc+=DexIndex.width(code[pc]&255))if((code[pc]&255)==0x13&&(short)code[pc+1]>0) {
                int at=selector.code+16+(pc+1)*2,value=(short)code[pc+1]+1000;bytes[at]=(byte)value;bytes[at+1]=(byte)(value>>8);
            }
            CapsuleDefaultsProbe.clear(index);BundleWireTemplateResolver.Plan shifted=discover(index,resources);
            expect(shifted.value("left.icon")==plan.value("left.icon"),"Unrelated left default changed");
            for(String role:TemplateResolver.ROLES) {
                expect(shifted.value(role)==plan.value(role)+1000,"Wire ID was not derived from shifted renderer: "+role);
                List<Integer> expected=new ArrayList<>();for(int value:plan.verifiedAliases.get(role))expected.add(value+1000);
                expect(shifted.verifiedAliases.get(role).equals(expected),"Renderer aliases were not revalidated: "+role);
            }
        }finally{System.arraycopy(saved,0,bytes,0,bytes.length);CapsuleDefaultsProbe.clear(index);}
        BeanResolver.Plan core=BeanResolver.discover(index);BeanPropertyResolver.Plan properties=BeanPropertyResolver.discover(index,core);
        CapsuleDefaultsResolver.Plan capsule=CapsuleDefaultsResolver.discover(index,core,properties);
        DexIndex.MethodData capsuleProducer=index.get(capsule.producer);int literal=capsule.literalPcs.get("left.template"),unit=capsuleProducer.codeUnits()[literal];
        int value=capsule.integer("left.template")+1;
        int op=unit&255;int[] replacement=Arrays.copyOfRange(capsuleProducer.codeUnits(),literal,literal+DexIndex.width(op));
        if(op==0x12){expect(value>=-8&&value<=7,"const/4 range");replacement[0]=(unit&0x0fff)|((value&15)<<12);}
        else if(op==0x13)replacement[1]=value;
        else if(op==0x14){replacement[1]=value&65535;replacement[2]=value>>>16;}
        else throw new AssertionError("Unsupported actual default literal encoding");
        CapsuleDefaultsProbe.units(index,capsuleProducer,literal,replacement,()->expect(discover(index,resources).value("left.icon")==value,"Left icon default was hardcoded"));
        expect(discover(index,resources).values.equals(plan.values),"Mutations did not restore original behavior");
        System.out.println("PASS actual Bundle forwarding, renderer progress aliases, raw private renaming, shifted renderer IDs/aliases, changed left icon default, missing/ambiguous source, changed keys and reversed conversion-gate refusal");
    }
    static void put(byte[] bytes,DexIndex.MethodData method,int unit,int value){int at=method.code+16+unit*2;for(int i=0;i<4;i++)bytes[at+i]=(byte)(value>>(i*8));}
}
