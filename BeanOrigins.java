package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Bounded forward provenance with control-flow joins; conflicting definitions become unknown. */
final class BeanOrigins {
    static final class Value {
        final String kind,text;
        final int index;
        Value(String kind,int index,String text){this.kind=kind;this.index=index;this.text=text;}
        @Override public boolean equals(Object o){return o instanceof Value&&kind.equals(((Value)o).kind)&&index==((Value)o).index&&Objects.equals(text,((Value)o).text);}
        @Override public int hashCode(){return Objects.hash(kind,index,text);}
        @Override public String toString(){return kind+":"+index+":"+text;}
    }
    final DexIndex.MethodData method;
    final int[] code;
    final int registers;
    final Map<Integer,Value[]> states=new LinkedHashMap<>();
    final Map<Integer,List<Integer>> edges=new LinkedHashMap<>(),predecessors=new LinkedHashMap<>();
    final List<String> frameTypes;
    final List<Integer> frameWordOffsets;
    final Set<Integer> instructionStarts;
    BeanOrigins(DexIndex.MethodData method)throws IOException {
        this.method=method;code=method.codeUnits();registers=method.registerCount();
        require(registers>0&&registers<=256&&code.length>0&&code.length<=32768,"Unbounded DEX provenance method");
        frameTypes=new ArrayList<>();if(!method.isStatic())frameTypes.add(method.ref.owner);frameTypes.addAll(parameters(method.ref.type));
        frameWordOffsets=wordOffsets(frameTypes);
        int words=0;for(String type:frameTypes)words+=wide(type)?2:1;
        require(words==method.incomingWords()&&words<=registers,"DEX incoming register/prototype disagreement");
        Set<Integer> starts=starts(code);instructionStarts=Collections.unmodifiableSet(starts);
        // Data-payload alignment may contain unreachable NOPs after a return.
        // Validate successors only on paths from entry/handlers; a reachable
        // fall-through into payload data is still rejected.
        ArrayDeque<Integer> reachable=new ArrayDeque<>();reachable.add(0);reachable.addAll(method.exceptionHandlers());
        while(!reachable.isEmpty()) {
            int pc=reachable.removeFirst();require(starts.contains(pc),"Entry/handler is not an instruction boundary");
            if(edges.containsKey(pc))continue;List<Integer> next=successors(pc,starts);edges.put(pc,next);reachable.addAll(next);
        }
        for(Map.Entry<Integer,List<Integer>> e:edges.entrySet())for(int target:e.getValue())predecessors.computeIfAbsent(target,k->new ArrayList<>()).add(e.getKey());
        Value[] initial=new Value[registers+1];
        for(int i=0;i<frameTypes.size();i++)initial[registers-words+frameWordOffsets.get(i)]=new Value("arg",i,frameTypes.get(i));
        ArrayDeque<Integer> queue=new ArrayDeque<>();merge(0,initial,queue);
        // A handler may receive exceptional values from many instructions.
        // Seed it with unknown registers instead of inventing normal-path origins.
        for(int pc:method.exceptionHandlers()){require(starts.contains(pc),"Handler is not an instruction boundary");merge(pc,new Value[registers+1],queue);}
        int iterations=0;
        while(!queue.isEmpty()) {
            require(++iterations<=code.length*32,"DEX provenance does not converge");int pc=queue.removeFirst();
            Value[] output=transfer(pc,states.get(pc));for(int next:edges.get(pc))merge(next,output,queue);
        }
    }
    Value at(int pc,int register)throws IOException {require(register>=0&&register<registers,"Invalid operand register");Value[] state=states.get(pc);return state==null?null:state[register];}
    int[] arguments(int pc)throws IOException {return arguments(code,pc);}
    boolean dominates(int anchor,int target)throws IOException {
        require(states.containsKey(anchor)&&states.containsKey(target),"Dominance endpoint is unreachable");if(anchor==target)return true;
        Set<Integer> visited=new HashSet<>();ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(0);queue.addAll(method.exceptionHandlers());
        while(!queue.isEmpty()) {int pc=queue.removeFirst();if(pc==anchor||!visited.add(pc))continue;if(pc==target)return false;queue.addAll(edges.get(pc));}
        return true;
    }
    private void merge(int pc,Value[] incoming,ArrayDeque<Integer> queue) {
        Value[] old=states.get(pc);
        if(old==null){states.put(pc,incoming.clone());queue.add(pc);return;}
        boolean changed=false;
        for(int i=0;i<old.length;i++)if(old[i]!=null&&!old[i].equals(incoming[i])){old[i]=null;changed=true;}
        if(changed)queue.add(pc);
    }
    private Value[] transfer(int pc,Value[] before)throws IOException {
        Value[] out=before.clone();out[registers]=null;int unit=code[pc],op=unit&255;
        if(op>=0x01&&op<=0x09) {
            int kind=(op-1)%3,dst=kind==0?(unit>>8)&15:kind==1?unit>>8:code[pc+1],src=kind==0?unit>>12:kind==1?code[pc+1]:code[pc+2];
            put(out,dst,at(pc,src),op>=4&&op<=6);
        } else if(op>=0x0a&&op<=0x0c)put(out,unit>>8,before[registers],op==0x0b);
        else if(op==0x1a||op==0x1b)put(out,unit>>8,new Value("string",0,method.stringAt(pc)),false);
        else if(op==0x22)put(out,unit>>8,new Value("new",pc,method.typeAt(pc)),false);
        else if(op==0x44) {
            Value array=at(pc,code[pc+1]&255);put(out,unit>>8,array!=null&&array.kind.equals("arg")&&array.text.equals("[I")?new Value("element",array.index,array.text):null,false);
        } else if(invoke(op)) {
            DexIndex.Ref call=method.invokedAt(pc);int[] args=arguments(pc);
            if(call.owner.equals("Ljava/lang/StringBuilder;")&&args.length>=1) {
                Value receiver=at(pc,args[0]);
                if(call.name.equals("append")&&call.type.endsWith(")Ljava/lang/StringBuilder;"))out[registers]=receiver;
                else if(call.name.equals("toString")&&call.type.equals("()Ljava/lang/String;")&&receiver!=null&&receiver.kind.equals("new"))
                    out[registers]=new Value("built",receiver.index,receiver.text);
            }
            if (!call.type.endsWith(")V") && out[registers] == null)
                out[registers] = new Value("call",pc,call.key());
        } else if(op==0x12)put(out,(unit>>8)&15,new Value("constant",(short)unit>>12,null),false);
        else if(op==0x13)put(out,unit>>8,new Value("constant",(short)code[pc+1],null),false);
        else if(op==0x14)put(out,unit>>8,new Value("constant",integer(code,pc+1),null),false);
        else if(op==0x15)put(out,unit>>8,new Value("constant",code[pc+1]<<16,null),false);
        else if(op>=0x52&&op<=0x58)put(out,(unit>>8)&15,new Value("field",pc,method.fieldAt(pc).key()),op==0x53);
        else if(op>=0x60&&op<=0x66)put(out,unit>>8,new Value("field",pc,method.fieldAt(pc).key()),op==0x61);
        else if(op==0x0d||op>=0x13&&op<=0x19||op==0x1c||op>=0x2d&&op<=0x31||op>=0x45&&op<=0x4a||op>=0x60&&op<=0x66||op>=0x90&&op<=0xaf||op>=0xd8&&op<=0xe2)
            put(out,unit>>8,null,op==0x16||op==0x17||op==0x18||op==0x19||op==0x45||op==0x61||op==0x9b||op==0x9c||op==0x9d||op==0x9e||op==0x9f||op==0xa0||op==0xa1||op==0xa2||op==0xa3||op==0xa4||op==0xa5||op==0xab||op==0xac||op==0xad||op==0xae||op==0xaf);
        else if(op==0x20||op==0x21||op==0x23||op>=0x52&&op<=0x58||op>=0x7b&&op<=0x8f||op>=0xb0&&op<=0xcf||op>=0xd0&&op<=0xd7)
            put(out,(unit>>8)&15,null,op==0x53||op==0x7d||op==0x7e||op==0x80||op==0x81||op==0x83||op==0x86||op==0x88||op==0x89||op==0x8b||op>=0xbb&&op<=0xc5||op>=0xcb&&op<=0xcf);
        else if(op==0x00||op>=0x0e&&op<=0x11||op==0x1d||op==0x1e||op==0x1f||op>=0x24&&op<=0x2c||op>=0x32&&op<=0x3d||op>=0x4b&&op<=0x51||op>=0x59&&op<=0x5f||op>=0x67&&op<=0x6d) { }
        else throw new IOException("Unsupported provenance opcode: "+op);
        return out;
    }
    private void put(Value[] out,int register,Value value,boolean wide)throws IOException {
        require(register>=0&&register<registers&&(!wide||register+1<registers),"DEX write register out of bounds");out[register]=value;if(wide)out[register+1]=null;
    }
    private List<Integer> successors(int pc,Set<Integer> starts)throws IOException {
        int unit=code[pc],op=unit&255,width=DexIndex.width(op);List<Integer> result=new ArrayList<>();
        if(op>=0x0e&&op<=0x11||op==0x27)return result;
        if(op>=0x28&&op<=0x2a)result.add(Math.addExact(pc,op==0x28?(byte)(unit>>8):op==0x29?(short)code[pc+1]:integer(code,pc+1)));
        else {
            result.add(pc+width);
            if(op>=0x32&&op<=0x3d)result.add(Math.addExact(pc,(short)code[pc+1]));
            else if(op==0x2b||op==0x2c) {
                int payload=Math.addExact(pc,integer(code,pc+1));range(code,payload,2);require(code[payload]==(op==0x2b?0x100:0x200),"Wrong switch payload");
                int count=code[payload+1];require(count<=256,"Unbounded switch cases");
                for(int i=0;i<count;i++)result.add(Math.addExact(pc,integer(code,payload+(op==0x2b?4:2+count*2)+i*2)));
            }
        }
        for(int target:result)require(starts.contains(target),"Branch is not an instruction boundary at "+pc+" -> "+target);return result;
    }
    private static Set<Integer> starts(int[] code)throws IOException {
        Set<Integer> starts=new LinkedHashSet<>();
        for(int pc=0;pc<code.length;) {
            int unit=code[pc],width;
            if(unit==0x100||unit==0x200){range(code,pc,2);int count=code[pc+1];width=unit==0x100?4+count*2:2+count*4;}
            else if(unit==0x300){range(code,pc,4);long bytes=(long)code[pc+1]*Integer.toUnsignedLong(integer(code,pc+2));require(bytes<=code.length*2L,"Unbounded array payload");width=4+(int)((bytes+1)/2);}
            else {width=DexIndex.width(unit&255);require(width>0&&(unit&255)!=0||unit==0,"Unknown DEX instruction/payload");starts.add(pc);}
            require(width>0&&pc<=code.length-width,"Truncated DEX instruction/payload");pc+=width;
        }
        return starts;
    }
    static boolean invoke(int op){return op>=0x6e&&op<=0x72||op>=0x74&&op<=0x78;}
    static int[] arguments(int[] code,int pc)throws IOException {
        int op=code[pc]&255;require(invoke(op),"Not an ordinary invocation");int count=op>=0x74?code[pc]>>8:code[pc]>>12;
        require(count<=5||op>=0x74,"Invalid invoke register count");int[] result=new int[count];
        for(int i=0;i<count;i++)result[i]=op>=0x74?code[pc+2]+i:i==4?(code[pc]>>8)&15:code[pc+2]>>(i*4)&15;
        return result;
    }
    static List<String> parameters(String descriptor)throws IOException {
        require(descriptor.startsWith("("),"Invalid method descriptor");List<String> result=new ArrayList<>();
        for(int i=1;descriptor.charAt(i)!=')';) {
            int start=i;while(descriptor.charAt(i)=='[')i++;
            if(descriptor.charAt(i)=='L'){int end=descriptor.indexOf(';',i);require(end>i,"Invalid reference descriptor");i=end+1;}
            else {require("ZBCSIJFD".indexOf(descriptor.charAt(i))>=0,"Invalid primitive descriptor");i++;}
            result.add(descriptor.substring(start,i));
        }
        return result;
    }
    static List<Integer> wordOffsets(List<String> types){List<Integer> result=new ArrayList<>();int word=0;for(String type:types){result.add(word);word+=wide(type)?2:1;}return result;}
    static boolean wide(String type){return type.equals("J")||type.equals("D");}
    static int integer(int[] code,int pc)throws IOException {range(code,pc,2);return code[pc]|code[pc+1]<<16;}
    private static void range(int[] code,int at,int size)throws IOException {require(at>=0&&size>=0&&at<=code.length-size,"Invalid instruction range");}
    static void require(boolean value,String message)throws IOException {if(!value)throw new IOException(message);}
}
