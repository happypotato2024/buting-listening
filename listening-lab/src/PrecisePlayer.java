package cn.buting.lab;
import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

final class PrecisePlayer {
 private final AtomicLong generation=new AtomicLong();volatile boolean playing,paused;volatile long positionMs;volatile AudioTrack active;volatile RemoteAudioSource fetching;volatile String failure="",phase="";
 synchronized void pause(){paused=true;AudioTrack t=active;if(t!=null)try{t.pause();}catch(Exception ignored){}Diagnostics.event("play_pause","positionMs",positionMs);}
 synchronized void resume(){paused=false;AudioTrack t=active;if(t!=null)try{t.play();}catch(Exception ignored){}Diagnostics.event("play_resume","positionMs",positionMs);}
 synchronized void stop(){generation.incrementAndGet();playing=false;phase="";RemoteAudioSource source=fetching;if(source!=null)source.close();fetching=null;AudioTrack t=active;if(t!=null)try{t.pause();t.flush();}catch(Exception ignored){}Diagnostics.event("play_stop","positionMs",positionMs);}
 void play(int sentence,long startMs,long endMs,float speed,boolean loop){playState(Store.snapshot(),sentence,startMs,endMs,speed,loop);}
 void playState(JSONObject state,int sentence,long startMs,long endMs,float speed,boolean loop){playFrom(state,sentence,startMs,endMs,startMs,speed,loop,false);}
 void playFrom(JSONObject state,int sentence,long startMs,long endMs,long fromMs,float speed,boolean loop,boolean initiallyPaused){stop();long ticket=generation.get();paused=initiallyPaused;positionMs=fromMs;playing=true;failure="";phase="准备播放";
  new Thread(()->{AudioTrack track=null;long started=SystemClock.elapsedRealtime();try{
   JSONObject identity=Repository.identity(state,sentence,startMs,endMs);Diagnostics.event("play_request","identity",identity,"generation",ticket);
   File pcm;long first,last,origin;
   if(state.optString("mode").equals("repository")){pcm=RangeDecoder.decode(state,startMs,endMs,()->ticket!=generation.get(),source->{synchronized(this){if(ticket==generation.get())fetching=source;else source.close();}});first=0;last=pcm.length()/2;origin=startMs;}
   else{pcm=Store.pcm();first=Math.max(0,startMs*16);last=Math.min(pcm.length()/2,endMs*16);origin=first/16;}
   long rangeFirst=first;first+=Math.max(0,(Math.min(fromMs,endMs)-startMs)*16);origin=Math.max(startMs,fromMs);
   if(last<=first){positionMs=endMs;return;}if(ticket!=generation.get())return;
   int buffer=Math.max(8192,AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT));
   track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build();
   if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("音频播放设备初始化失败");synchronized(this){if(ticket!=generation.get())return;active=track;fetching=null;track.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1));phase="播放中";}
   Diagnostics.event("play_begin","identity",identity,"sentence",sentence,"startMs",startMs,"endMs",endMs,"speed",speed,"loop",loop,"pcmFrames",last-first,"mapping",pcm.getName(),"generation",ticket);
   long playedFrames=0;int repeats=0;
   do{long before=playedFrames;try(RandomAccessFile file=new RandomAccessFile(pcm,"r")){file.seek(first*2);long bytes=(last-first)*2;byte[] data=new byte[4096];synchronized(this){if(ticket!=generation.get())break;if(!paused)track.play();}
    while(bytes>0&&ticket==generation.get()){int n=file.read(data,0,(int)Math.min(bytes,data.length));if(n<0)throw new IOException("PCM 文件不完整");int at=0;while(at<n&&ticket==generation.get()){if(paused){Thread.sleep(80);continue;}int sent=track.write(data,at,n-at,AudioTrack.WRITE_NON_BLOCKING);if(sent<0)throw new IOException("播放错误："+sent);if(sent==0)Thread.sleep(10);else at+=sent;positionMs=origin+Math.max(0,head(track)-before)/16;}bytes-=n;playedFrames+=n/2;}
   }
   long deadline=SystemClock.elapsedRealtime()+(long)((last-first)/16.0/speed)+10000;
   while(ticket==generation.get()&&head(track)<playedFrames){if(paused){long pauseAt=SystemClock.elapsedRealtime();Thread.sleep(80);deadline+=SystemClock.elapsedRealtime()-pauseAt;continue;}positionMs=origin+Math.max(0,head(track)-before)/16;if(SystemClock.elapsedRealtime()>deadline)throw new IOException("播放设备没有完成输出");Thread.sleep(15);}
   if(ticket!=generation.get())break;positionMs=endMs;Diagnostics.event("play_range_complete","identity",identity,"sentence",sentence,"iteration",++repeats,"expectedFrames",playedFrames,"headFrames",head(track),"elapsedMs",SystemClock.elapsedRealtime()-started,"generation",ticket);
   first=rangeFirst;origin=startMs;
   }while(loop&&ticket==generation.get());
  }catch(Exception e){if(ticket==generation.get()){failure="播放失败，请联网重试；日志已记录";Diagnostics.error("play",e);}else Diagnostics.event("play_cancelled","generation",ticket);}finally{if(track!=null){synchronized(this){if(active==track)active=null;}try{track.stop();}catch(Exception ignored){}track.release();}synchronized(this){if(ticket==generation.get()){playing=false;phase="";fetching=null;}}}
  },"sentence-player").start();
 }
 private long head(AudioTrack t){return t.getPlaybackHeadPosition()&0xffffffffL;}
}
