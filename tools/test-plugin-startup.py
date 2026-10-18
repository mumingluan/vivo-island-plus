"""Exercise the real renderer startup hook with host lifecycle/Xposed stubs.

Checks cold start, publication order, wrong package, repeated lifecycle calls,
late loading, and fail-closed discovery. Does not execute Android rendering.
"""
from pathlib import Path
import subprocess, json

project = Path(__file__).resolve().parents[1]
out = project / 'build/host-plugin-startup'
java = Path('C:/Program Files/Java/jdk-21.0.9+10/bin')
sources = {
'android/app/Application.java': '''package android.app;
public class Application { private final String pkg;
 public Application(String pkg){this.pkg=pkg;} public String getPackageName(){return pkg;}}
''',
'android/app/AndroidAppHelper.java': '''package android.app;
public class AndroidAppHelper {public static Application app;
 public static Application currentApplication(){return app;}}
''',
'android/app/Instrumentation.java': 'package android.app; public class Instrumentation {}',
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
public class XC_MethodHook {public static class MethodHookParam {public Object[] args;}
 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}}
''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public class XposedHelpers {public static XC_MethodHook hook;public static int installs;
 public static void findAndHookMethod(Class<?> owner,String name,Object... args){
  if(!owner.getName().equals("android.app.Instrumentation")||!name.equals("callApplicationOnCreate"))throw new AssertionError("wrong lifecycle");
  installs++;hook=(XC_MethodHook)args[args.length-1];}
 public static void before(android.app.Application app)throws Throwable {
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{app};hook.beforeHookedMethod(p);}}
''',
'com/axuan/vivoislandplus/NativeIslandAdapter.java': '''package com.axuan.vivoislandplus;
final class NativeIslandAdapter {static int calls;static boolean fail;static ClassLoader seen;
 static void installRenderer(ClassLoader loader){if(android.app.AndroidAppHelper.currentApplication()==null)throw new AssertionError("early discovery");
  calls++;seen=loader;if(fail)throw new IllegalStateException("ambiguous contract");}}
''',
'com/axuan/vivoislandplus/IslandPlus.java': '''package com.axuan.vivoislandplus;
final class IslandPlus {static String last;static void log(String text){last=text;}}
''',
'com/axuan/vivoislandplus/PluginRendererStartupTest.java': '''package com.axuan.vivoislandplus;
import android.app.*;import de.robv.android.xposed.*;
public class PluginRendererStartupTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] args)throws Throwable {
  ClassLoader loader=PluginRendererStartupTest.class.getClassLoader();
  Application app=new Application("com.vivo.systemuiplugin");
  String mode=args[0];
  if(mode.equals("late"))AndroidAppHelper.app=app;
  PluginRendererStartup.install(loader);PluginRendererStartup.install(loader);
  if(mode.equals("late")){check(NativeIslandAdapter.calls==1&&XposedHelpers.installs==0,"late binding");}
  else {
   check(NativeIslandAdapter.calls==0&&XposedHelpers.installs==1,"defer cold discovery exactly once");
   XposedHelpers.before(app);check(NativeIslandAdapter.calls==0,"unpublished application");
   Application other=new Application("another.package");AndroidAppHelper.app=other;
   XposedHelpers.before(other);XposedHelpers.before(app);check(NativeIslandAdapter.calls==0,"wrong application");
   AndroidAppHelper.app=app;NativeIslandAdapter.fail=mode.equals("failure");
   XposedHelpers.before(app);XposedHelpers.before(app);
   check(NativeIslandAdapter.calls==1,"one discovery attempt");
   check(IslandPlus.last.contains(mode.equals("failure")?"unavailable":"completed"),"discovery result");
  }
  check(NativeIslandAdapter.seen==loader,"original plugin loader");
  System.out.println("PASS "+mode);
 }
}
''',
}
files=[]
for name, source in sources.items():
    path=out/'src'/name
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(source,'utf8')
    files.append(path)
classes=out/'classes';classes.mkdir(parents=True,exist_ok=True)
subprocess.run([str(java/'javac.exe'),'--release','8','-encoding','UTF-8','-d',str(classes),
                str(project/'PluginRendererStartup.java'),*[str(p) for p in files]],check=True)
results=[]
for mode in ['cold','late','failure']:
    run=subprocess.run([str(java/'java.exe'),'-cp',str(classes),
                        'com.axuan.vivoislandplus.PluginRendererStartupTest',mode],check=True,capture_output=True,text=True)
    print(run.stdout.strip());results.append(run.stdout.strip())
(out/'results.json').write_text(json.dumps({'scope':'host lifecycle stubs; no Android rendering','results':results},indent=2),'utf8')
