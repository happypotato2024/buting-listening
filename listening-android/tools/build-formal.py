"""Reproducible formal release: established web UI + shared native lab engines.
Large intermediate files live on the system temporary drive.
"""
import os,json,subprocess,tempfile,shutil,hashlib,zipfile,re
from pathlib import Path
root=Path(__file__).resolve().parents[1];lab=root.parent/'listening-lab'
cache=Path(os.environ.get('BUTING_TOOLCHAIN_CACHE',str(Path(tempfile.gettempdir())/'buting-lab-toolchain')));work=cache/'formal-build';work.mkdir(parents=True,exist_ok=True)
jdk=Path(os.environ.get('JAVA_HOME','D:/PyCharm/PyCharm Community Edition 2024.3.1/jbr'));sdk=root/'.toolchain'
android=Path(os.environ.get('BUTING_ANDROID_JAR',str(sdk/'android-35/android.jar')));tools=Path(os.environ.get('BUTING_BUILD_TOOLS',str(sdk/'android-15')))
ndk=Path(os.environ.get('BUTING_NDK_HOME',str(cache/'android-ndk-r27d')))
for abi in ['arm64-v8a','x86_64']:
    for name in ['libbuting_whisper.so','libbuting_translate.so']:
        if not (cache/'libs'/abi/name).is_file():raise FileNotFoundError(f'Missing native library: {abi}/{name}; run listening-lab/tools/native-build.py first')
def run(exe,*args,env=None):subprocess.run([str(exe),*map(str,args)],check=True,env=env)
# Only reset small generated sources/classes within the verified build directory.
for name in ['src','res','generated','classes','dex','assets']:
    target=(work/name).resolve()
    assert target.parent==work.resolve() and work.resolve().parent==cache.resolve()
    if target.exists():shutil.rmtree(target)
    target.mkdir()
shutil.copytree(root/'res',work/'res',dirs_exist_ok=True)
for f in (root/'src').glob('*.java'):shutil.copy2(f,work/'src'/f.name)
for f in (lab/'src').glob('*.java'):
    if f.name=='MainActivity.java':continue
    text=f.read_text(encoding='utf-8').replace('package cn.buting.lab;','package cn.buting.lab;\nimport cn.tingjian.cet6.R;\nimport cn.tingjian.cet6.MainActivity;')
    text=text.replace('R.drawable.icon','R.drawable.app_icon').replace('不听听力·测试版','不听听力').replace('ButingListeningLab/0.4','ButingListening/2.0')
    if f.name=='Diagnostics.java':text=text.replace('"version","0.5.0"','"version","2.0.0"')
    if f.name=='PlaybackService.java':
        text=text.replace('try{if(!value&&completed)', 'try{if(!value&&!failure.isEmpty()){begin(Math.max(start,position),false);return;}if(!value&&completed)')
        text=text.replace('if(instance!=PlaybackService.this)return;', 'if(instance!=PlaybackService.this)return;ListeningTime.tick(PlaybackService.this);')
        text=text.replace('private void stopPlayback(boolean finish){', 'private void stopPlayback(boolean finish){ListeningTime.tick(this);ListeningTime.save();')
    if f.name=='ThermalGovernor.java':
        text=re.sub(r' static int mode\(\).*? private final Runnable poll=', ' static String modeName(){return "固定 2 线程";}\n private final Runnable poll=',text,flags=re.S)
        text=re.sub(r' int threads\(\)\{[^\n]+', ' int threads(){return 2;}',text)
        text=re.sub(r' long restMs\(long workMs\)\{[^\n]+',' long restMs(long workMs){return Math.min(15000,Math.max(0,(long)(workMs*.2)));}',text)
        assert 'mode()' not in text and 'return 2;' in text
    if f.name=='ProcessingService.java':
        text=text.replace('TranslationEngine.open(model.getPath(),1)','TranslationEngine.open(model.getPath(),2)')
        text=text.replace('// Replace only after the selected file has been copied successfully.', 'if(library==null && NativeListening.restoreImported(importedHash,part))return;\n  // Replace only after the selected file has been copied successfully.')
    if f.name=='Repository.java':
        needle='boolean hit=bytes!=null;'
        text=text.replace(needle,'if(bytes==null){try(InputStream in=Diagnostics.app.getAssets().open("metadata/"+t.getString("timingsSha256")+".json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);byte[] seed=out.toByteArray();if(hash(seed).equals(t.getString("timingsSha256")))bytes=seed;}catch(IOException ignored){}}\n  '+needle)
    (work/'src'/f.name).write_text(text,encoding='utf-8')
