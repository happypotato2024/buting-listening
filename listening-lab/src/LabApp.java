package cn.buting.lab;
import android.app.Application;
public final class LabApp extends Application {
 @Override public void onCreate(){super.onCreate();Diagnostics.init(this);AudioLibrary.init(this);
  final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
  Thread.setDefaultUncaughtExceptionHandler((thread,error)->{Diagnostics.error("uncaught",error);if(previous!=null)previous.uncaughtException(thread,error);});
 }
}
