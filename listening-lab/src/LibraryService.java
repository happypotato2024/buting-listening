package cn.buting.lab;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground download with resume, disk checks and verified atomic completion. */
public final class LibraryService extends Service {
 static volatile String activeId="",detail="";static volatile long received,total;static volatile LibraryService instance;
 private final AtomicBoolean cancelled=new AtomicBoolean();private volatile HttpURLConnection connection;private PowerManager.WakeLock wake;
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onCreate(){super.onCreate();instance=this;getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("downloads","音频下载",NotificationManager.IMPORTANCE_LOW));}
 static void cancel(){LibraryService s=instance;if(s!=null){s.cancelled.set(true);HttpURLConnection c=s.connection;if(c!=null)c.disconnect();}}
 @Override public int onStartCommand(Intent i,int flags,int startId){if(i==null){stopSelf();return START_NOT_STICKY;}if(!activeId.isEmpty())return START_NOT_STICKY;
  JSONObject track;try{track=AudioLibrary.find(i.getStringExtra("id"));}catch(Exception e){stopSelf();return START_NOT_STICKY;}
  activeId=track.optString("id");detail="下载中";received=0;total=track.optLong("audioBytes");cancelled.set(false);startForeground(2,notification(track));wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"buting:download");wake.acquire(60*60*1000L);
  new Thread(()->{try{download(track);setState(track,"已下载");Diagnostics.event("library_download_complete","id",track.optString("id"),"bytes",total);}
   catch(Throwable e){String message=cancelled.get()?"已暂停，点击继续":friendly(e);setState(track,message);if(cancelled.get())Diagnostics.event("library_download_paused","id",track.optString("id"),"bytes",received);else{Diagnostics.event("library_download_failed","id",track.optString("id"),"bytes",received,"reason",message);Diagnostics.error("library_download",e);}}
   finally{HttpURLConnection c=connection;if(c!=null)c.disconnect();connection=null;if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);activeId="";stopSelf(startId);}},"library-download").start();return START_NOT_STICKY;
 }
 private String friendly(Throwable e){if(e instanceof UnknownHostException||e instanceof SocketTimeoutException||e instanceof java.net.ConnectException)return "网络不可用，点击重试";return "下载失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
 private Notification notification(JSONObject t){PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class).putExtra("library",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"downloads").setSmallIcon(R.drawable.icon).setContentTitle(AudioLibrary.title(t)).setContentText(detail).setContentIntent(open).setProgress(100,total>0?(int)(received*100/total):0,total<=0).setOngoing(true).build();}
 private void setState(JSONObject t,String value){detail=value;AudioLibrary.prefs().edit().putString(t.optString("id"),value).apply();getSystemService(NotificationManager.class).notify(2,notification(t));}
 private void check()throws IOException{if(cancelled.get())throw new IOException("CANCELLED");}
 private void download(JSONObject t)throws Exception{
  if(AudioLibrary.ready(t)){Diagnostics.event("library_cache_hit","id",t.optString("id"));return;}
  File part=AudioLibrary.part(t);long offset=part.length();if(offset>total){if(!part.delete())throw new IOException("无法清理未完成文件");offset=0;}received=offset;
  if(AudioLibrary.dir().getUsableSpace()<total-offset+64L*1024*1024)throw new IOException("存储空间不足，请删除不需要的音频");
  Diagnostics.event("library_download_begin","id",t.optString("id"),"offset",offset,"bytes",total);setState(t,"下载中");
  if(offset<total){
   URL url=new URL(t.getString("url"));HttpURLConnection c=null;
   for(int redirects=0;redirects<6;redirects++){check();if(!url.getProtocol().equals("https"))throw new IOException("音频地址需使用 HTTPS");c=(HttpURLConnection)url.openConnection();connection=c;c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("User-Agent","ButingListeningLab/0.4");if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");int code=c.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("下载重定向错误");url=new URL(url,location);c=null;continue;}break;}
   if(c==null)throw new IOException("下载重定向过多");int code=c.getResponseCode();boolean append=false;
   if(code==206){String range=c.getHeaderField("Content-Range");if(range==null||!range.startsWith("bytes "+offset+"-")||!range.endsWith("/"+total))throw new IOException("服务器续传范围不匹配");append=offset>0;}
   else if(code==200){offset=0;received=0;}else throw new IOException("音频源 HTTP "+code+"，可稍后重试");
   long last=0;try(InputStream in=new BufferedInputStream(c.getInputStream());FileOutputStream raw=new FileOutputStream(part,append);OutputStream out=new BufferedOutputStream(raw)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check();if(received+n>total){out.flush();part.delete();throw new IOException("下载内容长度不符");}if(AudioLibrary.dir().getUsableSpace()<n+32L*1024*1024)throw new IOException("存储空间不足");out.write(b,0,n);received+=n;long now=SystemClock.elapsedRealtime();if(now-last>750){last=now;detail="下载中 "+(received*100/total)+"%";getSystemService(NotificationManager.class).notify(2,notification(t));}}out.flush();raw.getFD().sync();}finally{c.disconnect();connection=null;}
  }
  check();if(part.length()!=total)throw new IOException("下载中断，点击继续");setState(t,"校验中");MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(part))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check();hash.update(b,0,n);}}
  if(!Models.hex(hash.digest()).equals(t.getString("audioHash"))){part.delete();throw new IOException("音频校验失败，点击重新下载");}check();synchronized(RemoteAudioSource.CACHE_LOCK){File target=AudioLibrary.file(t);if(!AudioLibrary.ready(t)){AudioLibrary.marker(t).delete();if(target.exists()&&!target.delete())throw new IOException("无法替换缓存");if(!part.renameTo(target))throw new IOException("音频保存失败");Repository.atomic(AudioLibrary.marker(t),t.getString("audioHash").getBytes("UTF-8"));}else part.delete();}
 }
 @Override public void onTimeout(int id,int type){cancel();}
 @Override public void onDestroy(){cancelled.set(true);if(instance==this)instance=null;super.onDestroy();}
}
