package cn.buting.lab;
import java.io.*;
import java.security.*;
import java.net.*;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;
final class Models {
 static final String ASR="ggml-base.en.bin",TRANSLATOR="qwen2.5-1.5b-instruct-q4_k_m.gguf";
 static final String ASR_HASH="a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",TRANSLATOR_HASH="6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e";
 static File ensure(String name,String hash,BooleanSupplier cancel)throws Exception{
  File home=new File(Diagnostics.app.getFilesDir(),"models");home.mkdirs();File model=new File(home,name),valid=new File(home,name+".verified");
  if(model.exists()&&valid.exists()&&new String(Diagnostics.read(valid),"UTF-8").equals(hash+":"+model.length())){Diagnostics.event("model_ready","model",name,"sha256",hash,"bytes",model.length());return model;}
  File part=new File(home,name+".partial");long expected=name.equals(ASR)?147964211L:1117320736L;
  if(home.getUsableSpace()<expected-part.length()+64*1024*1024)throw new IOException("空间不足，自行导入的离线处理模型需要约 1.3 GB 空闲空间");
  long begin=SystemClock.elapsedRealtime();Diagnostics.event("model_install_begin","model",name,"sha256",hash,"expectedBytes",expected);MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0,last=0;
  InputStream bundled=null;try{bundled=Diagnostics.app.getAssets().open("models/"+name);}catch(IOException ignored){}
  if(bundled!=null){try(InputStream in=bundled;OutputStream out=new BufferedOutputStream(new FileOutputStream(part))){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){AudioDecoder.check(cancel);out.write(b,0,n);count+=n;}}}
  else download(name,part,expected,cancel);
  count=part.length();try(InputStream in=new BufferedInputStream(new FileInputStream(part))){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){AudioDecoder.check(cancel);digest.update(b,0,n);}}
  String actual=hex(digest.digest());if(!actual.equals(hash)||count!=expected){part.delete();throw new IOException("模型校验失败："+name);}
  if(model.exists()&&!model.delete())throw new IOException("无法替换模型");if(!part.renameTo(model))throw new IOException("无法保存模型");try(OutputStream out=new FileOutputStream(valid)){out.write((hash+":"+count).getBytes("UTF-8"));}
  Diagnostics.event("model_install_complete","model",name,"elapsedMs",SystemClock.elapsedRealtime()-begin);return model;
 }
 static void download(String name,File part,long expected,BooleanSupplier cancel)throws Exception{
  long offset=part.length();if(offset>expected){part.delete();offset=0;}if(offset==expected)return;String base=name.equals(ASR)?"https://huggingface.co/ggerganov/whisper.cpp/resolve/main/":"https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/";URL url=new URL(base+name);HttpURLConnection connection=null;
  Diagnostics.event("model_download_begin","model",name,"offset",offset,"bytes",expected);
  try{for(int redirects=0;redirects<8;redirects++){AudioDecoder.check(cancel);if(!url.getProtocol().equals("https"))throw new IOException("模型地址必须使用 HTTPS");connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Accept-Encoding","identity");if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");int code=connection.getResponseCode();if(code>=300&&code<400){String location=connection.getHeaderField("Location");connection.disconnect();connection=null;if(location==null)throw new IOException("模型下载重定向失败");url=new URL(url,location);continue;}break;}
   if(connection==null)throw new IOException("模型重定向过多");int code=connection.getResponseCode();boolean append=code==206&&offset>0;if(code==206){String range=connection.getHeaderField("Content-Range");if(range==null||!range.startsWith("bytes "+offset+"-")||!range.endsWith("/"+expected))throw new IOException("模型续传范围不符");}else if(code==200){offset=0;}else throw new IOException("模型源 HTTP "+code+"，可联网后继续处理");
   long count=offset,last=0;try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(part,append)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){AudioDecoder.check(cancel);count+=n;if(count>expected){part.delete();throw new IOException("模型长度不符");}if(part.getParentFile().getUsableSpace()<n+32*1024*1024)throw new IOException("模型下载空间不足");out.write(b,0,n);long now=SystemClock.elapsedRealtime();if(now-last>1000){last=now;Store.progress("running","下载离线模型",(name.equals(ASR)?"识别 ":"翻译 ")+count*100/expected+"%");}}out.getFD().sync();}if(count!=expected)throw new IOException("模型下载中断，可继续处理");
  }finally{if(connection!=null)connection.disconnect();}
 }
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.US,"%02x",v&255));return s.toString();}
}
