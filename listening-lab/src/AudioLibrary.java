package cn.buting.lab;
import android.content.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Pinned, attributed catalogue. Recordings are fetched on demand, never bundled. */
final class AudioLibrary {
 static Context context;static volatile List<JSONObject> tracks=new ArrayList<>();
 static void init(Context c){context=c.getApplicationContext();try{tracks=Repository.initial();}catch(Exception e){Diagnostics.error("library_catalogue",e);}}
 static JSONObject find(String id){for(JSONObject t:tracks)if(t.optString("id").equals(id))return t;throw new IllegalArgumentException("音频不在资料库中");}
 static File dir(){File d=new File(context.getFilesDir(),"library");d.mkdirs();return d;}
 static File file(JSONObject t){return new File(dir(),t.optString("audioHash")+".audio");}
 static File part(JSONObject t){return new File(dir(),t.optString("audioHash")+".partial");}
 static File marker(JSONObject t){return new File(dir(),t.optString("audioHash")+".verified");}
 static boolean ready(JSONObject t){return file(t).length()==t.optLong("audioBytes")&&marker(t).exists();}
 static String title(JSONObject t){String[] date=t.optString("date").split("\\.");if(date.length!=2)return t.optString("name",t.optString("id"));try{return date[0]+" 年 "+Integer.parseInt(date[1])+" 月 · 第 "+t.optInt("set")+" 套";}catch(NumberFormatException e){return t.optString("name",t.optString("id"));}}
 static SharedPreferences prefs(){return context.getSharedPreferences("library",Context.MODE_PRIVATE);}
 static String state(JSONObject t){if(ready(t))return "已下载";if(t.optString("id").equals(LibraryService.activeId))return LibraryService.detail;String prior=prefs().getString(t.optString("id"),"");if(prior.startsWith("下载中"))return "下载中断，点击继续";return prior.isEmpty()?String.format(Locale.US,"%.1f MB",t.optLong("audioBytes")/1000000.0):prior;}
 static void remove(JSONObject t)throws IOException{if(t.optString("id").equals(LibraryService.activeId))throw new IOException("请先暂停下载");for(File f:new File[]{file(t),marker(t),part(t)})if(f.exists()&&!f.delete())throw new IOException("无法删除缓存");prefs().edit().remove(t.optString("id")).apply();Diagnostics.event("library_deleted","id",t.optString("id"));}
}
