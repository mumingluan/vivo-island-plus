package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Crosses DTO -> fluent builder -> runtime model -> profile/gesture/keyguard use. */
final class BeanModelResolver {
    private static final String SBN="Landroid/service/notification/StatusBarNotification;";
    private static final String KEYGUARD_TEXT="mKeyguardShowing continue getId = ";
    static final class Plan {
        final DexIndex.Ref consumer,constructor,materializer,gesture,keyguard;
        final String model;
        final Map<String,DexIndex.Ref> fields,setters,modelFields;
        final Set<DexIndex.Ref> unreadDto,unreadModel;
        Plan(DexIndex.Ref consumer,DexIndex.Ref constructor,DexIndex.Ref materializer,String model,DexIndex.Ref gesture,DexIndex.Ref keyguard,
             Map<String,DexIndex.Ref> fields,Map<String,DexIndex.Ref> setters,Map<String,DexIndex.Ref> modelFields,
             Set<DexIndex.Ref> unreadDto,Set<DexIndex.Ref> unreadModel) {
            this.consumer=consumer;this.constructor=constructor;this.materializer=materializer;this.model=model;this.gesture=gesture;this.keyguard=keyguard;
            this.fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));this.setters=Collections.unmodifiableMap(new LinkedHashMap<>(setters));
            this.modelFields=Collections.unmodifiableMap(new LinkedHashMap<>(modelFields));
            this.unreadDto=Collections.unmodifiableSet(new LinkedHashSet<>(unreadDto));this.unreadModel=Collections.unmodifiableSet(new LinkedHashSet<>(unreadModel));
        }
        String describe(){return "consumer="+consumer+"\nmodel="+model+"\nconstructor="+constructor+"\nmaterializer="+materializer+"\ngesture="+gesture+"\nkeyguard="+keyguard+
            "\nfields="+fields+"\nsetters="+setters+"\nmodelFields="+modelFields+"\nunreadDto="+unreadDto+"\nunreadModel="+unreadModel;}
    }
    private static final class Copy {
        final DexIndex.Ref constructor;final Map<DexIndex.Ref,DexIndex.Ref> fields;
        Copy(DexIndex.Ref constructor,Map<DexIndex.Ref,DexIndex.Ref> fields){this.constructor=constructor;this.fields=fields;}
    }
    private static final class Gate {
        final DexIndex.Ref method,field;Gate(DexIndex.Ref method,DexIndex.Ref field){this.method=method;this.field=field;}
    }
    private BeanModelResolver() { }
    static Plan discover(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties)throws IOException {
        try{return checked(index,core,properties);}
        catch(IndexOutOfBoundsException|ArithmeticException malformed){throw new IOException("Malformed runtime-model contract",malformed);}
    }
    private static Plan checked(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties)throws IOException {
        List<Plan> plans=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values()) {
            if(!method.ref.type.equals("("+core.bean+")V"))continue;
            DexIndex.Facts facts=method.facts();boolean userApi=false,ongoingApi=false;
            for(DexIndex.Ref ref:facts.calls) {
                userApi|=ref.owner.equals("Landroid/os/UserHandle;")&&ref.name.equals("getIdentifier")&&ref.type.equals("()I");
                ongoingApi|=ref.owner.equals(SBN)&&ref.name.equals("isOngoing")&&ref.type.equals("()Z");
            }
            if(!userApi||!ongoingApi)continue;
            Map<DexIndex.Ref,DexIndex.Ref> fluent=new LinkedHashMap<>();Set<String> builders=new LinkedHashSet<>();
            for(DexIndex.Ref ref:facts.calls)if(ref.type.endsWith(")"+ref.owner)) {
                DexIndex.Ref field=BeanResolver.fluentField(index,ref);if(field!=null){fluent.put(ref,field);builders.add(ref.owner);}
            }
            if(fluent.size()<3)continue;require(builders.size()==1,"Ambiguous mutable model builder");String builder=builders.iterator().next();
            BeanValueFlow flow=new BeanValueFlow(method,fluent.keySet());
            Integer builderDefinition=null;Map<DexIndex.Ref,Set<DexIndex.Ref>> projected=new LinkedHashMap<>();
            DexIndex.Ref userSlot=null,ongoingSlot=null;
            for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.graph.code[pc]&255)) {
                DexIndex.Ref ref=method.invokedAt(pc),slot=fluent.get(ref);if(slot==null)continue;
                int[] args=flow.graph.arguments(pc);if(args.length<2)continue;
                Integer receiver=flow.single(flow.at(pc,args[0]));if(receiver==null||receiver<0||!BeanOrigins.invoke(flow.graph.code[receiver]&255))continue;
                DexIndex.Ref factory=method.invokedAt(receiver);DexIndex.MethodData factoryMethod=index.get(factory);
                if(!factory.type.equals("()"+builder)||factoryMethod==null||!factoryMethod.isStatic())continue;
                if(builderDefinition==null)builderDefinition=receiver;require(builderDefinition.equals(receiver),"Multiple consumer model instances");
                Integer source=flow.single(flow.at(pc,args[1]));if(source==null||source<0||!BeanOrigins.invoke(flow.graph.code[source]&255))continue;
                DexIndex.Ref sourceCall=method.invokedAt(source);
                if(sourceCall.owner.equals(core.bean)) {
                    int[] sourceArgs=flow.graph.arguments(source);
                    if(sourceArgs.length==1&&argument(flow,flow.at(source,sourceArgs[0]),core.bean)) {
                        DexIndex.Ref dto=BeanResolver.readField(index,sourceCall);
                        projected.computeIfAbsent(dto,key->new LinkedHashSet<>()).add(slot);
                    }
                }
                if(sourceCall.owner.equals(core.right)) {
                    int[] sourceArgs=flow.graph.arguments(source);
                    Integer child=sourceArgs.length==1?flow.single(flow.at(source,sourceArgs[0])):null;
                    if(child!=null&&child>=0&&BeanOrigins.invoke(flow.graph.code[child]&255)) {
                        DexIndex.Ref childGetter=method.invokedAt(child);int[] childArgs=flow.graph.arguments(child);
                        if(childGetter.owner.equals(core.bean)&&childGetter.type.equals("()"+core.right)&&childArgs.length==1
                                &&argument(flow,flow.at(child,childArgs[0]),core.bean)
                                &&BeanResolver.readField(index,childGetter).equals(core.fields.get("right"))) {
                            DexIndex.Ref dto=BeanResolver.readField(index,sourceCall);
                            if(dto.equals(properties.fields.get("right.waveState")))projected.computeIfAbsent(dto,key->new LinkedHashSet<>()).add(slot);
                        }
                    }
                }
                if(publicUser(flow,source,core,index)){require(userSlot==null||userSlot.equals(slot),"Ambiguous profile projection");userSlot=slot;}
                if(sourceCall.owner.equals(SBN)&&sourceCall.name.equals("isOngoing")&&sourceCall.type.equals("()Z")) {
                    int[] sourceArgs=flow.graph.arguments(source);
                    if(sourceArgs.length==1&&sbn(flow,flow.at(source,sourceArgs[0]),core,index)) {
                        require(ongoingSlot==null||ongoingSlot.equals(slot),"Ambiguous ongoing projection");ongoingSlot=slot;
                    }
                }
            }
            require(builderDefinition!=null&&userSlot!=null&&ongoingSlot!=null,"Missing public profile/ongoing model projection");
            String model=method.invokedAt(builderDefinition).owner;Copy copy=copy(index,model,builder);
            DexIndex.Ref materializer=materializer(index,copy.constructor,model,builder);
            require(copy.fields.containsKey(userSlot)&&copy.fields.containsKey(ongoingSlot),"Projected fields are absent from model constructor");
            DexIndex.Ref userModel=copy.fields.get(userSlot),ongoingModel=copy.fields.get(ongoingSlot);
            Gate gesture=gesture(index,model,ongoingModel),keyguard=keyguard(index,model);
            Map<String,DexIndex.Ref> modelFields=new LinkedHashMap<>();modelFields.put("userId",userModel);modelFields.put("scrollRemove",gesture.field);modelFields.put("keyguardShow",keyguard.field);
            Map<String,DexIndex.Ref> fields=new LinkedHashMap<>(),setters=new LinkedHashMap<>();
            for(Map.Entry<String,DexIndex.Ref> role:modelFields.entrySet()) {
                List<DexIndex.Ref> sources=new ArrayList<>();
                for(Map.Entry<DexIndex.Ref,Set<DexIndex.Ref>> projection:projected.entrySet())
                    for(DexIndex.Ref slot:projection.getValue())if(role.getValue().equals(copy.fields.get(slot)))sources.add(projection.getKey());
                require(sources.size()==1,"Missing/ambiguous DTO projection for "+role.getKey());DexIndex.Ref dto=sources.get(0);
                require(!core.fields.containsValue(dto)&&!properties.fields.containsValue(dto)&&!fields.containsValue(dto),"Runtime role aliases a known property");
                fields.put(role.getKey(),dto);setters.put(role.getKey(),setter(index,dto));
            }
            // Reuse the already proved DTO -> builder -> model path for card
            // transport. These semantic roles are module-owned, not OEM names.
            for(String role:Arrays.asList("templateCard","sbn","priority","right.waveState")) {
                DexIndex.Ref dto=core.fields.containsKey(role)?core.fields.get(role):properties.fields.get(role);
                Set<DexIndex.Ref> targets=new LinkedHashSet<>();
                for(DexIndex.Ref slot:projected.getOrDefault(dto,Collections.emptySet()))
                    if(copy.fields.containsKey(slot))targets.add(copy.fields.get(slot));
                require(targets.size()==1,"Missing/ambiguous card runtime projection: "+role);
                modelFields.put(role,targets.iterator().next());
            }
            Set<DexIndex.Ref> tracked=new LinkedHashSet<>();
            for(DexIndex.Ref field:copy.fields.values())if(field.type.equals("I")||field.type.equals("Z"))tracked.add(field);
            Set<DexIndex.Ref> reads=new HashSet<>();
            // Decode reads without caching facts for every unrelated plugin method.
            for(DexIndex.MethodData body:index.methods.values())reads.addAll(body.fieldReads(tracked));
            Set<DexIndex.Ref> unreadModel=new LinkedHashSet<>(),unreadDto=new LinkedHashSet<>();int booleans=0,integers=0;
            for(Map.Entry<DexIndex.Ref,Set<DexIndex.Ref>> projection:projected.entrySet())for(DexIndex.Ref slot:projection.getValue()) {
                DexIndex.Ref target=copy.fields.get(slot);if(target==null||!tracked.contains(target)||reads.contains(target))continue;
                // Do not classify a DTO source as unused if any of its other
                // forwarded runtime slots has a real reader.
                boolean onlyUnread=true;
                for(DexIndex.Ref other:projection.getValue())if(copy.fields.containsKey(other)&&reads.contains(copy.fields.get(other)))onlyUnread=false;
                unreadModel.add(target);if(onlyUnread)unreadDto.add(projection.getKey());
            }
            for(DexIndex.Ref field:unreadModel)if(field.type.equals("Z"))booleans++;else if(field.type.equals("I"))integers++;
            require(booleans==2&&integers==1&&unreadDto.size()==unreadModel.size(),"Write-only primitive model capability changed");
            for(DexIndex.Ref unread:unreadDto)require(!fields.containsValue(unread)&&!core.fields.containsValue(unread)&&!properties.fields.containsValue(unread),"An active DTO role forwards to a write-only slot");
            plans.add(new Plan(method.ref,copy.constructor,materializer,model,gesture.method,keyguard.method,fields,setters,modelFields,unreadDto,unreadModel));
        }
        require(plans.size()==1,"Missing/ambiguous DTO/runtime-model bridge: "+plans.size());return plans.get(0);
    }
    private static DexIndex.Ref setter(DexIndex index,DexIndex.Ref field)throws IOException {
        Set<DexIndex.Ref> found=new LinkedHashSet<>();
        for(DexIndex.MethodData method:index.methods.values())if(!method.isStatic()&&method.ref.owner.equals(field.owner)
                &&method.ref.type.equals("("+field.type+")V")&&!method.ref.name.startsWith("<")&&method.facts().fields.contains(field)) {
            if(BeanResolver.assignedField(index,method.ref).equals(field))found.add(method.ref);
        }
        require(found.size()==1,"Missing/ambiguous runtime DTO setter");return found.iterator().next();
    }
    private static Copy copy(DexIndex index,String model,String builder)throws IOException {
        List<Copy> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values())if(method.ref.owner.equals(model)&&method.ref.name.equals("<init>")&&method.ref.type.equals("("+builder+")V")) {
            BeanValueFlow flow=new BeanValueFlow(method);Map<DexIndex.Ref,DexIndex.Ref> fields=new LinkedHashMap<>();
            for(int pc:flow.states.keySet()) {
                int unit=flow.graph.code[pc],op=unit&255;if(op<0x59||op>0x5f)continue;
                DexIndex.Ref target=method.fieldAt(pc);Integer source=flow.single(flow.at(pc,(unit>>8)&15));
                if(!target.owner.equals(model)||source==null||source<0||!argument(flow,flow.at(pc,unit>>12),model))continue;
                int read=flow.graph.code[source],get=read&255;DexIndex.Ref from;
                if(get>=0x52&&get<=0x58&&argument(flow,flow.at(source,read>>12),builder))from=method.fieldAt(source);
                else if(BeanOrigins.invoke(get)) {
                    DexIndex.Ref access=method.invokedAt(source);DexIndex.MethodData accessor=index.get(access);int[] args=flow.graph.arguments(source);
                    if(accessor==null||!accessor.isStatic()||args.length!=1||!argument(flow,flow.at(source,args[0]),builder))continue;
                    from=BeanResolver.staticReadField(index,access);
                } else continue;
                if(!from.owner.equals(builder)||!from.type.equals(target.type))continue;
                require(index.declaredFields.containsKey(from.key())&&index.declaredFields.containsKey(target.key()),"Missing model copy field declaration");
                require(!fields.containsKey(from)&&!fields.containsValue(target),"Aliased model constructor fields");fields.put(from,target);
            }
            if(fields.size()>=3)found.add(new Copy(method.ref,fields));
        }
        require(found.size()==1,"Missing/ambiguous builder-to-model constructor: "+model+" / "+builder+" / "+found.size());return found.get(0);
    }
    private static boolean argument(BeanValueFlow flow,Set<Integer> values,String type) {
        Integer definition=flow.single(values);int index=definition==null?-1:-2-definition;
        return index>=0&&index<flow.graph.frameTypes.size()&&flow.graph.frameTypes.get(index).equals(type);
    }
    private static DexIndex.Ref materializer(DexIndex index,DexIndex.Ref constructor,String model,String builder)throws IOException {
        List<DexIndex.Ref> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values())if(!method.isStatic()&&method.ref.owner.equals(builder)&&method.ref.type.equals("()"+model)) {
            BeanValueFlow flow=new BeanValueFlow(method);boolean valid=true,returned=false;
            for(int pc:flow.states.keySet())if((flow.graph.code[pc]&255)==0x11) {
                Integer object=flow.single(flow.at(pc,flow.graph.code[pc]>>8));boolean initialized=false;
                if(object==null||object<0||(flow.graph.code[object]&255)!=0x22||!method.typeAt(object).equals(model)){valid=false;break;}
                for(int at:flow.states.keySet())if(BeanOrigins.invoke(flow.graph.code[at]&255)&&method.invokedAt(at).equals(constructor)) {
                    int[] args=flow.graph.arguments(at);
                    if(args.length==2&&Collections.singleton(object).equals(flow.at(at,args[0]))&&argument(flow,flow.at(at,args[1]),builder)&&flow.graph.dominates(at,pc))initialized=true;
                }
                valid&=initialized;returned=true;
            }
            if(valid&&returned)found.add(method.ref);
        }
        require(found.size()==1,"Missing/ambiguous model materialization");return found.get(0);
    }
    private static boolean sbn(BeanValueFlow flow,Set<Integer> values,BeanResolver.Plan core,DexIndex index)throws IOException {
        Integer pc=flow.single(values);if(pc==null||pc<0||!BeanOrigins.invoke(flow.graph.code[pc]&255))return false;
        DexIndex.Ref ref=flow.graph.method.invokedAt(pc);int[] args=flow.graph.arguments(pc);
        return ref.owner.equals(core.bean)&&ref.type.equals("()"+SBN)&&args.length==1&&argument(flow,flow.at(pc,args[0]),core.bean)
                &&BeanResolver.readField(index,ref).equals(core.fields.get("sbn"));
    }
    private static boolean publicUser(BeanValueFlow flow,int pc,BeanResolver.Plan core,DexIndex index)throws IOException {
        DexIndex.Ref ref=flow.graph.method.invokedAt(pc);int[] args=flow.graph.arguments(pc);
        if(!ref.owner.equals("Landroid/os/UserHandle;")||!ref.name.equals("getIdentifier")||!ref.type.equals("()I")||args.length!=1)return false;
        Integer user=flow.single(flow.at(pc,args[0]));if(user==null||user<0||!BeanOrigins.invoke(flow.graph.code[user]&255))return false;
        DexIndex.Ref source=flow.graph.method.invokedAt(user);int[] frame=flow.graph.arguments(user);
        return source.owner.equals(SBN)&&source.name.equals("getUser")&&source.type.equals("()Landroid/os/UserHandle;")&&frame.length==1
                &&sbn(flow,flow.at(user,frame[0]),core,index);
    }
    private static Gate gesture(DexIndex index,String model,DexIndex.Ref ongoing)throws IOException {
        List<Gate> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values())if(method.ref.type.equals("()I")&&method.facts().text("away from center location")) {
            Set<DexIndex.Ref> fields=new LinkedHashSet<>();boolean angle=false,degrees=false;
            for(DexIndex.Ref ref:method.facts().calls) {
                angle|=ref.owner.equals("Ljava/lang/Math;")&&ref.name.equals("atan2")&&ref.type.equals("(DD)D");
                degrees|=ref.owner.equals("Ljava/lang/Math;")&&ref.name.equals("toDegrees")&&ref.type.equals("(D)D");
                if(ref.owner.equals(model)&&ref.type.equals("()Z"))fields.add(BeanResolver.readField(index,ref));
            }
            if(angle&&degrees&&fields.remove(ongoing)&&fields.size()==1) {
                DexIndex.Ref permission=fields.iterator().next();BeanValueFlow flow=new BeanValueFlow(method);int gates=0;
                for(int pc:flow.states.keySet()) {
                    int unit=flow.graph.code[pc],op=unit&255;if(op!=0x38&&op!=0x39)continue;
                    Integer definition=flow.single(flow.at(pc,unit>>8));
                    if(definition==null||definition<0||!BeanOrigins.invoke(flow.graph.code[definition]&255))continue;
                    DexIndex.Ref call=method.invokedAt(definition);
                    if(!call.owner.equals(model)||!call.type.equals("()Z")||!BeanResolver.readField(index,call).equals(permission))continue;
                    int equal=op==0x38?pc+(short)flow.graph.code[pc+1]:pc+2,other=op==0x38?pc+2:pc+(short)flow.graph.code[pc+1];
                    if(!reachesAngle(flow,equal,pc,definition)&&reachesAngle(flow,other,pc,definition))gates++;
                }
                if(gates==1)found.add(new Gate(method.ref,permission));
            }
        }
        require(found.size()==1,"Missing/ambiguous gesture removal permission");return found.get(0);
    }
    private static boolean reachesAngle(BeanValueFlow flow,int start,int condition,int getter)throws IOException {
        Set<Integer> seen=new HashSet<>();ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(start);
        while(!queue.isEmpty()) {
            int pc=queue.removeFirst();if(pc==condition||pc==getter||!seen.add(pc))continue;
            if(BeanOrigins.invoke(flow.graph.code[pc]&255)) {
                DexIndex.Ref ref=flow.graph.method.invokedAt(pc);
                if(ref.owner.equals("Ljava/lang/Math;")&&ref.name.equals("atan2")&&ref.type.equals("(DD)D"))return true;
            }
            queue.addAll(flow.graph.edges.get(pc));
        }
        return false;
    }
    private static Gate keyguard(DexIndex index,String model)throws IOException {
        List<Gate> found=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values())if(method.ref.type.endsWith(")V")
                &&method.ref.type.matches(".*Ljava/util/(?:HashMap|Map|LinkedHashMap|List|ArrayList|Collection|Set);.*")&&method.facts().text(KEYGUARD_TEXT)) {
            BeanValueFlow flow=new BeanValueFlow(method);Map<Integer,DexIndex.Ref> anchors=anchorLogs(flow);
            require(anchors.size()==1,"Missing/ambiguous keyguard diagnostic sink");int anchor=anchors.keySet().iterator().next();DexIndex.Ref logger=anchors.get(anchor);
            for(int pc:flow.states.keySet()) {
                int unit=flow.graph.code[pc],op=unit&255;if(op!=0x38&&op!=0x39)continue;
                Integer definition=flow.single(flow.at(pc,unit>>8));if(definition==null||definition<0||!BeanOrigins.invoke(flow.graph.code[definition]&255))continue;
                DexIndex.Ref getter=method.invokedAt(definition);if(!getter.owner.equals(model)||!getter.type.equals("()Z"))continue;
                int equal=op==0x38?pc+(short)flow.graph.code[pc+1]:pc+2,other=op==0x38?pc+2:pc+(short)flow.graph.code[pc+1];
                Set<Integer> falseLogs=firstLogs(flow,equal,logger,pc,definition),trueLogs=firstLogs(flow,other,logger,pc,definition);
                if(falseLogs.equals(Collections.singleton(anchor))&&!trueLogs.contains(anchor))found.add(new Gate(method.ref,BeanResolver.readField(index,getter)));
            }
        }
        require(found.size()==1,"Missing/ambiguous keyguard permission polarity");return found.get(0);
    }
    private static Map<Integer,DexIndex.Ref> anchorLogs(BeanValueFlow flow)throws IOException {
        Set<Integer> builders=new HashSet<>();Map<Integer,DexIndex.Ref> result=new LinkedHashMap<>();
        for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.graph.code[pc]&255)) {
            DexIndex.Ref call=flow.graph.method.invokedAt(pc);int[] args=flow.graph.arguments(pc);
            if(call.owner.equals("Ljava/lang/StringBuilder;")&&call.name.equals("append")&&args.length==2) {
                String text=string(flow,flow.at(pc,args[1]));Integer builder=flow.single(flow.at(pc,args[0]));
                if(text!=null&&text.contains(KEYGUARD_TEXT)&&builder!=null&&builder>=0)builders.add(builder);
            }
        }
        for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.graph.code[pc]&255)) {
            DexIndex.Ref call=flow.graph.method.invokedAt(pc);int[] args=flow.graph.arguments(pc);
            if(!call.type.equals("(Ljava/lang/String;Ljava/lang/String;)V")||args.length!=2)continue;
            Integer text=flow.single(flow.at(pc,args[1]));if(text==null||text<0||!BeanOrigins.invoke(flow.graph.code[text]&255))continue;
            DexIndex.Ref conversion=flow.graph.method.invokedAt(text);int[] convertArgs=flow.graph.arguments(text);
            if(conversion.owner.equals("Ljava/lang/StringBuilder;")&&conversion.name.equals("toString")&&conversion.type.equals("()Ljava/lang/String;")&&convertArgs.length==1) {
                Integer builder=flow.single(flow.at(text,convertArgs[0]));if(builders.contains(builder))result.put(pc,call);
            }
        }
        return result;
    }
    private static Set<Integer> firstLogs(BeanValueFlow flow,int start,DexIndex.Ref logger,int condition,int getter)throws IOException {
        Set<Integer> found=new LinkedHashSet<>(),seen=new HashSet<>();ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(start);
        while(!queue.isEmpty()) {
            int pc=queue.removeFirst();if(pc==condition||pc==getter||!seen.add(pc))continue;
            if(BeanOrigins.invoke(flow.graph.code[pc]&255)) {
                DexIndex.Ref ref=flow.graph.method.invokedAt(pc);
                if(ref.owner.equals(logger.owner)&&ref.type.equals(logger.type)){found.add(pc);continue;}
            }
            queue.addAll(flow.graph.edges.get(pc));
        }
        return found;
    }
    private static String string(BeanValueFlow flow,Set<Integer> values)throws IOException {
        Integer pc=flow.single(values);if(pc==null||pc<0)return null;int op=flow.graph.code[pc]&255;
        return op==0x1a||op==0x1b?flow.graph.method.stringAt(pc):null;
    }
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
}
