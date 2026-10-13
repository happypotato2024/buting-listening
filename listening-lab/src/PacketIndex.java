package cn.buting.lab;
import android.util.Base64;
import java.io.*;
import java.nio.*;
import java.util.zip.InflaterInputStream;
import org.json.*;

final class PacketIndex {
 final long[] positions,samples;final int[] sizes,counts;final int rate,channels,skip;final String codec;final byte[] config;
 PacketIndex(JSONObject o,long totalBytes)throws Exception{
  codec=o.getString("codec");if(!codec.equals("mp3")&&!codec.equals("aac"))throw new IOException("音频帧编码无效");config=codec.equals("aac")?Base64.decode(o.getString("audioSpecificConfig"),Base64.DEFAULT):null;if(config!=null&&config.length!=2)throw new IOException("AAC 配置无效");int n=o.getInt("packets");if(n<=0||n>200000||!o.getString("encoding").equals("zlib-base64-le16"))throw new IOException("音频帧索引无效");rate=o.getInt("sampleRate");channels=o.getInt("channels");skip=o.getInt("leadingSkipSamples");if(rate<8000||rate>96000||channels<1||channels>2||skip<0)throw new IOException("音频帧格式无效");
  byte[] raw=new byte[n*16];try(InputStream in=new InflaterInputStream(new ByteArrayInputStream(Base64.decode(o.getString("data"),Base64.DEFAULT)))){int at=0,read;while(at<raw.length&&(read=in.read(raw,at,raw.length-at))!=-1)at+=read;if(at!=raw.length||in.read()!=-1)throw new IOException("音频帧索引不完整");}
  ByteBuffer bytes=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);positions=new long[n];samples=new long[n];sizes=new int[n];counts=new int[n];long frame=0,previousEnd=0;
  for(int i=0;i<n;i++){positions[i]=bytes.getLong();sizes[i]=bytes.getInt();counts[i]=bytes.getInt();samples[i]=frame;frame+=counts[i];boolean validCount=codec.equals("aac")?counts[i]==1024:counts[i]==576||counts[i]==1152;if(positions[i]<previousEnd||sizes[i]<=0||sizes[i]>16384||positions[i]+sizes[i]>totalBytes||!validCount)throw new IOException("音频帧位置错误");previousEnd=positions[i]+sizes[i];}
 }
 long pts(int i){return Math.round((samples[i]-skip)*1000000.0/rate);}
 int begin(long us){long sample=Math.round(us*rate/1000000.0)+skip;int at=java.util.Arrays.binarySearch(samples,sample);if(at<0)at=-at-2;return Math.max(0,at);}
}
