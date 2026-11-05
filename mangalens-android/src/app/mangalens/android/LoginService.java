package app.mangalens.android;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
public final class LoginService extends Service {
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(Build.VERSION.SDK_INT>=29)startForeground(21,Core.notification(this,"ChatGPT 로그인 연결 대기 중",false),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(21,Core.notification(this,"ChatGPT 로그인 연결 대기 중",false));
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int id,int type){Auth.cancel();stopSelf();}
    @Override public IBinder onBind(Intent intent){return null;}
}
