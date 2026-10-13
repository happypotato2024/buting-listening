package cn.buting.lab;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Best-effort free online translation; unavailable service never blocks listening. */
final class OnlineTranslation {
 static volatile HttpURLConnection active;
 static File home(){File f=new File(Diagnostics.app.getFilesDir(),"sentence-translations");f.mkdirs();return f;}
 static void cancel(){HttpURLConnection c=active;if(c!=null)c.disconnect();}
 static String cached(String text)throws Exception{File f=new File(home(),Repository.hash(text.getBytes("UTF-8"))+".json");try{JSONObject o=new JSONObject(new String(Diagnostics.read(f),"UTF-8"));if(o.getString("text").equals(text)&&chinese(o.getString("translation"))){f.setLastModified(System.currentTimeMillis());return o.getString("translation");}}catch(IOException|JSONException ignored){}return "";}
 static boolean chinese(String text){return text.matches("(?s).*[\\u3400-\\u9fff].*");}
 static void save(String text,String translation)throws Exception{Repository.atomic(new File(home(),Repository.hash(text.getBytes("UTF-8"))+".json"),new JSONObject().put("text",text).put("translation",translation).toString().getBytes("UTF-8"));}
 static String request(String text,BooleanSupplier cancelled)throws Exception{
  AudioDecoder.check(cancelled);HttpURLConnection c=(HttpURLConnection)new URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=zh-CN&dt=t&q="+URLEncoder.encode(text,"UTF-8")).openConnection();active=c;c.setConnectTimeout(5000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","ButingListening/0.4");
  try{int status=c.getResponseCode();if(status!=200)throw new IOException("翻译服务 HTTP "+status);ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){AudioDecoder.check(cancelled);if(out.size()+n>128*1024)throw new IOException("译文响应过大");out.write(b,0,n);}}JSONArray rows=new JSONArray(out.toString("UTF-8")).getJSONArray(0);StringBuilder translated=new StringBuilder();for(int i=0;i<rows.length();i++)translated.append(rows.getJSONArray(i).optString(0,""));AudioDecoder.check(cancelled);if(!chinese(translated.toString()))throw new IOException("翻译服务未返回中文");return translated.toString().trim();}finally{c.disconnect();if(active==c)active=null;}
 }
 static void apply(JSONObject sentence,String translation)throws Exception{sentence.put("translation",translation).put("translationScope","sentence").put("translationStatus","machine").put("translationProvider","Google Translate").put("translationSourceSha256",Repository.hash(sentence.getString("text").getBytes("UTF-8")));sentence.remove("translationError");}
 static int fill(JSONArray sentences,BooleanSupplier cancelled)throws Exception{
  List<JSONObject> pending=new ArrayList<>();int ready=0;
  for(int i=0;i<sentences.length();i++){JSONObject s=sentences.getJSONObject(i);if(!s.optString("translation").isEmpty()){ready++;continue;}String hit=cached(s.getString("text"));if(!hit.isEmpty()){apply(s,hit);ready++;}else pending.add(s);}
  for(int start=0;start<pending.size();){AudioDecoder.check(cancelled);int end=start;StringBuilder source=new StringBuilder();while(end<pending.size()&&end-start<12){String row=String.format(Locale.US,"[[%04d]]\n",end-start)+pending.get(end).getString("text")+"\n";if(end>start&&source.length()+row.length()>2500)break;source.append(row);end++;}
   try{String response=request(source.toString(),cancelled);Matcher matches=Pattern.compile("\\[\\[\\s*(\\d{4})\\s*\\]\\]\\s*([\\s\\S]*?)(?=\\[\\[\\s*\\d{4}\\s*\\]\\]|$)").matcher(response);List<String> translations=new ArrayList<>();boolean valid=true;while(matches.find()){if(Integer.parseInt(matches.group(1))!=translations.size()||!chinese(matches.group(2)))valid=false;translations.add(matches.group(2).trim());}
    if(!valid||translations.size()!=end-start){translations.clear();for(int i=start;i<end;i++)translations.add(request(pending.get(i).getString("text"),cancelled));}
    for(int i=start;i<end;i++){JSONObject s=pending.get(i);String text=s.getString("text"),translated=translations.get(i-start);save(text,translated);apply(s,translated);ready++;}Diagnostics.event("online_translation_batch","sentences",end-start,"provider","Google Translate");
   }catch(Exception e){AudioDecoder.check(cancelled);Diagnostics.error("online_translation_unavailable",e);break;}start=end;
  }trim();return ready;
 }
 static void trim(){File[] f=home().listFiles((d,n)->n.endsWith(".json"));if(f==null)return;Arrays.sort(f,Comparator.comparingLong(File::lastModified));long total=0;for(File file:f)total+=file.length();for(File file:f){if(total<=20L*1024*1024)break;long bytes=file.length();if(file.delete())total-=bytes;}}
}