manifest=(root/'AndroidManifest.xml').read_text(encoding='utf-8')
permissions=['FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','FOREGROUND_SERVICE_MEDIA_PLAYBACK','POST_NOTIFICATIONS','WAKE_LOCK']
manifest=manifest.replace('<application ','\n'.join('<uses-permission android:name="android.permission.'+p+'"/>' for p in permissions)+'\n <application android:name="cn.buting.lab.LabApp" android:extractNativeLibs="false" ',1)
manifest=manifest.replace('</application>',''.join('<service android:name="cn.buting.lab.'+name+'" android:exported="false" android:foregroundServiceType="'+kind+'"/>' for name,kind in [('PlaybackService','mediaPlayback'),('ProcessingService','dataSync'),('LibraryService','dataSync')])+'</application>')
manifest=manifest.replace('android:name=".MainActivity"','android:name=".MainActivity" android:launchMode="singleTop"')
(work/'AndroidManifest.xml').write_text(manifest,encoding='utf-8')
assets=work/'assets';run('node',root/'tools/prepare-formal.mjs',assets/'www')
for f in (lab/'assets').iterdir():shutil.copy2(f,assets/f.name)
license_text='不听听力 2.0.0\nWhisper base.en；Qwen2.5-1.5B-Instruct Q4_K_M（可选）。\n\n'
for folder in ['whisper.cpp-1.8.2','llama.cpp-0.6.0']:license_text+=folder+'\n'+(cache/folder/'LICENSE').read_text(encoding='utf-8')+'\n'
for name in ['OPENAI-WHISPER-LICENSE.txt','APACHE-2.0.txt']:license_text+=(lab/'assets'/name).read_text(encoding='utf-8')+'\n'
(assets/'LICENSES.txt').write_text(license_text,encoding='utf-8');(assets/'www/licenses.txt').write_text(license_text,encoding='utf-8')
run(tools/'aapt2.exe','compile','--dir',work/'res','-o',work/'resources.zip')
run(tools/'aapt2.exe','link','--manifest',work/'AndroidManifest.xml','-I',android,'--min-sdk-version','26','--target-sdk-version','35','--version-code','13','--version-name','2.0.0','--java',work/'generated','-A',assets,'-o',work/'unsigned.apk',work/'resources.zip')
run(jdk/'bin/javac.exe','-encoding','UTF-8','-source','8','-target','8','-classpath',android,'-d',work/'classes',*list((work/'src').glob('*.java')),*list((work/'generated').rglob('*.java')))
run(jdk/'bin/java.exe','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--min-api','26','--output',work/'dex',*list((work/'classes').rglob('*.class')))
with zipfile.ZipFile(work/'unsigned.apk','a') as apk:
    for dex in (work/'dex').glob('*.dex'):apk.write(dex,dex.name,compress_type=zipfile.ZIP_DEFLATED)
    for abi in ['arm64-v8a','x86_64']:
        stripped=work/'stripped'/abi;stripped.mkdir(parents=True,exist_ok=True)
        for lib in (cache/'libs'/abi).glob('*.so'):
            copy=stripped/lib.name;shutil.copy2(lib,copy)
            run(ndk/'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe','--strip-unneeded',copy)
            apk.write(copy,f'lib/{abi}/{lib.name}',compress_type=zipfile.ZIP_STORED)
run(tools/'zipalign.exe','-P','16','-f','4',work/'unsigned.apk',work/'aligned.apk')
env=os.environ.copy()
if not env.get('BUTING_SIGNING_PASSWORD'):env['BUTING_SIGNING_PASSWORD']=json.loads((root/'.signing/config.json').read_text())['password']
keystore=Path(os.environ.get('BUTING_KEYSTORE',str(root/'.signing/tingjian-release.p12')))
alias=os.environ.get('BUTING_KEY_ALIAS','tingjian')
run(jdk/'bin/java.exe','-jar',tools/'lib/apksigner.jar','sign','--ks',keystore,'--ks-key-alias',alias,'--ks-pass','env:BUTING_SIGNING_PASSWORD','--key-pass','env:BUTING_SIGNING_PASSWORD','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',work/'signed.apk',work/'aligned.apk',env=env)
run(jdk/'bin/java.exe','-jar',tools/'lib/apksigner.jar','verify','--verbose',work/'signed.apk')
run(tools/'zipalign.exe','-P','16','-c','4',work/'signed.apk')
digest=hashlib.file_digest((work/'signed.apk').open('rb'),'sha256').hexdigest()
info={'apk':str(work/'signed.apk'),'version':'2.0.0','versionCode':13,'package':'cn.tingjian.cet6','sha256':digest,'bytes':(work/'signed.apk').stat().st_size}
if os.environ.get('FORMAL_DELIVER')=='1':
    output=root.parent/'不听听力-v2.0.0.apk';shutil.copy2(work/'signed.apk',output);Path(str(output)+'.sha256').write_text(digest+'  '+output.name+'\n',encoding='utf-8');info['apk']=str(output)
(root/'build').mkdir(exist_ok=True)
(root/'build/latest-apk.json').write_text(json.dumps(info,ensure_ascii=False),encoding='utf-8');print(json.dumps(info,ensure_ascii=False))
