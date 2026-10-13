package cn.buting.lab;
import android.media.MediaDataSource;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Seekable HTTPS source: bounded block cache; full verified fallback if Range is ignored. */
final class RemoteAudioSource extends MediaDataSource {
 static final int BLOCK=256*1024;static final long LIMIT=192L*1024*1024;
 static final Object CACHE_LOCK=new Object();
 final JSONObject state;final BooleanSupplier cancelled;final String hash,url;final long size;volatile boolean closed;volatile HttpURLConnection connection;long fetched;
 RemoteAudioSource(JSONObject s,BooleanSupplier cancel)throws Exception{state=s;cancelled=cancel;hash=s.getString("sourceSha256");url=s.getString("audioUrl");size=s.getLong("audioBytes");if(!hash.matches("[0-9a-f]{64}")||size<=0)throw new IOException("音频身份无效");}
 static File cache(){File d=new File(Diagnostics.app.getCacheDir(),"stream");d.mkdirs();return d;}
 File full(){if(state.optBoolean("localImport")&&Store.source().length()==size)return Store.source();File f=new File(AudioLibrary.dir(),hash+".audio"),m=new File(AudioLibrary.dir(),hash+".verified");return f.length()==size&&m.exists()?f:null;}
 void check()throws IOException{AudioDecoder.check(()->closed||cancelled.getAsBoolean());}
 @Override public long getSize(){return size;}
 @Override public synchronized int readAt(long position,byte[] target,int offset,int count)throws IOException{
  try{check();if(position<0||offset<0||count<0||offset+count>target.length)throw new IOException("无效读取范围");if(position>=size)return -1;if(count==0)return 0;int wanted=(int)Math.min(count,size-position);File complete=full();if(complete!=null)return read(complete,position,target,offset,wanted);
   int done=0;while(done<wanted){check();long at=position+done,index=at/BLOCK;File block=block(index);int within=(int)(at%BLOCK),n=(int)Math.min(wanted-done,block.length()-within);if(n<=0)throw new IOException("缓存音频不完整");int got=read(block,within,target,offset+done,n);if(got!=n)throw new IOException("缓存音频读取中断");done+=got;}return done;
  }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e.getMessage()==null?"在线音频读取失败":e.getMessage(),e);}
 }
 static int read(File f,long at,byte[] output,int offset,int size)throws IOException{try(RandomAccessFile in=new RandomAccessFile(f,"r")){in.seek(at);return in.read(output,offset,size);}}
 File block(long index)throws Exception{
  long first=index*BLOCK,last=Math.min(size-1,first+BLOCK-1);int expected=(int)(last-first+1);File block=new File(cache(),hash+"-"+index+".blk");synchronized(CACHE_LOCK){if(block.length()==expected){block.setLastModified(System.currentTimeMillis());return block;}}
  URL address=new URL(url);HttpURLConnection c=null;
  try{for(int i=0;i<6;i++){check();if(!address.getProtocol().equals("https"))throw new IOException("音频地址必须使用 HTTPS");c=(HttpURLConnection)address.openConnection();connection=c;c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(15000);c.setRequestProperty("Range","bytes="+first+"-"+last);c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("User-Agent","ButingListeningLab/0.4");int code=c.getResponseCode();if(code>=300&&code<400){String location=c.getHeaderField("Location");c.disconnect();c=null;if(location==null)throw new IOException("音频重定向失败");address=new URL(address,location);continue;}break;}
   if(c==null)throw new IOException("音频重定向过多");int code=c.getResponseCode();if(code==200){fullFallback(c);File f=full();byte[] bytes=new byte[expected];if(read(f,first,bytes,0,expected)!=expected)throw new IOException("完整缓存不完整");Repository.atomic(block,bytes);return block;}
   if(code!=206||!("bytes "+first+"-"+last+"/"+size).equals(c.getHeaderField("Content-Range")))throw new IOException("音频服务器未返回正确范围（HTTP "+code+"）");
   byte[] bytes=new byte[expected];try(InputStream in=c.getInputStream()){int at=0,n;while(at<expected&&(n=in.read(bytes,at,expected-at))!=-1){check();at+=n;}if(at!=expected||in.read()!=-1)throw new IOException("在线音频范围长度不符");}check();Repository.atomic(block,bytes);fetched+=expected;trim(block);return block;
  }finally{if(c!=null)c.disconnect();connection=null;}
 }
 void fullFallback(HttpURLConnection c)throws Exception{
  Diagnostics.event("stream_full_fallback","audioSha256",hash,"bytes",size);if(AudioLibrary.dir().getUsableSpace()<size+64L*1024*1024)throw new IOException("服务器需要完整缓存，存储空间不足");File part=File.createTempFile(hash+"-",".streaming-partial",AudioLibrary.dir());MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
  try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check();total+=n;if(total>size)throw new IOException("音频长度不符");out.write(b,0,n);digest.update(b,0,n);}out.getFD().sync();if(total!=size||!Models.hex(digest.digest()).equals(hash))throw new IOException("音频校验失败");check();synchronized(CACHE_LOCK){File target=new File(AudioLibrary.dir(),hash+".audio"),marker=new File(AudioLibrary.dir(),hash+".verified");if(target.length()!=size||!marker.exists()){marker.delete();if(target.exists()&&!target.delete())throw new IOException("无法替换音频缓存");if(!part.renameTo(target))throw new IOException("无法保存音频缓存");Repository.atomic(marker,hash.getBytes("UTF-8"));}}fetched+=total;
  }finally{part.delete();}
 }
 static void trim(File keep){synchronized(CACHE_LOCK){File[] files=cache().listFiles((d,n)->n.endsWith(".blk"));if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long bytes=0;for(File f:files)bytes+=f.length();for(File f:files){if(bytes<=LIMIT)break;if(!f.equals(keep)&&f.delete())bytes-=f.length();}}}
 static long cacheBytes(){File[] files=cache().listFiles();long size=0;if(files!=null)for(File f:files)size+=f.length();return size;}
 static void clearCache(){synchronized(CACHE_LOCK){File[] files=cache().listFiles();if(files!=null)for(File f:files)f.delete();}}
 @Override public void close(){closed=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
}
