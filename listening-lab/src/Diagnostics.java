package cn.buting.lab;
import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

final class Diagnostics {
 static Context app; static File dir; static String session=UUID.randomUUID().toString();
 static synchronized void init(Context ctx){if(app!=null)return;app=ctx.getApplicationContext();dir=new File(app.getFilesDir(),"logs");dir.mkdirs();
  event("launch","device",Build.MANUFACTURER+" "+Build.MODEL,"sdk",Build.VERSION.SDK_INT,"abi",Arrays.toString(Build.SUPPORTED_ABIS),"version","0.5.0","session",session,"diskFree",app.getFilesDir().getUsableSpace(),"maxJavaMemory",Runtime.getRuntime().maxMemory(),"cores",Runtime.getRuntime().availableProcessors());
  ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();((ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);event("memory","available",memory.availMem,"total",memory.totalMem,"low",memory.lowMemory);
  if(Build.VERSION.SDK_INT>=30)try{
   for(ApplicationExitInfo info:((ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE)).getHistoricalProcessExitReasons(null,0,3))event("previous_exit","exitTimestamp",info.getTimestamp(),"reason",info.getReason(),"description",info.getDescription(),"pssKb",info.getPss());
  }catch(Exception e){error("exit_info",e);}
 }
 static synchronized void event(String name,Object... fields){try{
  JSONObject row=new JSONObject();row.put("time",new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(new Date()));row.put("uptimeMs",SystemClock.elapsedRealtime());row.put("event",name);row.put("session",session);
  for(int i=0;i+1<fields.length;i+=2)row.put(String.valueOf(fields[i]),fields[i+1]);
  File file=new File(dir,"usage.jsonl");if(file.length()>3*1024*1024){File oldest=new File(dir,"usage-2.jsonl");oldest.delete();File previous=new File(dir,"usage-1.jsonl");previous.renameTo(oldest);file.renameTo(previous);}
  try(FileOutputStream out=new FileOutputStream(file,true)){out.write((row.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
 }catch(Exception e){Log.e("ButingLab","Log write failed",e);}}
 static void error(String stage,Throwable e){event("error","stage",stage,"type",e.getClass().getName(),"message",String.valueOf(e.getMessage()),"stack",Log.getStackTraceString(e));}
 static void export(Context ctx,android.net.Uri uri,boolean withText)throws Exception {
  event("export_begin","includeText",withText);
  try(OutputStream out=ctx.getContentResolver().openOutputStream(uri);ZipOutputStream zip=new ZipOutputStream(out)){
   JSONObject snapshot=Store.snapshot();snapshot.remove("packetIndex");
   String summary="不听听力测试日志 / 0.5.0\n处理模式："+ThermalGovernor.modeName()+"\n温控记录中的温度是电池温度，不是处理器温度。\n设备："+Build.MANUFACTURER+" "+Build.MODEL+"\nAndroid API："+Build.VERSION.SDK_INT+"\nABI："+Arrays.toString(Build.SUPPORTED_ABIS)+"\n状态："+snapshot.optString("status")+"\n阶段："+snapshot.optString("stage")+"\n当前进度："+snapshot.optString("detail")+"\n句数："+snapshot.optJSONArray("sentences").length()+"\n说明：无音频、无模型文件；时间和播放范围保留。文本仅在勾选后包含。\n";
   add(zip,"说明.txt",summary.getBytes(StandardCharsets.UTF_8));
   File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".jsonl"))add(zip,f.getName(),read(f));
   if(!withText){snapshot.remove("tokens");snapshot.remove("paragraphs");JSONArray sentences=snapshot.optJSONArray("sentences");for(int i=0;i<sentences.length();i++){JSONObject s=sentences.getJSONObject(i);s.remove("text");s.remove("translation");s.remove("paragraphTranslation");}snapshot.remove("displayName");}
   add(zip,"processing.json",snapshot.toString(2).getBytes(StandardCharsets.UTF_8));
  }catch(Exception e){event("export_failed");throw e;}
  event("export_complete");
 }
 static byte[] read(File f)throws IOException {try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
 static void add(ZipOutputStream zip,String name,byte[] data)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
}
