package cn.buting.lab;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.webkit.JavascriptInterface;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Narrow, local-origin bridge to the shared listening engines. */
public final class NativeListening {
 private final Activity activity;private final BooleanSupplier trusted;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private volatile boolean loading;private volatile String error="";private boolean exportText;
 public NativeListening(Activity activity,BooleanSupplier trusted){this.activity=activity;this.trusted=trusted;}
 private void check(){if(!trusted.getAsBoolean())throw new SecurityException("Local app only");}
 private void task(Runnable work){if(loading){error="正在加载，请稍候";return;}loading=true;error="";worker.execute(()->{try{work.run();}catch(Throwable e){Diagnostics.error("formal_bridge",e);error=e.getMessage()==null?"操作失败，请重试":e.getMessage();}finally{loading=false;}});}
 private File vault(){File f=new File(activity.getFilesDir(),"recordings");f.mkdirs();return f;}
 private void archive()throws Exception{
  JSONObject s=Store.snapshot();String hash=s.optString("sourceSha256");
  if(s.optString("mode").equals("repository")&&s.optString("metadataSha256").matches("[a-f0-9]{64}"))Repository.atomic(new File(Repository.home(),"practice-"+s.getString("metadataSha256")+".json"),s.toString().getBytes("UTF-8"));
  if(!s.optString("mode").equals("repository")&&hash.matches("[a-f0-9]{64}")&&Store.source().exists()){
   File dest=new File(vault(),hash);if(dest.exists())throw new IOException("录音归档冲突，请导出日志");
   if(!Store.home().renameTo(dest))throw new IOException("无法保存当前录音");
  }
  Store.reset();
 }
 static boolean restoreImported(String hash,File part)throws Exception{
  File saved=new File(new File(Diagnostics.app.getFilesDir(),"recordings"),hash);if(!new File(saved,"source").exists())return false;
  JSONObject restored=new JSONObject(new String(Diagnostics.read(new File(saved,"processing.json")),"UTF-8"));if(!hash.equals(restored.optString("sourceSha256")))throw new IOException("录音缓存校验失败");
  for(File f:saved.listFiles()){File dest=new File(Store.home(),f.getName());if(dest.exists()&&!dest.delete())throw new IOException("无法恢复录音缓存");if(!f.renameTo(dest))throw new IOException("无法恢复录音");}saved.delete();part.delete();Store.update(restored);Diagnostics.event("import_reused_archive","sha256",hash);return true;
 }
 private void requireIdle(){if(ProcessingService.busy)throw new IllegalStateException("请先暂停导入处理，再更换资料");}
 @JavascriptInterface public String catalogue(){check();try{JSONArray list=new JSONArray();for(JSONObject t:AudioLibrary.tracks){JSONObject row=new JSONObject(t.toString());row.put("title",AudioLibrary.title(t)).put("cacheState",AudioLibrary.state(t)).put("downloaded",AudioLibrary.ready(t));list.put(row);}File[] dirs=vault().listFiles();if(dirs!=null)for(File dir:dirs){try{JSONObject s=new JSONObject(new String(Diagnostics.read(new File(dir,"processing.json")),"UTF-8"));list.put(new JSONObject().put("id","local-"+dir.getName()).put("title",s.optString("displayName")).put("local",true).put("sentences",s.optJSONArray("sentences")).put("durationMs",s.optLong("durationMs")).put("downloaded",true).put("cacheState","本机录音"));}catch(Exception ignored){}}
  JSONObject s=Store.snapshot();if(!s.optString("mode").equals("repository")&&s.has("sourceSha256"))list.put(new JSONObject().put("id","local-"+s.getString("sourceSha256")).put("title",s.optString("displayName")).put("local",true).put("sentences",s.optJSONArray("sentences")).put("durationMs",s.optLong("durationMs")).put("downloaded",true).put("cacheState",s.optString("stage")));return list.toString();}catch(Exception e){return "[]";}}
 @JavascriptInterface public String snapshot(long revision){check();try{JSONObject r=new JSONObject().put("revision",Store.revision).put("busy",ProcessingService.busy).put("loading",loading).put("error",error).put("listeningSeconds",ListeningTime.snapshot()).put("downloadId",LibraryService.activeId).put("downloadDetail",LibraryService.detail);if(revision!=Store.revision){JSONObject visible=Store.snapshot();visible.remove("tokens");visible.remove("packetIndex");visible.remove("paragraphs");r.put("current",visible);}PlaybackService p=PlaybackService.instance;
  if(p!=null&&p.state!=null)r.put("player",new JSONObject().put("hash",p.state.optString("sourceSha256")).put("whole",p.whole).put("sentence",p.whole?p.indexAt(p.position):p.sentence).put("position",p.position).put("start",p.start).put("end",p.end).put("paused",p.paused).put("completed",p.completed).put("preparing",p.preparing).put("failure",p.failure));return r.toString();}catch(Exception e){return "{}";}}
 @JavascriptInterface public void choose(String id){check();task(()->{try{requireIdle();JSONObject before=Store.snapshot();if(id.equals(before.optString("libraryId"))||id.equals("local-"+before.optString("sourceSha256")))return;
  if(id.startsWith("local-")){String hash=id.substring(6);if(!hash.matches("[a-f0-9]{64}"))throw new IOException("录音编号无效");File dir=new File(vault(),hash);if(!dir.isDirectory())throw new IOException("录音未找到");PlaybackService.stopCurrent();archive();File current=Store.home();for(File f:current.listFiles())if(!f.delete())throw new IOException("无法切换录音");if(!current.delete()||!dir.renameTo(current))throw new IOException("无法恢复录音");synchronized(Store.class){Store.state=null;Store.ensure();Store.revision++;}}
  else{JSONObject loaded=Repository.load(AudioLibrary.find(id),()->false);File saved=new File(Repository.home(),"practice-"+loaded.getString("metadataSha256")+".json");if(saved.exists()){JSONObject prior=new JSONObject(new String(Diagnostics.read(saved),"UTF-8"));if(prior.optString("sourceSha256").equals(loaded.getString("sourceSha256")))loaded=prior;}PlaybackService.stopCurrent();archive();Store.update(loaded);}
 }catch(Exception e){throw new RuntimeException(e.getMessage(),e);}});}
 @JavascriptInterface public void refresh(){check();task(()->{try{Repository.refresh();}catch(Exception e){throw new RuntimeException("目录更新失败，可继续使用已有资料",e);}});}
 @JavascriptInterface public void play(int index,boolean whole,long position,float speed,boolean loop){check();if(loading)return;activity.runOnUiThread(()->{if(whole)PlaybackService.whole(activity,position,speed);else PlaybackService.sentence(activity,index,speed,loop);});}
 @JavascriptInterface public void control(String command,long position,boolean playing,float speed,boolean loop){check();activity.runOnUiThread(()->{try{if(command.equals("seek"))PlaybackService.seek(activity,position,playing);else if(command.equals("speed"))activity.startForegroundService(new Intent(activity,PlaybackService.class).setAction("speed").putExtra("speed",speed).putExtra("loop",loop));else if(java.util.Arrays.asList("pause","resume","toggle","stop","next","previous").contains(command))PlaybackService.send(activity,command);}catch(Exception e){error=e.getMessage();}});}
 @JavascriptInterface public void download(String id){check();activity.runOnUiThread(()->activity.startForegroundService(new Intent(activity,LibraryService.class).putExtra("id",id)));}
 @JavascriptInterface public void pauseDownload(){check();LibraryService.cancel();}
 @JavascriptInterface public void removeDownload(String id){check();task(()->{try{AudioLibrary.remove(AudioLibrary.find(id));}catch(Exception e){throw new RuntimeException(e.getMessage(),e);}});}
 @JavascriptInterface public void process(String action){check();if(action.equals("pause")){ProcessingService.cancel();return;}if(!action.equals("resume")&&!action.equals("offlineTranslate"))return;activity.runOnUiThread(()->{if(!loading&&!ProcessingService.busy){PlaybackService.stopCurrent();activity.startForegroundService(new Intent(activity,ProcessingService.class).setAction(action));}});}
 @JavascriptInterface public void importAudio(){check();activity.runOnUiThread(()->{if(loading||ProcessingService.busy){error="请先暂停当前处理";return;}new AlertDialog.Builder(activity).setTitle("导入录音").setMessage("本机识别首次下载约 148 MB 模型。默认联网翻译，只向 Google 发送英文文本，录音留在本机。已有录音会保留。").setNegativeButton("取消",null).setPositiveButton("选择音频",(d,w)->activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),210)).show();});}
 @JavascriptInterface public void exportLogs(String note,boolean includeText){check();Diagnostics.event("user_note","note",note);exportText=includeText;activity.runOnUiThread(()->activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"不听听力-使用日志.zip"),211));}
 public boolean result(int request,int result,Intent data){if(request!=210&&request!=211)return false;if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;Uri uri=data.getData();if(request==211){task(()->{try{Diagnostics.export(activity,uri,exportText);}catch(Exception e){throw new RuntimeException("日志导出失败",e);}});return true;}
  try{activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}task(()->{try{requireIdle();PlaybackService.stopCurrent();archive();activity.runOnUiThread(()->activity.startForegroundService(new Intent(activity,ProcessingService.class).setAction("import").setData(uri)));}catch(Exception e){throw new RuntimeException(e.getMessage(),e);}});return true;}
 @JavascriptInterface public void note(int index,String issue){check();Diagnostics.event("user_feedback","sentence",index,"issue",issue);}
 @JavascriptInterface public void edit(int index,String text,String translation,long start,long end){check();requireIdle();if(loading)throw new IllegalStateException("正在加载资料");if(text.trim().isEmpty()||start<0||end<=start||end>Store.snapshot().optLong("durationMs"))throw new IllegalArgumentException("原文或时间范围无效");Store.edit(index,text.trim(),translation.trim(),start,end);}
 public void close(){worker.shutdown();}
}
