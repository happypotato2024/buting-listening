"""Build a separate instrumentation APK, never add test hooks to the app."""
import json,os,subprocess,tempfile,zipfile
from pathlib import Path
r=Path(__file__).resolve().parents[1];f=r.parent/'listening-android';c=Path(tempfile.gettempdir())/'buting-lab-toolchain';w=c/'qa/repository-tests';w.mkdir(exist_ok=True)
jdk=Path('D:/PyCharm/PyCharm Community Edition 2024.3.1/jbr');sdk=f/'.toolchain';tools=sdk/'android-15';android=c/'apk-build/android.jar'
def run(exe,*args,env=None):subprocess.run([str(exe),*map(str,args)],check=True,env=env)
(w/'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="cn.buting.lab.qa"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Repository QA"/><instrumentation android:name=".RepositoryChecks" android:targetPackage="cn.buting.lab"/><instrumentation android:name=".V05Checks" android:targetPackage="cn.buting.lab"/></manifest>')
for d in ['classes','dex']:(w/d).mkdir(exist_ok=True)
run(tools/'aapt2.exe','link','--manifest',w/'AndroidManifest.xml','-I',android,'-o',w/'unsigned.apk')
run(jdk/'bin/javac.exe','-encoding','UTF-8','-source','8','-target','8','-classpath',android,'-d',w/'classes',*list((r/'tools').glob('*Checks.java')))
run(jdk/'bin/java.exe','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--min-api','26','--output',w/'dex',*list((w/'classes').rglob('*.class')))
with zipfile.ZipFile(w/'unsigned.apk','a') as z:
 for p in (w/'dex').glob('*.dex'):z.write(p,p.name)
run(tools/'zipalign.exe','-f','4',w/'unsigned.apk',w/'aligned.apk')
env=os.environ.copy();env['BUTING_SIGNING_PASSWORD']=json.loads((f/'.signing/config.json').read_text())['password']
run(jdk/'bin/java.exe','-jar',tools/'lib/apksigner.jar','sign','--ks',f/'.signing/tingjian-release.p12','--ks-key-alias','tingjian','--ks-pass','env:BUTING_SIGNING_PASSWORD','--key-pass','env:BUTING_SIGNING_PASSWORD','--out',w/'tests.apk',w/'aligned.apk',env=env)
print(w/'tests.apk')
