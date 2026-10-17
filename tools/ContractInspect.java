package com.axuan.vivoislandplus;
import java.io.*;
import java.util.*;

/** Local diagnostic only: decoded references and instructions in original firmware APKs. */
public final class ContractInspect {
    public static void main(String[] args) throws Exception {
        DexIndex index=DexIndex.readApks(Collections.singletonList(new File(args[0])));
        for(DexIndex.MethodData method:index.methods.values()) {
            boolean match=false;
            for(int i=1;i<args.length;i++) {
                if(args[i].equals("--summary"))continue;
                if(args[i].startsWith("call:")) {
                    for(DexIndex.Ref ref:method.facts().calls)if(ref.toString().contains(args[i].substring(5)))match=true;
                } else if(method.ref.toString().contains(args[i]) || method.facts().text(args[i]))match=true;
            }
            if(!match)continue;
            System.out.println("METHOD "+method.ref+" regs="+method.registerCount()+" ins="+method.incomingWords());
            System.out.println("STRINGS "+method.facts().strings);
            if(Arrays.asList(args).contains("--summary"))continue;
            for(DexIndex.Ref field:method.facts().fields)System.out.println(" FIELD "+field);
            int[] code=method.codeUnits();
            for(int at=0;at<code.length;) {
                int op=code[at]&255;if(op==0&&code[at]!=0)break;
                int width=DexIndex.width(op);if(width<=0||at+width>code.length)break;
                String ref="";
                if(op>=0x52&&op<=0x6d)ref=" "+method.fieldAt(at);
                else if(op>=0x6e&&op<=0x72||op>=0x74&&op<=0x78)ref=" "+method.invokedAt(at);
                else if(op==0x22||op==0x1f)ref=" "+method.typeAt(at);
                StringBuilder line=new StringBuilder(String.format(" %04x:",at));
                for(int j=0;j<width;j++)line.append(String.format(" %04x",code[at+j]));
                System.out.println(line+ref);at+=width;
            }
        }
    }
}
