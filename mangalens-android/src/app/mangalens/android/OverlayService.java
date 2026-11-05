package app.mangalens.android;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

public final class OverlayService extends Service {
    static volatile OverlayService instance;
    private WindowManager windows;
    private LinearLayout bar;
    private Button translate,toggle;
    private WindowManager.LayoutParams barParams;
    private TranslationView overlay;
    private TranslationClient.Page lastPage;
    private int lastWidth,lastHeight;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler capture;
    private int width,height,operation;
    private boolean busy,ending;
    private Future<?> task;
    private final TranslationClient client=new TranslationClient();
    private final MediaProjection.Callback projectionCallback=new MediaProjection.Callback(){
        @Override public void onStop(){if(!ending){Core.status("화면 캡처 연결이 종료됐습니다. 앱에서 번역바를 다시 시작하세요.");stopSelf();}}
        @Override public void onCapturedContentResize(int w,int h){if(w>0&&h>0&&(w!=width||h!=height))resize(w,h);}
    };
    @Override public void onCreate(){super.onCreate();instance=this;windows=getSystemService(WindowManager.class);captureThread=new HandlerThread("MangaLensCapture");captureThread.start();capture=new Handler(captureThread.getLooper());}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        try{
            if(Build.VERSION.SDK_INT>=29)startForeground(22,Core.notification(this,"화면 공유 연결 유지 · 번역 버튼을 누른 화면만 전송",true),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(22,Core.notification(this,"번역 버튼을 누른 화면만 전송",true));
            Intent data=intent.getParcelableExtra("data");int code=intent.getIntExtra("code",0);if(data==null||code!=Activity.RESULT_OK)throw new IllegalStateException("화면 캡처 허용을 완료해 주세요.");
            MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);projection=manager.getMediaProjection(code,data);projection.registerCallback(projectionCallback,Core.UI);
            android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);width=metrics.widthPixels;height=metrics.heightPixels;
            reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
            display=projection.createVirtualDisplay("MangaLens",width,height,getResources().getConfiguration().densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,capture);
            createBar();Core.status("번역바 준비 완료. 일본어 그림을 띄운 뒤 번역을 누르세요.");
        }catch(Exception ex){Core.status(Core.message(ex));stopSelf();}
        return START_NOT_STICKY;
    }
    private void createBar(){
        bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setPadding(Core.dp(this,4),Core.dp(this,4),Core.dp(this,4),Core.dp(this,4));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(26,39,59));bg.setCornerRadius(Core.dp(this,20));bar.setBackground(bg);bar.setElevation(Core.dp(this,10));
        TextView grip=new TextView(this);grip.setText("⠿");grip.setTextColor(Color.WHITE);grip.setTextSize(20);grip.setGravity(Gravity.CENTER);bar.addView(grip,new LinearLayout.LayoutParams(Core.dp(this,24),Core.dp(this,36)));
        translate=button("번역",62,()->translateOrCancel());toggle=button("숨김",52,()->{if(overlay!=null){overlay.setVisibility(overlay.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);toggle.setText(overlay.getVisibility()==View.VISIBLE?"숨김":"표시");}});
        button("지우기",52,this::clear);
        button("설정",52,()->startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        button("×",34,this::stopSelf);
        barParams=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        barParams.gravity=Gravity.TOP|Gravity.LEFT;barParams.x=Math.max(0,width-Core.dp(this,292));barParams.y=Core.dp(this,12);windows.addView(bar,barParams);
        grip.setOnTouchListener(new View.OnTouchListener(){float x,y;int left,top;public boolean onTouch(View v,android.view.MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();left=barParams.x;top=barParams.y;return true;}if(e.getAction()==MotionEvent.ACTION_MOVE){barParams.x=Math.max(0,Math.min(width-bar.getWidth(),left+(int)(e.getRawX()-x)));barParams.y=Math.max(0,Math.min(height-bar.getHeight()-Core.dp(OverlayService.this,28),top+(int)(e.getRawY()-y)));windows.updateViewLayout(bar,barParams);return true;}return true;}});
    }
    private Button button(String text,int size,Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setBackgroundColor(Color.TRANSPARENT);bar.addView(b,new LinearLayout.LayoutParams(Core.dp(this,size),Core.dp(this,36)));b.setOnClickListener(v->action.run());return b;}
    private void translateOrCancel(){
        if(busy){operation++;client.cancel();if(task!=null)task.cancel(true);finish("번역을 중지했습니다.");return;}
        if(reader==null)return;busy=true;translate.setText("중지");int current=++operation;
        if(overlay!=null)overlay.setVisibility(View.GONE);bar.setVisibility(View.INVISIBLE);
        Core.status("화면을 캡처하고 있습니다…");
        capture.post(()->{
            try{Image old=reader.acquireLatestImage();if(old!=null)old.close();}catch(Exception ignored){}
            capture.postDelayed(()->take(current,0),220);
        });
    }
    private void take(int current,int tries){
        if(ending||current!=operation)return;
        try{
            Image frame=reader.acquireLatestImage();if(frame==null){if(tries<20){capture.postDelayed(()->take(current,tries+1),80);return;}throw new IllegalStateException("화면 캡처를 받지 못했습니다. 그림을 띄운 뒤 다시 눌러 주세요.");}
            Bitmap screenshot;
            try{Image.Plane plane=frame.getPlanes()[0];ByteBuffer pixels=plane.getBuffer();int pixelStride=plane.getPixelStride(),rowStride=plane.getRowStride();Bitmap padded=Bitmap.createBitmap(rowStride/pixelStride,frame.getHeight(),Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(pixels);screenshot=Bitmap.createBitmap(padded,0,0,frame.getWidth(),frame.getHeight());if(screenshot!=padded)padded.recycle();}finally{frame.close();}
            Core.UI.post(()->{if(!ending&&current==operation){bar.setVisibility(View.VISIBLE);Core.status("캡처 완료 · GPT가 일본어를 읽고 번역하고 있습니다…");}});
            task=Core.IO.submit(()->{
                try{
                    TranslationClient.Page page=client.translate(this,screenshot);
                    Core.UI.post(()->{
                        if(ending||current!=operation)return;
                        int shown=show(page,screenshot.getWidth(),screenshot.getHeight());finish(shown==0?"표시할 번역이 없습니다. 앱의 ‘처리 결과’를 확인해 주세요.":shown+"개 영역 번역 완료");
                    });
                }catch(Exception ex){Core.UI.post(()->{if(!ending&&current==operation){Core.transcript="번역 실패\n"+Core.message(ex);finish(Core.message(ex));}});}
                finally{Core.UI.post(screenshot::recycle);}
            });
        }catch(Exception ex){Core.UI.post(()->{if(!ending&&current==operation)finish(Core.message(ex));});}
    }
    private void finish(String text){busy=false;if(bar!=null){bar.setVisibility(View.VISIBLE);translate.setText("번역");}Core.status(text);Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private int show(TranslationClient.Page original,int w,int h){
        removeOverlay();lastPage=original;lastWidth=w;lastHeight=h;
        TranslationClient.Page page=DisplayPolicy.visible(original,Core.prefs(this).getBoolean("show_effects",true));
        Core.transcript="사용 모델: "+page.model+" / 추론: "+page.effort+"\n"+(page.reused?"동일 화면 결과 재사용 · 추가 요청 없음":String.format(java.util.Locale.KOREAN,"전체 처리 %.1f초",page.seconds))+"\n화면: "+w+" × "+h+"\n\n"+page.report;
        if(page.blocks.isEmpty())return 0;overlay=new TranslationView(this,page,w,h);
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;
        // Android permits touches through an untrusted overlay only below its obscuring-opacity limit.
        params.alpha=.79f;if(Build.VERSION.SDK_INT>=28)params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        windows.addView(overlay,params);if(bar!=null){windows.removeView(bar);windows.addView(bar,barParams);}toggle.setText("숨김");
        return page.blocks.size();
    }
    private void removeOverlay(){if(overlay!=null){windows.removeView(overlay);overlay=null;}if(toggle!=null)toggle.setText("숨김");}
    private void clear(){removeOverlay();lastPage=null;}
    void refreshDisplay(){
        if(lastPage==null||ending)return;
        boolean hidden=overlay!=null&&overlay.getVisibility()!=View.VISIBLE;
        show(lastPage,lastWidth,lastHeight);
        if(hidden&&overlay!=null){overlay.setVisibility(View.GONE);toggle.setText("표시");}
    }
    void resetContext(){client.reset();clear();}
    private void resize(int w,int h){
        if(ending||display==null||(width==w&&height==h))return;operation++;client.cancel();if(task!=null)task.cancel(true);busy=false;clear();width=w;height=h;
        ImageReader previous=reader;reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);display.resize(w,h,getResources().getConfiguration().densityDpi);display.setSurface(reader.getSurface());previous.close();
        if(bar!=null){barParams.x=Math.max(0,Math.min(barParams.x,w-bar.getWidth()));barParams.y=Math.max(0,Math.min(barParams.y,h-bar.getHeight()-Core.dp(this,28)));windows.updateViewLayout(bar,barParams);bar.setVisibility(View.VISIBLE);translate.setText("번역");}Core.status("화면 방향이 바뀌었습니다. 새 화면을 번역해 주세요.");
    }
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);if(Build.VERSION.SDK_INT<34){android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);resize(metrics.widthPixels,metrics.heightPixels);}}
    @Override public void onDestroy(){ending=true;operation++;client.cancel();if(task!=null)task.cancel(true);clear();if(bar!=null){windows.removeView(bar);bar=null;}if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null){projection.unregisterCallback(projectionCallback);projection.stop();}if(captureThread!=null)captureThread.quitSafely();instance=null;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
