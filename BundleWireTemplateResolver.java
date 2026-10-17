package com.axuan.vivoislandplus;
import java.io.IOException;
import java.util.*;

/** Select real renderer enums only when the official Bundle decoder forwards them. */
final class BundleWireTemplateResolver {
    static final class Plan {
        final DexIndex.Ref producer;
        final int leftRead,rightRead,postPc;
        final Map<String,Integer> values;
        final Map<String,List<Integer>> verifiedAliases;
        Plan(DexIndex.Ref producer,int l,int r,int post,Map<String,Integer> values,Map<String,List<Integer>> aliases) {
            this.producer=producer;leftRead=l;rightRead=r;postPc=post;
            this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));
            Map<String,List<Integer>> copy=new LinkedHashMap<>();for(String role:aliases.keySet())copy.put(role,Collections.unmodifiableList(new ArrayList<>(aliases.get(role))));
            verifiedAliases=Collections.unmodifiableMap(copy);
        }
        int value(String role){return values.get(role);}
        String describe(){return "wireProducer="+producer+" inputs="+leftRead+","+rightRead+" post="+postPc+" values="+values+" aliases="+verifiedAliases;}
    }
    static Plan discover(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties,CapsuleDefaultsResolver.Plan capsule,TemplateResolver.Plan templates)throws IOException {
        try{return checked(index,core,properties,capsule,templates);}
        catch(IndexOutOfBoundsException|ArithmeticException malformed){throw new IOException("Malformed Bundle template conversion",malformed);}
    }
    private static Plan checked(DexIndex index,BeanResolver.Plan core,BeanPropertyResolver.Plan properties,CapsuleDefaultsResolver.Plan capsule,TemplateResolver.Plan templates)throws IOException {
        DexIndex.MethodData method=index.get(properties.producer);if(method==null)throw new IOException("Missing Bundle producer");
        BeanValueFlow flow=new BeanValueFlow(method);
        int left=source(flow,"island.superx.leftTemplate"),right=source(flow,"island.superx.rightTemplate");
        int leftSetter=properties.setterPcs.get("left.template"),rightSetter=properties.setterPcs.get("right.template");
        require(left!=right&&flow.graph.dominates(left,leftSetter)&&flow.graph.dominates(right,rightSetter),"Raw template input does not dominate setter");
        List<Integer> posts=new ArrayList<>();
        for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.graph.code[pc]&255)&&method.invokedAt(pc).equals(core.post)
                &&flow.graph.dominates(leftSetter,pc)&&flow.graph.dominates(rightSetter,pc))posts.add(pc);
        require(posts.size()==1,"Missing/ambiguous template submission");int post=posts.get(0),icon=capsule.integer("left.template");
        Map<String,Integer> values=new LinkedHashMap<>();values.put("left.icon",icon);
        Map<String,List<Integer>> verified=new LinkedHashMap<>();
        for(String role:TemplateResolver.ROLES) {
            List<Integer> aliases=templates.aliases.get(role);require(aliases!=null&&!aliases.isEmpty()&&aliases.size()<=64,"Unbounded/missing renderer aliases");
            List<Integer> accepted=new ArrayList<>();
            for(int candidate:aliases) {
                Map<Integer,Integer> fixed=new LinkedHashMap<>();fixed.put(left,icon);fixed.put(right,candidate);
                ScalarCallFlow specialized=new ScalarCallFlow(flow.graph,fixed);
                int[] leftFrame=flow.graph.arguments(leftSetter),rightFrame=flow.graph.arguments(rightSetter);
                if(specialized.states.containsKey(post)&&Objects.equals(specialized.at(leftSetter,leftFrame[1]),icon)
                        &&Objects.equals(specialized.at(rightSetter,rightFrame[1]),candidate))accepted.add(candidate);
            }
            require(!accepted.isEmpty(),"No renderer enum forwarded unchanged by Bundle decoder: "+role);
            // Alias order comes from the decoded selector. All choices have the
            // same verified view-list semantics and pass the actual decoder.
            values.put(role,accepted.get(0));verified.put(role,accepted);
        }
        return new Plan(method.ref,left,right,post,values,verified);
    }
    private static int source(BeanValueFlow flow,String key)throws IOException {
        List<Integer> found=new ArrayList<>();
        for(int pc:flow.states.keySet())if(BeanOrigins.invoke(flow.graph.code[pc]&255)
                &&BeanPropertyResolver.keyValue(flow,Collections.singleton(pc),key,"I"))found.add(pc);
        require(found.size()==1,"Missing/ambiguous raw Bundle template key: "+key);return found.get(0);
    }
    private static void require(boolean value,String why)throws IOException {if(!value)throw new IOException(why);}
}
