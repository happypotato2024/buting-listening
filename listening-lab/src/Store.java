package cn.buting.lab;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
final class Store {
 static JSONObject state;static volatile long revision;
 static File home(){File f=new File(Diagnostics.app.getFilesDir(),"audio");f.mkdirs();return f;}
 static File source(){return new File(home(),"source");}
 static File pcm(){return new File(home(),"audio.pcm");}
 static File stateFile(){return new File(home(),"processing.json");}
 static synchronized JSONObject snapshot(){ensure();try{return new JSONObject(state.toString());}catch(Exception e){throw new RuntimeException(e);}}
 static synchronized void ensure(){if(state!=null)return;try{state=new JSONObject(new String(new AtomicFile(stateFile()).readFully(),StandardCharsets.UTF_8));}catch(Exception e){reset();}}
 static synchronized void reset(){state=new JSONObject();try{state.put("status","empty").put("stage","未导入").put("detail","").put("asrOffsetMs",0).put("tokens",new JSONArray()).put("sentences",new JSONArray());save();}catch(Exception e){throw new RuntimeException(e);}}
 static synchronized void put(String key,Object value){ensure();try{state.put(key,value);save();}catch(Exception e){throw new RuntimeException(e);}}
 static synchronized void update(JSONObject value){state=value;save();}
 static synchronized void progress(String status,String stage,String detail){ensure();try{state.put("status",status).put("stage",stage).put("detail",detail);save();}catch(Exception e){throw new RuntimeException(e);}}
 static synchronized void save(){AtomicFile f=new AtomicFile(stateFile());FileOutputStream out=null;try{out=f.startWrite();out.write(state.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);revision++;}catch(Exception e){if(out!=null)f.failWrite(out);throw new RuntimeException("无法保存处理进度",e);}}
 static synchronized void edit(int index,String english,String chinese,long start,long end){ensure();try{JSONObject s=state.getJSONArray("sentences").getJSONObject(index);boolean changed=!s.optString("text").equals(english);s.put("text",english).put("translation",chinese).put("startMs",start).put("endMs",end).put("manual",true);if(changed&&chinese.isEmpty())s.put("translationStatus","pending");else s.put("translationStatus",chinese.isEmpty()?"pending":"edited");save();Diagnostics.event("sentence_edited","index",index,"startMs",start,"endMs",end);}catch(Exception e){throw new RuntimeException(e);}}
}
