package app.mangalens.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

public final class MainActivity extends Activity {
    private LinearLayout content;
    private TextView status,account;
    private Spinner models,efforts,font;
    private EditText glossary,repo;
    private Button login;
    private JSONArray choices=new JSONArray();
    private List<String> effortValues=new ArrayList<>();
    private boolean loading,fetching,attemptedModels;
    private boolean knownAccount;
    private static final int CAPTURE=101;
    @Override public void onCreate(Bundle bundle){super.onCreate(bundle);build();UpdateManager.check(this,true,this::offerUpdate);}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(18),dp(22),dp(28));content.setBackgroundColor(Color.rgb(244,247,249));scroll.addView(content);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
        label("MangaLens",30,true);label("읽던 그림 그대로, 한국어로.",16,false);label("화면 캡처 · 일본어 → 한국어 · "+Core.VERSION,12,false);
        status=label(Core.status,14,false);status.setPadding(0,dp(16),0,dp(12));
        Button start=button("작은 번역바 시작",this::startCapture);start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34,121,105)));start.setTextColor(Color.WHITE);
        button("번역바 종료",()->{stopService(new Intent(this,OverlayService.class));Core.status("번역바를 종료했습니다.");});
        section("ChatGPT 연결");account=label("아직 연결하지 않았습니다.",14,false);
        login=button("Continue with ChatGPT",()->{if(Auth.signingIn.get()){Auth.cancel();Core.status("로그인 연결을 취소했습니다.");}else Auth.begin(this);});
        button("모델 새로고침",this::refreshModels);
        label("번역 모델",14,true);models=new Spinner(this);content.addView(models,fill());
        label("추론 수준",14,true);efforts=new Spinner(this);content.addView(efforts,fill());
        label("지원하는 수준만 표시합니다. 높은 수준은 더 오래 걸릴 수 있습니다.",12,false);
        models.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){if(loading||choices.length()==0)return;try{String slug=choices.getJSONObject(position).getString("slug");Core.prefs(MainActivity.this).edit().putString("model",slug).apply();fillEfforts(slug);}catch(Exception ex){Core.status(Core.message(ex));}}});
        efforts.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){if(!loading&&position<effortValues.size())Core.prefs(MainActivity.this).edit().putString("effort",effortValues.get(position)).apply();}});
        button("로그아웃",this::signOut);
        section("번역 표시와 참고사항");label("글자 크기",14,true);font=new Spinner(this);font.setAdapter(adapter(Arrays.asList("80%","100%","120%","140%")));font.setSelection(Math.max(0,Arrays.asList(80,100,120,140).indexOf(Core.prefs(this).getInt("font",100))));content.addView(font,fill());
        CheckBox effects=new CheckBox(this);effects.setText("효과음 표시");effects.setTextSize(14);effects.setChecked(Core.prefs(this).getBoolean("show_effects",true));content.addView(effects,fill());
        label("판독이 불확실한 영역은 원문 그대로 둡니다. 효과음 표시를 끄면 확실하게 읽힌 효과음도 숨깁니다.",12,false);
        effects.setOnCheckedChangeListener((button,checked)->{Core.prefs(this).edit().putBoolean("show_effects",checked).apply();if(OverlayService.instance!=null)OverlayService.instance.refreshDisplay();Core.status(checked?"확실히 읽힌 효과음을 표시합니다.":"효과음 표시를 껐습니다.");});
        glossary=edit("이름·호칭·말투 참고사항 (선택)",Core.prefs(this).getString("glossary",""));glossary.setSingleLine(false);glossary.setMinLines(2);glossary.setMaxLines(5);
        button("번역 설정 저장",()->{Core.prefs(this).edit().putString("glossary",glossary.getText().toString()).putInt("font",new int[]{80,100,120,140}[font.getSelectedItemPosition()]).apply();Core.status("설정을 저장했습니다. 다음 번역부터 적용됩니다.");});
        button("새 작품 · 이전 맥락 초기화",()->{if(OverlayService.instance!=null)OverlayService.instance.resetContext();Core.status("이전 페이지의 번역 맥락을 초기화했습니다.");});
        button("원문·번역·처리 결과",()->new AlertDialog.Builder(this).setTitle("마지막 처리 결과").setView(reportView(Core.transcript)).setPositiveButton("닫기",null).show());
        section("앱 업데이트");label("GitHub 배포 주소에서 새 버전을 확인합니다. 설치는 Android 확인 화면에서 진행합니다.",13,false);
        repo=edit("배포 저장소: github.com/이름/저장소",Core.prefs(this).getString("updates",Core.DEFAULT_UPDATE_REPO));
        button("업데이트 주소 저장",()->{String raw=repo.getText().toString(),value=UpdateManager.repo(raw);if(!raw.trim().isEmpty()&&value.isEmpty()){Core.status("GitHub 저장소 주소 또는 이름/저장소 형식으로 입력해 주세요.");return;}Core.prefs(this).edit().putString("updates",value).putLong("last_update_check",0).apply();Core.status(value.isEmpty()?"자동 업데이트 확인을 껐습니다.":"주소를 저장했습니다. 앱 시작 시 새 버전을 확인합니다.");});
        button("업데이트 확인",()->UpdateManager.check(this,false,this::offerUpdate));button("받은 업데이트 설치",()->UpdateManager.install(this));
        label("번역 버튼을 누른 화면만 OpenAI로 전송합니다. 캡처 세션 중에는 Android에 화면 공유 알림이 표시됩니다. Plus 한도에 도달하면 멈추며 별도 유료 API로 전환하지 않습니다.",12,false);
        try{String saved=Core.prefs(this).getString("models","");fillModels(saved.isEmpty()?new JSONArray():new JSONArray(saved));}catch(Exception ignored){fillModels(new JSONArray());}
    }
    private int dp(float n){return Core.dp(this,n);}
    private LinearLayout.LayoutParams fill(){return new LinearLayout.LayoutParams(-1,-2);}
    private TextView label(String text,int size,boolean bold){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(Color.rgb(26,39,59));t.setPadding(0,dp(6),0,dp(6));if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);content.addView(t,fill());return t;}
    private void section(String text){TextView t=label(text,19,true);LinearLayout.LayoutParams p=fill();p.topMargin=dp(20);t.setLayoutParams(p);}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(46));b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=fill();p.topMargin=dp(5);content.addView(b,p);return b;}
    private EditText edit(String hint,String value){EditText e=new EditText(this);e.setTextSize(14);e.setHint(hint);e.setText(value);e.setSingleLine(true);content.addView(e,fill());return e;}
    private android.widget.ArrayAdapter<String> adapter(List<String> values){android.widget.ArrayAdapter<String> a=new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return a;}
    private View reportView(String text){ScrollView scroll=new ScrollView(this);TextView t=new TextView(this);t.setText(text);t.setTextSize(14);t.setTextIsSelectable(true);t.setPadding(dp(18),dp(12),dp(18),dp(12));scroll.addView(t);return scroll;}
    @Override protected void onResume(){super.onResume();Core.observer=this::updateState;updateState();}
    @Override protected void onPause(){Core.observer=null;super.onPause();}
    private void updateState(){
        status.setText(Core.status);login.setText(Auth.signingIn.get()?"로그인 연결 취소":"Continue with ChatGPT");
        try{JSONObject a=Core.account(this);boolean connected=Auth.enabled(a);account.setText(connected?"ChatGPT 요금제 사용 중\n"+a.optString("email"):"아직 ChatGPT 요금제가 연결되지 않았습니다.");if(connected&&!knownAccount&&!attemptedModels){knownAccount=true;refreshModels();}if(!connected)knownAccount=false;}catch(Exception ex){account.setText(Core.message(ex));}
    }
    private void refreshModels(){
        if(fetching)return;fetching=true;attemptedModels=true;Core.status("ChatGPT 계정의 사용 가능한 모델을 확인하고 있습니다…");
        Core.IO.execute(()->{try{JSONArray data=Auth.models(this);Core.prefs(this).edit().putString("models",data.toString()).apply();Core.UI.post(()->{if(!isFinishing()){fillModels(data);Core.status("연결과 모델을 확인했습니다. 번역바를 시작하세요.");}});}catch(Exception ex){Core.status(Core.message(ex));}finally{fetching=false;}});
    }
    private void fillModels(JSONArray data){
        loading=true;choices=data;List<String> names=new ArrayList<>();int selected=0;String wanted=Core.prefs(this).getString("model","");
        if(data.length()==0){models.setAdapter(adapter(Arrays.asList("로그인 후 모델을 불러오세요")));models.setEnabled(false);effortValues=new ArrayList<>();efforts.setAdapter(adapter(Arrays.asList("번역 모델을 먼저 선택하세요")));efforts.setEnabled(false);loading=false;return;}
        models.setEnabled(true);efforts.setEnabled(true);
        for(int i=0;i<data.length();i++){JSONObject item=data.optJSONObject(i);names.add(item.optString("display_name"));if(item.optString("slug").equals(wanted))selected=i;}
        models.setAdapter(adapter(names));if(!names.isEmpty()){models.setSelection(selected);String slug=data.optJSONObject(selected).optString("slug");Core.prefs(this).edit().putString("model",slug).apply();fillEfforts(slug);}loading=false;
    }
    private void fillEfforts(String model){
        boolean before=loading;loading=true;effortValues=new ArrayList<>(Arrays.asList("auto","default"));effortValues.addAll(Core.efforts(model));List<String> names=new ArrayList<>();
        for(String value:effortValues){switch(value){case "auto":names.add("빠르게 (자동)");break;case "default":names.add("모델 기본값");break;case "none":names.add("없음");break;case "low":names.add("낮음");break;case "medium":names.add("보통");break;case "high":names.add("높음");break;case "xhigh":names.add("매우 높음");break;case "max":names.add("최대");break;}}
        String wanted=Core.prefs(this).getString("effort","auto");int position=effortValues.indexOf(wanted);if(position<0){position=1;Core.prefs(this).edit().putString("effort","default").apply();}
        efforts.setAdapter(adapter(names));efforts.setSelection(position);loading=before;
    }
    private void startCapture(){
        try{if(!Auth.enabled(Core.account(this))){Core.status("먼저 ChatGPT 로그인과 요금제 사용 허용을 완료해 주세요.");return;}}
        catch(Exception ex){Core.status(Core.message(ex));return;}
        if(OverlayService.instance!=null){moveTaskToBack(true);return;}
        if(!Settings.canDrawOverlays(this)){Core.status("‘다른 앱 위에 표시’를 허용한 뒤 돌아와 번역바 시작을 다시 눌러 주세요.");startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&!Core.prefs(this).getBoolean("notification_asked",false)){
            Core.prefs(this).edit().putBoolean("notification_asked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},102);return;
        }
        MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
        Intent request=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
        startActivityForResult(request,CAPTURE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==CAPTURE&&result==RESULT_OK&&data!=null){startForegroundService(new Intent(this,OverlayService.class).putExtra("code",result).putExtra("data",data));moveTaskToBack(true);}else if(request==CAPTURE)Core.status("화면 캡처 허용을 취소했습니다.");}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==102)startCapture();}
    private void signOut(){
        Auth.cancel();stopService(new Intent(this,OverlayService.class));
        Core.IO.execute(()->{boolean revoked=false;synchronized(Auth.class){try{JSONObject a=Core.account(this);String token=a.optString("refresh_token");if(!token.isEmpty()){Map<String,String> form=new HashMap<>();form.put("token",token);form.put("client_id",a.optString("client_id"));Http.json("https://auth.openai.com/api/accounts/oauth/revoke",null,form);revoked=true;}}catch(Exception ignored){}
            Core.prefs(this).edit().remove("account").remove("models").remove("model").commit();}attemptedModels=false;Core.status(revoked?"로그아웃했습니다.":"기기의 연결을 지웠습니다. 서버 연결 해제는 확인하지 못했습니다. ChatGPT 설정에서도 확인해 주세요.");Core.UI.post(()->fillModels(new JSONArray()));});
    }
    private void offerUpdate(UpdateManager.Release release){new AlertDialog.Builder(this).setTitle("새 버전 "+release.version).setMessage("APK를 내려받아 무결성과 앱 서명을 확인한 뒤 Android 설치 화면을 엽니다.").setPositiveButton("업데이트",(d,w)->UpdateManager.download(this,release)).setNegativeButton("나중에",null).show();}
}
