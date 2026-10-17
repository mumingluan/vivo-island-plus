package com.axuan.vivoislandplus;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Mutate actual executable literals and object wiring in the original plugin. */
public final class CapsuleDefaultsProbe {
    interface Check {void run()throws Exception;}
    static final Field READER,FACTS;
    static {try {READER=DexIndex.MethodData.class.getDeclaredField("reader");READER.setAccessible(true);FACTS=DexIndex.MethodData.class.getDeclaredField("facts");FACTS.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static Object table(DexIndex.MethodData method,String name)throws Exception {
        Object reader=READER.get(method);Field field=reader.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(reader);
    }
    static void expect(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void clear(DexIndex index)throws Exception {for(DexIndex.MethodData method:index.methods.values())FACTS.set(method,null);}
    static CapsuleDefaultsResolver.Plan discover(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties)throws Exception {return CapsuleDefaultsResolver.discover(index,core,properties);}
    static void reject(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties,String why)throws Exception {
        try{discover(index,core,properties);}catch(IOException expected){return;}throw new AssertionError("Accepted "+why);
    }
    static void units(DexIndex index,DexIndex.MethodData method,int pc,int[] replacement,Check action)throws Exception {
        byte[] bytes=(byte[])table(method,"bytes");int at=method.code+16+pc*2;byte[] saved=Arrays.copyOfRange(bytes,at,at+replacement.length*2);
        for(int i=0;i<replacement.length;i++){bytes[at+i*2]=(byte)replacement[i];bytes[at+i*2+1]=(byte)(replacement[i]>>8);}
        expect(!Arrays.equals(saved,Arrays.copyOfRange(bytes,at,at+saved.length)),"Mutation made no change");clear(index);
        try{action.run();}finally{System.arraycopy(saved,0,bytes,at,saved.length);clear(index);}
    }
    static void text(DexIndex index,DexIndex.MethodData method,String old,String value,Check action)throws Exception {
        String[] strings=(String[])table(method,"strings");List<Integer> slots=new ArrayList<>();
        for(int i=0;i<strings.length;i++)if(strings[i].equals(old)){slots.add(i);strings[i]=value;}
        expect(!slots.isEmpty(),"Missing string mutation");clear(index);
        try{action.run();}finally{for(int slot:slots)strings[slot]=old;clear(index);}
    }
    public static void main(String[] args)throws Exception {
        File apk=new File(args[0]);DexIndex index=DexIndex.readApks(Collections.singletonList(apk));
        BeanResolver.Plan core=BeanResolver.discover(index);BeanPropertyResolver.Plan properties=BeanPropertyResolver.discover(index,core);
        BeanModelResolver.Plan model=BeanModelResolver.discover(index,core,properties);
        System.out.println("model="+model.model+" card="+model.modelFields.get("templateCard")+" sbn="+model.modelFields.get("sbn"));
        CardTemplateTransportResolver.Plan transport=CardTemplateTransportResolver.discover(index,model);System.out.println(transport.describe());
        Map<String,DexIndex.Ref> aliased=new LinkedHashMap<>(model.modelFields);aliased.put("priority",aliased.get("templateCard"));
        BeanModelResolver.Plan aliasedModel=new BeanModelResolver.Plan(model.consumer,model.constructor,model.materializer,model.model,model.gesture,model.keyguard,
            model.fields,model.setters,aliased,model.unreadDto,model.unreadModel);
        rejectCard(index,aliasedModel,"aliased card role mapping");
        CapsuleDefaultsResolver.Plan plan=discover(index,core,properties);System.out.println(plan.describe());
        DexIndex renamed=RawCodeNames.renamed(DexIndex.readApks(Collections.singletonList(apk)));
        BeanResolver.Plan renamedCore=BeanResolver.discover(renamed);
        BeanPropertyResolver.Plan renamedProperties=BeanPropertyResolver.discover(renamed,renamedCore);
        CapsuleDefaultsResolver.Plan changed=discover(renamed,renamedCore,renamedProperties);
        CardTemplateTransportResolver.Plan renamedTransport=CardTemplateTransportResolver.discover(renamed,BeanModelResolver.discover(renamed,renamedCore,renamedProperties));
        expect(renamedTransport.consumer.owner.startsWith("Lchanged/")&&!renamedTransport.templateGetter.name.equals(transport.templateGetter.name),"Card transport kept private names");
        expect(changed.producer.owner.startsWith("Lchanged/")&&!changed.producer.name.equals(plan.producer.name)&&changed.values.equals(plan.values),"Private raw names remained binding");
        DexIndex.MethodData method=index.get(plan.producer);
        index.methods.remove(plan.producer.key());reject(index,core,properties,"missing producer");index.methods.put(plan.producer.key(),method);
        index.methods.put("duplicate-capsule",method);reject(index,core,properties,"ambiguous producer");index.methods.remove("duplicate-capsule");
        for(String role:Arrays.asList("left.template","atomType","priority")) {
            int pc=plan.literalPcs.get(role),old=plan.integer(role),value=old+1;int[] code=method.codeUnits();int op=code[pc]&255;
            int[] replacement=Arrays.copyOfRange(code,pc,pc+DexIndex.width(op));
            if(op==0x12){expect(value>=-8&&value<=7,"const/4 mutation range");replacement[0]=(replacement[0]&0x0fff)|((value&15)<<12);}
            else if(op==0x13)replacement[1]=value;
            else if(op==0x14){replacement[1]=value&65535;replacement[2]=value>>>16;}
            else throw new AssertionError("Unsupported actual integer mutation encoding");
            units(index,method,pc,replacement,()->{
                CapsuleDefaultsResolver.Plan result=discover(index,core,properties);
                for(String affected:Arrays.asList("left.template","atomType","priority"))
                    expect(result.integer(affected)==(plan.literalPcs.get(affected)==pc?value:plan.integer(affected)),"Numeric default was not derived: "+affected);
            });
        }
        text(index,method,plan.string("businessKey"),"DISCOVERED_BUSINESS",()->expect(discover(index,core,properties).string("businessKey").equals("DISCOVERED_BUSINESS"),"Business key was hardcoded"));
        text(index,method,"notification.superx.capsule.icon","changed.capsule.icon",()->reject(index,core,properties,"changed capsule icon protocol"));
        BeanOrigins graph=new BeanOrigins(method);int iconSetter=-1;
        for(int pc:graph.states.keySet())if(BeanOrigins.invoke(graph.code[pc]&255)&&method.invokedAt(pc).equals(core.setters.get("left.icon")))iconSetter=pc;
        expect(iconSetter>=0,"Missing typed icon setter");
        units(index,method,iconSetter,new int[]{0,0,0},()->reject(index,core,properties,"missing icon assignment"));
        int[] frame=graph.arguments(iconSetter);int[] code=method.codeUnits();int[] wrong=Arrays.copyOfRange(code,iconSetter,iconSetter+3);
        expect((wrong[0]&255)<0x74&&frame.length==2,"Expected real 35c icon setter");wrong[2]=(wrong[2]&~0xf0)|((frame[0]&15)<<4);
        units(index,method,iconSetter,wrong,()->reject(index,core,properties,"icon value is the bean rather than typed Bundle source"));
        for(String role:CapsuleDefaultsResolver.ROLES) {
            int pc=plan.setterPcs.get(role);units(index,method,pc,new int[]{0,0,0},()->reject(index,core,properties,"missing default assignment "+role));
        }
        expect(discover(index,core,properties).values.equals(plan.values),"Restoration changed originals");
        DexIndex.MethodData card=index.get(transport.consumer);
        text(index,card,"notification.superx.template","changed.request.template",()->rejectCard(index,model,"wrong request key"));
        index.methods.remove(transport.consumer.key());rejectCard(index,model,"missing card consumer");index.methods.put(transport.consumer.key(),card);
        index.methods.put("ambiguous-card",card);rejectCard(index,model,"ambiguous card consumer");index.methods.remove("ambiguous-card");
        DexIndex.MethodData getter=index.get(transport.templateGetter);BeanOrigins getterFlow=new BeanOrigins(getter);
        DexIndex.Ref[] fields=(DexIndex.Ref[])table(getter,"fieldIds");int alternate=-1;
        for(int i=0;i<fields.length;i++)if(fields[i].equals(model.modelFields.get("priority")))alternate=i;
        expect(alternate>=0,"Missing independent typed model field");boolean mutated=false;
        for(int pc:getterFlow.states.keySet())if((getterFlow.code[pc]&255)==0x52) {
            units(index,getter,pc+1,new int[]{alternate},()->rejectCard(index,model,"card getter reads priority rather than template"));mutated=true;break;
        }
        expect(mutated,"No real card field read mutated");
        expect(CardTemplateTransportResolver.discover(index,model).consumer.equals(transport.consumer),"Card restoration failed");
        System.out.println("PASS four official capsule defaults and card enum transport, raw private renaming, changed numeric/string literals, missing/ambiguous producer/consumer, typed icon source, scalar assignment, aliased card roles and wrong card field/key refusal");
    }
    static void rejectCard(DexIndex index,BeanModelResolver.Plan model,String why)throws Exception {
        try{CardTemplateTransportResolver.discover(index,model);}catch(IOException expected){return;}throw new AssertionError("Accepted "+why);
    }
}
