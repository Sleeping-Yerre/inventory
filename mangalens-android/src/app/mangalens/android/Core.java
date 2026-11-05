package app.mangalens.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

final class Core {
    static final String PACKAGE="app.mangalens.android", CHANNEL="mangalens", VERSION=BuildInfo.VERSION_NAME;
    static final String DEFAULT_UPDATE_REPO="Sleeping-Yerre/inventory";
    static final Handler UI=new Handler(Looper.getMainLooper());
    static final ExecutorService IO=Executors.newCachedThreadPool();
    static volatile String status="ChatGPT를 연결하고 번역바를 시작하세요.";
    static volatile String transcript="아직 번역한 화면이 없습니다.";
    static volatile Runnable observer;
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("settings",Context.MODE_PRIVATE);}
    static void status(String text){status=text;UI.post(()->{if(observer!=null)observer.run();});}
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static Notification notification(Context c,String text,boolean stop){
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"MangaLens",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(c,1,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_manga).setContentTitle("MangaLens").setContentText(text).setContentIntent(open).setOngoing(true);
        if(stop){PendingIntent end=PendingIntent.getService(c,2,new Intent(c,OverlayService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);b.addAction(new Notification.Action.Builder(null,"종료",end).build());}
        return b.build();
    }
    static String message(Throwable t){
        if(t instanceof java.net.SocketTimeoutException)return "연결 시간이 초과됐습니다. 네트워크 상태를 확인해 주세요.";
        if(t instanceof CancellationException||t instanceof InterruptedException)return "번역을 중지했습니다.";
        return t.getMessage()==null?"처리하지 못했습니다. 다시 시도해 주세요.":t.getMessage();
    }
    static synchronized JSONObject account(Context c)throws Exception{
        String encrypted=prefs(c).getString("account","");if(encrypted.isEmpty())return new JSONObject();
        byte[] bytes=Base64.decode(encrypted,Base64.NO_WRAP);
        if(bytes.length<29)throw new IOException("저장된 연결 정보를 읽지 못했습니다. 다시 로그인해 주세요.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));
        return new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8));
    }
    static synchronized void saveAccount(Context c,JSONObject account)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=cipher.getIV(),body=cipher.doFinal(account.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(iv);out.write(body);
        if(!prefs(c).edit().putString("account",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)).commit())throw new IOException("연결 정보를 저장하지 못했습니다.");
    }
    private static javax.crypto.SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias("mangalens-account"))return (javax.crypto.SecretKey)store.getKey("mangalens-account",null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("mangalens-account",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static String host(Context c){String h=prefs(c).getString("host","");if(h.isEmpty()){h="urn:uuid:"+UUID.randomUUID();prefs(c).edit().putString("host",h).commit();}return h;}
    static List<String> efforts(String model){
        switch(model){
            case "gpt-6.1-sol":case "gpt-6-astra":return Arrays.asList("low","medium","high","xhigh","max");
            case "gpt-6-luna":case "gpt-6-sol":case "gpt-6.1-terra":case "gpt-5.6-sol":case "gpt-5.6-terra":case "gpt-5.6-luna":return Arrays.asList("none","low","medium","high","xhigh","max");
            default:return Collections.emptyList();
        }
    }
    static String effort(String model,String choice)throws IOException{
        if(choice.equals("default"))return "";
        if(choice.equals("auto")){if(model.equals("gpt-6-luna")||model.equals("gpt-6-sol")||model.equals("gpt-5.6-sol"))return "none";if(model.equals("gpt-6.1-sol")||model.equals("gpt-6-astra"))return "low";return "";}
        if(!efforts(model).contains(choice))throw new IOException("선택한 모델이 지원하지 않는 추론 수준입니다. 설정에서 다시 선택해 주세요.");
        return choice;
    }
}
