package cn.buting.lab;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Each catalogue resolves to an immutable commit; metadata binds to exact audio. */
final class Repository {
 static final String REPO="happypotato2024/buting-listening-resources";
 static File home(){File f=new File(Diagnostics.app.getFilesDir(),"repository");f.mkdirs();return f;}
 static byte[] get(String address,int limit,BooleanSupplier cancel)throws Exception{
  URL url=new URL(address);HttpURLConnection c=null;
  try{for(int i=0;i<6;i++){AudioDecoder.check(cancel);if(!url.getProtocol().equals("https"))throw new IOException("资料地址必须使用 HTTPS");c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("User-Agent","ButingListeningLab/0.4");int status=c.getResponseCode();if(status>=300&&status<400){String location=c.getHeaderField("Location");c.disconnect();c=null;if(location==null)throw new IOException("资料重定向失败");url=new URL(url,location);continue;}if(status!=200)throw new IOException("资料源 HTTP "+status);break;}
   if(c==null)throw new IOException("资料重定向过多");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){AudioDecoder.check(cancel);if(out.size()+n>limit)throw new IOException("资料文件过大");out.write(b,0,n);}return out.toByteArray();}
  }finally{if(c!=null)c.disconnect();}
 }
 static void atomic(File file,byte[] bytes)throws IOException{AtomicFile a=new AtomicFile(file);FileOutputStream out=null;try{out=a.startWrite();out.write(bytes);a.finishWrite(out);}catch(IOException e){if(out!=null)a.failWrite(out);throw e;}}
 static String hash(byte[] bytes)throws Exception{return Models.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 static String url(String commit,String path)throws Exception{if(!commit.matches("[0-9a-f]{40}"))throw new IOException("资料版本无效");if(path.startsWith("/")||path.contains("..")||path.contains("\\"))throw new IOException("资料路径无效");StringBuilder encoded=new StringBuilder();for(String part:path.split("/")){if(encoded.length()>0)encoded.append('/');encoded.append(URLEncoder.encode(part,"UTF-8").replace("+","%20"));}return "https://raw.githubusercontent.com/"+REPO+"/"+commit+"/"+encoded;}
 static List<JSONObject> normalize(JSONObject index,String commit)throws Exception{
  JSONArray items=index.getJSONArray("tracks");if(items.length()==0||items.length()>3000)throw new IOException("资料目录无效");List<JSONObject> result=new ArrayList<>();Set<String> ids=new HashSet<>();
  for(int i=0;i<items.length();i++){JSONObject source=items.getJSONObject(i),t=new JSONObject(source.toString());String id=t.getString("id"),name=t.getString("name");if(!ids.add(id)||!t.getString("audioSha256").matches("[0-9a-f]{64}")||!t.getString("timingsSha256").matches("[0-9a-f]{64}"))throw new IOException("资料校验信息缺失");if(t.getLong("audioBytes")<=0||t.getLong("audioBytes")>1024L*1024*1024)throw new IOException("资料音频长度无效");
   String[] parts=id.split("-");String date=t.optString("date");
   if(parts.length==5){date=parts[2]+"."+parts[3];t.put("set",Integer.parseInt(parts[4]));}
   t.put("date",date).put("audioHash",t.getString("audioSha256")).put("url",url(commit,t.getString("audio"))).put("metadataUrl",url(commit,t.getString("timings"))).put("repositoryRevision",commit).put("prepared",true);result.add(t);
  }return result;
 }
 static List<JSONObject> initial()throws Exception{
  JSONObject bundle;try(InputStream in=Diagnostics.app.getAssets().open("repository.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);bundle=new JSONObject(out.toString("UTF-8"));}
  File cached=new File(home(),"catalogue.json");try{JSONObject saved=new JSONObject(new String(new AtomicFile(cached).readFully(),StandardCharsets.UTF_8));if(saved.getJSONObject("catalogue").optInt("contentRevision")>=bundle.getJSONObject("catalogue").optInt("contentRevision"))return normalize(saved.getJSONObject("catalogue"),saved.getString("commit"));}catch(Exception ignored){}return normalize(bundle.getJSONObject("catalogue"),bundle.getString("commit"));
 }
 static void refresh()throws Exception{
  String commit;try{JSONObject ref=new JSONObject(new String(get("https://api.github.com/repos/"+REPO+"/git/ref/heads/main",128*1024,()->false),StandardCharsets.UTF_8));commit=ref.getJSONObject("object").getString("sha");}catch(Exception api){String refs=new String(get("https://github.com/"+REPO+".git/info/refs?service=git-upload-pack",256*1024,()->false),StandardCharsets.UTF_8);java.util.regex.Matcher m=java.util.regex.Pattern.compile("([0-9a-f]{40}) refs/heads/main").matcher(refs);if(!m.find())throw new IOException("资料分支读取失败",api);commit=m.group(1);Diagnostics.event("repository_git_ref_fallback","commit",commit);}
  JSONObject index=new JSONObject(new String(get(url(commit,"catalog.json"),2*1024*1024,()->false),StandardCharsets.UTF_8));List<JSONObject> parsed=normalize(index,commit);atomic(new File(home(),"catalogue.json"),new JSONObject().put("commit",commit).put("catalogue",index).toString().getBytes(StandardCharsets.UTF_8));AudioLibrary.tracks=parsed;Diagnostics.event("repository_refresh","commit",commit,"tracks",parsed.size());
 }
 static JSONObject load(JSONObject t,BooleanSupplier cancelled)throws Exception{
  File cache=new File(home(),t.getString("timingsSha256")+".json");byte[] bytes=null;if(cache.exists()){bytes=Diagnostics.read(cache);if(!hash(bytes).equals(t.getString("timingsSha256"))){cache.delete();bytes=null;}}
  boolean hit=bytes!=null;if(!hit){bytes=get(t.getString("metadataUrl"),8*1024*1024,cancelled);if(!hash(bytes).equals(t.getString("timingsSha256")))throw new IOException("原文或时间标记版本校验失败");}
  JSONObject document=new JSONObject(new String(bytes,StandardCharsets.UTF_8)),audio=document.getJSONObject("audio");if(!document.getString("id").equals(t.getString("id"))||!audio.getString("sha256").equals(t.getString("audioHash"))||audio.getLong("bytes")!=t.getLong("audioBytes"))throw new IOException("原文和录音不是同一份资料");
  JSONArray input=document.getJSONArray("sentences"),output=new JSONArray();Set<String> ids=new HashSet<>();double duration=audio.getDouble("duration"),previous=-1;
  for(int i=0;i<input.length();i++){JSONObject s=new JSONObject(input.getJSONObject(i).toString());double a=s.getDouble("start"),b=s.getDouble("end");if(!Double.isFinite(a)||!Double.isFinite(b)||a<0||b<=a||b>duration||a<previous-.004||s.getString("text").trim().isEmpty()||!ids.add(s.getString("id")))throw new IOException("时间标记无效");previous=b;s.put("startMs",Math.round(a*1000)).put("endMs",Math.round(b*1000)).put("translationStatus","reference").put("timingReview",s.optBoolean("boundaryReviewRecommended"));output.put(s);}
  if(output.length()!=t.getInt("sentences"))throw new IOException("句子资料不完整");AudioDecoder.check(cancelled);if(!hit)atomic(cache,bytes);
  Diagnostics.event("repository_metadata","id",t.getString("id"),"commit",t.getString("repositoryRevision"),"metadataSha256",t.getString("timingsSha256"),"audioSha256",t.getString("audioHash"),"sentences",output.length(),"cacheHit",hit);
  return new JSONObject().put("mode","repository").put("contentRevision",document.optInt("contentRevision")).put("status","ready").put("stage","已就绪").put("detail","").put("displayName",AudioLibrary.title(t)).put("libraryId",t.getString("id")).put("repositoryRevision",t.getString("repositoryRevision")).put("metadataSha256",t.getString("timingsSha256")).put("sourceSha256",t.getString("audioHash")).put("audioBytes",t.getLong("audioBytes")).put("audioUrl",t.getString("url")).put("durationMs",Math.round(duration*1000)).put("sentences",output).put("tokens",new JSONArray()).put("asrComplete",true).put("paragraphs",document.getJSONArray("paragraphs")).put("packetIndex",document.opt("packetIndex"));
 }
 static JSONObject identity(JSONObject state,int index,long start,long end)throws Exception{JSONObject r=new JSONObject().put("libraryId",state.optString("libraryId")).put("repositoryRevision",state.optString("repositoryRevision")).put("metadataSha256",state.optString("metadataSha256")).put("audioSha256",state.optString("sourceSha256")).put("startMs",start).put("endMs",end).put("index",index);if(index>=0)r.put("sentenceId",state.getJSONArray("sentences").getJSONObject(index).optString("id","index-"+index));return r;}
}
