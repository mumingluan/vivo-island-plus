package com.axuan.vivoislandplus;

/** Host reflection checks; no Android classes or singleton initialization during binding. */
public final class LegacyNightActionsTest {
    static int initialized,calls;static boolean background;static Object received;
    public static final class Present {
        public static final Present INSTANCE=initialize();
        static Present initialize(){initialized++;return new Present();}
        public void updateAodDisplayForDefaultForLiveNotification(Object value,boolean light){calls++;received=value;background=light;}
    }
    public static final class Absent { }
    public static final class WrongReturn {
        public static final WrongReturn INSTANCE=new WrongReturn();
        public int updateAodDisplayForDefaultForLiveNotification(Object value,boolean light){return 0;}
    }
    public static final class WrongStatic {
        public static final WrongStatic INSTANCE=new WrongStatic();
        public static void updateAodDisplayForDefaultForLiveNotification(Object value,boolean light){}
    }
    public static final class WrongSingleton {
        public static final Object INSTANCE=new WrongSingleton();
        public void updateAodDisplayForDefaultForLiveNotification(Object value,boolean light){}
    }
    static void expect(boolean value){if(!value)throw new AssertionError();}
    static void reject(Class<?> type)throws Exception {
        try{LegacyNightActions.bind(type,Object.class);}catch(ReflectiveOperationException expected){return;}
        throw new AssertionError("Accepted incompatible legacy API "+type);
    }
    public static void main(String[] args)throws Exception {
        LegacyNightActions plan=LegacyNightActions.bind(Present.class,Object.class);
        expect(plan.supported()&&initialized==0&&calls==0);
        Object actions=new Object();plan.update(actions,true);
        expect(initialized==1&&calls==1&&received==actions&&background);
        plan.update(actions,false);expect(initialized==1&&calls==2&&!background);
        LegacyNightActions missing=LegacyNightActions.bind(Absent.class,Object.class);
        expect(!missing.supported());missing.update(actions,true);expect(calls==2);
        LegacyNightActions missingClass=LegacyNightActions.resolve(new ClassLoader(null){},Object.class);
        expect(!missingClass.supported());missingClass.update(actions,false);expect(calls==2);
        reject(WrongReturn.class);reject(WrongStatic.class);reject(WrongSingleton.class);
        System.out.println("PASS legacy helper forwarding, absent API/class, lazy singleton, wrong return/static/singleton refusal");
    }
}
