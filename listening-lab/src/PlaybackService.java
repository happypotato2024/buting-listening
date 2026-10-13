package cn.buting.lab;
import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import android.view.KeyEvent;
import org.json.*;
import java.util.Locale;

/** One playback owner shared by the screen, media notification and headphones. */
public final class PlaybackService extends Service {
 static volatile PlaybackService instance;
 final PrecisePlayer player=new PrecisePlayer();
 volatile JSONObject state;volatile boolean whole,paused,preparing,completed;volatile int sentence=-1;volatile long position,start,end;volatile String failure="";
 private float speed=1;private boolean loop,prepared,resumeOnFocus,focusHeld;private MediaPlayer media;private RemoteAudioSource remote;
 private MediaSession session;private AudioManager audio;private AudioFocusRequest focus;private PowerManager.WakeLock wake;
 private final Handler handler=new Handler(Looper.getMainLooper());private String shown="";private long generation;private long seenRevision=-1;
 static boolean engaged(){PlaybackService p=instance;return p!=null&&p.state!=null&&!p.paused&&!p.completed&&p.failure.isEmpty();}
 static boolean matches(JSONObject state){PlaybackService p=instance;return p!=null&&p.state!=null&&p.state.optString("sourceSha256").equals(state.optString("sourceSha256"));}
 static void send(Context context,String action){context.startForegroundService(new Intent(context,PlaybackService.class).setAction(action));}
 static void sentence(Context context,int index,float speed,boolean loop){context.startForegroundService(new Intent(context,PlaybackService.class).setAction("sentence").putExtra("index",index).putExtra("speed",speed).putExtra("loop",loop));}
 static void whole(Context context,long from,float speed){context.startForegroundService(new Intent(context,PlaybackService.class).setAction("whole").putExtra("from",from).putExtra("speed",speed));}
 static void seek(Context context,long position,boolean play){context.startForegroundService(new Intent(context,PlaybackService.class).setAction("seek").putExtra("position",position).putExtra("play",play));}
 static void stopCurrent(){PlaybackService p=instance;if(p!=null)p.handler.post(()->p.stopPlayback(true));}
 @Override public IBinder onBind(Intent intent){return null;}
 @Override public void onCreate(){super.onCreate();instance=this;getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("playback","听力播放",NotificationManager.IMPORTANCE_LOW));
  audio=getSystemService(AudioManager.class);wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"buting:playback");
  focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener(change->{
   if(change==AudioManager.AUDIOFOCUS_GAIN){if(resumeOnFocus){resumeOnFocus=false;setPaused(false,false);}}
   else if(change==AudioManager.AUDIOFOCUS_LOSS){resumeOnFocus=false;focusHeld=false;setPaused(true,false);}
   else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){resumeOnFocus=!paused&&!completed;setPaused(true,false);}
  },handler).build();
  session=new MediaSession(this,"ButingListening");session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);session.setCallback(new MediaSession.Callback(){
   @Override public void onPlay(){setPaused(false,true);}
   @Override public void onPause(){setPaused(true,true);}
   @Override public void onStop(){stopPlayback(true);}
   @Override public void onSeekTo(long pos){seekTo(pos+(whole?0:start),!paused);}
   @Override public void onSkipToNext(){skip(1);}
   @Override public void onSkipToPrevious(){skip(-1);}
   @Override public void onFastForward(){seekTo(position+5000,!paused);}
   @Override public void onRewind(){seekTo(position-5000,!paused);}
  },handler);session.setActive(true);
  IntentFilter f=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(noisy,f);
  handler.post(tick);
 }
 private final BroadcastReceiver noisy=new BroadcastReceiver(){public void onReceive(Context c,Intent i){setPaused(true,true);}};
 @Override public int onStartCommand(Intent intent,int flags,int id){startForeground(3,notification());if(intent==null){stopPlayback(true);return START_NOT_STICKY;}
  try{String action=intent.getAction();
   if("sentence".equals(action)){loadCurrent();whole=false;sentence=intent.getIntExtra("index",0);speed=intent.getFloatExtra("speed",1);loop=intent.getBooleanExtra("loop",false);JSONObject row=state.getJSONArray("sentences").getJSONObject(sentence);start=row.getLong("startMs");end=row.getLong("endMs");begin(start,false);}
   else if("whole".equals(action)){loadCurrent();whole=true;sentence=-1;speed=intent.getFloatExtra("speed",1);loop=false;start=0;end=state.getLong("durationMs");begin(intent.getLongExtra("from",0),false);}
   else if("preview".equals(action)){JSONObject t=AudioLibrary.find(intent.getStringExtra("id"));state=new JSONObject().put("sourceSha256",t.getString("audioHash")).put("audioUrl",t.getString("url")).put("audioBytes",t.getLong("audioBytes")).put("displayName",AudioLibrary.title(t)).put("libraryId",t.getString("id")).put("repositoryRevision",t.getString("repositoryRevision")).put("mode","repository").put("durationMs",Math.round(t.optDouble("duration",0)*1000)).put("sentences",new JSONArray());whole=true;sentence=-1;speed=1;loop=false;start=0;end=state.optLong("durationMs");begin(0,false);}
   else if("pause".equals(action))setPaused(true,true);
   else if("resume".equals(action))setPaused(false,true);
   else if("toggle".equals(action))setPaused(!paused,true);
   else if("seek".equals(action))seekTo(intent.getLongExtra("position",position),intent.getBooleanExtra("play",!paused));
   else if("next".equals(action))skip(1);
   else if("previous".equals(action))skip(-1);
   else if("stop".equals(action))stopPlayback(true);
   else if("speed".equals(action)){speed=intent.getFloatExtra("speed",1);loop=intent.getBooleanExtra("loop",loop);if(state!=null)begin(position,paused);}
  }catch(Exception e){failed(e);}publish();return START_NOT_STICKY;
 }
 private void loadCurrent()throws Exception{state=Store.snapshot();if(state.optLong("durationMs")<=0)throw new java.io.IOException("请先选择录音");}
 private boolean requestFocus(){if(focusHeld)return true;focusHeld=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;return focusHeld;}
 private void begin(long from,boolean pause)throws Exception{
  releasePlayers();session.setActive(true);generation++;position=Math.max(start,Math.min(end>0?end:Long.MAX_VALUE,from));paused=pause;completed=false;failure="";preparing=true;
  if(!paused&&!requestFocus()){paused=true;Diagnostics.event("audio_focus_denied");}
  if(whole&&state.optString("mode").equals("repository")){final long ticket=generation;MediaPlayer p=new MediaPlayer();media=p;p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
   RemoteAudioSource source=new RemoteAudioSource(state,()->ticket!=generation);remote=source;
   p.setOnPreparedListener(m->{if(ticket!=generation||media!=m)return;try{prepared=true;end=m.getDuration();m.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1));m.pause();m.seekTo(position,MediaPlayer.SEEK_CLOSEST);preparing=true;publish();}catch(Exception e){failed(e);}});
   p.setOnSeekCompleteListener(m->{if(ticket!=generation||media!=m)return;preparing=false;try{if(!paused)m.start();publish();}catch(Exception e){failed(e);}});
   p.setOnCompletionListener(m->{if(ticket!=generation)return;position=end;completed=true;paused=true;publish();});p.setOnErrorListener((m,a,b)->{if(ticket==generation)failed(new java.io.IOException("整篇播放错误 "+a+":"+b));return true;});
   new Thread(()->{try{p.setDataSource(source);if(ticket==generation)p.prepareAsync();}catch(Exception e){handler.post(()->{if(ticket==generation)failed(e);});}},"whole-player").start();
  }else{player.playFrom(state,whole?-1:sentence,start,end,position,speed,!whole&&loop,paused);}
  Diagnostics.event("playback_start","whole",whole,"sentence",sentence,"fromMs",position,"paused",paused,"speed",speed);publish();
 }
 private void setPaused(boolean value,boolean user){if(state==null)return;if(user)resumeOnFocus=false;
  try{if(!value&&completed){begin(start,false);return;}if(!value&&!requestFocus()){paused=true;publish();return;}
   paused=value;if(media!=null){if(prepared&&!preparing){if(value){media.pause();position=media.getCurrentPosition();}else media.start();}}else if(value){player.pause();position=player.positionMs;}else player.resume();
   if(value&&user&&focusHeld){audio.abandonAudioFocusRequest(focus);focusHeld=false;}Diagnostics.event(value?"playback_pause":"playback_resume","positionMs",position);publish();
  }catch(Exception e){failed(e);}
 }
 private void seekTo(long to,boolean play){if(state==null)return;try{position=Math.max(start,Math.min(end,to));completed=false;paused=!play;if(play&&!requestFocus())paused=true;
   if(media!=null&&prepared){media.pause();preparing=true;media.seekTo(position,MediaPlayer.SEEK_CLOSEST);}else if(media!=null){/* pending position is consumed when prepared */}else begin(position,paused);
   Diagnostics.event("playback_seek","positionMs",position,"whole",whole,"paused",paused);publish();
  }catch(Exception e){failed(e);}}
 private void skip(int direction){if(state==null)return;try{JSONArray rows=state.optJSONArray("sentences");if(rows==null||rows.length()==0){seekTo(position+direction*5000,true);return;}int next;
  if(whole){next=direction>0?rows.length()-1:0;if(direction>0){for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getLong("startMs")>position){next=i;break;}}else{for(int i=rows.length()-1;i>=0;i--)if(rows.getJSONObject(i).getLong("endMs")<=position){next=i;break;}}seekTo(rows.getJSONObject(next).getLong("startMs"),true);}
  else{next=(sentence+direction+rows.length())%rows.length();sentence=next;JSONObject row=rows.getJSONObject(next);start=row.getLong("startMs");end=row.getLong("endMs");begin(start,false);}
 }catch(Exception e){failed(e);}}
 int indexAt(long pos){try{JSONArray rows=state.getJSONArray("sentences");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(pos>=r.getLong("startMs")&&pos<r.getLong("endMs"))return i;}return -1;}catch(Exception e){return -1;}}
 private final Runnable tick=new Runnable(){public void run(){if(instance!=PlaybackService.this)return;
  if(state!=null){try{if(whole&&!state.optString("mode").equals("repository")&&seenRevision!=Store.revision){long revision=Store.revision;JSONObject current=Store.snapshot();seenRevision=revision;if(current.optString("sourceSha256").equals(state.optString("sourceSha256")))state=current;}if(media!=null&&prepared&&!preparing&&!completed)position=media.getCurrentPosition();else if(media==null){position=player.positionMs;preparing=player.phase.equals("准备播放");if(!player.failure.isEmpty()){failure=player.failure;paused=true;}if(!player.playing&&!preparing&&!completed&&failure.isEmpty()){completed=true;paused=true;position=end;}}publish();}catch(Exception e){failed(e);}}
  handler.postDelayed(this,500);
 }};
 private PendingIntent action(String name){return PendingIntent.getService(this,name.hashCode(),new Intent(this,PlaybackService.class).setAction(name),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
 private Notification notification(){String title=state==null?"不听听力":state.optString("displayName","不听听力");String subtitle=whole?"整篇播放":sentence>=0?"第 "+(sentence+1)+" 句":"听力播放";
  PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  return new Notification.Builder(this,"playback").setSmallIcon(R.drawable.icon).setContentTitle(title).setContentText(failure.isEmpty()?subtitle:failure).setContentIntent(open).setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(!paused&&!completed).setDeleteIntent(action("stop"))
   .addAction(android.R.drawable.ic_media_previous,"上一句",action("previous")).addAction(paused||completed?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause,paused||completed?"继续":"暂停",action(paused||completed?"resume":"pause")).addAction(android.R.drawable.ic_media_next,"下一句",action("next")).addAction(android.R.drawable.ic_menu_close_clear_cancel,"停止",action("stop"))
   .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2)).build();
 }
 private void publish(){if(instance!=this||session==null)return;
  boolean engaged=engaged();if(!engaged&&(completed||!failure.isEmpty())&&focusHeld){audio.abandonAudioFocusRequest(focus);focusHeld=false;}if(engaged&&!wake.isHeld())wake.acquire(2*60*60*1000L);else if(!engaged&&wake.isHeld())wake.release();
  if(state==null)return;long duration=whole?end:end-start,relative=whole?position:position-start;String key=state.optString("sourceSha256")+":"+whole+":"+sentence+":"+paused+":"+completed+":"+preparing+":"+failure+":"+end;
  if(!key.equals(shown)){shown=key;session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,state.optString("displayName","不听听力")).putString(MediaMetadata.METADATA_KEY_ARTIST,whole?"整篇播放":"第 "+(sentence+1)+" 句").putLong(MediaMetadata.METADATA_KEY_DURATION,Math.max(0,duration)).build());getSystemService(NotificationManager.class).notify(3,notification());}
  long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_STOP|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_REWIND|PlaybackState.ACTION_FAST_FORWARD;
  int status=!failure.isEmpty()?PlaybackState.STATE_ERROR:completed?PlaybackState.STATE_STOPPED:paused?PlaybackState.STATE_PAUSED:preparing?PlaybackState.STATE_BUFFERING:PlaybackState.STATE_PLAYING;
  session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(status,Math.max(0,relative),engaged?speed:0,SystemClock.elapsedRealtime()).build());
 }
 private void failed(Exception e){Diagnostics.error("playback_service",e);failure="播放失败，可重试";paused=true;preparing=false;releasePlayers();publish();}
 private void releasePlayers(){generation++;player.stop();prepared=false;MediaPlayer p=media;media=null;RemoteAudioSource r=remote;remote=null;if(r!=null)r.close();if(p!=null)try{p.release();}catch(Exception ignored){}}
 private void stopPlayback(boolean finish){releasePlayers();state=null;paused=true;completed=true;preparing=false;if(wake.isHeld())wake.release();if(focusHeld){audio.abandonAudioFocusRequest(focus);focusHeld=false;}session.setActive(false);stopForeground(STOP_FOREGROUND_REMOVE);if(finish)stopSelf();}
 @Override public void onDestroy(){handler.removeCallbacks(tick);stopPlayback(false);try{unregisterReceiver(noisy);}catch(Exception ignored){}session.release();if(instance==this)instance=null;super.onDestroy();}
}
