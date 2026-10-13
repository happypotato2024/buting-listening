from pathlib import Path
import tempfile
root=Path(__file__).resolve().parents[1]
source=(root.parent/'listening-lab/tools/qa-repository-build.py').read_text(encoding='utf-8')
source=source.replace("r=Path(__file__).resolve().parents[1];f=r.parent/'listening-android'", "r=Path(__file__).resolve().parents[1];f=r")
source=source.replace("qa/repository-tests","qa/formal-tests").replace('cn.buting.lab.qa','cn.tingjian.qa').replace('android:targetPackage="cn.buting.lab"','android:targetPackage="cn.tingjian.cet6"')
source=source.replace('<instrumentation android:name=".RepositoryChecks" android:targetPackage="cn.tingjian.cet6"/><instrumentation android:name=".V05Checks" android:targetPackage="cn.tingjian.cet6"/>','<instrumentation android:name=".FormalChecks" android:targetPackage="cn.tingjian.cet6"/>')
source=source.replace("glob('*Checks.java')","glob('FormalChecks.java')")
exec(compile(source,str(__file__),'exec'))
