package com.axuan.vivoislandplus;

public final class ReplyApiCompatTest {
    static String reason;static boolean flag;static Object selected;static int calls;
    public static class OldBouncer { public void showBouncer(String value,boolean scrim){reason=value;flag=scrim;calls++;} }
    public static final class NewBouncer { public void showBouncer(boolean scrim,String value){reason=value;flag=scrim;calls++;} }
    public static final class ChildBouncer extends OldBouncer { }
    public static final class AmbiguousBouncer extends OldBouncer { public void showBouncer(boolean scrim,String value){} }
    public static final class WrongBouncer { public int showBouncer(boolean scrim,String value){return 0;} }
    public static final class StaticBouncer { public static void showBouncer(boolean scrim,String value){} }
    public static final class LegacyEntry { }
    public static final class EntryAdapter { }
    public static class LegacyGroup { public void setGroupExpanded(LegacyEntry value,boolean expand){selected=value;flag=expand;calls++;} }
    public static final class ModernGroup { public void setGroupExpanded(EntryAdapter value,boolean expand){selected=value;flag=expand;calls++;} }
    public static final class AmbiguousGroup extends LegacyGroup { public void setGroupExpanded(EntryAdapter value,boolean expand){} }
    public static final class LegacyParent {
        final LegacyEntry entry=new LegacyEntry();
        public LegacyEntry getEntry(){return entry;}
        public EntryAdapter getEntryAdapter(){throw new AssertionError("Inactive new UI getter invoked");}
    }
    public static final class ModernParent {
        final EntryAdapter adapter=new EntryAdapter();
        public EntryAdapter getEntryAdapter(){return adapter;}
        public LegacyEntry getEntry(){throw new AssertionError("Legacy getter invoked");}
    }
    public static final class NullParent { public EntryAdapter getEntryAdapter(){return null;} }
    public static final class WrongParent { public Object getEntryAdapter(){return new EntryAdapter();} }
    interface Work { void run()throws Exception; }
    static void expect(boolean value){if(!value)throw new AssertionError();}
    static void reject(Work work)throws Exception {
        int before=calls;
        try{work.run();}catch(ReflectiveOperationException expected){expect(calls==before);return;}
        throw new AssertionError("Accepted incompatible reply API");
    }
    public static void main(String[] args)throws Exception {
        for(Object bouncer:new Object[]{new OldBouncer(),new NewBouncer(),new ChildBouncer()}){
            ReplyApiCompat.showBouncer(bouncer,"reply",false);expect(reason.equals("reply")&&!flag);
            ReplyApiCompat.showBouncer(bouncer,"second",true);expect(reason.equals("second")&&flag);
        }
        reject(()->ReplyApiCompat.showBouncer(new AmbiguousBouncer(),"reply",false));
        reject(()->ReplyApiCompat.showBouncer(new WrongBouncer(),"reply",false));
        reject(()->ReplyApiCompat.showBouncer(new StaticBouncer(),"reply",false));
        reject(()->ReplyApiCompat.showBouncer(new Object(),"reply",false));
        LegacyParent old=new LegacyParent();ModernParent modern=new ModernParent();
        ReplyApiCompat.expandGroup(new LegacyGroup(),old);expect(selected==old.entry&&flag);
        ReplyApiCompat.expandGroup(new ModernGroup(),modern);expect(selected==modern.adapter&&flag);
        reject(()->ReplyApiCompat.expandGroup(new AmbiguousGroup(),old));
        reject(()->ReplyApiCompat.expandGroup(new ModernGroup(),new WrongParent()));
        reject(()->ReplyApiCompat.expandGroup(new ModernGroup(),new NullParent()));
        reject(()->ReplyApiCompat.expandGroup(new ModernGroup(),old));
        System.out.println("PASS both bouncer orders/inheritance, exact entry/adapter forwarding, inactive getter avoidance and missing/ambiguous/static/return/null refusal; calls="+calls);
    }
}
