import hashlib,json,re,subprocess,tempfile,zipfile,io
from PIL import Image
from pathlib import Path
root=Path(__file__).resolve().parents[1];work=Path(tempfile.gettempdir())/'buting-lab-toolchain/formal-build';apk=work/'signed.apk'
tools=root/'.toolchain/android-15';java=Path('D:/PyCharm/PyCharm Community Edition 2024.3.1/jbr/bin/java.exe')
def out(*cmd):return subprocess.check_output(list(map(str,cmd)),stderr=subprocess.STDOUT).decode('utf-8',errors='replace')
def cert(p):
    text=out(java,'-jar',tools/'lib/apksigner.jar','verify','--print-certs',p)
    return re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',text).group(1)
assert cert(apk)==cert(root.parent/'不听听力-v1.10.0.apk')
badging=out(tools/'aapt2.exe','dump','badging',apk)
assert "package: name='cn.tingjian.cet6' versionCode='13' versionName='2.0.0'" in badging
assert "launchable-activity: name='cn.tingjian.cet6.MainActivity'" in badging
assert "minSdkVersion:'26'" in badging or "sdkVersion:'26'" in badging
for permission in ['FOREGROUND_SERVICE_MEDIA_PLAYBACK','POST_NOTIFICATIONS','WAKE_LOCK']:assert permission in badging
assert "native-code: 'arm64-v8a' 'x86_64'" in badging
with zipfile.ZipFile(apk) as z:
    names=z.namelist();assert not any(n.endswith(('.mp3','.wav','.m4a','.gguf','.bin')) for n in names)
    bundle=json.loads(z.read('assets/repository.json'));tracks=bundle['catalogue']['tracks'];count=0
    for t in tracks:
        data=z.read('assets/metadata/'+t['timingsSha256']+'.json');assert hashlib.sha256(data).hexdigest()==t['timingsSha256'];doc=json.loads(data)
        assert doc['audio']['sha256']==t['audioSha256']
        for s in doc['sentences']:assert s['translationScope']=='sentence' and s['translation'].strip()
        count+=len(doc['sentences'])
    assert len(tracks)==29 and count==4507
    packed_icon=Image.open(io.BytesIO(z.read('res/drawable/app_icon.png'))).convert('RGBA')
    source_icon=Image.open(root/'content/app-icon.png').convert('RGBA')
    assert packed_icon.size==source_icon.size and packed_icon.tobytes()==source_icon.tobytes()
    assert b'cn.buting.lab.MainActivity' not in z.read('classes.dex')
assert 'int threads(){return 2;}' in (work/'src/ThermalGovernor.java').read_text('utf-8')
assert 'TranslationEngine.open(model.getPath(),2)' in (work/'src/ProcessingService.java').read_text('utf-8')
assert 'static void mode(' not in (work/'src/ThermalGovernor.java').read_text('utf-8')
out(tools/'zipalign.exe','-P','16','-c','4',apk)
report={'package':'cn.tingjian.cet6','version':'2.0.0','versionCode':13,'bytes':apk.stat().st_size,'sha256':hashlib.file_digest(apk.open('rb'),'sha256').hexdigest(),'upgradeSignatureMatches':True,'recordings':29,'sentenceTranslations':count,'bundledAudio':0,'bundledModels':0,'recognitionThreads':2,'localTranslationThreads':2,'abis':['arm64-v8a','x86_64'],'alignment16KB':True}
report_dir=root/'.qa/v2';report_dir.mkdir(parents=True,exist_ok=True)
(report_dir/'package-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False))
