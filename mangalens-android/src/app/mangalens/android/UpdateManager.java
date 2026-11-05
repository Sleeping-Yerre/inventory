package app.mangalens.android;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

final class UpdateManager {
    static final class Release {String version,apk,sha256,repo;}
    static String repo(String text){String r=text.trim().replaceFirst("^https://github\\.com/","").replaceFirst("/+$","");return r.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")?r:"";}
    static void check(Activity activity,boolean quiet,Consumer<Release> found){
        String repo=repo(Core.prefs(activity).getString("updates",Core.DEFAULT_UPDATE_REPO));if(repo.isEmpty()){if(!quiet)Core.status("업데이트를 배포할 GitHub 저장소 주소를 먼저 저장해 주세요.");return;}
        long now=System.currentTimeMillis();if(quiet&&now-Core.prefs(activity).getLong("last_update_check",0)<6*60*60*1000)return;
        Core.IO.execute(()->{
            try{
                HttpURLConnection c=Http.connection("https://raw.githubusercontent.com/"+repo+"/main/updates/android.json",25000);String data;
                try{int code=c.getResponseCode();if(code==404)throw new IOException("공개된 업데이트 정보가 없습니다. 저장소 공개 여부와 배포 상태를 확인해 주세요.");if(code!=200)throw new IOException("업데이트 목록을 확인하지 못했습니다. (HTTP "+code+")");data=Http.read(c.getInputStream(),20000);}finally{c.disconnect();}
                JSONObject info=new JSONObject(data);Release release=manifest(repo,info);Core.prefs(activity).edit().putLong("last_update_check",now).apply();String tag=release.version;
                if(compare(tag,Core.VERSION)<=0){if(!quiet)Core.status("설치된 버전이 최신 배포 버전입니다.");return;}
                Core.UI.post(()->{if(!activity.isFinishing())found.accept(release);});
            }catch(Exception ex){if(!quiet)Core.status(Core.message(ex));}
        });
    }
    static Release manifest(String repo,JSONObject info)throws Exception{
        Release release=new Release();release.version=info.getString("versionName");release.repo=repo;release.sha256=info.getString("sha256").toLowerCase(Locale.ROOT);release.apk=info.getString("apkUrl");
        if(info.optInt("versionCode")<1||!release.sha256.matches("[a-f0-9]{64}")||!release.apk.equals("https://raw.githubusercontent.com/"+repo+"/main/downloads/MangaLens-Android.apk"))throw new IOException("업데이트 파일 주소 또는 무결성 정보를 확인하지 못했습니다.");
        compare(release.version,Core.VERSION);return release;
    }
    static int compare(String left,String right)throws IOException{
        if(!left.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IOException("업데이트 버전 형식을 확인하지 못했습니다.");
        String[] a=left.split("\\."),b=right.split("\\.");for(int i=0;i<3;i++){int result=Integer.compare(Integer.parseInt(a[i]),Integer.parseInt(b[i]));if(result!=0)return result;}return 0;
    }
    static void download(Activity activity,Release release){
        Core.status("업데이트를 내려받고 서명을 확인하고 있습니다…");Core.IO.execute(()->{
            File file=new File(activity.getCacheDir(),"update.apk"),partial=new File(activity.getCacheDir(),"update.apk.part");
            try{
                String expected=release.sha256;if(!expected.matches("[a-f0-9]{64}"))throw new IOException("업데이트 무결성 정보를 읽지 못했습니다.");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");HttpURLConnection c=Http.connection(release.apk+"?v="+expected,45000);
                try{if(c.getResponseCode()!=200)throw new IOException("APK 다운로드에 실패했습니다.");try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(partial)){byte[] buffer=new byte[32768];long total=0;int read;while((read=in.read(buffer))!=-1){total+=read;if(total>150000000)throw new IOException("APK 파일이 허용 크기를 넘었습니다.");digest.update(buffer,0,read);out.write(buffer,0,read);}}}finally{c.disconnect();}
                if(!hex(digest.digest()).equals(expected))throw new IOException("다운로드한 APK의 무결성이 일치하지 않습니다.");
                verify(activity,partial);if(file.exists()&&!file.delete())throw new IOException("이전 업데이트 파일을 정리하지 못했습니다.");if(!partial.renameTo(file))throw new IOException("업데이트 파일을 준비하지 못했습니다.");
                Core.UI.post(()->{if(!activity.isFinishing())install(activity);});
            }catch(Exception ex){partial.delete();Core.status(Core.message(ex));}
        });
    }
    static void verify(Context c,File file)throws Exception{
        PackageManager pm=c.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo incoming=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags),current=pm.getPackageInfo(Core.PACKAGE,flags);
        if(incoming==null||!Core.PACKAGE.equals(incoming.packageName))throw new IOException("MangaLens의 업데이트 파일이 아닙니다.");
        long newCode=Build.VERSION.SDK_INT>=28?incoming.getLongVersionCode():incoming.versionCode,oldCode=Build.VERSION.SDK_INT>=28?current.getLongVersionCode():current.versionCode;
        if(newCode<=oldCode)throw new IOException("더 새로운 버전의 APK가 아닙니다.");
        if(!certs(incoming).equals(certs(current)))throw new IOException("설치된 앱과 업데이트의 서명이 다릅니다. 설치하지 않았습니다.");
    }
    private static Set<String> certs(PackageInfo info)throws Exception{android.content.pm.Signature[] signatures=Build.VERSION.SDK_INT>=28?info.signingInfo.getApkContentsSigners():info.signatures;Set<String> result=new HashSet<>();for(android.content.pm.Signature s:signatures)result.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));if(result.isEmpty())throw new IOException("앱 서명을 확인하지 못했습니다.");return result;}
    static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    static void install(Activity activity){
        try{
            File file=new File(activity.getCacheDir(),"update.apk");if(!file.isFile())throw new IOException("먼저 업데이트를 내려받아 주세요.");
            if(!activity.getPackageManager().canRequestPackageInstalls()){
                new android.app.AlertDialog.Builder(activity).setTitle("업데이트 설치 허용").setMessage("Android 설정에서 MangaLens의 앱 설치를 허용한 뒤 돌아와 ‘받은 업데이트 설치’를 눌러 주세요.")
                    .setPositiveButton("설정 열기",(d,w)->activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+Core.PACKAGE)))).setNegativeButton("나중에",null).show();return;
            }
            verify(activity,file);Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://"+Core.PACKAGE+".updates/update.apk"),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(intent);
        }catch(Exception ex){Core.status(Core.message(ex));}
    }
}
