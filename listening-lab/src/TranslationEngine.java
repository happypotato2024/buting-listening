package cn.buting.lab;
final class TranslationEngine {
    static { System.loadLibrary("buting_translate"); }
    static native long open(String path,int threads);
    static native byte[] translate(long handle,String text);
    static native void cancel();
    static native void close(long handle);
}
