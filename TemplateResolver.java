package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;

/** Derives compact right-template IDs from resource bindings and the renderer's switch. */
final class TemplateResolver {
    static final List<String> ROLES = Collections.unmodifiableList(Arrays.asList("wave", "loading", "text", "progress"));
    static final class Plan {
        final DexIndex.Ref inflater, selector;
        final Map<String,Integer> templates;
        final Map<String,List<Integer>> aliases;
        final Map<String,DexIndex.Ref> viewFields;
        Plan(DexIndex.Ref inflater, DexIndex.Ref selector, Map<String,Integer> values,Map<String,List<Integer>> aliases,Map<String,DexIndex.Ref> fields) {
            this.inflater = inflater; this.selector = selector;
            templates = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            Map<String,List<Integer>> copy=new LinkedHashMap<>();
            for(String role:aliases.keySet())copy.put(role,Collections.unmodifiableList(new ArrayList<>(aliases.get(role))));
            this.aliases=Collections.unmodifiableMap(copy);
            viewFields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
        int value(String role) { return templates.get(role); }
        String describe() { return "inflater=" + inflater + "\nselector=" + selector + "\ntemplates=" + templates+"\naliases="+aliases; }
    }
    private TemplateResolver() { }
    static Plan discover(DexIndex index, Map<String,Integer> resources) throws IOException {
        try { return checked(index, resources); }
        catch (IndexOutOfBoundsException | ArithmeticException malformed) { throw new IOException("Malformed template bytecode", malformed); }
    }
    private static Plan checked(DexIndex index, Map<String,Integer> resources) throws IOException {
        if (!resources.keySet().equals(new HashSet<>(ROLES)) || resources.containsValue(0)
                || new HashSet<>(resources.values()).size() != ROLES.size()) throw new IOException("Missing/ambiguous template resources");
        List<Plan> plans = new ArrayList<>();
        for (DexIndex.MethodData inflater : index.methods.values()) {
            if (inflater.isStatic() || !inflater.ref.type.equals("()V")) continue;
            Set<Integer> inputs = new HashSet<>(inflater.facts().constants);
            for(DexIndex.Ref ref:inflater.facts().fields) {
                DexIndex.FieldData field = index.declaredFields.get(ref.key());
                if(field != null && field.initialInteger != null) inputs.add(field.initialInteger);
            }
            if(!inputs.containsAll(resources.values()))continue;
            Map<String,DexIndex.Ref> fields = bindings(index, inflater, resources);
            if (fields.size() != ROLES.size()) continue;
            for (DexIndex.MethodData selector : index.methods.values()) {
                if (selector.isStatic() || !selector.ref.owner.equals(inflater.ref.owner)
                        || !selector.ref.type.equals("(I)Ljava/util/List;")) continue;
                Map<Integer,Integer> cases = switchCases(selector);
                if (cases.isEmpty()) continue;
                // New renderers may route templates through comparisons before
                // their packed switch. Test those actual literal inputs as well.
                Set<Integer> branchInputs = new LinkedHashSet<>(cases.keySet());
                branchInputs.addAll(selector.facts().constants);
                require(branchInputs.size() <= 256, "Too many template branch inputs");
                Map<String,Integer> values = new LinkedHashMap<>();
                Map<String,List<Integer>> aliases=new LinkedHashMap<>();
                for (int key : branchInputs) {
                    List<DexIndex.Ref> views = views(selector, key);
                    if (views.size() != 1) continue;
                    for (String role : ROLES) if (fields.get(role).equals(views.get(0))) {
                        List<Integer> choices=aliases.computeIfAbsent(role,k->new ArrayList<>());
                        if(!role.equals("progress")&&!choices.isEmpty())throw new IOException("Ambiguous template for " + role);
                        choices.add(key);values.putIfAbsent(role,key);
                    }
                }
                int unknown = Integer.MIN_VALUE; while (branchInputs.contains(unknown)) ++unknown;
                if (!views(selector, unknown).isEmpty()) throw new IOException("Unknown template has side effects");
                if (values.size() == ROLES.size()) plans.add(new Plan(inflater.ref, selector.ref, values,aliases,fields));
            }
        }
        if (plans.size() != 1) throw new IOException("Missing/ambiguous right-template renderer: " + plans.size());
        return plans.get(0);
    }
    private static Map<String,DexIndex.Ref> bindings(DexIndex index, DexIndex.MethodData method, Map<String,Integer> resources) throws IOException {
        Map<String,DexIndex.Ref> result = new LinkedHashMap<>();
        int[] code = method.codeUnits();
        Map<Integer,Integer> constants = new HashMap<>();
        Map<Integer,Integer> foundViews = new HashMap<>();
        Integer pending = null; int receiver = -1;
        for (int at = 0; at < code.length;) {
            int unit = code[at], op = unit & 255, width = DexIndex.width(op);
            if (width <= 0 || at + width > code.length || (op == 0 && unit != 0)) break;
            if (op >= 0x12 && op <= 0x15) {
                int destination = op == 0x12 ? (unit >> 8) & 15 : unit >> 8;
                constants.put(destination, constant(code, at)); foundViews.remove(destination); pending = null;
            } else if (op == 0x60) {
                DexIndex.FieldData field = index.declaredFields.get(method.fieldAt(at).key());
                constants.clear(); foundViews.clear(); pending = null;
                if(field != null && field.isStatic() && field.initialInteger != null) constants.put(unit >> 8,field.initialInteger);
            } else if (invoke(op)) {
                DexIndex.Ref call = method.invokedAt(at); int[] args = arguments(code, at); pending = null;
                if (call.name.equals("findViewById") && call.type.equals("(I)Landroid/view/View;") && args.length == 2) {
                    pending = constants.get(args[1]); receiver = args[0];
                }
                constants.clear(); foundViews.clear();
            } else if (op == 0x0c) {
                if (pending != null) foundViews.put(unit >> 8, pending);
                pending = null;
            } else if (op == 0x1f) { // check-cast leaves the object identity intact
                pending = null;
            } else if (op == 0x5b) { // iput-object, actual view returned by findViewById
                int source = (unit >> 8) & 15, object = unit >> 12;
                Integer id = foundViews.get(source);
                DexIndex.Ref field = method.fieldAt(at);
                if (id != null && object == receiver && field.owner.equals(method.ref.owner))
                    for (String role : ROLES) if (id.equals(resources.get(role))) {
                        if (result.put(role, field) != null) throw new IOException("Repeated resource view binding: " + role);
                    }
                constants.clear(); foundViews.clear(); pending = null;
            } else { constants.clear(); foundViews.clear(); pending = null; }
            at += width;
        }
        if (new HashSet<>(result.values()).size() != result.size()) throw new IOException("Shared resource view fields");
        return result;
    }
    static Map<Integer,Integer> switchCases(DexIndex.MethodData method) throws IOException {
        int[] code = method.codeUnits(); Map<Integer,Integer> result = new LinkedHashMap<>(); int found = 0;
        for (int at = 0; at < code.length;) {
            int unit = code[at], op = unit & 255;
            if (op == 0 && unit != 0) break; // payload data follows executable instructions
            int width = DexIndex.width(op); require(width > 0 && at + width <= code.length, "Truncated selector");
            if (op == 0x2b || op == 0x2c) {
                require(++found == 1, "Multiple template switches");
                int payload = Math.addExact(at, integer(code, at + 1)); range(code, payload, 2);
                require(code[payload] == (op == 0x2b ? 0x100 : 0x200), "Invalid switch payload kind");
                int count = code[payload + 1]; require(count > 0 && count <= 128, "Invalid switch count");
                range(code, payload, op == 0x2b ? 4 + count * 2 : 2 + count * 4);
                int first = op == 0x2b ? integer(code, payload + 2) : 0;
                Integer previous = null;
                for (int i = 0; i < count; ++i) {
                    int key = op == 0x2b ? Math.addExact(first, i) : integer(code, payload + 2 + i * 2);
                    int targetAt = op == 0x2b ? payload + 4 + i * 2 : payload + 2 + count * 2 + i * 2;
                    int target = Math.addExact(at, integer(code, targetAt)); range(code, target, 1);
                    require(previous == null || key > previous, "Unsorted template switch");
                    result.put(key, target); previous = key;
                }
            }
            at += width;
        }
        Set<Integer> starts = instructionStarts(code);
        for (int target : result.values()) require(starts.contains(target), "Switch target is not an instruction boundary");
        return result;
    }
    private static Set<Integer> instructionStarts(int[] code) throws IOException {
        Set<Integer> result = new HashSet<>();
        for(int at=0;at<code.length;) {
            if((code[at]&255)==0 && code[at]!=0)break;
            int width=DexIndex.width(code[at]&255);require(width>0 && at+width<=code.length,"Malformed instruction range");
            result.add(at);at+=width;
        }
        return result;
    }
    private static final Object THIS = new Object(), LIST = new Object();
    private static List<DexIndex.Ref> views(DexIndex.MethodData method, int template) throws IOException {
        require(method.incomingWords() == 2, "Unexpected selector parameters");
        int[] code = method.codeUnits(); Object[] registers = new Object[method.registerCount()];
        require(registers.length >= 2, "Invalid selector register count");
        registers[registers.length - 2] = THIS; registers[registers.length - 1] = template;
        List<DexIndex.Ref> result = new ArrayList<>(); boolean constructed = false, created = false;
        Map<Integer,Integer> cases = switchCases(method);
        Set<Integer> starts = instructionStarts(code);
        for (int at = 0, steps = 0; steps++ < 128;) {
            range(code, at, 1); require(starts.contains(at),"Branch target is not an instruction boundary");
            int unit = code[at], op = unit & 255, width = DexIndex.width(op);
            require(width > 0 && at + width <= code.length, "Truncated selector instruction");
            if (op == 0x22) {
                require(!created && method.typeAt(at).equals("Ljava/util/ArrayList;"),"Unexpected selector allocation");
                registers[unit >> 8] = LIST; created = true;
            }
            else if (op == 0x54) {
                require(registers[unit >> 12] == THIS, "View field is not on renderer");
                registers[(unit >> 8) & 15] = method.fieldAt(at);
            } else if (op == 0x2b || op == 0x2c) {
                require(registers[unit >> 8] instanceof Integer && ((Integer)registers[unit >> 8]) == template,
                        "Template switch does not use the input parameter");
                if (cases.containsKey(template)) { at = cases.get(template); continue; }
            } else if (op == 0x28 || op == 0x29 || op == 0x2a) {
                int offset = op == 0x28 ? (byte)(unit >> 8) : op == 0x29 ? (short)code[at+1] : integer(code,at+1);
                at = Math.addExact(at, offset); continue;
            } else if (op >= 0x32 && op <= 0x3d) {
                Object left=registers[op<=0x37?(unit>>8)&15:unit>>8];
                Object right=op<=0x37?registers[unit>>12]:Integer.valueOf(0);
                require(left instanceof Integer && right instanceof Integer,"Unknown selector comparison operands");
                int a=(Integer)left,b=(Integer)right,kind=op<=0x37?op-0x32:op-0x38;
                boolean taken=kind==0?a==b:kind==1?a!=b:kind==2?a<b:kind==3?a>=b:kind==4?a>b:a<=b;
                if(taken){at=Math.addExact(at,(short)code[at+1]);continue;}
            } else if (invoke(op)) {
                DexIndex.Ref call = method.invokedAt(at); int[] args = arguments(code, at);
                if (call.owner.equals("Ljava/util/ArrayList;") && call.name.equals("<init>")
                        && call.type.equals("()V") && args.length == 1 && registers[args[0]] == LIST && !constructed) constructed = true;
                else if ((call.owner.equals("Ljava/util/ArrayList;") || call.owner.equals("Ljava/util/List;"))
                        && call.name.equals("add") && call.type.equals("(Ljava/lang/Object;)Z") && args.length == 2
                        && constructed && registers[args[0]] == LIST && registers[args[1]] instanceof DexIndex.Ref)
                    result.add((DexIndex.Ref)registers[args[1]]);
                else throw new IOException("Unexpected template selector call: " + call);
            } else if (op == 0x11) {
                require(constructed && registers[unit >> 8] == LIST, "Selector returned another object"); return result;
            } else if (op == 0x0a) registers[unit >> 8] = null;
            else if (op == 0x01 || op == 0x07) registers[(unit >> 8) & 15] = registers[unit >> 12];
            else if (op == 0x02 || op == 0x08) registers[unit >> 8] = registers[code[at+1]];
            else if (op == 0x03 || op == 0x09) registers[code[at+1]] = registers[code[at+2]];
            else if (op >= 0x12 && op <= 0x15) registers[op == 0x12 ? (unit >> 8) & 15 : unit >> 8] = constant(code,at);
            else if (op != 0) throw new IOException("Unsupported template selector opcode: " + op);
            at += width;
        }
        throw new IOException("Template selector exceeds execution bound");
    }
    private static boolean invoke(int op) { return op >= 0x6e && op <= 0x72 || op >= 0x74 && op <= 0x78; }
    private static int[] arguments(int[] code, int at) throws IOException {
        int op = code[at] & 255, count = op >= 0x74 ? code[at] >> 8 : code[at] >> 12;
        require(count <= 5 || op >= 0x74, "Invalid invocation argument count"); int[] args = new int[count];
        for (int i = 0; i < count; ++i) args[i] = op >= 0x74 ? code[at+2] + i : i == 4 ? (code[at] >> 8) & 15 : (code[at+2] >> (4*i)) & 15;
        return args;
    }
    private static int constant(int[] code, int at) throws IOException {
        int op = code[at] & 255;
        if (op == 0x12) return (short)code[at] >> 12;
        if (op == 0x13) { range(code, at, 2); return (short)code[at+1]; }
        if (op == 0x14) return integer(code, at+1);
        if (op == 0x15) { range(code, at, 2); return code[at+1] << 16; }
        throw new IOException("Not an integer constant");
    }
    private static int integer(int[] code, int at) throws IOException { range(code,at,2); return code[at] | code[at+1] << 16; }
    private static void range(int[] code, int at, int length) throws IOException { require(at >= 0 && length >= 0 && at <= code.length-length,"Selector range invalid"); }
    private static void require(boolean value, String message) throws IOException { if (!value) throw new IOException(message); }
}
