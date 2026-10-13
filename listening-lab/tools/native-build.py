import os,sys,tempfile,subprocess,shutil
from pathlib import Path
root=Path(__file__).resolve().parents[1]
cache=Path(tempfile.gettempdir())/'buting-lab-toolchain'
cmake=cache/'cmake/bin/cmake.exe'
ndk=cache/'android-ndk-r27d'
stage=cache/'native-src';stage.mkdir(exist_ok=True)
for f in (root/'native').iterdir():shutil.copy2(f,stage/f.name)
abi=sys.argv[1] if len(sys.argv)>1 else 'arm64-v8a'
for engine,source,target in [('whisper','whisper.cpp-1.8.2','buting_whisper'),('llama','llama.cpp-0.6.0','buting_translate')]:
    build=cache/f'build-{engine}-{abi}'
    args=[str(cmake),'-S',str(stage),'-B',str(build),'-G','Ninja',f'-DCMAKE_MAKE_PROGRAM={cache}/cmake/bin/ninja.exe',f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',f'-DANDROID_ABI={abi}','-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release',f'-DENGINE={engine}',f'-DENGINE_SOURCE={cache}/{source}']
    subprocess.run(args,check=True)
    subprocess.run([str(cmake),'--build',str(build),'--target',target,'-j','4'],check=True)
    output=cache/'libs'/abi;output.mkdir(parents=True,exist_ok=True)
    shutil.copy2(build/f'lib{target}.so',output/f'lib{target}.so')
print('Native libraries ready',abi)
