package cn.buting.lab;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProcessingService extends Service {
 static volatile boolean busy;static volatile ProcessingService instance;private final AtomicBoolean cancelled=new AtomicBoolean();private PowerManager.WakeLock wake;private volatile boolean asrActive,translationActive;private boolean offlineTranslation,onlineAvailable=true,firstPublished;private long pipelineStarted;private ThermalGovernor governor;private volatile boolean computing,interrupted;private int computeThreads;
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onCreate(){super.onCreate();instance=this;NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("processing","音频处理",NotificationManager.IMPORTANCE_LOW));}
 @Override public int onStartCommand(Intent intent,int flags,int id){if(intent==null){stopSelf();return START_NOT_STICKY;}if("cancel".equals(intent.getAction())){cancelWork();return START_NOT_STICKY;}if(busy)return START_NOT_STICKY;
  busy=true;cancelled.set(false);offlineTranslation="offlineTranslate".equals(intent.getAction());onlineAvailable=true;firstPublished=false;pipelineStarted=SystemClock.elapsedRealtime();startForeground(1,notification("准备处理"));wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"buting:processing");wake.acquire(6*60*60*1000L);
  governor=new ThermalGovernor(this,()->{if(computing){interrupted=true;if(asrActive)WhisperEngine.cancel();if(translationActive)TranslationEngine.cancel();}});
  new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);long start=SystemClock.elapsedRealtime();try{Diagnostics.event("pipeline_begin","action",intent.getAction());if("import".equals(intent.getAction())||"sample".equals(intent.getAction())||"library".equals(intent.getAction()))importFile(intent);
   runPipeline();Diagnostics.event("pipeline_complete","elapsedMs",SystemClock.elapsedRealtime()-start);
  }catch(Throwable e){if(cancelled.get())Diagnostics.event("pipeline_paused","elapsedMs",SystemClock.elapsedRealtime()-start);else Diagnostics.error("pipeline",e);try{Store.progress(cancelled.get()?"paused":"error",cancelled.get()?"已暂停":"处理失败",cancelled.get()?"进度已保存，可继续处理":readable(e));}catch(Exception ignored){}}
  finally{computing=false;if(governor!=null)governor.close();busy=false;asrActive=false;translationActive=false;if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}},"offline-pipeline").start();return START_NOT_STICKY;
 }
 private String readable(Throwable e){if(e instanceof OutOfMemoryError)return "内存不足：关闭其他应用后重试";if(e instanceof UnsatisfiedLinkError)return "本机无法加载离线引擎，请导出日志";return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 static void cancel(){ProcessingService service=instance;if(service!=null)service.cancelWork();}
 private void cancelWork(){cancelled.set(true);OnlineTranslation.cancel();Diagnostics.event("pipeline_cancel_requested");if(asrActive)try{WhisperEngine.cancel();}catch(Throwable ignored){}if(translationActive)try{TranslationEngine.cancel();}catch(Throwable ignored){}}
 private void check()throws IOException{AudioDecoder.check(cancelled::get);}
 private Notification notification(String detail){Intent open=new Intent(this,MainActivity.class);PendingIntent p=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"processing").setSmallIcon(R.drawable.icon).setContentTitle("不听听力·测试版").setContentText(detail).setContentIntent(p).setOngoing(true).build();}
 private void stage(String name,String detail){checkQuiet();Store.progress("running",name,detail);getSystemService(NotificationManager.class).notify(1,notification(name+" "+detail));}
 private void checkQuiet(){if(cancelled.get())throw new IllegalStateException("CANCELLED");}
 private void importFile(Intent intent)throws Exception{
  String name="内置测试录音";Uri uri=intent.getData();JSONObject library=null;if("library".equals(intent.getAction())){library=AudioLibrary.find(intent.getStringExtra("id"));if(!AudioLibrary.ready(library))throw new IOException("请先下载这套音频");name=AudioLibrary.title(library);}if(uri!=null)try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}
  stage("导入",name);File part=new File(Store.home(),"import.partial");long count=0,lastProgress=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
  try(InputStream in=library!=null?new FileInputStream(AudioLibrary.file(library)):uri==null?getAssets().open("demo.wav"):getContentResolver().openInputStream(uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(part))){if(in==null)throw new IOException("无法读取选中的文件");byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){check();if(Store.home().getUsableSpace()<n+64*1024*1024)throw new IOException("音频导入空间不足");out.write(b,0,n);digest.update(b,0,n);count+=n;long now=SystemClock.elapsedRealtime();if(now-lastProgress>500){lastProgress=now;stage("导入",String.format(java.util.Locale.US,"%.1f MB",count/1000000.0));}}}catch(Exception e){part.delete();throw e;}
  if(count==0){part.delete();throw new IOException("文件为空");}
  String importedHash=Models.hex(digest.digest());if(library!=null&&!importedHash.equals(library.getString("audioHash"))){part.delete();AudioLibrary.marker(library).delete();throw new IOException("音频缓存校验失败，请重新下载");}if(library==null)for(JSONObject candidate:AudioLibrary.tracks)if(candidate.optString("audioHash").equals(importedHash)){library=candidate;break;}
  if(importedHash.equals(Store.snapshot().optString("sourceSha256"))&&Store.source().exists()&&Store.pcm().exists()&&!Store.snapshot().optString("mode").equals("repository")){part.delete();Diagnostics.event("import_reused_current","sha256",importedHash);return;}
  // Replace only after the selected file has been copied successfully.
  if(Store.source().exists()&&!Store.source().delete())throw new IOException("无法替换旧录音");if(!part.renameTo(Store.source()))throw new IOException("音频导入保存失败");Store.pcm().delete();Store.reset();Store.put("displayName",name);Store.put("sourceBytes",count);Store.put("sourceSha256",importedHash);if(library!=null)Store.put("libraryId",library.optString("id"));Diagnostics.event("import_complete","bytes",count,"sha256",Store.snapshot().optString("sourceSha256"));
 }
 private int threads(){return governor.threads();}
 private void releaseWake(){if(wake!=null&&wake.isHeld())wake.release();}
 private void acquireWake(){if(wake!=null&&!wake.isHeld())wake.acquire(6*60*60*1000L);}
 private void waitForBudget()throws Exception{JSONObject before=Store.snapshot();final boolean[] waited={false};governor.await(cancelled::get,name->{waited[0]=true;stage(name,"进度已保存，自动继续");},this::releaseWake,this::acquireWake);check();if(waited[0])stage(before.optString("stage"),before.optString("detail"));}
 private void rest(long workMs)throws Exception{governor.rest(workMs,cancelled::get,name->stage(name,""),this::releaseWake,this::acquireWake);}
 private byte[] recognize(long handle,float[] pcm)throws Exception{while(true){waitForBudget();interrupted=false;computing=true;try{computeThreads=threads();byte[] result=WhisperEngine.recognize(handle,pcm,computeThreads);check();if(!interrupted)return result;}catch(Exception e){check();if(!interrupted)throw e;}finally{computing=false;}Diagnostics.event("compute_interrupted","kind","asr","reason",governor.reason());}}
 private String translate(long handle,String text)throws Exception{while(true){waitForBudget();interrupted=false;computing=true;try{String result=new String(TranslationEngine.translate(handle,text),StandardCharsets.UTF_8).trim();check();if(!interrupted)return result;}catch(Exception e){check();if(!interrupted)throw e;}finally{computing=false;}Diagnostics.event("compute_interrupted","kind","translation","reason",governor.reason());}}

 private void runPipeline()throws Exception{
  if(!Store.source().exists())throw new IOException("请先导入录音");JSONObject state=Store.snapshot();long duration=state.optLong("durationMs");
  if(!state.optString("libraryId").isEmpty()){JSONObject track=AudioLibrary.find(state.getString("libraryId"));if(track.optString("audioHash").equals(state.optString("sourceSha256"))){stage("加载配套原文","");JSONObject prepared=Repository.load(track,cancelled::get);prepared.put("localImport",true).put("sourceBytes",Store.source().length());Store.update(prepared);Diagnostics.event("import_matched_repository","id",track.getString("id"),"audioSha256",track.getString("audioHash"));return;}}
  if(!Store.pcm().exists()||duration<=0){waitForBudget();stage("解码","");duration=AudioDecoder.decode(Store.source(),Store.pcm(),cancelled::get);Store.put("durationMs",duration);}
  check();state=Store.snapshot();
  if(!state.optBoolean("asrComplete")){
   File model=Models.ensure(Models.ASR,Models.ASR_HASH,cancelled::get);waitForBudget();stage("识别","加载模型");long handle=0;
   try{asrActive=true;long begin=SystemClock.elapsedRealtime();handle=WhisperEngine.open(model.getPath());Diagnostics.event("asr_model_loaded","elapsedMs",SystemClock.elapsedRealtime()-begin,"threads",threads());
    long offset=state.optLong("asrOffsetMs",0);JSONArray words=state.getJSONArray("tokens");
    for(;offset<duration;offset+=28000){waitForBudget();check();long end=Math.min(duration,offset+30000);stage("识别",String.format(java.util.Locale.US,"%.1f / %.1f 秒",offset/1000.0,duration/1000.0));float[] pcm=AudioDecoder.samples(offset,end);double rms=0;for(float v:pcm)rms+=v*v;rms=Math.sqrt(rms/pcm.length);begin=SystemClock.elapsedRealtime();
     JSONArray raw=rms<.0015?new JSONArray():new JSONArray(new String(recognize(handle,pcm),StandardCharsets.UTF_8));check();long workMs=SystemClock.elapsedRealtime()-begin;JSONArray owned=Sentences.owned(raw,offset,duration);Sentences.merge(words,owned,offset);
     JSONObject checkpoint=Store.snapshot();checkpoint.put("tokens",words).put("asrOffsetMs",offset+28000);Store.update(checkpoint);Diagnostics.event("asr_chunk","startMs",offset,"endMs",end,"tokens",raw.length(),"words",owned.length(),"rms",rms,"threads",rms<.0015?0:computeThreads,"processCpuMs",android.os.Process.getElapsedCpuTime(),"elapsedMs",workMs);
     publishWords(words,end==duration?duration:Math.min(duration,offset+29000),end==duration);
     if(!offlineTranslation&&onlineAvailable)fillOnline();
     if(end==duration)break;rest(workMs);
    }
    JSONArray sentences=Store.snapshot().getJSONArray("sentences");if(sentences.length()==0)throw new IOException("未识别到英语语句，可播放原音频或更换录音重试");JSONObject checkpoint=Store.snapshot();checkpoint.put("asrComplete",true);Store.update(checkpoint);Diagnostics.event("sentence_assembly","count",sentences.length());
   }finally{if(handle!=0)WhisperEngine.close(handle);asrActive=false;}
  }
  check();JSONArray sentences=Store.snapshot().getJSONArray("sentences");boolean pending=false;for(int i=0;i<sentences.length();i++)if(sentences.getJSONObject(i).optString("translation").isEmpty())pending=true;
  if(!offlineTranslation){if(pending&&onlineAvailable)fillOnline();sentences=Store.snapshot().getJSONArray("sentences");int missing=0;for(int i=0;i<sentences.length();i++)if(sentences.getJSONObject(i).optString("translation").isEmpty())missing++;Store.progress(missing>0?"partial":"ready","可练习","共 "+sentences.length()+" 句"+(missing>0?" · "+missing+" 句译文待重试":""));return;}
  int failures=0;
  if(pending){File model=Models.ensure(Models.TRANSLATOR,Models.TRANSLATOR_HASH,cancelled::get);waitForBudget();stage("翻译","加载模型");long handle=0;
   try{translationActive=true;long begin=SystemClock.elapsedRealtime();handle=TranslationEngine.open(model.getPath(),1);Diagnostics.event("translation_model_loaded","elapsedMs",SystemClock.elapsedRealtime()-begin);
    for(int i=0;i<sentences.length();i++){check();JSONObject sentence=sentences.getJSONObject(i);if(!sentence.optString("translation").isEmpty())continue;stage("翻译",(i+1)+" / "+sentences.length());begin=SystemClock.elapsedRealtime();
     try{String text=translate(handle,sentence.getString("text"));check();if(!text.matches("(?s).*[\\u3400-\\u9fff].*"))throw new IOException("翻译模型未生成中文，请修正原文后重试");OnlineTranslation.apply(sentence,text);sentence.put("translationProvider","Qwen local");Diagnostics.event("translation_sentence","index",i,"elapsedMs",SystemClock.elapsedRealtime()-begin,"characters",text.length());}
     catch(Exception e){check();sentence.put("translationStatus","failed").put("translationError",readable(e));Diagnostics.error("translation_"+i,e);failures++;}
     JSONObject checkpoint=Store.snapshot();checkpoint.put("sentences",sentences);Store.update(checkpoint);if(i+1<sentences.length())rest(SystemClock.elapsedRealtime()-begin);
    }
   }finally{if(handle!=0)TranslationEngine.close(handle);translationActive=false;}
  }
  check();Store.progress(failures>0?"partial":"ready",failures>0?"部分译文失败":"处理完成",failures>0?failures+" 句译文待重试":"共 "+sentences.length()+" 句");
 }
 private void publishWords(JSONArray words,long covered,boolean complete)throws Exception{
  JSONArray rows=Sentences.assemble(new JSONArray(words.toString()),covered);if(!complete&&rows.length()>0)rows.remove(rows.length()-1);JSONObject snapshot=Store.snapshot();JSONArray previous=snapshot.getJSONArray("sentences");Map<String,JSONObject> translated=new HashMap<>();for(int i=0;i<previous.length();i++){JSONObject s=previous.getJSONObject(i);if(!s.optString("translation").isEmpty())translated.put(s.getString("text"),s);}
  for(int i=0;i<rows.length();i++){JSONObject s=rows.getJSONObject(i);s.put("id","import-"+Repository.hash((s.getString("text")+":"+s.getLong("startMs")).getBytes("UTF-8")).substring(0,20)).put("translationScope","sentence");JSONObject saved=translated.get(s.getString("text"));if(saved!=null){s.put("translation",saved.getString("translation")).put("translationStatus",saved.optString("translationStatus","machine")).put("translationProvider",saved.optString("translationProvider"));}}
  snapshot.put("sentences",rows);Store.update(snapshot);if(rows.length()>0&&!firstPublished){firstPublished=true;Diagnostics.event("import_first_sentences","elapsedMs",SystemClock.elapsedRealtime()-pipelineStarted,"sentences",rows.length(),"asrComplete",complete);}
 }
 private void fillOnline()throws Exception{JSONObject snapshot=Store.snapshot();JSONArray sentences=snapshot.getJSONArray("sentences");if(sentences.length()==0)return;stage("联网译文","共 "+sentences.length()+" 句");int ready=OnlineTranslation.fill(sentences,cancelled::get);check();snapshot=Store.snapshot();snapshot.put("sentences",sentences);Store.update(snapshot);onlineAvailable=ready==sentences.length();}
 @Override public void onTimeout(int startId,int fgsType){cancelWork();Store.progress("paused","处理超时","进度已保存，可继续处理");stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
 @Override public void onDestroy(){instance=null;super.onDestroy();}
}
