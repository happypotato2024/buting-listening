package cn.buting.lab;
import android.content.*;
import android.os.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Conservative CPU duty control. Battery temperature is not CPU temperature. */
final class ThermalGovernor {
 private final Context context;private final PowerManager power;private final Runnable interrupt;
 private final Handler monitor=new Handler(Looper.getMainLooper());
 private volatile boolean closed,cooling;private volatile int thermal=-1,battery=-1;
 private long lastLog;private String lastReason="";private PowerManager.OnThermalStatusChangedListener listener;
 ThermalGovernor(Context context,Runnable interrupt){this.context=context;this.interrupt=interrupt;power=context.getSystemService(PowerManager.class);
  if(Build.VERSION.SDK_INT>=29){listener=status->{thermal=status;sample();};try{power.addThermalStatusListener(context.getMainExecutor(),listener);}catch(Exception e){Diagnostics.error("thermal_listener",e);}}
  monitor.post(poll);
 }
 static int mode(){return Diagnostics.app.getSharedPreferences("processing",0).getInt("mode",2);}
 static String modeName(){return mode()==1?"低发热":mode()==4?"快速":"均衡";}
 static void mode(int value){Diagnostics.app.getSharedPreferences("processing",0).edit().putInt("mode",value==1?1:value==4?4:2).commit();Diagnostics.event("processing_mode","mode",modeName());}
 private final Runnable poll=new Runnable(){public void run(){if(closed)return;sample();monitor.postDelayed(this,1000);}};
 synchronized void sample(){if(closed)return;
  if(Build.VERSION.SDK_INT>=29)try{thermal=power.getCurrentThermalStatus();}catch(Exception ignored){}
  Intent intent=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));battery=intent==null?-1:intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1);
  cooling=cooling?thermal>1||battery>400:thermal>=3||battery>=430;
  String reason=reason();long now=SystemClock.elapsedRealtime();
  if(now-lastLog>=15000||!reason.equals(lastReason)){lastLog=now;lastReason=reason;BatteryManager bm=context.getSystemService(BatteryManager.class);
   int level=intent==null?-1:intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent==null?100:intent.getIntExtra(BatteryManager.EXTRA_SCALE,100);
   Diagnostics.event("thermal_sample","thermalStatus",thermal,"batteryTemperatureDeciC",battery,"batteryPercent",scale>0&&level>=0?level*100/scale:-1,"chargeCounterMicroAh",bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),"processCpuMs",android.os.Process.getElapsedCpuTime(),"mode",modeName(),"threads",threads(),"reason",reason);
  }
  if(!reason.isEmpty())interrupt.run();
 }
 String reason(){return cooling?"冷却等待":PlaybackService.engaged()?"播放优先":"";}
 int threads(){return Math.max(1,Math.min(thermal>=2||battery>=400?1:mode(),Math.max(1,Runtime.getRuntime().availableProcessors()-1)));}
 long restMs(long workMs){double ratio=mode()==1?.5:mode()==4?0:.2;if(thermal>=2||battery>=400)ratio=Math.max(ratio,.5);return Math.min(15000,Math.max(0,(long)(workMs*ratio)));}
 void await(BooleanSupplier cancelled,java.util.function.Consumer<String> status,Runnable release,Runnable acquire)throws Exception{
  String previous="";boolean released=false;
  try{while(true){AudioDecoder.check(cancelled);String reason=reason();if(reason.isEmpty())break;if(!released){release.run();released=true;}if(!reason.equals(previous)){status.accept(reason);Diagnostics.event("processing_wait","reason",reason);previous=reason;}Thread.sleep(250);}}
  finally{if(released&&!cancelled.getAsBoolean())acquire.run();}
 }
 void rest(long workMs,BooleanSupplier cancelled,java.util.function.Consumer<String> status,Runnable release,Runnable acquire)throws Exception{
  long rest=restMs(workMs);if(rest<=0)return;status.accept("间歇处理");Diagnostics.event("processing_rest","workMs",workMs,"restMs",rest);release.run();
  try{long end=SystemClock.elapsedRealtime()+rest;while(SystemClock.elapsedRealtime()<end){AudioDecoder.check(cancelled);Thread.sleep(Math.max(1,Math.min(250,end-SystemClock.elapsedRealtime())));}}
  finally{if(!cancelled.getAsBoolean())acquire.run();}
 }
 void close(){closed=true;monitor.removeCallbacks(poll);if(listener!=null)try{power.removeThermalStatusListener(listener);}catch(Exception ignored){}}
}
