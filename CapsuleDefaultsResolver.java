package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Read official capsule defaults from the model receiving the typed capsule icon. */
final class CapsuleDefaultsResolver {
    static final List<String> ROLES=Collections.unmodifiableList(Arrays.asList("left.template","atomType","priority","businessKey"));
    static final class Plan {
        final DexIndex.Ref producer;
        final Map<String,Object> values;
        final Map<String,Integer> setterPcs,literalPcs;
        Plan(DexIndex.Ref producer,Map<String,Object> values,Map<String,Integer> setters,Map<String,Integer> literals) {
            this.producer=producer;this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));
            setterPcs=Collections.unmodifiableMap(new LinkedHashMap<>(setters));literalPcs=Collections.unmodifiableMap(new LinkedHashMap<>(literals));
        }
        int integer(String role){return (Integer)values.get(role);}
        String string(String role){return (String)values.get(role);}
        String describe(){return "capsule="+producer+" values="+values+" setters="+setterPcs+" literals="+literalPcs;}
    }
    static Plan discover(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties)throws IOException {
        try{return checked(index,core,properties);}
        catch(IndexOutOfBoundsException|ArithmeticException malformed){throw new IOException("Malformed capsule defaults",malformed);}
    }
    private static Plan checked(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties)throws IOException {
        List<Plan> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values()) {
            if(!method.ref.type.endsWith(")V")||!method.ref.type.contains("Landroid/service/notification/StatusBarNotification;"))continue;
            DexIndex.Facts facts=method.facts();
            if(!facts.strings.contains("notification.superx.capsule.icon")||!facts.strings.contains("notification.superx.capsule.content")
                    ||!facts.calls.containsAll(Arrays.asList(core.post,core.mainFactory,core.leftFactory,core.rightFactory)))continue;
            BeanValueFlow flow=new BeanValueFlow(method);
            for(int post:flow.states.keySet()) {
                if(!call(flow,post,core.post))continue;
                int[] submitted=flow.graph.arguments(post);if(submitted.length!=2)continue;
                Integer root=factory(flow,flow.at(post,submitted[1]),core.mainFactory);if(root==null)continue;
                Integer left=child(flow,core.setters.get("left"),root,post,core.leftFactory);
                Integer right=child(flow,core.setters.get("right"),root,post,core.rightFactory);
                if(left==null||right==null)continue;
                int icon=assignment(flow,core.setters.get("left.icon"),left,post);
                if(icon<0)continue;
                int[] frame=flow.graph.arguments(icon);
                if(!BeanPropertyResolver.keyValue(flow,flow.at(icon,frame[1]),"notification.superx.capsule.icon","Landroid/graphics/drawable/Icon;"))continue;
                Map<String,Object> values=new LinkedHashMap<>();Map<String,Integer> setters=new LinkedHashMap<>(),literals=new LinkedHashMap<>();
                for(String role:ROLES) {
                    int at=assignment(flow,properties.setters.get(role),role.startsWith("left.")?left:root,post);
                    require(at>=0,"Missing capsule scalar assignment: "+role);
                    int[] args=flow.graph.arguments(at);Integer definition=flow.single(flow.at(at,args[1]));
                    require(definition!=null&&definition>=0,"Capsule default is not a unique literal: "+role);
                    int unit=flow.graph.code[definition],op=unit&255;Object value;
                    if(role.equals("businessKey")) {
                        require(op==0x1a||op==0x1b,"Capsule business key is not a string literal");
                        value=method.stringAt(definition);require(!((String)value).isEmpty(),"Empty capsule business key");
                    } else {
                        require(op>=0x12&&op<=0x15,"Capsule scalar is not an integer literal: "+role);
                        value=op==0x12?(short)unit>>12:op==0x13?(short)flow.graph.code[definition+1]
                                :op==0x14?BeanOrigins.integer(flow.graph.code,definition+1):flow.graph.code[definition+1]<<16;
                    }
                    values.put(role,value);setters.put(role,at);literals.put(role,definition);
                }
                found.add(new Plan(method.ref,values,setters,literals));
            }
        }
        require(found.size()==1,"Missing/ambiguous capsule defaults producer: "+found.size());return found.get(0);
    }
    private static Integer factory(BeanValueFlow flow,Set<Integer> value,DexIndex.Ref factory)throws IOException {
        Integer pc=flow.single(value);return pc!=null&&pc>=0&&call(flow,pc,factory)?pc:null;
    }
    private static boolean call(BeanValueFlow flow,int pc,DexIndex.Ref ref)throws IOException {
        return ref!=null&&BeanOrigins.invoke(flow.graph.code[pc]&255)&&flow.graph.method.invokedAt(pc).equals(ref);
    }
    private static int assignment(BeanValueFlow flow,DexIndex.Ref setter,int object,int post)throws IOException {
        int selected=-1;
        for(int pc:flow.states.keySet())if(call(flow,pc,setter)&&flow.graph.dominates(pc,post)) {
            int[] args=flow.graph.arguments(pc);
            if(args.length==2&&flow.at(pc,args[0]).equals(Collections.singleton(object))) {
                require(selected<0,"Repeated capsule default assignment");selected=pc;
            }
        }
        return selected;
    }
    private static Integer child(BeanValueFlow flow,DexIndex.Ref setter,int root,int post,DexIndex.Ref factory)throws IOException {
        int pc=assignment(flow,setter,root,post);return pc<0?null:factory(flow,flow.at(pc,flow.graph.arguments(pc)[1]),factory);
    }
    private static void require(boolean value,String why)throws IOException {if(!value)throw new IOException(why);}
}
