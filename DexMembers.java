package com.axuan.vivoislandplus;
import java.lang.reflect.*;
import java.util.*;
/** Resolves a discovered DEX descriptor and validates the exact runtime contract. */
final class DexMembers {
 private DexMembers() { }
 static Class<?> owner(DexIndex.Ref ref,ClassLoader loader)throws ClassNotFoundException { return type(ref.owner,loader); }
 static Method method(DexIndex.Ref ref,ClassLoader loader)throws ReflectiveOperationException {
  Method m=owner(ref,loader).getDeclaredMethod(ref.name,parameters(ref.type,loader));
  String result=ref.type.substring(ref.type.indexOf(')')+1);
  if(m.getReturnType()!=type(result,loader))throw new NoSuchMethodException("Return type changed: "+ref);
  m.setAccessible(true);return m;
 }
 static Field field(DexIndex.Ref ref,ClassLoader loader)throws ReflectiveOperationException {
  Field field=owner(ref,loader).getDeclaredField(ref.name);
  if(field.getType()!=type(ref.type,loader))throw new NoSuchFieldException("Field type changed: "+ref);
  field.setAccessible(true);return field;
 }
 static Constructor<?> constructor(DexIndex.Ref ref,ClassLoader loader)throws ReflectiveOperationException {
  Constructor<?> c=owner(ref,loader).getDeclaredConstructor(parameters(ref.type,loader));c.setAccessible(true);return c;
 }
 static Class<?>[] parameters(String descriptor,ClassLoader loader)throws ClassNotFoundException {
  List<Class<?>> out=new ArrayList<>();int i=1;
  while(descriptor.charAt(i)!=')') {
   int start=i;while(descriptor.charAt(i)=='[')i++;
   i=descriptor.charAt(i)=='L'?descriptor.indexOf(';',i)+1:i+1;
   if(i<=start)throw new ClassNotFoundException("Invalid descriptor");
   out.add(type(descriptor.substring(start,i),loader));
  }
  return out.toArray(new Class<?>[0]);
 }
 static Class<?> type(String value,ClassLoader loader)throws ClassNotFoundException {
  switch(value) {case "V":return void.class;case "Z":return boolean.class;case "B":return byte.class;case "C":return char.class;
   case "S":return short.class;case "I":return int.class;case "J":return long.class;case "F":return float.class;case "D":return double.class;}
  return Class.forName(value.charAt(0)=='L'?value.substring(1,value.length()-1).replace('/','.') : value.replace('/','.'),false,loader);
 }
}
