package com.axuan.vivoislandplus;
import java.io.IOException;
import java.util.*;

/** Bounded scalar specialization; unknown conditions retain both paths. */
final class ScalarCallFlow {
    final BeanOrigins graph;
    final Map<Integer,Integer[]> states=new LinkedHashMap<>();
    private final Map<Integer,Integer> calls;
    ScalarCallFlow(BeanOrigins graph,Map<Integer,Integer> calls)throws IOException {
        this(graph,calls,Collections.emptyMap());
    }
    ScalarCallFlow(BeanOrigins graph,Map<Integer,Integer> calls,Map<Integer,Integer> arguments)throws IOException {
        this.graph=graph;this.calls=new LinkedHashMap<>(calls);
        Integer[] initial=new Integer[graph.registers+1];
        for(Map.Entry<Integer,Integer> entry:arguments.entrySet()) {
            int argument=entry.getKey();require(argument>=0&&argument<graph.frameTypes.size(),"Scalar argument outside descriptor");
            String type=graph.frameTypes.get(argument);require(type.equals("I")||type.equals("Z"),"Scalar argument must be int or boolean");
            initial[graph.registers-graph.method.incomingWords()+graph.frameWordOffsets.get(argument)]=entry.getValue();
        }
        ArrayDeque<Integer> queue=new ArrayDeque<>();merge(0,initial,queue);
        for(int handler:graph.method.exceptionHandlers())merge(handler,new Integer[graph.registers+1],queue);
        int count=0;
        while(!queue.isEmpty()) {
            require(++count<=graph.code.length*64,"Scalar specialization does not converge");int pc=queue.removeFirst();
            Integer[] output=transfer(pc,states.get(pc));for(int next:successors(pc,states.get(pc)))merge(next,output,queue);
        }
    }
    Integer at(int pc,int register)throws IOException {require(register>=0&&register<graph.registers,"Scalar operand outside frame");return states.containsKey(pc)?states.get(pc)[register]:null;}
    private void merge(int pc,Integer[] incoming,ArrayDeque<Integer> queue) {
        Integer[] old=states.get(pc);if(old==null){states.put(pc,incoming.clone());queue.add(pc);return;}
        boolean changed=false;
        for(int i=0;i<old.length;i++)if(old[i]!=null&&!old[i].equals(incoming[i])){old[i]=null;changed=true;}
        if(changed)queue.add(pc);
    }
    private void put(Integer[] out,int register,Integer value,boolean wide)throws IOException {
        require(register>=0&&register<graph.registers&&(!wide||register+1<graph.registers),"Scalar destination outside frame");out[register]=value;if(wide)out[register+1]=null;
    }
    private Integer[] transfer(int pc,Integer[] in)throws IOException {
        Integer[] out=in.clone();out[graph.registers]=null;int[] code=graph.code;int unit=code[pc],op=unit&255;
        if(op>=1&&op<=9) {
            int form=(op-1)%3,dst=form==0?(unit>>8)&15:form==1?unit>>8:code[pc+1],src=form==0?unit>>12:form==1?code[pc+1]:code[pc+2];
            put(out,dst,at(pc,src),op>=4&&op<=6);
        } else if(op>=0x0a&&op<=0x0c)put(out,unit>>8,op==0x0a?in[graph.registers]:null,op==0x0b);
        else if(BeanOrigins.invoke(op))out[graph.registers]=calls.get(pc);
        else if(op>=0x12&&op<=0x15)put(out,op==0x12?(unit>>8)&15:unit>>8,op==0x12?(short)unit>>12:op==0x13?(short)code[pc+1]:op==0x14?BeanOrigins.integer(code,pc+1):code[pc+1]<<16,false);
        else if(op>=0x90&&op<=0x9a)put(out,unit>>8,arithmetic(op-0x90,at(pc,code[pc+1]&255),at(pc,code[pc+1]>>8)),false);
        else if(op>=0xb0&&op<=0xba)put(out,(unit>>8)&15,arithmetic(op-0xb0,at(pc,(unit>>8)&15),at(pc,unit>>12)),false);
        else if(op>=0xd0&&op<=0xd7) {
            int operation=op-0xd0;Integer input=at(pc,unit>>12),literal=(int)(short)code[pc+1];
            put(out,(unit>>8)&15,operation==1?subtract(literal,input):arithmetic(operation,input,literal),false);
        } else if(op>=0xd8&&op<=0xe2) {
            int operation=op-0xd8;Integer input=at(pc,code[pc+1]&255),literal=(int)(byte)(code[pc+1]>>8);
            put(out,unit>>8,operation==1?subtract(literal,input):arithmetic(operation,input,literal),false);
        } else if(op==0x0d||op>=0x16&&op<=0x1c||op==0x22||op>=0x2d&&op<=0x31||op>=0x44&&op<=0x4a||op>=0x60&&op<=0x66||op>=0x9b&&op<=0xaf)
            put(out,unit>>8,null,op>=0x16&&op<=0x19||op==0x45||op==0x61||op>=0x9b&&op<=0xa5||op>=0xab);
        else if(op==0x20||op==0x21||op==0x23||op>=0x52&&op<=0x58||op>=0x7b&&op<=0x8f||op>=0xbb&&op<=0xcf)
            put(out,(unit>>8)&15,null,op==0x53||op==0x7d||op==0x7e||op==0x80||op==0x81||op==0x83||op==0x86||op==0x88||op==0x89||op==0x8b||op>=0xbb&&op<=0xc5||op>=0xcb);
        else if(op==0||op>=0x0e&&op<=0x11||op>=0x1d&&op<=0x1f||op>=0x24&&op<=0x2c||op>=0x32&&op<=0x3d||op>=0x4b&&op<=0x51||op>=0x59&&op<=0x5f||op>=0x67&&op<=0x6d) { }
        else throw new IOException("Unsupported scalar opcode: "+op);
        return out;
    }
    private List<Integer> successors(int pc,Integer[] in)throws IOException {
        int unit=graph.code[pc],op=unit&255;
        if(op>=0x32&&op<=0x3d) {
            Integer left=at(pc,op<=0x37?(unit>>8)&15:unit>>8),right=op<=0x37?at(pc,unit>>12):Integer.valueOf(0);
            if(left!=null&&right!=null) {
                int kind=op<=0x37?op-0x32:op-0x38,a=left,b=right;
                boolean take=kind==0?a==b:kind==1?a!=b:kind==2?a<b:kind==3?a>=b:kind==4?a>b:a<=b;
                return Collections.singletonList(take?Math.addExact(pc,(short)graph.code[pc+1]):pc+2);
            }
        } else if(op==0x2b||op==0x2c) {
            Integer key=at(pc,unit>>8);
            if(key!=null) {
                int payload=Math.addExact(pc,BeanOrigins.integer(graph.code,pc+1)),count=graph.code[payload+1];
                for(int i=0;i<count;i++) {
                    int candidate=op==0x2b?Math.addExact(BeanOrigins.integer(graph.code,payload+2),i):BeanOrigins.integer(graph.code,payload+2+i*2);
                    if(key==candidate)return Collections.singletonList(Math.addExact(pc,BeanOrigins.integer(graph.code,payload+(op==0x2b?4:2+count*2)+i*2)));
                }
                return Collections.singletonList(pc+3);
            }
        }
        return graph.edges.get(pc);
    }
    private static Integer subtract(Integer a,Integer b){return a==null||b==null?null:a-b;}
    private static Integer arithmetic(int op,Integer a,Integer b) {
        if(a==null||b==null)return null;
        switch(op) {case 0:return a+b;case 1:return a-b;case 2:return a*b;case 3:return b==0?null:a/b;case 4:return b==0?null:a%b;
            case 5:return a&b;case 6:return a|b;case 7:return a^b;case 8:return a<<b;case 9:return a>>b;case 10:return a>>>b;default:return null;}
    }
    private static void require(boolean value,String why)throws IOException {if(!value)throw new IOException(why);}
}
