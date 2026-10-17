package com.axuan.vivoislandplus;
import java.io.*;import java.lang.reflect.*;import java.util.*;
public final class VendorDisplayProbe {
 public static void main(String[] args)throws Exception {
  DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
  if(args.length>1)for(DexIndex.MethodData method:index.methods.values())if(method.ref.name.equals("shouldSuppressVisualEffect")) {
   int[] units=method.codeUnits();System.out.println(method.ref);
   for(int i=0;i<Math.min(40,units.length);i++)System.out.printf("%04x ",units[i]);System.out.println();
   for(DexIndex.Ref ref:method.facts().calls){System.out.println(ref);DexIndex.MethodData child=index.get(ref);if(child!=null)System.out.println("child "+child.facts().instructions+" "+child.facts().calls);}
  }
  VendorDisplayPlan plan=VendorDisplayPlan.discover(index);
  System.out.println(plan.suppression+" mask="+plan.unconditionalMask);
  if(args.length>1) {int[] units=index.get(plan.suppression).codeUnits();for(int i=0;i<Math.min(32,units.length);i++)System.out.printf("%04x ",units[i]);System.out.println();}
  DexIndex.MethodData selected=index.get(plan.suppression);
  Field readerField=DexIndex.MethodData.class.getDeclaredField("reader");readerField.setAccessible(true);
  Object reader=readerField.get(selected);Field bytesField=reader.getClass().getDeclaredField("bytes");bytesField.setAccessible(true);
  byte[] bytes=(byte[])bytesField.get(reader);int[] units=selected.codeUnits();int position=-1;
  for(int at=0;at<units.length;at+=DexIndex.width(units[at]&255))if((units[at]&255)==0xd5){position=selected.code+16+at*2;break;}
  if(position<0)throw new AssertionError("Expected a literal AND in fixture");
  byte[] old=Arrays.copyOfRange(bytes,position,position+4);
  try {
   bytes[position+2]=0;bytes[position+3]=4;
   if(VendorDisplayPlan.discover(index).unconditionalMask!=1024)throw new AssertionError("Changed mask was not discovered");
   bytes[position+2]=1;rejects(index,"non-single-bit mask");
   System.arraycopy(old,0,bytes,position,4);bytes[position]=(byte)0xd0;rejects(index,"arithmetic without masking");
  } finally {System.arraycopy(old,0,bytes,position,4);}
  index.methods.remove(plan.suppression.key());rejects(index,"missing rule");index.methods.put(plan.suppression.key(),selected);
  index.methods.put("ambiguous-probe",selected);rejects(index,"ambiguous rule");index.methods.remove("ambiguous-probe");
  System.out.println("PASS original + changed mask + malformed mask + wrong operation + missing + ambiguous");
 }
 static void rejects(DexIndex index,String reason)throws Exception {try{VendorDisplayPlan.discover(index);}catch(IOException expected){return;}throw new AssertionError("Accepted "+reason);}
}
