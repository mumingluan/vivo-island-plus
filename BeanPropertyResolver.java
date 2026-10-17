package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Property roles from the original notification protocol, diagnostics and submitted object. */
final class BeanPropertyResolver {
    private static final String SBN="Landroid/service/notification/StatusBarNotification;";
    private static final String BUNDLE="Landroid/os/Bundle;";
    private static final String BUILDER="Ljava/lang/StringBuilder;";
    private static final Pattern LABEL=Pattern.compile("(?:^|[, ])\\s*([A-Za-z][A-Za-z0-9]*)\\s*=\\s*$");
    private static final class Spec {
        final String role,side,type,key,label;
        Spec(String role,String side,String type,String key,String label){this.role=role;this.side=side;this.type=type;this.key=key;this.label=label;}
    }
    private static final List<Spec> SPECS=Collections.unmodifiableList(Arrays.asList(
        new Spec("forceScreenOn","main","Z","notification.superx.keepScreenOn",null),
        new Spec("businessKey","main","Ljava/lang/String;",null,"businessKey"),
        new Spec("sourcePackageName","main","Ljava/lang/String;",null,"@package"),
        new Spec("templateCard","main","I","island.superx.template",null),
        new Spec("atomType","main","I",null,"islandType"),
        new Spec("islandClick","main","I","island.superx.islandClick",null),
        new Spec("clickResp","main","Landroid/app/PendingIntent;","island.superx.clickResp",null),
        new Spec("priority","main","I",null,"priority"),
        new Spec("showTime","main","I",null,"showTime"),
        new Spec("timeout","main","J",null,"showTime"),
        new Spec("landingInfo","main","Ljava/lang/String;","island.superx.landingInfo",null),
        new Spec("left.template","left","I",null,"leftTemplate"),
        new Spec("right.template","right","I",null,"rightTemplate"),
        new Spec("right.waveState","right","I","island.superx.rightInfo.waveState",null),
        new Spec("right.content","right","Ljava/lang/CharSequence;","island.superx.rightInfo.content",null)
    ));
    static final class Plan {
        final DexIndex.Ref producer;
        final Map<String,DexIndex.Ref> fields,setters;
        final Map<String,Integer> setterPcs;
        Plan(DexIndex.Ref producer,Map<String,DexIndex.Ref> fields,Map<String,DexIndex.Ref> setters,Map<String,Integer> pcs) {
            this.producer=producer;this.fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            this.setters=Collections.unmodifiableMap(new LinkedHashMap<>(setters));setterPcs=Collections.unmodifiableMap(new LinkedHashMap<>(pcs));
        }
        String describe(){return "producer="+producer+"\nproperties="+fields+"\nsetters="+setters+"\nsetterPcs="+setterPcs;}
    }
    private BeanPropertyResolver() { }
    static Plan discover(DexIndex index,BeanResolver.Plan core)throws IOException {
        try{return checked(index,core);}
        catch(IndexOutOfBoundsException|ArithmeticException malformed){throw new IOException("Malformed property source contract",malformed);}
    }
    private static Plan checked(DexIndex index,BeanResolver.Plan core)throws IOException {
        List<Plan> plans=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values()) {
            // Keep discovery on notification entry points before decoding facts.
            // Extra parameters and static/instance implementations are allowed.
            int boundary=method.ref.type.indexOf(')');
            if(!method.ref.type.endsWith(")V")||method.ref.type.indexOf(SBN)<1||method.ref.type.indexOf(SBN)>=boundary)continue;
            DexIndex.Facts facts=method.facts();
            if(!facts.calls.containsAll(Arrays.asList(core.post,core.mainFactory,core.leftFactory,core.rightFactory))
                    ||!facts.strings.contains("notification.superx.keepScreenOn")||!facts.strings.contains("island.superx.islandClick"))continue;
            BeanValueFlow flow=new BeanValueFlow(method);Map<String,List<Set<Integer>>> labels=labels(flow);
            for(int post:flow.states.keySet())if(call(flow,post,core.post)) {
                int[] args=flow.graph.arguments(post);if(args.length!=2)continue;
                Integer root=flow.single(flow.at(post,args[1]));if(!factory(flow,root,core.mainFactory))continue;
                Integer left=child(flow,core.setters.get("left"),root,post,core.leftFactory);
                Integer right=child(flow,core.setters.get("right"),root,post,core.rightFactory);
                if(left==null||right==null)continue; // withdrawal models do not carry children
                Map<String,DexIndex.Ref> fields=new LinkedHashMap<>(),setters=new LinkedHashMap<>();Map<String,Integer> pcs=new LinkedHashMap<>();
                Map<String,Set<Integer>> boundValues=new LinkedHashMap<>();
                for(Spec spec:SPECS) {
                    String owner=spec.side.equals("main")?core.bean:spec.side.equals("left")?core.left:core.right;
                    int receiver=spec.side.equals("main")?root:spec.side.equals("left")?left:right;
                    Set<DexIndex.Ref> matches=new LinkedHashSet<>();Map<DexIndex.Ref,Integer> locations=new HashMap<>();
                    for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.graph.code[pc]&255)) {
                        DexIndex.Ref setter=method.invokedAt(pc);
                        if(!setter.owner.equals(owner)||!setter.type.equals("("+spec.type+")V")||!flow.graph.dominates(pc,post))continue;
                        int[] frame=flow.graph.arguments(pc);
                        if(frame.length!=(BeanOrigins.wide(spec.type)?3:2)||!Collections.singleton(receiver).equals(flow.at(pc,frame[0])))continue;
                        Set<Integer> values=flow.at(pc,frame[1]);boolean match;
                        if(spec.key!=null)match=keyValue(flow,values,spec.key,spec.type);
                        else if(spec.label.equals("@package"))match=packageValue(flow,values);
                        else {
                            match=false;
                            for(Set<Integer> labeled:labels.getOrDefault(spec.label,Collections.emptyList()))
                                if(!labeled.contains(-1)&&flow.expanded(values).containsAll(labeled))match=true;
                            if(spec.role.equals("timeout"))match&=timeoutValue(flow,values,boundValues.get("showTime"));
                        }
                        if(match&&!flow.expanded(values).contains(-1)) {
                            matches.add(setter);require(locations.put(setter,pc)==null,"Repeated semantic property assignment");
                        }
                    }
                    require(matches.size()==1,"Missing/ambiguous property role "+spec.role+": "+matches);
                    DexIndex.Ref setter=matches.iterator().next(),field=BeanResolver.assignedField(index,setter);
                    require(!fields.containsValue(field)&&!core.fields.containsValue(field),"Property roles alias the same field");
                    fields.put(spec.role,field);setters.put(spec.role,setter);pcs.put(spec.role,locations.get(setter));
                    int location=locations.get(setter);boundValues.put(spec.role,flow.at(location,flow.graph.arguments(location)[1]));
                }
                plans.add(new Plan(method.ref,fields,setters,pcs));
            }
        }
        require(plans.size()==1,"Missing/ambiguous notification property producer: "+plans.size());return plans.get(0);
    }
    private static boolean call(BeanValueFlow flow,int pc,DexIndex.Ref ref)throws IOException {
        return pc>=0&&BeanOrigins.invoke(flow.graph.code[pc]&255)&&flow.graph.method.invokedAt(pc).equals(ref);
    }
    private static boolean factory(BeanValueFlow flow,Integer pc,DexIndex.Ref ref)throws IOException {return pc!=null&&call(flow,pc,ref);}
    private static Integer child(BeanValueFlow flow,DexIndex.Ref setter,int root,int post,DexIndex.Ref factory)throws IOException {
        Set<Integer> found=new LinkedHashSet<>();
        for(int pc:flow.states.keySet())if(call(flow,pc,setter)&&flow.graph.dominates(pc,post)) {
            int[] args=flow.graph.arguments(pc);if(args.length!=2||!flow.at(pc,args[0]).equals(Collections.singleton(root)))continue;
            Integer value=flow.single(flow.at(pc,args[1]));if(factory(flow,value,factory))found.add(value);
        }
        require(found.size()<=1,"Ambiguous assembled child");return found.isEmpty()?null:found.iterator().next();
    }
    private static boolean append(DexIndex.Ref call) {
        return call.owner.equals(BUILDER)&&call.name.equals("append")&&call.type.endsWith(")Ljava/lang/StringBuilder;");
    }
    private static Map<String,List<Set<Integer>>> labels(BeanValueFlow flow)throws IOException {
        Map<String,List<Set<Integer>>> result=new LinkedHashMap<>();
        for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.graph.code[pc]&255)&&append(flow.graph.method.invokedAt(pc))) {
            int[] args=flow.graph.arguments(pc);if(args.length!=2)continue;String text=string(flow,flow.at(pc,args[1]));
            if(text==null)continue;Matcher label=LABEL.matcher(text);if(!label.find())continue;
            Set<Integer> builder=flow.at(pc,args[0]);Integer definition=flow.single(builder);
            if(definition==null||definition<0||(flow.graph.code[definition]&255)!=0x22
                    ||!flow.graph.method.typeAt(definition).equals(BUILDER))continue;
            Set<Integer> next=nextAppends(flow,pc,builder);if(next.size()!=1)continue;
            int valueAt=next.iterator().next();int[] valueArgs=flow.graph.arguments(valueAt);if(valueArgs.length!=2)continue;
            Set<Integer> values=flow.at(valueAt,valueArgs[1]);
            if(values.contains(-1)||values.stream().allMatch(value->value<0||literal(flow.graph.code[value]&255)))continue;
            result.computeIfAbsent(label.group(1),key->new ArrayList<>()).add(values);
        }
        return result;
    }
    private static Set<Integer> nextAppends(BeanValueFlow flow,int start,Set<Integer> builder)throws IOException {
        Set<Integer> seen=new HashSet<>(),result=new LinkedHashSet<>();ArrayDeque<Integer> queue=new ArrayDeque<>(flow.graph.edges.get(start));
        while(!queue.isEmpty()) {
            int pc=queue.removeFirst();if(!seen.add(pc))continue;
            if(BeanOrigins.invoke(flow.graph.code[pc]&255)&&append(flow.graph.method.invokedAt(pc))) {
                int[] args=flow.graph.arguments(pc);if(args.length>=1&&flow.at(pc,args[0]).equals(builder)){result.add(pc);continue;}
            }
            queue.addAll(flow.graph.edges.get(pc));
        }
        return result;
    }
    private static String string(BeanValueFlow flow,Set<Integer> values)throws IOException {
        Integer pc=flow.single(values);if(pc==null||pc<0)return null;int op=flow.graph.code[pc]&255;
        return op==0x1a||op==0x1b?flow.graph.method.stringAt(pc):null;
    }
    private static boolean literal(int op){return op>=0x12&&op<=0x1c;}
    static boolean keyValue(BeanValueFlow flow,Set<Integer> values,String key,String type)throws IOException {
        boolean found=false;
        for(int definition:flow.expanded(values)) {
            // These wire properties are forwarded directly. An inversion or
            // arithmetic rewrite would require an explicit conversion contract.
            if(definition<0||flow.arithmetic.containsKey(definition))return false;
            int op=flow.graph.code[definition]&255;if(literal(op))return false;
            if(!BeanOrigins.invoke(op))return false;
            DexIndex.Ref call=flow.graph.method.invokedAt(definition);int[] args=flow.graph.arguments(definition);
            if(!bundleOwner(call.owner)||args.length<2||!key.equals(string(flow,flow.at(definition,args[1])))
                    ||!bundle(flow,flow.at(definition,args[0]),0))return false;
            String getter=type.equals("I")?"getInt":type.equals("Z")?"getBoolean":type.equals("Ljava/lang/String;")?"getString"
                    :type.equals("Ljava/lang/CharSequence;")?"getCharSequence":"getParcelable";
            if(!call.name.equals(getter))return false;
            if(getter.equals("getParcelable")&&(args.length!=3||!classLiteral(flow,flow.at(definition,args[2]),type)))return false;
            if(!getter.equals("getParcelable")&&!call.type.endsWith(")"+type))return false;
            found=true;
        }
        return found;
    }
    private static boolean bundle(BeanValueFlow flow,Set<Integer> values,int depth)throws IOException {
        Integer pc=flow.single(values);if(pc==null||pc<0||depth>8)return false;int unit=flow.graph.code[pc],op=unit&255;
        if(op==0x54) {
            DexIndex.Ref field=flow.graph.method.fieldAt(pc);
            return field.owner.equals("Landroid/app/Notification;")&&field.name.equals("extras")&&field.type.equals(BUNDLE)
                    &&notification(flow,flow.at(pc,unit>>12));
        }
        if(!BeanOrigins.invoke(op))return false;DexIndex.Ref call=flow.graph.method.invokedAt(pc);int[] args=flow.graph.arguments(pc);
        if(!bundleOwner(call.owner)||args.length<2||!bundle(flow,flow.at(pc,args[0]),depth+1))return false;
        return call.name.equals("getBundle")&&call.type.equals("(Ljava/lang/String;)Landroid/os/Bundle;")
                ||call.name.equals("getParcelable")&&args.length==3&&classLiteral(flow,flow.at(pc,args[2]),BUNDLE);
    }
    private static boolean classLiteral(BeanValueFlow flow,Set<Integer> values,String type)throws IOException {
        Integer pc=flow.single(values);return pc!=null&&pc>=0&&(flow.graph.code[pc]&255)==0x1c&&flow.graph.method.typeAt(pc).equals(type);
    }
    private static boolean bundleOwner(String owner){return owner.equals(BUNDLE)||owner.equals("Landroid/os/BaseBundle;");}
    private static boolean notification(BeanValueFlow flow,Set<Integer> values)throws IOException {
        Integer pc=flow.single(values);if(pc==null||pc<0||!BeanOrigins.invoke(flow.graph.code[pc]&255))return false;
        DexIndex.Ref call=flow.graph.method.invokedAt(pc);int[] args=flow.graph.arguments(pc);
        return call.owner.equals(SBN)&&call.name.equals("getNotification")&&call.type.equals("()Landroid/app/Notification;")
                &&args.length==1&&sbnArgument(flow,flow.at(pc,args[0]));
    }
    private static boolean sbnArgument(BeanValueFlow flow,Set<Integer> values) {
        Integer definition=flow.single(values);int index=definition==null?-1:-2-definition;
        return index>=0&&index<flow.graph.frameTypes.size()&&flow.graph.frameTypes.get(index).equals(SBN);
    }
    private static boolean packageValue(BeanValueFlow flow,Set<Integer> values)throws IOException {
        Integer pc=flow.single(values);if(pc==null||pc<0||!BeanOrigins.invoke(flow.graph.code[pc]&255))return false;
        DexIndex.Ref call=flow.graph.method.invokedAt(pc);int[] args=flow.graph.arguments(pc);
        return call.owner.equals(SBN)&&call.name.equals("getPackageName")&&call.type.equals("()Ljava/lang/String;")
                &&args.length==1&&sbnArgument(flow,flow.at(pc,args[0]));
    }
    private static boolean timeoutValue(BeanValueFlow flow,Set<Integer> values,Set<Integer> seconds)throws IOException {
        Integer add=flow.single(values);if(add==null||add<0||seconds==null||!arithmeticOp(flow,add,0x9b))return false;
        Set<Integer> added=flow.arithmetic.get(add);if(added==null||added.size()!=2)return false;
        Integer multiply=null;boolean clock=false;
        for(int pc:added) {
            if(pc<0)return false;
            if(BeanOrigins.invoke(flow.graph.code[pc]&255)) {
                DexIndex.Ref call=flow.graph.method.invokedAt(pc);
                clock|=call.owner.equals("Ljava/lang/System;")&&call.name.equals("currentTimeMillis")&&call.type.equals("()J");
            } else if(arithmeticOp(flow,pc,0x9d))multiply=pc;
        }
        if(!clock||multiply==null)return false;Set<Integer> factors=flow.arithmetic.get(multiply);
        if(factors==null||factors.size()!=2)return false;boolean cast=false,scale=false;
        for(int pc:factors) {
            if(pc<0)return false;int op=flow.graph.code[pc]&255;
            if(op==0x81)cast=seconds.equals(flow.arithmetic.get(pc));
            else if(op==0x16)scale=(short)flow.graph.code[pc+1]==1000;
            else if(op==0x17)scale=BeanOrigins.integer(flow.graph.code,pc+1)==1000;
            else if(op==0x18)scale=BeanOrigins.integer(flow.graph.code,pc+1)==1000&&BeanOrigins.integer(flow.graph.code,pc+3)==0;
        }
        return cast&&scale;
    }
    private static boolean arithmeticOp(BeanValueFlow flow,int pc,int operation) {
        int op=flow.graph.code[pc]&255;return op==operation||op==operation+0x20;
    }
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
}
