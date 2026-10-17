package com.axuan.vivoislandplus;
import java.io.*;import java.util.*;
public final class MusicResolverProbe {
 public static void main(String[] args)throws Exception {
  DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));long start=System.nanoTime();
  System.out.println(MusicResolver.discover(index).describe());System.out.println("discoveryMs="+(System.nanoTime()-start)/1000000);
 }
}
