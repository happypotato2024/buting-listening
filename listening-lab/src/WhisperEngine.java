package cn.buting.lab;
final class WhisperEngine {
    static { System.loadLibrary("buting_whisper"); }
    static native long open(String path);
    static native byte[] recognize(long handle,float[] samples,int threads);
    static native void cancel();
    static native void close(long handle);
}
