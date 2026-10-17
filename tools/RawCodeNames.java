package com.axuan.vivoislandplus;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;

/** Renames actual decoded reference tables while retaining executable code units. */
final class RawCodeNames {
    private RawCodeNames() { }
    private static boolean platform(String owner) {
        return owner.startsWith("Ljava/") || owner.startsWith("Landroid/")
                || owner.startsWith("Ljavax/") || owner.startsWith("Lorg/json/");
    }
    static DexIndex renamed(DexIndex source) throws Exception {
        Map<String,String> types=new LinkedHashMap<>();
        for(String owner:source.parents.keySet()) if(!platform(owner)) types.put(owner,"Lchanged/Type"+types.size()+";");
        Map<DexIndex.Ref,String> methods=new HashMap<>(),fields=new HashMap<>();
        for(DexIndex.MethodData method:source.methods.values())
            if(!platform(method.ref.owner)&&!method.ref.name.startsWith("<")) methods.put(method.ref,"method"+methods.size());
        for(DexIndex.FieldData field:source.declaredFields.values())
            if(!platform(field.ref.owner)) fields.put(field.ref,"field"+fields.size());
        DexIndex result=new DexIndex();
        for(Map.Entry<String,String> parent:source.parents.entrySet())
            result.parents.put(types.getOrDefault(parent.getKey(),parent.getKey()),types.getOrDefault(parent.getValue(),parent.getValue()));
        for(DexIndex.FieldData field:source.declaredFields.values()) {
            DexIndex.Ref ref=ref(field.ref,types,fields);
            result.declaredFields.put(ref.key(),new DexIndex.FieldData(ref,field.flags,field.initialInteger));
        }
        Field readerField=DexIndex.MethodData.class.getDeclaredField("reader");readerField.setAccessible(true);
        Set<Object> readers=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        for(DexIndex.MethodData method:source.methods.values()) {
            DexIndex.Ref ref=ref(method.ref,types,methods);result.methods.put(ref.key(),method.withRef(ref));
            readers.add(readerField.get(method));
        }
        for(Object reader:readers) {
            for(String table:new String[]{"methodIds","fieldIds"}) {
                Field field=reader.getClass().getDeclaredField(table);field.setAccessible(true);
                DexIndex.Ref[] refs=(DexIndex.Ref[])field.get(reader);
                for(int i=0;i<refs.length;++i)refs[i]=ref(refs[i],types,table.equals("methodIds")?methods:fields);
            }
            Field field=reader.getClass().getDeclaredField("types");field.setAccessible(true);
            String[] refs=(String[])field.get(reader);for(int i=0;i<refs.length;++i)refs[i]=descriptor(refs[i],types);
        }
        return result;
    }
    private static DexIndex.Ref ref(DexIndex.Ref ref,Map<String,String> types,Map<DexIndex.Ref,String> names) {
        return new DexIndex.Ref(types.getOrDefault(ref.owner,ref.owner),names.getOrDefault(ref,ref.name),descriptor(ref.type,types));
    }
    private static String descriptor(String descriptor,Map<String,String> types) {
        Matcher matcher=Pattern.compile("L[^;]+;").matcher(descriptor);StringBuffer result=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(result,Matcher.quoteReplacement(types.getOrDefault(matcher.group(),matcher.group())));
        matcher.appendTail(result);return result.toString();
    }
}
