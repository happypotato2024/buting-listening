package cn.tingjian.cet6;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final int PICK_FILE = 100, SAVE_BACKUP = 101, PICK_WALLPAPER = 102;
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor();
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingBackup;
    private volatile boolean trustedHome;
    private boolean wallpaperPicking;
    private cn.buting.lab.NativeListening listening;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(248,249,251));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(248,249,251));
        frame.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(248,249,251));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setTextZoom(100);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setBuiltInZoomControls(false);
        WebView.setWebContentsDebuggingEnabled(false);
        web.addJavascriptInterface(new AppBridge(),"TingjianAndroid");
        listening=new cn.buting.lab.NativeListening(this,()->trustedHome);
        web.addJavascriptInterface(listening,"ButingNative");
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},200);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon) {trustedHome=HOME.equals(url);}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if(HOST.equals(request.getUrl().getHost())) return localAsset(request);
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if("https".equals(uri.getScheme()) && HOST.equals(uri.getHost())) return false;
                if(request.isForMainFrame()) openExternal(uri);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params) {
                if(fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] types = params.getAcceptTypes();
                if(types.length > 0 && types[0].startsWith("audio")) intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/*","application/octet-stream"});
                if(types.length > 0 && types[0].startsWith("image")) intent.setType("image/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {startActivityForResult(intent,PICK_FILE);}
                catch(ActivityNotFoundException e){fileCallback.onReceiveValue(null);fileCallback=null;notice("没有找到文件选择器。");}
                return true;
            }
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("确定",(d,w)->result.confirm())
                    .setNegativeButton("取消",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();
                return true;
            }
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("知道了",(d,w)->result.confirm())
                    .setOnCancelListener(d->result.cancel()).show();return true;
            }
        });
        web.setDownloadListener((url,agent,disposition,mime,length)->openExternal(Uri.parse(url)));
        frame.addView(web,new FrameLayout.LayoutParams(-1,-1));
        setContentView(frame);
        web.loadUrl(HOME);
    }

    private WebResourceResponse localAsset(WebResourceRequest request) {
        try {
            if(!"https".equals(request.getUrl().getScheme())) return emptyResponse(403,"Forbidden");
            String name = request.getUrl().getPath();
            if(name == null || name.equals("/")) name="/index.html";
            if(name.contains("..") || name.contains("\\")) return emptyResponse(403,"Forbidden");
            InputStream dataStream;
            long length;
            if(name.equals("/user-wallpaper.jpg")) {
                File wallpaper=new File(getFilesDir(),"wallpaper.jpg");
                if(!wallpaper.isFile())return emptyResponse(404,"Not Found");
                length=wallpaper.length();dataStream=new FileInputStream(wallpaper);
            } else if(name.endsWith(".mp3") || name.endsWith(".wav")) {
                AssetFileDescriptor descriptor=getAssets().openFd("www"+name);
                length=descriptor.getLength();
                dataStream=descriptor.createInputStream();
            } else {
                byte[] data;
                try(InputStream input=getAssets().open("www"+name);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[16384];int n;
                    while((n=input.read(buffer))!=-1)output.write(buffer,0,n);
                    data=output.toByteArray();
                }
                length=data.length;dataStream=new ByteArrayInputStream(data);
            }
            Map<String,String> headers = new HashMap<>();
            headers.put("Accept-Ranges","bytes");headers.put("Cache-Control","no-store");
            String mime=mimeType(name);String encoding=mime.startsWith("text/")||mime.equals("application/json")?"UTF-8":null;
            String range=request.getRequestHeaders().get("Range");
            if(range == null) range=request.getRequestHeaders().get("range");
            long start=0,end=length-1;int status=200;
            if(range != null) {
                Matcher match=Pattern.compile("^bytes=(\\d*)-(\\d*)$").matcher(range);
                if(!match.matches() || (match.group(1).isEmpty() && match.group(2).isEmpty())){dataStream.close();return emptyResponse(416,"Range Not Satisfiable");}
                try {
                    if(match.group(1).isEmpty())start=Math.max(0,length-Long.parseLong(match.group(2)));
                    else start=Long.parseLong(match.group(1));
                    if(!match.group(1).isEmpty()&&!match.group(2).isEmpty())end=Math.min(end,Long.parseLong(match.group(2)));
                } catch(NumberFormatException e){dataStream.close();return emptyResponse(416,"Range Not Satisfiable");}
                if(start<0 || start>end){dataStream.close();return emptyResponse(416,"Range Not Satisfiable");}
                status=206;headers.put("Content-Range","bytes "+start+"-"+end+"/"+length);
            }
            headers.put("Content-Length",String.valueOf(end-start+1));
            InputStream body;
            if("HEAD".equals(request.getMethod())){dataStream.close();body=new ByteArrayInputStream(new byte[0]);}
            else {
                long remaining=start;
                while(remaining>0){long skipped=dataStream.skip(remaining);if(skipped>0)remaining-=skipped;else if(dataStream.read()!=-1)remaining--;else{dataStream.close();return emptyResponse(416,"Range Not Satisfiable");}}
                body=new BoundedInputStream(dataStream,end-start+1);
            }
            return new WebResourceResponse(mime,encoding,status,status==206?"Partial Content":"OK",headers,body);
        } catch(Exception e) { return emptyResponse(404,"Not Found"); }
    }
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream input,long length){super(input);remaining=length;}
        @Override public int read() throws IOException {
            if(remaining<=0)return -1;
            int value=in.read();if(value>=0)remaining--;return value;
        }
        @Override public int read(byte[] buffer,int offset,int length) throws IOException {
            if(length==0)return 0;if(remaining<=0)return -1;
            int n=in.read(buffer,offset,(int)Math.min(remaining,length));if(n>0)remaining-=n;return n;
        }
        @Override public long skip(long count) throws IOException {
            long skipped=in.skip(Math.min(Math.max(0,count),remaining));remaining-=skipped;return skipped;
        }
        @Override public int available() throws IOException {return (int)Math.min(remaining,in.available());}
    }
    private WebResourceResponse emptyResponse(int status,String reason) {
        return new WebResourceResponse("text/plain","UTF-8",status,reason,new HashMap<>(),new ByteArrayInputStream(new byte[0]));
    }
    private String mimeType(String name) {
        if(name.endsWith(".html"))return "text/html";
        if(name.endsWith(".js"))return "text/javascript";
        if(name.endsWith(".css"))return "text/css";
        if(name.endsWith(".md"))return "text/plain";
        if(name.endsWith(".json")||name.endsWith(".webmanifest"))return "application/json";
        if(name.endsWith(".wav"))return "audio/wav";
        if(name.endsWith(".mp3"))return "audio/mpeg";
        if(name.endsWith(".png"))return "image/png";
        if(name.endsWith(".jpg")||name.endsWith(".jpeg"))return "image/jpeg";
        if(name.endsWith(".svg"))return "image/svg+xml";
        return "application/octet-stream";
    }
    private void openExternal(Uri uri) {
        if(!"https".equals(uri.getScheme())&&!"http".equals(uri.getScheme()))return;
        try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(ActivityNotFoundException e){notice("没有找到可打开链接的浏览器。");}
    }
    private void notice(String text) {runOnUiThread(()->Toast.makeText(this,text,Toast.LENGTH_LONG).show());}

    private SharedPreferences wallpaperPreferences(){return getSharedPreferences("buting-wallpaper",MODE_PRIVATE);}
    private String wallpaperJson() {
        SharedPreferences prefs=wallpaperPreferences();if(!prefs.contains("kind"))return "null";
        try{
            JSONObject result=new JSONObject();String kind=prefs.getString("kind","winter");
            boolean photo=new File(getFilesDir(),"wallpaper.jpg").isFile();
            result.put("kind",kind.equals("photo")&&!photo?"winter":kind);result.put("text",prefs.getString("text","dark"));
            if(photo)result.put("nativePhotoUrl","https://"+HOST+"/user-wallpaper.jpg?v="+prefs.getLong("photoVersion",0));
            return result.toString();
        }catch(Exception error){return "null";}
    }
    private void wallpaperResult(String status,String message) {
        runOnUiThread(()->{
            wallpaperPicking=false;if(isFinishing()||web==null||!trustedHome)return;
            try{JSONObject result=new JSONObject();result.put("status",status);result.put("message",message);result.put("wallpaper",new JSONObject(wallpaperJson().equals("null")?"{}":wallpaperJson()));
                web.evaluateJavascript("window.butingWallpaperResult?.("+result.toString()+")",null);
            }catch(Exception error){notice("壁纸处理失败，请重试。");}
        });
    }
    private void importWallpaper(Uri uri) {
        File incoming=new File(getCacheDir(),"wallpaper-input.tmp"),encoded=new File(getFilesDir(),"wallpaper-new.jpg");
        Bitmap decoded=null,scaled=null,rotated=null;
        try{
            try(InputStream input=getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(incoming)){
                if(input==null)throw new IOException("图片无法读取");byte[] buffer=new byte[16384];int n;long total=0;
                while((n=input.read(buffer))!=-1){total+=n;if(total>30L*1024*1024)throw new IOException("图片超过30MB，请换一张较小的图片");output.write(buffer,0,n);}
            }
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(incoming.getPath(),bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("图片格式无法读取，请使用JPG、PNG或WebP");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
            while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>1920)options.inSampleSize*=2;
            decoded=BitmapFactory.decodeFile(incoming.getPath(),options);if(decoded==null)throw new IOException("图片无法解码");
            float ratio=Math.min(1f,1920f/Math.max(decoded.getWidth(),decoded.getHeight()));
            scaled=Bitmap.createScaledBitmap(decoded,Math.max(1,Math.round(decoded.getWidth()*ratio)),Math.max(1,Math.round(decoded.getHeight()*ratio)),true);
            Matrix matrix=new Matrix();
            try{int orientation=new ExifInterface(incoming.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
                switch(orientation){
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
                    case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
                    case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
                    case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
                    case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(270);matrix.postScale(-1,1);break;
                    case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(270);break;
                }
            }catch(IOException ignored){}
            rotated=Bitmap.createBitmap(scaled,0,0,scaled.getWidth(),scaled.getHeight(),matrix,true);
            try(OutputStream output=new FileOutputStream(encoded)){if(!rotated.compress(Bitmap.CompressFormat.JPEG,88,output))throw new IOException("图片保存失败");}
            if(!encoded.renameTo(new File(getFilesDir(),"wallpaper.jpg")))throw new IOException("图片保存失败");
            if(!wallpaperPreferences().edit().putString("kind","photo").putLong("photoVersion",System.currentTimeMillis()).commit())throw new IOException("壁纸设置未保存");
            wallpaperResult("saved","图片已设为首页壁纸，重启后保留。");
        }catch(Exception error){wallpaperResult("error",error.getMessage()==null?"图片处理失败，请重试。":error.getMessage());}
        finally{incoming.delete();encoded.delete();if(rotated!=null&&rotated!=scaled)rotated.recycle();if(scaled!=null&&scaled!=decoded)scaled.recycle();if(decoded!=null)decoded.recycle();}
    }

    public final class AppBridge {
        @JavascriptInterface public String getWallpaper(){return trustedHome?wallpaperJson():"null";}
        @JavascriptInterface public boolean setWallpaper(String kind,String text){
            if(!trustedHome||kind==null||!(kind.equals("winter")||kind.equals("dusk")||kind.equals("mist")||kind.equals("photo")))return false;
            if(kind.equals("photo")&&!new File(getFilesDir(),"wallpaper.jpg").isFile())return false;
            return wallpaperPreferences().edit().putString("kind",kind).putString("text","light".equals(text)?"light":"dark").commit();
        }
        @JavascriptInterface public void pickWallpaper(){runOnUiThread(()->{
            if(!trustedHome||wallpaperPicking||isFinishing())return;wallpaperPicking=true;
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try{startActivityForResult(intent,PICK_WALLPAPER);}catch(ActivityNotFoundException error){wallpaperResult("error","没有找到相册或文件选择器。");}
        });}
        @JavascriptInterface public void saveBackup(String json,String filename) {
            if(json == null || json.length()>150*1024*1024){notice("备份文件过大。");return;}
            try{new JSONObject(json);}catch(Exception e){notice("备份数据格式不正确。");return;}
            String cleanName=filename==null?"不听听力-备份.json":filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");
            if(cleanName.isEmpty())cleanName="不听听力-备份.json";
            final String safeName=cleanName.substring(0,Math.min(cleanName.length(),180));
            runOnUiThread(()->{
                if(isFinishing()||web==null||web.getUrl()==null)return;
                Uri location=Uri.parse(web.getUrl());
                if(!"https".equals(location.getScheme())||!HOST.equals(location.getHost())||!"/index.html".equals(location.getPath()))return;
                if(pendingBackup!=null){notice("请先完成上一次备份保存。");return;}
                pendingBackup=json;
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE,safeName);
                try{startActivityForResult(intent,SAVE_BACKUP);}catch(ActivityNotFoundException e){pendingBackup=null;notice("没有找到文件保存工具。");}
            });
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(listening!=null && listening.result(request,result,data))return;
        if(request==PICK_FILE&&fileCallback!=null){
            Uri uri=result==RESULT_OK&&data!=null?data.getData():null;
            fileCallback.onReceiveValue(uri!=null&&"content".equals(uri.getScheme())?new Uri[]{uri}:null);fileCallback=null;
        }
        if(request==PICK_WALLPAPER){
            Uri uri=result==RESULT_OK&&data!=null?data.getData():null;
            if(uri==null&&result==RESULT_OK&&data!=null&&data.getClipData()!=null&&data.getClipData().getItemCount()>0)uri=data.getClipData().getItemAt(0).getUri();
            if(uri!=null&&"content".equals(uri.getScheme())){final Uri selected=uri;fileWorker.execute(()->importWallpaper(selected));}
            else wallpaperResult("cancelled","未更换壁纸。");
        }
        if(request==SAVE_BACKUP){
            final String backup=pendingBackup;pendingBackup=null;
            Uri uri=result==RESULT_OK&&data!=null?data.getData():null;
            if(backup!=null&&uri!=null&&"content".equals(uri.getScheme()))fileWorker.execute(()->{
                try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){
                    if(output==null)throw new IllegalStateException("No output stream");
                    output.write(backup.getBytes(StandardCharsets.UTF_8));notice("完整备份已保存。");
                }catch(Exception e){notice("保存失败，请换一个文件夹重试。");}
            });
        }
    }
    @Override public void onBackPressed() {
        if(web!=null && !HOME.equals(web.getUrl()) && web.canGoBack()){web.goBack();return;}
        web.evaluateJavascript("(()=>{const d=document.querySelector('dialog[open]');if(d){d.close();return true;}const h=document.getElementById('view-home');if(h&&h.hidden){document.getElementById('back-home').click();return true;}return false;})()",result->{if(!"true".equals(result))super.onBackPressed();});
    }
    @Override protected void onPause() {
        if(web!=null)web.evaluateJavascript("window.butingPause?window.butingPause():document.querySelector('audio')?.pause()",null);
        super.onPause();
    }
    @Override protected void onDestroy() {
        if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}
        fileWorker.shutdown();
        if(listening!=null)listening.close();
        if(web!=null){web.removeJavascriptInterface("TingjianAndroid");web.destroy();web=null;}
        super.onDestroy();
    }
}
