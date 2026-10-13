package cn.buting.lab;
import org.json.*;
import java.util.*;

final class Sentences {
 // Retain complete words at overlapping chunk boundaries, rather than partial BPE tokens.
 static JSONArray owned(JSONArray raw,long offset,long duration)throws Exception{
  ArrayList<JSONObject> words=new ArrayList<>();JSONObject word=null;StringBuilder text=new StringBuilder();float probability=1;int pieces=0,anchors=0;long center=0,start=0,end=0;
  for(int i=0;i<=raw.length();i++){
   JSONObject token=i<raw.length()?raw.getJSONObject(i):null;String piece=token==null?"":token.optString("text");boolean boundary=token==null||(!piece.isEmpty()&&Character.isWhitespace(piece.charAt(0)));
   if(boundary&&pieces>0){word=new JSONObject().put("text",text.toString()).put("centerMs",(anchors>0?center/anchors:(start+end)/2)+offset).put("startMs",Math.max(0,start)+offset).put("endMs",Math.max(start,end)+offset).put("probability",probability/pieces);words.add(word);pieces=0;anchors=0;text.setLength(0);probability=0;center=0;}
   if(token==null)break;if(pieces==0){start=token.optLong("startMs");probability=0;}
   long c=token.optLong("centerMs",-1);if(c<0)c=(token.optLong("startMs")+token.optLong("endMs"))/2;
   c=Math.max(0,Math.min(30000,c));text.append(piece);int weight=piece.replaceAll("[^A-Za-z0-9]","").length();if(weight>0){center+=c*weight;anchors+=weight;end=token.optLong("endMs");}probability+=(float)token.optDouble("probability",0);pieces++;
  }
  JSONArray result=new JSONArray();long lower=offset==0?0:offset+1000,upper=offset+30000>=duration?duration+1:offset+29000;
  for(JSONObject w:words){long c=w.getLong("centerMs");if(c>=lower&&c<upper)result.put(w);}return result;
 }
 static void merge(JSONArray previous,JSONArray next,long offset)throws Exception{
  int skip=0;
  for(int count=1;count<=Math.min(5,Math.min(previous.length(),next.length()));count++){
   boolean match=true;
   for(int i=0;i<count;i++){JSONObject a=previous.getJSONObject(previous.length()-count+i),b=next.getJSONObject(i);String x=a.optString("text").toLowerCase(Locale.US).replaceAll("[^a-z0-9]",""),y=b.optString("text").toLowerCase(Locale.US).replaceAll("[^a-z0-9]","");if(x.isEmpty()||!x.equals(y)||Math.abs(a.optLong("centerMs")-b.optLong("centerMs"))>1200){match=false;break;}}
   if(match)skip=count;
  }
  if(skip>0)Diagnostics.event("asr_overlap_deduplicated","offsetMs",offset,"words",skip);
  for(int i=skip;i<next.length();i++)previous.put(next.getJSONObject(i));
 }
 static JSONArray assemble(JSONArray words,long duration)throws Exception{
  JSONArray result=new JSONArray();StringBuilder text=new StringBuilder();int first=0;double confidence=0,minConfidence=1;long previousCenter=0;
  for(int i=0;i<words.length();i++){
   JSONObject word=words.getJSONObject(i);long c=Math.max(previousCenter,Math.min(duration,word.optLong("centerMs")));word.put("centerMs",c);previousCenter=c;
  }
  for(int i=0;i<words.length();i++){
   JSONObject w=words.getJSONObject(i);String t=w.optString("text");text.append(t);confidence+=w.optDouble("probability");minConfidence=Math.min(minConfidence,w.optDouble("probability"));String trimmed=text.toString().trim();
   boolean punctuation=trimmed.matches("(?s).*[.!?][\"'’”]?$")&&!t.trim().matches("(?i)(Mr|Mrs|Ms|Dr|Prof|St|U\\.S|U\\.K)\\."),longSentence=trimmed.length()>700||(w.optLong("centerMs")-words.getJSONObject(first).optLong("centerMs")>25000);
   if(!punctuation&&!longSentence&&i<words.length()-1)continue;
   if(trimmed.isEmpty()){first=i+1;text.setLength(0);confidence=0;continue;}
   long a=words.getJSONObject(first).optLong("centerMs"),b=w.optLong("centerMs");long previous=first>0?words.getJSONObject(first-1).optLong("centerMs"):-1,next=i+1<words.length()?words.getJSONObject(i+1).optLong("centerMs"):duration;
   long start=previous<0?Math.max(0,a-400):splitPoint(previous,a);
   long end=i+1<words.length()?splitPoint(b,next):duration;
   start=Math.max(0,start);end=Math.min(duration,Math.max(start+100,end));double p=confidence/(i-first+1);
   JSONObject sentence=new JSONObject().put("text",trimmed.replaceAll("\\s+"," ")).put("translation","").put("startMs",start).put("endMs",end).put("confidence",p).put("minWordConfidence",minConfidence).put("timingReview",p<.65||minConfidence<.6||b<=a||longSentence||!punctuation||i==words.length()-1).put("translationStatus","pending");
   result.put(sentence);first=i+1;text.setLength(0);confidence=0;minConfidence=1;
  }
  return result;
 }
 static long splitPoint(long previous,long next)throws Exception{
  if(next<=previous)return previous;long middle=(previous+next)/2,left=Math.max(previous+30,middle-650),right=Math.min(next-30,middle+650);if(right-left<100)return middle;
  float[] audio=AudioDecoder.samples(left,right);double best=Double.MAX_VALUE;long point=middle;
  // Seek a low-energy 60 ms gap between the final and first spoken word anchors.
  // If there is no quiet gap, retain the acoustic midpoint and allow manual correction.
  for(int i=480;i+480<audio.length;i+=160){double energy=0;for(int k=i-480;k<i+480;k++)energy+=audio[k]*audio[k];double rms=Math.sqrt(energy/960);if(rms>.008)continue;long at=left+i/16;double score=rms+Math.abs(at-middle)*.000001;if(score<best){best=score;point=at;}}
  return point;
 }
}
