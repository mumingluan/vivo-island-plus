package com.axuan.vivoislandplus;
import java.io.*;
import java.util.*;
import java.lang.reflect.*;
import java.nio.*;
public final class WaveStateProbe {
    static WaveStateResolver.Plan discover(DexIndex index,Map<String,Integer> resources)throws Exception {
        BeanResolver.Plan core=BeanResolver.discover(index);BeanPropertyResolver.Plan properties=BeanPropertyResolver.discover(index,core);
        return WaveStateResolver.discover(index,BeanModelResolver.discover(index,core,properties),TemplateResolver.discover(index,resources));
    }
    static void reject(DexIndex index,Map<String,Integer> resources,String why)throws Exception {
        try{discover(index,resources);}catch(IOException expected){return;}throw new AssertionError("Accepted "+why);
    }
    static int comparison(BeanOrigins graph,int argument)throws Exception {
        for(int pc:graph.states.keySet()) {
            int unit=graph.code[pc],op=unit&255;if(op!=0x32&&op!=0x33&&op!=0x38&&op!=0x39)continue;
            BeanOrigins.Value a=graph.at(pc,op<=0x33?(unit>>8)&15:unit>>8),b=op<=0x33?graph.at(pc,unit>>12):null;
            if(a!=null&&a.kind.equals("arg")&&a.index==argument||b!=null&&b.kind.equals("arg")&&b.index==argument)return pc;
        }
        throw new AssertionError("No actual input equality branch");
    }
    static int flipped(int unit){int op=unit&255;return (unit&~255)|(op==0x32?0x33:op==0x33?0x32:op==0x38?0x39:0x38);}
    static void changedEnum(DexIndex index,Map<String,Integer> resources,WaveStateResolver.Plan plan)throws Exception {
        DexIndex.MethodData old=index.get(plan.stateSetter);BeanOrigins flow=new BeanOrigins(old);
        int receiver=flow.registers-old.incomingWords(),input=receiver+1,local=0;
        CapsuleDefaultsProbe.expect(receiver>0&&input<16,"Alternate verified frame requires a spare local and 35c operands");
        int field=-1,method=-1,invoke=-1;int[] original=old.codeUnits();
        for(int pc:flow.states.keySet()) {
            if((original[pc]&255)==0x5c&&old.fieldAt(pc).equals(plan.enabled))field=original[pc+1];
            if(BeanOrigins.invoke(original[pc]&255)&&old.invokedAt(pc).equals(plan.gate)){method=original[pc+1];invoke=original[pc]&255;}
        }
        CapsuleDefaultsProbe.expect(field>=0&&method>=0&&invoke<0x74,"Missing original field/gate operands");
        int value=plan.playing+4;CapsuleDefaultsProbe.expect(value>=-8&&value<=7,"Alternate enum const/4 range");
        // Preserve the actual field/method/prototype/frame, but separate the
        // enum comparison from its 0/1 boolean result (R8 shares the old 1).
        int[] code={0x12|(local<<8)|((value&15)<<12),0x33|(input<<8)|(local<<12),4,
            0x12|(input<<8)|(1<<12),0x0228,0x12|(input<<8),0x5c|(input<<8)|(receiver<<12),field,
            invoke|(2<<12),method,receiver|(input<<4),0x0e};
        Object reader=CapsuleDefaultsProbe.READER.get(old);Field bytesField=reader.getClass().getDeclaredField("bytes");bytesField.setAccessible(true);
        byte[] bytes=(byte[])bytesField.get(reader);int at=(bytes.length+3)&~3;byte[] expanded=Arrays.copyOf(bytes,at+16+code.length*2);
        ByteBuffer buffer=ByteBuffer.wrap(expanded).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(at,(short)flow.registers);buffer.putShort(at+2,(short)old.incomingWords());buffer.putShort(at+4,(short)2);buffer.putInt(at+12,code.length);
        for(int i=0;i<code.length;i++)buffer.putShort(at+16+i*2,(short)code[i]);
        Constructor<?> constructor=DexIndex.MethodData.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
        DexIndex.MethodData replacement=(DexIndex.MethodData)constructor.newInstance(old.ref,old.flags,at,reader);
        bytesField.set(reader,expanded);index.methods.put(old.ref.key(),replacement);CapsuleDefaultsProbe.clear(index);
        try{CapsuleDefaultsProbe.expect(discover(index,resources).playing==value,"Changed enum with independent boolean result was not inferred");}
        finally{index.methods.put(old.ref.key(),old);bytesField.set(reader,bytes);CapsuleDefaultsProbe.clear(index);}
    }
    public static void main(String[] args)throws Exception {
        File apk=new File(args[0]);DexIndex index=DexIndex.readApks(Collections.singletonList(apk));
        BeanResolver.Plan core=BeanResolver.discover(index);BeanPropertyResolver.Plan properties=BeanPropertyResolver.discover(index,core);
        BeanModelResolver.Plan model=BeanModelResolver.discover(index,core,properties);
        Map<String,Integer> resources=new LinkedHashMap<>();for(int i=0;i<TemplateResolver.ROLES.size();i++)resources.put(TemplateResolver.ROLES.get(i),Integer.decode(args[i+1]));
        WaveStateResolver.Plan plan=WaveStateResolver.discover(index,model,TemplateResolver.discover(index,resources));System.out.println(plan.describe());
        WaveStateResolver.Plan renamed=discover(RawCodeNames.renamed(DexIndex.readApks(Collections.singletonList(apk))),resources);
        CapsuleDefaultsProbe.expect(renamed.stateSetter.owner.startsWith("Lchanged/")&&!renamed.gate.name.equals(plan.gate.name)&&renamed.playing==plan.playing,"Private wave names remained binding");
        for(DexIndex.Ref ref:Arrays.asList(plan.callback,plan.stateSetter,plan.gate,plan.start,plan.stop)) {
            DexIndex.MethodData method=index.get(ref);index.methods.remove(ref.key());reject(index,resources,"missing wave role "+ref);index.methods.put(ref.key(),method);
        }
        index.methods.put("duplicate-wave-setter",index.get(plan.stateSetter));reject(index,resources,"ambiguous state setter");index.methods.remove("duplicate-wave-setter");
        changedEnum(index,resources,plan);
        DexIndex.MethodData setter=index.get(plan.stateSetter);
        BeanOrigins setterFlow=new BeanOrigins(setter);DexIndex.MethodData gate=index.get(plan.gate);BeanOrigins gateFlow=new BeanOrigins(gate);
        int setterBranch=comparison(setterFlow,1),gateBranch=comparison(gateFlow,1);
        CapsuleDefaultsProbe.units(index,gate,gateBranch,new int[]{flipped(gateFlow.code[gateBranch])},()->reject(index,resources,"gate polarity alone changes active-state domain"));
        CapsuleDefaultsProbe.units(index,setter,setterBranch,new int[]{flipped(setterFlow.code[setterBranch])},()->
            CapsuleDefaultsProbe.units(index,gate,gateBranch,new int[]{flipped(gateFlow.code[gateBranch])},()->{
                WaveStateResolver.Plan inverted=discover(index,resources);
                CapsuleDefaultsProbe.expect(inverted.playing==plan.playing&&inverted.gateOn==plan.gateOff&&inverted.gateOff==plan.gateOn,"Coherent gate polarity not inferred");
            }));
        // Change the real runtime wave getter to another int field, retaining its type.
        DexIndex.MethodData callback=index.get(plan.callback);DexIndex.MethodData getter=null;
        for(DexIndex.Ref ref:callback.facts().calls)if(ref.owner.equals(model.model)&&ref.type.equals("()I")&&BeanResolver.readField(index,ref).equals(model.modelFields.get("right.waveState")))getter=index.get(ref);
        CapsuleDefaultsProbe.expect(getter!=null,"Missing actual wave getter");DexIndex.Ref[] fields=(DexIndex.Ref[])CapsuleDefaultsProbe.table(getter,"fieldIds");int alternate=-1;
        for(int i=0;i<fields.length;i++)if(fields[i].equals(model.modelFields.get("priority")))alternate=i;
        CapsuleDefaultsProbe.expect(alternate>=0,"Missing alternate model int");BeanOrigins getterFlow=new BeanOrigins(getter);boolean mutated=false;
        for(int at:getterFlow.states.keySet())if((getterFlow.code[at]&255)==0x52){CapsuleDefaultsProbe.units(index,getter,at+1,new int[]{alternate},()->reject(index,resources,"callback reads priority rather than wave"));mutated=true;break;}
        CapsuleDefaultsProbe.expect(mutated,"Wave getter source not mutated");
        DexIndex.MethodData start=index.get(plan.start);DexIndex.Ref[] methods=(DexIndex.Ref[])CapsuleDefaultsProbe.table(start,"methodIds");Map<Integer,DexIndex.Ref> changed=new LinkedHashMap<>();
        for(int i=0;i<methods.length;i++)if((methods[i].owner.equals("Landroid/animation/ValueAnimator;")||methods[i].owner.equals("Landroid/animation/Animator;"))&&methods[i].name.equals("start")&&methods[i].type.equals("()V")){
            changed.put(i,methods[i]);methods[i]=new DexIndex.Ref(methods[i].owner,"cancel","()V");
        }
        CapsuleDefaultsProbe.expect(!changed.isEmpty(),"Animator start mutation missing");CapsuleDefaultsProbe.clear(index);
        try{reject(index,resources,"animator start action changed to cancel");}finally{for(Map.Entry<Integer,DexIndex.Ref> entry:changed.entrySet())methods[entry.getKey()]=entry.getValue();CapsuleDefaultsProbe.clear(index);}
        BeanOrigins callbackFlow=new BeanOrigins(callback);BeanValueFlow callbackValues=new BeanValueFlow(callback);int arrays=0;
        for(int at:callbackFlow.states.keySet())if((callbackFlow.code[at]&255)==0x23){
            arrays++;int unit=callbackFlow.code[at],destination=(unit>>8)&15;CapsuleDefaultsProbe.expect(destination<callbackFlow.registers,"Wrong 22c destination");
            for(int next:callbackFlow.edges.get(at))CapsuleDefaultsProbe.expect(callbackValues.at(next,destination).contains(at),"22c new-array definition did not reach its actual destination");
            if(callbackFlow.registers<16)CapsuleDefaultsProbe.units(index,callback,at,new int[]{(unit&0xf0ff)|(callbackFlow.registers<<8)},()->reject(index,resources,"new-array destination outside frame"));
        }
        if(args.length>5) {
            DexIndex music=DexIndex.readApks(Collections.singletonList(new File(args[5])));MusicResolver.Plan official=MusicResolver.discover(music);
            DexIndex.MethodData sender=music.get(official.notificationSender);BeanOrigins source=new BeanOrigins(sender);Set<Integer> values=new LinkedHashSet<>();
            for(int at:source.states.keySet())if(BeanOrigins.invoke(source.code[at]&255)) {
                DexIndex.Ref call=sender.invokedAt(at);int[] frame=source.arguments(at);if(!(call.owner.equals("Landroid/os/Bundle;")||call.owner.equals("Landroid/os/BaseBundle;"))||!call.name.equals("putInt")||!call.type.equals("(Ljava/lang/String;I)V")||frame.length!=3)continue;
                BeanOrigins.Value key=source.at(at,frame[1]),v=source.at(at,frame[2]);
                if(key!=null&&key.kind.equals("string")&&key.text.equals("island.superx.rightInfo.waveState")&&v!=null&&v.kind.equals("constant"))values.add(v.index);
            }
            CapsuleDefaultsProbe.expect(values.equals(Collections.singleton(plan.playing)),"Official Music writer and actual renderer disagree: "+values);
            System.out.println("officialMusic="+official.notificationSender+" activeWrites="+values);
        }
        CapsuleDefaultsProbe.expect(discover(index,resources).playing==plan.playing,"Wave mutations were not restored");
        System.out.println("PASS resource/model/callback/boolean/animator wave chain, raw private renaming, changed active enum, coherent polarity, missing/ambiguous/source/action refusal; callback new-array count="+arrays);
    }
}
