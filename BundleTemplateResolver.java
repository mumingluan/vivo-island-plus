package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Reads the actual notification request-enum to layout maps, not native View enums. */
final class BundleTemplateResolver {
    static final List<String> ROLES=Collections.unmodifiableList(Arrays.asList("normal","buttons","custom"));
    static final class Plan {
        final DexIndex.Ref initializer, consumer, validator, shadeMap, islandMap, customParser, customRenderer;
        final boolean customButtons;
        final Map<String,Integer> values, literalPcs;
        Plan(DexIndex.Ref initializer,DexIndex.Ref consumer,DexIndex.Ref validator,DexIndex.Ref shadeMap,DexIndex.Ref islandMap,
                DexIndex.Ref customParser,DexIndex.Ref customRenderer,boolean customButtons,
                Map<String,Integer> values,Map<String,Integer> literalPcs) {
            this.initializer=initializer;this.consumer=consumer;this.shadeMap=shadeMap;this.islandMap=islandMap;
            this.validator=validator;
            this.customParser=customParser;this.customRenderer=customRenderer;this.customButtons=customButtons;
            this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));
            this.literalPcs=Collections.unmodifiableMap(new LinkedHashMap<>(literalPcs));
        }
        int value(String role) { return values.get(role); }
        String describe() { return "initializer="+initializer+"\nconsumer="+consumer+"\nvalidator="+validator+"\nshade="+shadeMap+"\nisland="+islandMap+"\nvalues="+values+"\ncustomButtons="+customButtons+"\ncustomParser="+customParser+"\ncustomRenderer="+customRenderer; }
    }
    private static final Object UNKNOWN=new Object();
    private static final class Number {
        final int value,pc;
        Number(int value,int pc){this.value=value;this.pc=pc;}
    }
    private static final class Table {
        boolean initialized;
        DexIndex.Ref field;
        final Map<Integer,Number> entries=new LinkedHashMap<>();
        final Map<Integer,Number> keys=new LinkedHashMap<>();
    }
    private BundleTemplateResolver() { }
    static Plan discover(DexIndex index,Map<String,Integer> resources) throws IOException {
        try { return checked(index,resources); }
        catch(IndexOutOfBoundsException|ArithmeticException malformed) {throw new IOException("Malformed request-template bytecode",malformed);}
    }
    private static Plan checked(DexIndex index,Map<String,Integer> resources) throws IOException {
        Set<String> expected=new LinkedHashSet<>();
        for(String side:new String[]{"shade","island"})for(String role:ROLES)expected.add(side+"."+role);
        require(resources.keySet().equals(expected),"Missing request-template resource roles");
        List<Integer> available=new ArrayList<>();
        boolean customButtons=resources.get("shade.buttons")==0&&resources.get("island.buttons")==0;
        for(String side:new String[]{"shade","island"})for(String role:ROLES) {
            int id=resources.get(side+"."+role);
            require(id!=0||role.equals("buttons")&&customButtons,"Missing request-template layout");
            if(id!=0)available.add(id);
        }
        require(new HashSet<>(available).size()==available.size(),"Aliased request-template resources");
        List<Plan> found=new ArrayList<>();
        for(DexIndex.MethodData init:index.methods.values()) {
            if(!init.isStatic()||!init.ref.name.equals("<clinit>")||!init.ref.type.equals("()V"))continue;
            Set<Integer> constants=new HashSet<>(init.facts().constants);
            for(DexIndex.Ref ref:init.facts().fields) {
                DexIndex.FieldData field=index.declaredFields.get(ref.key());
                if(field!=null&&field.isStatic()&&field.initialInteger!=null)constants.add(field.initialInteger);
            }
            if(!constants.containsAll(available))continue;
            Collection<Table> tables=interpret(index,init);
            List<Table> shade=matching(tables,resources,"shade"),island=matching(tables,resources,"island");
            require(shade.size()==1&&island.size()==1&&shade.get(0)!=island.get(0),"Missing/ambiguous request layout map");
            Table a=shade.get(0),b=island.get(0);
            Map<String,Integer> values=new LinkedHashMap<>(),pcs=new LinkedHashMap<>();
            for(String role:ROLES) {
                if(role.equals("buttons")&&customButtons)continue;
                Number first=key(a,resources.get("shade."+role)),second=key(b,resources.get("island."+role));
                require(first.value==second.value,"Shade/island request enums disagree for "+role);
                require(first.pc>=0&&second.pc>=0,"Request key is not a decoded integer literal");
                values.put(role,first.value);pcs.put("shade."+role,first.pc);pcs.put("island."+role,second.pc);
            }
            require(new HashSet<>(values.values()).size()==values.size(),"Aliased request templates");
            if(customButtons)values.put("buttons",values.get("custom"));
            List<DexIndex.MethodData> consumers=new ArrayList<>();
            for(DexIndex.MethodData method:index.methods.values()) {
                if(!method.ref.owner.equals(init.ref.owner)||!method.isStatic()
                        ||!method.ref.type.equals("(Landroid/service/notification/StatusBarNotification;I)I"))continue;
                DexIndex.Facts facts=method.facts();
                if(facts.text("notification.superx.template")&&facts.text("island.superx.template")
                        &&facts.fields.contains(a.field)&&facts.fields.contains(b.field)
                        &&(calls(facts,"Landroid/os/Bundle;","getInt","(Ljava/lang/String;I)I")
                            ||calls(facts,"Landroid/os/BaseBundle;","getInt","(Ljava/lang/String;I)I"))
                        &&calls(facts,"Ljava/util/Map;","getOrDefault","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))consumers.add(method);
            }
            require(consumers.size()==1,"Missing/ambiguous request map consumer");
            List<DexIndex.MethodData> validators=new ArrayList<>();
            for(DexIndex.Ref call:consumers.get(0).facts().calls) {
                DexIndex.MethodData candidate=index.get(call);
                if(candidate!=null&&candidate.isStatic()&&candidate.ref.owner.equals(init.ref.owner)&&candidate.ref.type.equals("(I)Z"))validators.add(candidate);
            }
            require(validators.size()==1,"Missing/ambiguous request enum validator");DexIndex.MethodData validator=validators.get(0);
            for(int value:new HashSet<>(values.values()))require(valid(validator,value),"Registered request enum rejected by actual consumer");
            int unknown=Integer.MIN_VALUE;while(values.containsValue(unknown))unknown++;
            require(!valid(validator,unknown),"Unknown request enum accepted by validator");
            DexIndex.Ref[] custom=customContract(index,resources);
            found.add(new Plan(init.ref,consumers.get(0).ref,validator.ref,a.field,b.field,custom[0],custom[1],customButtons,values,pcs));
        }
        require(found.size()==1,"Missing/ambiguous notification request-template contract: "+found.size());
        return found.get(0);
    }
    private static boolean valid(DexIndex.MethodData method,int input)throws IOException {
        int[] code=method.codeUnits();Integer[] registers=new Integer[method.registerCount()];
        require(method.incomingWords()==1&&registers.length>=1&&registers.length<=256&&code.length<=512,"Unbounded request enum validator");
        registers[registers.length-1]=input;Set<Integer> starts=new HashSet<>();
        for(int at=0;at<code.length;) {
            int op=code[at]&255,width=DexIndex.width(op);require(width>0&&at+width<=code.length&&!(op==0&&code[at]!=0),"Malformed enum validator");
            starts.add(at);at+=width;
        }
        for(int at=0,steps=0;steps++<256;) {
            require(starts.contains(at),"Validator branch is not an instruction boundary");int unit=code[at],op=unit&255,width=DexIndex.width(op);
            if(op>=0x12&&op<=0x15)registers[op==0x12?(unit>>8)&15:unit>>8]=constant(code,at);
            else if(op==0x01)registers[(unit>>8)&15]=registers[unit>>12];
            else if(op==0x02)registers[unit>>8]=registers[code[at+1]];
            else if(op==0x03)registers[code[at+1]]=registers[code[at+2]];
            else if(op>=0x32&&op<=0x3d) {
                Integer left=registers[op<=0x37?(unit>>8)&15:unit>>8],right=op<=0x37?registers[unit>>12]:Integer.valueOf(0);
                require(left!=null&&right!=null,"Unknown enum comparison operands");int a=left,b=right,kind=op<=0x37?op-0x32:op-0x38;
                boolean take=kind==0?a==b:kind==1?a!=b:kind==2?a<b:kind==3?a>=b:kind==4?a>b:a<=b;
                if(take){at=Math.addExact(at,(short)code[at+1]);continue;}
            } else if(op==0x28||op==0x29||op==0x2a) {
                int offset=op==0x28?(byte)(unit>>8):op==0x29?(short)code[at+1]:code[at+1]|code[at+2]<<16;
                at=Math.addExact(at,offset);continue;
            } else if(op==0x0f) {Integer value=registers[unit>>8];require(value!=null&&(value==0||value==1),"Unknown validator result");return value==1;}
            else require(op==0,"Unsupported request validator opcode: "+op);
            at+=width;
        }
        throw new IOException("Request enum validator exceeds execution bound");
    }
    private static List<Table> matching(Collection<Table> tables,Map<String,Integer> resources,String side)throws IOException {
        List<Table> result=new ArrayList<>();
        for(Table table:tables) {
            if(table.field==null||!table.initialized)continue;
            boolean all=true;for(String role:ROLES)if(resources.get(side+"."+role)!=0)all&=count(table,resources.get(side+"."+role))==1;
            if(all)result.add(table);
        }
        return result;
    }
    private static DexIndex.Ref[] customContract(DexIndex index,Map<String,Integer> resources)throws IOException {
        List<DexIndex.MethodData> parsers=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values())if(!method.isStatic()
                &&method.ref.type.equals("(Landroid/service/notification/StatusBarNotification;II)Landroid/widget/RemoteViews;")
                &&method.facts().text("notification.superx.customTemplate")&&method.facts().text("island.superx.customTemplate")
                &&calls(method.facts(),"Landroid/os/Bundle;","getParcelable","(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;"))parsers.add(method);
        require(parsers.size()==1,"Missing/ambiguous custom RemoteViews parser");DexIndex.MethodData parser=parsers.get(0);
        List<DexIndex.MethodData> renderers=new ArrayList<>();
        for(DexIndex.MethodData method:index.methods.values())if(!method.isStatic()
                &&method.ref.type.equals("(Landroid/service/notification/StatusBarNotification;I)V")
                &&index.extendsType(method.ref.owner,parser.ref.owner)
                &&calls(method.facts(),"Landroid/app/Notification$Builder;","createBigContentView","()Landroid/widget/RemoteViews;")) {
            boolean usesParser=false;for(DexIndex.Ref ref:method.facts().calls)usesParser|=ref.name.equals(parser.ref.name)
                    &&ref.type.equals(parser.ref.type)&&index.extendsType(ref.owner,parser.ref.owner);
            if(!usesParser)continue;
            int layouts=0;
            for(DexIndex.MethodData layout:index.methods.values())if(!layout.isStatic()&&layout.ref.owner.equals(method.ref.owner)&&layout.ref.type.equals("(I)I")) {
                Set<Integer> ids=new HashSet<>(layout.facts().constants);
                for(DexIndex.Ref ref:layout.facts().fields) {
                    DexIndex.FieldData field=index.declaredFields.get(ref.key());
                    if(field!=null&&field.isStatic()&&field.initialInteger!=null)ids.add(field.initialInteger);
                }
                if(ids.contains(resources.get("shade.custom"))&&ids.contains(resources.get("island.custom")))layouts++;
            }
            if(layouts==1)renderers.add(method);
        }
        require(renderers.size()==1,"Missing/ambiguous registered custom notification renderer");
        return new DexIndex.Ref[]{parser.ref,renderers.get(0).ref};
    }
    private static int count(Table table,int resource) {int n=0;for(Number value:table.entries.values())if(value.value==resource)++n;return n;}
    private static Number key(Table table,int resource)throws IOException {
        Number result=null;
        for(Map.Entry<Integer,Number> entry:table.entries.entrySet())if(entry.getValue().value==resource) {
            require(result==null,"Repeated layout resource in request map");result=table.keys.get(entry.getKey());
        }
        require(result!=null,"Request map lacks layout");return result;
    }
    private static Collection<Table> interpret(DexIndex index,DexIndex.MethodData method)throws IOException {
        int[] code=method.codeUnits();require(code.length>0&&code.length<=8192&&method.incomingWords()==0,"Unbounded request map initializer");
        Object[] registers=new Object[method.registerCount()];require(registers.length>0&&registers.length<=256,"Invalid initializer register count");
        Arrays.fill(registers,UNKNOWN);Object pending=UNKNOWN;
        Map<DexIndex.Ref,Table> published=new LinkedHashMap<>();
        Set<Table> allocated=Collections.newSetFromMap(new IdentityHashMap<Table,Boolean>());
        boolean returned=false;
        for(int at=0;at<code.length;) {
            int unit=code[at],op=unit&255,width=DexIndex.width(op);
            require(width>0&&at+width<=code.length&&!(op==0&&unit!=0),"Malformed initializer instruction");
            if(op==0x22) {
                require(method.typeAt(at).equals("Ljava/util/HashMap;"),"Unexpected map initializer allocation");
                Table table=new Table();allocated.add(table);registers[unit>>8]=table;pending=UNKNOWN;
            } else if(op>=0x12&&op<=0x15) {
                registers[op==0x12?(unit>>8)&15:unit>>8]=new Number(constant(code,at),at);pending=UNKNOWN;
            } else if(op==0x60) {
                DexIndex.FieldData field=index.declaredFields.get(method.fieldAt(at).key());
                require(field!=null&&field.isStatic()&&field.ref.type.equals("I"),"Unknown integer resource field");
                registers[unit>>8]=field.initialInteger==null?UNKNOWN:new Number(field.initialInteger,-1);pending=UNKNOWN;
            } else if(op==0x62) {
                Table table=published.get(method.fieldAt(at));require(table!=null,"Read of unpublished map");
                registers[unit>>8]=table;pending=UNKNOWN;
            } else if(op==0x69) {
                DexIndex.Ref field=method.fieldAt(at);Object value=registers[unit>>8];
                DexIndex.FieldData declaration=index.declaredFields.get(field.key());
                require(declaration!=null&&declaration.isStatic()&&field.owner.equals(method.ref.owner)
                        &&field.type.equals("Ljava/util/Map;")&&value instanceof Table,"Unexpected static map publication");
                Table table=(Table)value;require(table.initialized&&table.field==null&&!published.containsKey(field),"Repeated/uninitialized map publication");
                table.field=field;published.put(field,table);pending=UNKNOWN;
            } else if(invoke(op)) {
                DexIndex.Ref call=method.invokedAt(at);int[] args=arguments(code,at);pending=UNKNOWN;
                if(call.owner.equals("Ljava/util/HashMap;")&&call.name.equals("<init>")&&call.type.equals("()V")&&args.length==1) {
                    require(registers[args[0]] instanceof Table,"Constructor on unknown map");Table table=(Table)registers[args[0]];
                    require(!table.initialized,"Repeated map constructor");table.initialized=true;
                } else if(call.owner.equals("Ljava/lang/Integer;")&&call.name.equals("valueOf")
                        &&call.type.equals("(I)Ljava/lang/Integer;")&&args.length==1)pending=registers[args[0]];
                else if((call.owner.equals("Ljava/util/Map;")||call.owner.equals("Ljava/util/HashMap;"))&&call.name.equals("put")
                        &&call.type.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")&&args.length==3) {
                    require(registers[args[0]] instanceof Table,"Put on unknown map");Table table=(Table)registers[args[0]];
                    require(table.initialized&&registers[args[1]] instanceof Number,"Unknown map key");
                    Number key=(Number)registers[args[1]],value=registers[args[2]] instanceof Number?(Number)registers[args[2]]:new Number(Integer.MIN_VALUE,-1);
                    require(!table.keys.containsKey(key.value),"Duplicate request map key");table.keys.put(key.value,key);table.entries.put(key.value,value);
                } else {
                    // Other primitive computations (for example a color table) do
                    // not supply resource values. Never let a tracked map escape.
                    for(int arg:args)require(!(registers[arg] instanceof Table),"Unknown initializer call uses request map");
                }
            } else if(op==0x0a||op==0x0c) {registers[unit>>8]=pending;pending=UNKNOWN;}
            else if(op==0x01||op==0x07) {registers[(unit>>8)&15]=registers[unit>>12];pending=UNKNOWN;}
            else if(op==0x02||op==0x08) {registers[unit>>8]=registers[code[at+1]];pending=UNKNOWN;}
            else if(op==0x03||op==0x09) {registers[code[at+1]]=registers[code[at+2]];pending=UNKNOWN;}
            else if(op==0x0e) {require(at+width==code.length,"Code after map initializer return");returned=true;}
            else require(op==0,"Unsupported request map initializer opcode: "+op);
            at+=width;
        }
        require(returned&&published.size()==allocated.size(),"Incomplete request map initializer");return published.values();
    }
    private static boolean calls(DexIndex.Facts facts,String owner,String name,String type) {
        return facts.calls.contains(new DexIndex.Ref(owner,name,type));
    }
    private static boolean invoke(int op){return op>=0x6e&&op<=0x72||op>=0x74&&op<=0x78;}
    private static int[] arguments(int[] code,int at)throws IOException {
        int op=code[at]&255,count=op>=0x74?code[at]>>8:code[at]>>12;
        require(count<=5||op>=0x74,"Invalid map call register count");int[] args=new int[count];
        for(int i=0;i<count;++i)args[i]=op>=0x74?code[at+2]+i:i==4?(code[at]>>8)&15:(code[at+2]>>(4*i))&15;
        return args;
    }
    private static int constant(int[] code,int at) {
        int op=code[at]&255;
        return op==0x12?(short)code[at]>>12:op==0x13?(short)code[at+1]:op==0x14?code[at+1]|code[at+2]<<16:code[at+1]<<16;
    }
    private static void require(boolean value,String message)throws IOException {if(!value)throw new IOException(message);}
}
