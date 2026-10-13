package cn.buting.lab;
import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
import java.util.function.BooleanSupplier;
import org.json.*;

final class AudioDecoder {
 static final int RATE=16000;
 static long decode(File input,File destination,BooleanSupplier cancelled)throws Exception {
  File partial=new File(destination.getPath()+".partial");MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
  long begin=SystemClock.elapsedRealtime();
  try(BufferedOutputStream output=new BufferedOutputStream(new FileOutputStream(partial),65536)){
   extractor.setDataSource(input.getPath());int track=-1;MediaFormat format=null;
   for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;format=f;break;}}
   if(track<0)throw new IOException("文件中没有可解码的音轨");extractor.selectTrack(track);
   String mime=format.getString(MediaFormat.KEY_MIME);int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
   long duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION)/1000:0;
   Diagnostics.event("decode_begin","mime",mime,"sampleRate",rate,"channels",channels,"durationMs",duration);
   Resampler converter=new Resampler(rate,channels,encoding(format),output);long lastProgress=0;
   if(mime.equals("audio/raw")){
    ByteBuffer buffer=ByteBuffer.allocate(4*1024*1024);
    while(true){check(cancelled);buffer.clear();int n=extractor.readSampleData(buffer,0);if(n<0)break;buffer.limit(n);buffer.position(0);converter.feed(buffer);extractor.advance();if(SystemClock.elapsedRealtime()-lastProgress>1000){progress(converter,duration);lastProgress=SystemClock.elapsedRealtime();}}
   }else{
    codec=MediaCodec.createDecoderByType(mime);codec.configure(format,null,null,0);codec.start();boolean inputEnd=false,outputEnd=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long lastData=SystemClock.elapsedRealtime();
    while(!outputEnd){check(cancelled);
     if(!inputEnd){int index=codec.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=codec.getInputBuffer(index);int n=extractor.readSampleData(buffer,0);if(n<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true;}else{codec.queueInputBuffer(index,0,n,Math.max(0,extractor.getSampleTime()),0);extractor.advance();}}}
     int index=codec.dequeueOutputBuffer(info,10000);
     if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();if(converter.frames!=0)throw new IOException("音频格式在解码中发生变化");converter=new Resampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding(f),output);}
     else if(index>=0){if(info.size>0){ByteBuffer buffer=codec.getOutputBuffer(index).duplicate();buffer.position(info.offset);buffer.limit(info.offset+info.size);converter.feed(buffer.slice());lastData=SystemClock.elapsedRealtime();}outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);}
     if(SystemClock.elapsedRealtime()-lastData>30000)throw new IOException("音频解码超时");
     if(SystemClock.elapsedRealtime()-lastProgress>1000){progress(converter,duration);lastProgress=SystemClock.elapsedRealtime();}
    }
   }
   converter.finish();output.flush();if(converter.outputFrames<800)throw new IOException("音频为空或长度不足 0.05 秒");
   if(!partial.renameTo(destination)){if(destination.exists()&&!destination.delete())throw new IOException("旧音频无法替换");if(!partial.renameTo(destination))throw new IOException("音频保存失败");}
   long length=converter.outputFrames*1000/RATE;Diagnostics.event("decode_complete","durationMs",length,"frames",converter.outputFrames,"elapsedMs",SystemClock.elapsedRealtime()-begin);return length;
  }catch(Exception e){if(cancelled.getAsBoolean())throw e;String message=e.getMessage();if(message!=null&&message.matches("(?s).*[\\u3400-\\u9fff].*"))throw e;throw new IOException("无法解码：文件损坏或系统不支持此音频格式",e);}finally{extractor.release();if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}partial.delete();}
 }
 static int encoding(MediaFormat f){return f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;}
 static void check(BooleanSupplier cancel)throws IOException{if(cancel.getAsBoolean())throw new IOException("CANCELLED");}
 static void progress(Resampler r,long duration)throws IOException{if(Store.home().getUsableSpace()<32*1024*1024)throw new IOException("空间不足，无法继续解码");Store.progress("running","解码",String.format(java.util.Locale.US,"%.1f / %.1f 秒",r.outputFrames/16000.0,duration/1000.0));}
 static final class Resampler {
  final int rate,channels,encoding;final OutputStream output;long frames,outputFrames;double next;float previous;final byte[] bytes=new byte[32768];int used;
  Resampler(int rate,int channels,int encoding,OutputStream output)throws IOException{if(rate<=0||channels<1||channels>32)throw new IOException("无效音频格式");if(encoding!=2&&encoding!=3&&encoding!=4&&encoding!=21&&encoding!=22)throw new IOException("不支持的 PCM 编码："+encoding);this.rate=rate;this.channels=channels;this.encoding=encoding;this.output=output;}
  void feed(ByteBuffer b)throws IOException{b.order(ByteOrder.LITTLE_ENDIAN);int size=encoding==3?1:encoding==2?2:encoding==21?3:4;int count=channels*size;if(b.remaining()%count!=0)throw new IOException("PCM 数据不是完整的采样帧");while(b.remaining()>=count){float value=0;for(int c=0;c<channels;c++){if(encoding==2)value+=b.getShort()/32768f;else if(encoding==3)value+=((b.get()&255)-128)/128f;else if(encoding==4)value+=b.getFloat();else if(encoding==21){int v=(b.get()&255)|((b.get()&255)<<8)|(b.get()<<16);value+=v/8388608f;}else value+=b.getInt()/2147483648f;}value/=channels;if(!Float.isFinite(value))value=0;
   if(frames==0){write(value);next=rate/16000.0;}else while(next<=frames){float at=(float)(next-(frames-1));write(previous+(value-previous)*at);next+=rate/16000.0;}previous=value;frames++;}}
  void write(float v)throws IOException{int s=Math.max(-32768,Math.min(32767,Math.round(v*32768)));bytes[used++]=(byte)s;bytes[used++]=(byte)(s>>8);outputFrames++;if(used==bytes.length){output.write(bytes,0,used);used=0;}}
  void finish()throws IOException{while(next<frames){write(previous);next+=rate/16000.0;}if(used>0)output.write(bytes,0,used);}
 }
 static float[] samples(long startMs,long endMs)throws IOException{long start=Math.max(0,startMs)*16,end=Math.min(Store.pcm().length()/2,endMs*16);int size=(int)(end-start);if(size<=0)throw new IOException("无效音频范围");float[] result=new float[size];try(RandomAccessFile file=new RandomAccessFile(Store.pcm(),"r")){file.seek(start*2);byte[] b=new byte[size*2];file.readFully(b);for(int i=0;i<size;i++)result[i]=(short)((b[i*2]&255)|(b[i*2+1]<<8))/32768f;}return result;}
}
