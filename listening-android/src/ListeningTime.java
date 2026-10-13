package cn.buting.lab;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;
/** Wall-clock listening time includes playback with the screen off. */
final class ListeningTime {
 private static long last,lastSave;private static boolean wasPlaying;private static final Map<String,Float> totals=new HashMap<>();private static boolean loaded;
 private static SharedPreferences prefs(){return Diagnostics.app.getSharedPreferences("listening-time",0);}
 private static void load(){if(!loaded){for(Map.Entry<String,?> e:prefs().getAll().entrySet())if(e.getValue() instanceof Float)totals.put(e.getKey(),(Float)e.getValue());loaded=true;}}
 static synchronized void tick(PlaybackService player){load();long now=SystemClock.elapsedRealtime();boolean playing=player.state!=null&&!player.paused&&!player.preparing&&!player.completed&&player.failure.isEmpty();
  if(wasPlaying&&last>0){String day=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());totals.put(day,totals.getOrDefault(day,0f)+Math.min(2,(now-last)/1000f));}wasPlaying=playing;last=now;if(now-lastSave>5000){save();lastSave=now;}}
 static synchronized void save(){load();SharedPreferences.Editor e=prefs().edit();for(Map.Entry<String,Float> t:totals.entrySet())e.putFloat(t.getKey(),t.getValue());e.apply();}
 static synchronized JSONObject snapshot(){load();return new JSONObject(totals);}
}
