from _build_support import *
import xml.etree.ElementTree as ET
import re, json

KIND = 'island'
if KIND == 'updater':
    android=SDK/'platforms/android-35/android.jar';version='37.0.0';minimum='26'
    sources=list((ROOT/'app/src/main/java').rglob('*.java'))
    manifest=ROOT/'app/src/main/AndroidManifest.xml';resources=ROOT/'app/src/main/res';assets=ROOT/'app/src/main/assets'
    text=(ROOT/'app/build.gradle').read_text('utf-8')
    package=re.search(r"applicationId '([^']+)'",text)[1]
    code=re.search(r'versionCode (\d+)',text)[1];release=re.search(r"versionName '([^']+)'",text)[1]
    tree=ET.parse(manifest);tree.getroot().set('package',package)
    ET.register_namespace('android','http://schemas.android.com/apk/res/android')
    manifest=BUILD/'AndroidManifest.xml';tree.write(manifest,encoding='utf-8',xml_declaration=True)
    output='VivoUpdaterPass-'+release+'.apk'
else:
    android=SDK/'platforms/android-36/android.jar'
    version='36.1.0' if KIND=='e2ee' else '37.0.0'
    minimum='26' if KIND=='e2ee' else '34'
    manifest=ROOT/'AndroidManifest.xml';resources=ROOT/'res';assets=ROOT/'assets'
    release=ET.parse(manifest).getroot().attrib['{http://schemas.android.com/apk/res/android}versionName']
    output=ROOT.name+'-'+release+'.apk'
    if KIND=='island':
        inputs=[p for p in ROOT.glob('*.java') if p.name!='BuildInfo.java']+[manifest,ROOT/'build.py']
        inputs += [p for folder in ['assets','res'] for p in (ROOT/folder).rglob('*') if p.is_file()]
        h=hashlib.sha256()
        for p in sorted(inputs):h.update(p.relative_to(ROOT).as_posix().encode()+b'\0'+p.read_bytes()+b'\0')
        (ROOT/'BuildInfo.java').write_text('package com.axuan.vivoislandplus;\nfinal class BuildInfo { static final String SOURCE_ID = "'+h.hexdigest()+'"; static final String VERSION = "'+release+'"; }\n',encoding='utf-8')
    sources=[p for p in ROOT.glob('*.java') if p.name!='RiskResolverProbe.java']
api=xposed()
classes=BUILD/'classes';dex=BUILD/'dex'
for d in [classes,dex]:
    if d.exists(): shutil.rmtree(d)
    d.mkdir()
run(java_tool('javac'),'--release','8','-Xlint:-options','-encoding','UTF-8','-cp',str(android)+os.pathsep+str(api),'-d',classes,*sources)
compiled=BUILD/'classes.jar'
with zipfile.ZipFile(compiled,'w',zipfile.ZIP_DEFLATED) as z:
    for f in sorted(classes.rglob('*.class')): z.write(f,f.relative_to(classes).as_posix())
run(tool(version,'d8'),'--lib',android,'--lib',api,'--min-api',minimum,'--output',dex,compiled)
unsigned=BUILD/'unsigned.apk'
args=[tool(version,'aapt2'),'link','-I',android,'--manifest',manifest,'-A',assets,'-o',unsigned]
if KIND=='updater':args+=['--min-sdk-version',minimum,'--target-sdk-version','35','--version-code',code,'--version-name',release]
if resources.exists():
    run(tool(version,'aapt2'),'compile','--dir',resources,'-o',BUILD/'resources.zip');args.append(BUILD/'resources.zip')
run(*args)
with zipfile.ZipFile(unsigned,'a') as z:
    z.write(dex/'classes.dex','classes.dex')
    z.write(ROOT/'LICENSE','assets/LICENSE')
    z.write(ROOT/'THIRD_PARTY.md','assets/THIRD_PARTY.md')
sign(unsigned,version,output)
