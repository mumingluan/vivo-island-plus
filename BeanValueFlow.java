package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Bounded reaching definitions for values; private calls remain opaque leaves. */
final class BeanValueFlow {
    final BeanOrigins graph;
    final Map<Integer,Set<Integer>[]> states=new LinkedHashMap<>();
    final Map<Integer,Set<Integer>> arithmetic=new LinkedHashMap<>();
    final int registers;
    private final Set<DexIndex.Ref> fluentAliases;
    private static final Set<Integer> UNKNOWN=Collections.singleton(-1);
    @SuppressWarnings("unchecked")
    BeanValueFlow(DexIndex.MethodData method)throws IOException {
        this(method,Collections.emptySet(),false);
    }
    @SuppressWarnings("unchecked")
    BeanValueFlow(DexIndex.MethodData method,Set<DexIndex.Ref> fluentAliases)throws IOException {
        this(method,fluentAliases,true);
    }
    @SuppressWarnings("unchecked")
    private BeanValueFlow(DexIndex.MethodData method,Set<DexIndex.Ref> fluentAliases,boolean exceptionEdges)throws IOException {
        this.fluentAliases=new HashSet<>(fluentAliases);
        graph=new BeanOrigins(method);registers=graph.registers;
        Set<Integer>[] initial=new Set[registers+1];Arrays.fill(initial,UNKNOWN);
        int words=method.incomingWords();
        for(int i=0;i<graph.frameTypes.size();i++) {
            int register=registers-words+graph.frameWordOffsets.get(i);initial[register]=Collections.singleton(-2-i);
            if(BeanOrigins.wide(graph.frameTypes.get(i)))initial[register+1]=initial[register];
        }
        ArrayDeque<Integer> queue=new ArrayDeque<>();merge(0,initial,queue);
        for(int handler:exceptionEdges?Collections.<Integer>emptySet():method.exceptionHandlers()) {
            Set<Integer>[] unknown=new Set[registers+1];Arrays.fill(unknown,UNKNOWN);merge(handler,unknown,queue);
        }
        List<int[]> protectedRegions=exceptionEdges?method.exceptionRegions():Collections.emptyList();
        Set<Integer> ends=new HashSet<>();
        for(int start:graph.instructionStarts)ends.add(start+DexIndex.width(graph.code[start]&255));
        for(int[] region:protectedRegions)require(graph.instructionStarts.contains(region[0])&&ends.contains(region[1])
                &&graph.instructionStarts.contains(region[2]),"Invalid protected instruction boundary");
        int rounds=0;
        while(!queue.isEmpty()) {
            require(++rounds<=graph.code.length*64,"Value definitions did not converge");int pc=queue.removeFirst();
            Set<Integer>[] output=transfer(pc,states.get(pc));for(int next:graph.edges.get(pc))merge(next,output,queue);
            // Any protected instruction may fail before completing its result.
            // Preserve pre-instruction locals, clear the transient invoke result,
            // and join every protected predecessor at the actual handler.
            for(int[] region:protectedRegions)if(region[0]<=pc&&pc<region[1]) {
                Set<Integer>[] exceptional=states.get(pc).clone();exceptional[registers]=UNKNOWN;merge(region[2],exceptional,queue);
            }
        }
    }
    Set<Integer> at(int pc,int register)throws IOException {
        require(register>=0&&register<registers&&states.containsKey(pc),"Value register out of bounds");return states.get(pc)[register];
    }
    private void merge(int pc,Set<Integer>[] incoming,ArrayDeque<Integer> queue)throws IOException {
        Set<Integer>[] old=states.get(pc);if(old==null){states.put(pc,incoming.clone());queue.add(pc);return;}
        boolean changed=false;
        for(int i=0;i<old.length;i++)if(!old[i].containsAll(incoming[i])) {old[i]=union(old[i],incoming[i]);changed=true;}
        if(changed)queue.add(pc);
    }
    private static Set<Integer> union(Set<Integer> a,Set<Integer> b)throws IOException {
        Set<Integer> result=new LinkedHashSet<>(a);result.addAll(b);require(result.size()<=256,"Too many value definitions");return result;
    }
    private void write(Set<Integer>[] out,int register,Set<Integer> value,boolean wide)throws IOException {
        require(register>=0&&register<registers&&(!wide||register+1<registers),"Value write out of bounds");
        out[register]=value;if(wide)out[register+1]=value;
    }
    private void expression(int pc,Set<Integer>[] out,int destination,boolean wide,int... inputs)throws IOException {
        Set<Integer> dependencies=new LinkedHashSet<>();for(int input:inputs)dependencies.addAll(at(pc,input));
        if(arithmetic.containsKey(pc))dependencies=union(arithmetic.get(pc),dependencies);
        arithmetic.put(pc,dependencies);write(out,destination,Collections.singleton(pc),wide);
    }
    private Set<Integer>[] transfer(int pc,Set<Integer>[] before)throws IOException {
        Set<Integer>[] out=before.clone();out[registers]=UNKNOWN;int[] code=graph.code;int unit=code[pc],op=unit&255;
        if(op>=1&&op<=9) {
            int form=(op-1)%3,dst=form==0?(unit>>8)&15:form==1?unit>>8:code[pc+1];
            int src=form==0?unit>>12:form==1?code[pc+1]:code[pc+2];write(out,dst,at(pc,src),op>=4&&op<=6);
        } else if(op>=0x0a&&op<=0x0c)write(out,unit>>8,before[registers],op==0x0b);
        else if(BeanOrigins.invoke(op)) {
            DexIndex.Ref call=graph.method.invokedAt(pc);int[] args=graph.arguments(pc);
            if(!call.type.endsWith(")V"))out[registers]=Collections.singleton(pc);
            if(call.owner.equals("Ljava/lang/StringBuilder;")&&call.name.equals("append")
                    &&call.type.endsWith(")Ljava/lang/StringBuilder;")&&args.length>=1)out[registers]=at(pc,args[0]);
            if(fluentAliases.contains(call)&&args.length>=1)out[registers]=at(pc,args[0]);
        } else if(op==0x12)write(out,(unit>>8)&15,Collections.singleton(pc),false);
        else if(op>=0x13&&op<=0x1c||op==0x22||op==0x0d||op>=0x44&&op<=0x4a||op>=0x60&&op<=0x66)
            write(out,unit>>8,Collections.singleton(pc),op>=0x16&&op<=0x19||op==0x45||op==0x61);
        else if(op>=0x52&&op<=0x58||op==0x20||op==0x23)write(out,(unit>>8)&15,Collections.singleton(pc),op==0x53);
        else if(op==0x21)expression(pc,out,(unit>>8)&15,false,unit>>12);
        else if(op>=0x2d&&op<=0x31)expression(pc,out,unit>>8,false,code[pc+1]&255,code[pc+1]>>8);
        else if(op>=0x7b&&op<=0x8f)expression(pc,out,(unit>>8)&15,
                op==0x7d||op==0x7e||op==0x80||op==0x81||op==0x83||op==0x86||op==0x88||op==0x89||op==0x8b,unit>>12);
        else if(op>=0x90&&op<=0xaf)expression(pc,out,unit>>8,op>=0x9b&&op<=0xa5||op>=0xab,code[pc+1]&255,code[pc+1]>>8);
        else if(op>=0xb0&&op<=0xcf)expression(pc,out,(unit>>8)&15,op>=0xbb&&op<=0xc5||op>=0xcb,(unit>>8)&15,unit>>12);
        else if(op>=0xd0&&op<=0xd7)expression(pc,out,(unit>>8)&15,false,unit>>12);
        else if(op>=0xd8&&op<=0xe2)expression(pc,out,unit>>8,false,code[pc+1]&255);
        else if(op==0||op>=0x0e&&op<=0x11||op>=0x1d&&op<=0x1f||op>=0x24&&op<=0x2c
                ||op>=0x32&&op<=0x3d||op>=0x4b&&op<=0x51||op>=0x59&&op<=0x5f||op>=0x67&&op<=0x6d) { }
        else throw new IOException("Unsupported value instruction: "+op);
        return out;
    }
    Set<Integer> expanded(Set<Integer> values)throws IOException {
        Set<Integer> result=new LinkedHashSet<>();ArrayDeque<Integer> queue=new ArrayDeque<>(values);
        while(!queue.isEmpty()) {int definition=queue.removeFirst();if(!result.add(definition))continue;
            require(result.size()<=1024,"Unbounded arithmetic slice");if(arithmetic.containsKey(definition))queue.addAll(arithmetic.get(definition));}
        return result;
    }
    Integer single(Set<Integer> definitions){return definitions.size()==1?definitions.iterator().next():null;}
    private static void require(boolean value,String message)throws IOException {if(!value)throw new IOException(message);}
}
