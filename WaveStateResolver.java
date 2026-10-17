package com.axuan.vivoislandplus;
import java.io.IOException;
import java.util.*;

/** Trace the resource-bound wave state through a boolean gate to animator actions. */
final class WaveStateResolver {
    static final class Plan {
        final DexIndex.Ref callback,stateSetter,gate,start,stop,enabled,controller;
        final int playing,gateOn,gateOff,activeLiteralPc;
        Plan(DexIndex.Ref callback,DexIndex.Ref stateSetter,Gate gate,DexIndex.Ref enabled,int playing,int literal) {
            this.callback=callback;this.stateSetter=stateSetter;this.gate=gate.method;
            start=gate.start;stop=gate.stop;controller=gate.controller;this.enabled=enabled;
            this.playing=playing;gateOn=gate.on;gateOff=gate.off;activeLiteralPc=literal;
        }
        String describe(){return "waveCallback="+callback+" state="+stateSetter+" gate="+gate+" start="+start+" stop="+stop+" field="+enabled+" controller="+controller+" playing="+playing+" gateOn="+gateOn+" gateOff="+gateOff+" literal="+activeLiteralPc;}
    }
    private static final class Gate {
        final DexIndex.Ref method,start,stop,controller;final int on,off;
        Gate(DexIndex.Ref method,DexIndex.Ref start,DexIndex.Ref stop,DexIndex.Ref controller,int on,int off){this.method=method;this.start=start;this.stop=stop;this.controller=controller;this.on=on;this.off=off;}
    }
    private static final class Access {
        final DexIndex.Ref field;final String receiver;
        Access(DexIndex.Ref field,String receiver){this.field=field;this.receiver=receiver;}
    }
    static Plan discover(DexIndex index,BeanModelResolver.Plan model,TemplateResolver.Plan templates)throws IOException {
        try{return checked(index,model,templates);}
        catch(IndexOutOfBoundsException|ArithmeticException malformed){throw new IOException("Malformed wave state contract",malformed);}
    }
    private static Plan checked(DexIndex index,BeanModelResolver.Plan model,TemplateResolver.Plan templates)throws IOException {
        DexIndex.Ref view=templates.viewFields.get("wave"),wave=model.modelFields.get("right.waveState");
        require(view!=null&&wave!=null&&wave.type.equals("I"),"Missing typed wave view/model role");
        require(index.extendsType(view.type,"Landroid/view/View;")&&new HashSet<>(model.modelFields.values()).size()==model.modelFields.size(),"Invalid wave view or aliased runtime model roles");
        List<Plan> found=new ArrayList<>();
        for(DexIndex.MethodData state:index.methods.values()) {
            if(state.isStatic()||!state.ref.owner.equals(view.type)||!state.ref.type.equals("(I)V"))continue;
            List<DexIndex.Ref> callbacks=callbacks(index,state.ref,view,wave);if(callbacks.isEmpty())continue;
            require(callbacks.size()==1,"Ambiguous model-to-wave callback");
            BeanOrigins flow=new BeanOrigins(state);BeanValueFlow values=new BeanValueFlow(state);
            Map<Integer,Integer> candidates=new LinkedHashMap<>();
            for(int pc:flow.states.keySet()) {
                int unit=flow.code[pc],op=unit&255;if(op!=0x32&&op!=0x33&&op!=0x38&&op!=0x39)continue;
                int ra=op<=0x33?(unit>>8)&15:unit>>8,rb=op<=0x33?unit>>12:-1;
                BeanOrigins.Value a=flow.at(pc,ra),b=rb<0?new BeanOrigins.Value("constant",0,null):flow.at(pc,rb);
                if(kind(a,"constant")){BeanOrigins.Value tmp=a;a=b;b=tmp;int r=ra;ra=rb;rb=r;}
                if(!arg(a,1)||!kind(b,"constant"))continue;
                Integer literal=rb<0?null:values.single(values.at(pc,rb));
                candidates.put(b.index,literal==null?-1:literal);
            }
            require(!candidates.isEmpty(),"No wave-state equality discriminator");
            for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.code[pc]&255)) {
                DexIndex.Ref gateRef=state.invokedAt(pc);int[] args=flow.arguments(pc);
                if(!gateRef.owner.equals(view.type)||!gateRef.type.equals("(Z)V")||args.length!=2||!arg(flow.at(pc,args[0]),0))continue;
                Gate gate=gate(index,gateRef);if(gate==null)continue;
                boolean dominates=true;for(int end:flow.states.keySet())if((flow.code[end]&255)==0x0e)dominates&=flow.dominates(pc,end);
                if(!dominates)continue;
                Set<DexIndex.Ref> flags=new LinkedHashSet<>();
                for(int write:flow.states.keySet())if((flow.code[write]&255)==0x5c&&flow.dominates(write,pc)) {
                    DexIndex.Ref flag=state.fieldAt(write);int unit=flow.code[write];
                    if(flag.owner.equals(view.type)&&flag.type.equals("Z")&&arg(flow.at(write,unit>>12),0))flags.add(flag);
                }
                require(flags.size()==1,"Missing/ambiguous persistent wave boolean flag");DexIndex.Ref enabled=flags.iterator().next();
                int write=-1;for(int at:flow.states.keySet())if((flow.code[at]&255)==0x5c&&state.fieldAt(at).equals(enabled)&&flow.dominates(at,pc))write=at;
                List<Integer> active=new ArrayList<>();
                Set<Integer> probes=new LinkedHashSet<>(candidates.keySet());int other=Integer.MIN_VALUE;while(probes.contains(other))other++;probes.add(other);
                for(int input:probes) {
                    ScalarCallFlow specialized=new ScalarCallFlow(flow,Collections.emptyMap(),Collections.singletonMap(1,input));
                    Integer value=specialized.at(pc,args[1]),stored=specialized.at(write,(flow.code[write]>>8)&15);
                    require(Objects.equals(value,stored)&&(Objects.equals(value,gate.on)||Objects.equals(value,gate.off)),"Wave gate/field disagree or unknown");
                    if(Objects.equals(value,gate.on))active.add(input);
                }
                require(active.size()==1&&candidates.containsKey(active.get(0)),"Missing/ambiguous active wave state");
                found.add(new Plan(callbacks.get(0),state.ref,gate,enabled,active.get(0),candidates.get(active.get(0))));
            }
        }
        require(found.size()==1,"Missing/ambiguous resource-bound wave state: "+found.size());return found.get(0);
    }
    private static List<DexIndex.Ref> callbacks(DexIndex index,DexIndex.Ref setter,DexIndex.Ref view,DexIndex.Ref modelField)throws IOException {
        List<DexIndex.Ref> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values()) {
            if(method.isStatic()||!method.ref.type.equals("()V")||!method.facts().calls.contains(setter))continue;
            BeanOrigins flow=new BeanOrigins(method);
            for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.code[pc]&255)&&method.invokedAt(pc).equals(setter)) {
                int[] args=flow.arguments(pc);if(args.length!=2)continue;
                Access target=access(index,flow,flow.at(pc,args[0]),0);BeanOrigins.Value value=flow.at(pc,args[1]);
                if(target==null||!target.field.equals(view)||!kind(value,"call"))continue;
                DexIndex.Ref getter=method.invokedAt(value.index);int[] getArgs=flow.arguments(value.index);
                if(!getter.owner.equals(modelField.owner)||!getter.type.equals("()I")||getArgs.length!=1||!BeanResolver.readField(index,getter).equals(modelField))continue;
                Access source=access(index,flow,flow.at(value.index,getArgs[0]),0);
                if(source!=null&&source.field.type.equals(modelField.owner)&&source.receiver.equals(target.receiver))found.add(method.ref);
            }
        }
        return found;
    }
    private static Gate gate(DexIndex index,DexIndex.Ref ref)throws IOException {
        DexIndex.MethodData method=index.get(ref);if(method==null||method.isStatic())return null;
        BeanOrigins flow=new BeanOrigins(method);List<DexIndex.Ref> starts=new ArrayList<>(),stops=new ArrayList<>();Map<DexIndex.Ref,DexIndex.Ref> fields=new LinkedHashMap<>();
        for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.code[pc]&255)) {
            DexIndex.Ref call=method.invokedAt(pc);DexIndex.MethodData body=index.get(call);int[] args=flow.arguments(pc);
            if(body==null||body.isStatic()||args.length<1||!(call.type.equals("()V")||call.type.equals("(Z)V")))continue;
            Access receiver=access(index,flow,flow.at(pc,args[0]),0);
            if(receiver==null||!receiver.field.owner.equals(ref.owner)||!receiver.field.type.equals(call.owner)||!receiver.receiver.equals("arg:0:"+ref.owner))continue;
            boolean start=publicCall(body,"start","()V"),running=publicCall(body,"isRunning","()Z"),cancel=publicCall(body,"cancel","()V"),end=publicCall(body,"end","()V");
            if(call.type.equals("()V")&&start&&running&&!cancel&&!end){starts.add(call);fields.put(call,receiver.field);}
            if(call.type.equals("(Z)V")&&start&&running&&cancel&&end){stops.add(call);fields.put(call,receiver.field);}
        }
        if(starts.size()!=1||stops.size()!=1||!fields.get(starts.get(0)).equals(fields.get(stops.get(0))))return null;
        DexIndex.Ref start=starts.get(0),stop=stops.get(0);List<Integer> on=new ArrayList<>(),off=new ArrayList<>();
        for(int input:new int[]{0,1}) {
            ScalarCallFlow specialized=new ScalarCallFlow(flow,Collections.emptyMap(),Collections.singletonMap(1,input));
            Set<DexIndex.Ref> actions=new LinkedHashSet<>();
            for(int pc:specialized.states.keySet())if(BeanOrigins.invoke(flow.code[pc]&255)) {
                DexIndex.Ref call=method.invokedAt(pc);if(call.equals(start)||call.equals(stop))actions.add(call);
            }
            if(actions.equals(Collections.singleton(start)))on.add(input);
            if(actions.equals(Collections.singleton(stop)))off.add(input);
        }
        if(on.size()!=1||off.size()!=1||on.get(0).equals(off.get(0)))return null;
        return new Gate(ref,start,stop,fields.get(start),on.get(0),off.get(0));
    }
    private static boolean publicCall(DexIndex.MethodData method,String name,String descriptor)throws IOException {
        for(DexIndex.Ref call:method.facts().calls)if((call.owner.equals("Landroid/animation/ValueAnimator;")||call.owner.equals("Landroid/animation/Animator;"))&&call.name.equals(name)&&call.type.equals(descriptor))return true;return false;
    }
    private static Access access(DexIndex index,BeanOrigins flow,BeanOrigins.Value value,int depth)throws IOException {
        if(value==null||depth>12)return null;
        if(kind(value,"field")) {
            int unit=flow.code[value.index],op=unit&255;if(op<0x52||op>0x58)return null;
            String receiver=origin(index,flow,flow.at(value.index,unit>>12),depth+1);
            return receiver==null?null:new Access(flow.method.fieldAt(value.index),receiver);
        }
        if(kind(value,"call")) {
            DexIndex.Ref call=flow.method.invokedAt(value.index);DexIndex.MethodData body=index.get(call);int[] args=flow.arguments(value.index);
            if(body==null||args.length!=1)return null;
            DexIndex.Ref field;
            try{field=body.isStatic()?BeanResolver.staticReadField(index,call):BeanResolver.readField(index,call);}
            catch(IOException notAccessor){return null;}
            String receiver=origin(index,flow,flow.at(value.index,args[0]),depth+1);
            return receiver==null?null:new Access(field,receiver);
        }
        return null;
    }
    private static String origin(DexIndex index,BeanOrigins flow,BeanOrigins.Value value,int depth)throws IOException {
        if(kind(value,"arg"))return "arg:"+value.index+":"+value.text;
        Access access=access(index,flow,value,depth);return access==null?null:access.receiver+"/"+access.field.key();
    }
    private static boolean kind(BeanOrigins.Value value,String kind){return value!=null&&value.kind.equals(kind);}
    private static boolean arg(BeanOrigins.Value value,int arg){return kind(value,"arg")&&value.index==arg;}
    private static void require(boolean value,String why)throws IOException {if(!value)throw new IOException(why);}
}
