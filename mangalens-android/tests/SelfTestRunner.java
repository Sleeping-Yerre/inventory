package app.mangalens.android;

import android.app.*;
import android.os.Bundle;
import android.graphics.*;
import android.text.TextPaint;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

public final class SelfTestRunner extends Instrumentation {
    private final List<String> results=new ArrayList<>();
    private interface Task {void run()throws Exception;}
    private void check(boolean condition,String label){if(!condition)throw new AssertionError(label);results.add("PASS "+label);}
    private void rejects(Task task,String label)throws Exception{try{task.run();}catch(Exception expected){check(true,label);return;}throw new AssertionError(label);}
    private String event(JSONObject value){return "data: "+value+"\n\n";}
    private JSONObject message(String payload)throws Exception{return new JSONObject().put("type","message").put("id","msg1").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",payload)));}
    private JSONObject response(String payload)throws Exception{return new JSONObject().put("status","completed").put("output",new JSONArray().put(message(payload)));}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{runChecks();String text=String.join("\n",results)+"\nSUCCESS "+results.size()+" checks\n";result.putString("stream",text);finish(Activity.RESULT_OK,result);}catch(Throwable ex){StringWriter out=new StringWriter();ex.printStackTrace(new PrintWriter(out));result.putString("stream",String.join("\n",results)+"\nFAIL "+out);finish(Activity.RESULT_CANCELED,result);}}
    private void runChecks()throws Exception{
        android.content.Context c=getTargetContext();
        check(android.os.Build.VERSION.SDK_INT>=37,"tests execute on Android 17 runtime");
        JSONObject request=TranslationClient.request(c,new byte[]{1,2,3},1080,2340,"gpt-6-luna","medium","","");
        check(request.getBoolean("stream")&&!request.getBoolean("store")&&!request.has("max_output_tokens"),"Plus request keeps completed streaming and does not use paid API parameters");
        check(request.getJSONObject("reasoning").getString("effort").equals("medium"),"selected reasoning level is sent unchanged");
        check(request.getJSONObject("text").getJSONObject("format").getJSONObject("schema").getJSONObject("properties").getJSONObject("blocks").getJSONObject("items").getJSONObject("properties").length()==7,"compact seven-field translation format retained");
        String instruction=request.getString("instructions");
        check(instruction.contains("성적 표현과 비속어를 임의로 순화하거나 과장하지 않고")&&instruction.contains("말더듬")&&instruction.contains("판독이 불확실한 부분은 추측으로 확정하지 않는다."),"original translation fidelity and uncertainty instructions preserved");
        check(!request.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text").contains("이전 페이지"),"empty optional prompt sections omitted");
        rejects(()->Core.effort("gpt-6.1-sol","none"),"unsupported reasoning level rejected");
        check(Core.effort("gpt-6-luna","auto").equals("none")&&Core.effort("gpt-6.1-sol","auto").equals("low")&&Core.effort("custom","default").isEmpty(),"model-specific automatic and default reasoning respected");
        String payload="{\"format_version\":2,\"japanese_detection\":\"found\",\"detected_japanese_regions\":1,\"blocks\":[{\"source\":\"待って\",\"translation\":\"잠깐만.\",\"source_language\":\"ja\",\"kind\":\"dialogue\",\"note\":\"\",\"uncertain\":false,\"source_lines\":[{\"x\":100,\"y\":100,\"width\":40,\"height\":100},{\"x\":180,\"y\":120,\"width\":40,\"height\":120}]}],\"context_notes\":\"\"}";
        TranslationClient.Page page=TranslationClient.parse(response(payload),1000,500);
        check(page.blocks.size()==1&&page.blocks.get(0).bounds.equals(new RectF(100,50,220,120)),"Japanese text parsed and line union converted to screen pixels");
        check(page.report.contains("原文")==false&&page.report.contains("원문: 待って")&&page.report.contains("번역: 잠깐만."),"source and translation retained in processing report");
        JSONObject broken=new JSONObject(payload);broken.getJSONArray("blocks").getJSONObject(0).put("source_lines",new JSONArray());
        TranslationClient.Page missing=TranslationClient.parse(response(broken.toString()),1000,500);
        check(missing.blocks.isEmpty()&&missing.report.contains("위치 오류")&&missing.report.contains("잠깐만."),"invalid coordinates retain readable translation for review");
        JSONObject foreign=new JSONObject(payload);foreign.getJSONArray("blocks").getJSONObject(0).put("source_language","other");
        check(TranslationClient.parse(response(foreign.toString()),1000,500).blocks.isEmpty(),"non-Japanese model result excluded");
        displayChecks(c);
        TranslationClient stream=new TranslationClient();
        String terminal=event(new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed").put("output",new JSONArray())));
        String item=event(new JSONObject().put("type","response.output_item.done").put("item",message(payload)));
        check(TranslationClient.parse(stream.completed(bytes(item+terminal)),1000,500).blocks.size()==1,"completed output item survives empty terminal output");
        String delta=event(new JSONObject().put("type","response.output_text.delta").put("delta",payload));
        check(TranslationClient.parse(stream.completed(bytes(delta+terminal)),1000,500).blocks.size()==1,"delta-only output commits after completion");
        rejects(()->stream.completed(bytes(delta)),"interrupted stream never produces a partial translation");
        String refusal=event(new JSONObject().put("type","response.refusal.done").put("refusal","QA refusal reason"));
        try{stream.completed(bytes(refusal+terminal));throw new AssertionError("refusal missing");}catch(IOException ex){check(ex.getMessage().contains("QA refusal reason"),"model refusal reason preserved");}
        String limited=event(new JSONObject().put("type","response.failed").put("response",new JSONObject().put("error",new JSONObject().put("code","subscription_sharing_usage_limit_exceeded"))));
        try{stream.completed(bytes(limited));throw new AssertionError("limit missing");}catch(IOException ex){check(ex.getMessage().contains("유료 API로 전환하지"),"usage-limit failure never changes billing route");}
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();RSAPublicKey pub=(RSAPublicKey)pair.getPublic();
        JSONArray keys=new JSONArray().put(new JSONObject().put("kid","qa").put("kty","RSA").put("n",Auth.b64(unsigned(pub.getModulus().toByteArray()))).put("e",Auth.b64(unsigned(pub.getPublicExponent().toByteArray()))));
        long now=System.currentTimeMillis()/1000;JSONObject claims=new JSONObject().put("iss","https://auth.openai.com").put("sub","qa-person").put("aud","oaiapp_qa").put("exp",now+3600).put("nonce","qa-nonce");
        String jwt=jwt(pair,claims);check(Auth.verifyWithKeys(jwt,"oaiapp_qa","qa-nonce",keys,now).getString("sub").equals("qa-person"),"OpenAI-style RSA signature and identity verified offline");
        rejects(()->Auth.verifyWithKeys(jwt,"oaiapp_other","qa-nonce",keys,now),"wrong OAuth audience rejected");
        rejects(()->Auth.verifyWithKeys(jwt,"oaiapp_qa","wrong-nonce",keys,now),"wrong OAuth nonce rejected");
        rejects(()->Auth.verifyWithKeys(jwt,"oaiapp_qa","qa-nonce",keys,now+7200),"expired identity token rejected");
        String[] pieces=jwt.split("\\.");byte[] sig=Auth.unb64(pieces[2]);sig[0]^=1;String forged=pieces[0]+"."+pieces[1]+"."+Auth.b64(sig);
        rejects(()->Auth.verifyWithKeys(forged,"oaiapp_qa","qa-nonce",keys,now),"tampered identity signature rejected");
        rejects(()->Auth.parseQuery("state=a&state=b"),"duplicate callback parameters rejected");
        JSONObject fake=new JSONObject().put("email","qa@example.invalid").put("access_token","qa-secret-token");Core.saveAccount(c,fake);
        check(Core.account(c).getString("access_token").equals("qa-secret-token")&&!Core.prefs(c).getString("account","").contains("qa-secret-token"),"Android Keystore encrypted account storage roundtrip");Core.prefs(c).edit().remove("account").commit();
        check(UpdateManager.repo("https://github.com/example/mangalens/").equals("example/mangalens")&&UpdateManager.repo("https://evil.invalid/x/y").isEmpty(),"update source accepts only explicit GitHub repository format");
        check(UpdateManager.compare("0.1.1","0.1.0")>0&&UpdateManager.compare("0.1.0","0.1.0")==0,"update version ordering detects newer release only");
        JSONObject manifest=new JSONObject().put("versionName","0.1.1").put("versionCode",2).put("sha256",String.join("",Collections.nCopies(64,"a"))).put("apkUrl","https://raw.githubusercontent.com/example/mangalens/main/downloads/MangaLens-Android.apk");
        check(UpdateManager.manifest("example/mangalens",manifest).version.equals("0.1.1"),"published update manifest is accepted for the selected repository");
        JSONObject hostile=new JSONObject(manifest.toString()).put("apkUrl","https://evil.invalid/app.apk");rejects(()->UpdateManager.manifest("example/mangalens",hostile),"update cannot redirect the APK to an unrelated host");
        JSONObject badHash=new JSONObject(manifest.toString()).put("sha256","bad");rejects(()->UpdateManager.manifest("example/mangalens",badHash),"missing or malformed APK checksum rejected");
        try(java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));java.net.Socket outgoing=new java.net.Socket("127.0.0.1",server.getLocalPort());java.net.Socket accepted=server.accept()){check(accepted.getInetAddress().isLoopbackAddress(),"Android 17 permits the local-only OAuth callback socket");}
        rejects(()->UpdateManager.verify(c,new File(c.getApplicationInfo().sourceDir)),"same-version APK cannot be presented as an update");
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);paint.setTextSize(28);String korean="아저씨는 기쁘구나♥ 오늘은 잔뜩 장난쳐 줄게♥";
        String wrapped=TranslationView.wrap(korean,paint,200);
        check(wrapped.replace("\n"," ").equals(korean),"Korean wrapping retains words and attached particles");
        String emoji="아주아주길게이어지는하나의단어👩‍🚀♥";String split=TranslationView.wrap(emoji,paint,80);
        check(split.replace("\n","").equals(emoji)&&!split.contains("👩\n")&&!split.contains("‍\n"),"long-word fallback preserves emoji grapheme clusters");
        Bitmap bitmap=Bitmap.createBitmap(1000,500,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.rgb(225,232,228));
        TranslationView view=new TranslationView(c,page,1000,500);view.layout(0,0,1000,500);view.draw(canvas);
        try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),"qa-overlay.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        check(new File(c.getFilesDir(),"qa-overlay.png").length()>1000,"white masks and Korean text render on Android canvas");
        MainActivity activity=(MainActivity)startActivitySync(new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        runOnMainSync(()->{
            android.view.View root=activity.getWindow().getDecorView();String labels=labels(root);
            check(labels.contains("Continue with ChatGPT")&&labels.contains("추론 수준")&&labels.contains("작은 번역바 시작")&&labels.contains("업데이트 확인"),"Android 17 main screen starts with login, overlay, reasoning and update controls");
            check(root.getWidth()>0&&root.getHeight()>0,"main window receives a usable measured size");
            android.widget.CheckBox effects=findEffects(root);
            check(effects!=null&&effects.isChecked(),"effects display remains enabled by default");
            effects.setChecked(false);check(!Core.prefs(c).getBoolean("show_effects",true),"effects display checkbox immediately saves the local display preference");effects.setChecked(true);
            Bitmap preview=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(preview));
            try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"qa-main.png"))){preview.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception ex){throw new RuntimeException(ex);}finally{preview.recycle();}
            activity.finish();
        });
    }
    private ByteArrayInputStream bytes(String text){return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));}
    private void displayChecks(android.content.Context c)throws Exception{
        TranslationClient.Page raw=new TranslationClient.Page();raw.report="원문과 번역을 보관한 원래 보고서";
        TranslationClient.Block dialogue=block("待って","기다려","dialogue",false),effect=block("ドキッ","두근","effect",false),flagged=block("ゴ…","소리","effect",true),marked=block("[판독 불확실]","[판정 불확실]","dialogue",false);
        raw.blocks.addAll(Arrays.asList(dialogue,effect,flagged,marked));
        check(DisplayPolicy.uncertain(flagged),"uncertainty flag suppresses even a nonempty translated effect");
        boolean variants=true;for(String marker:Arrays.asList("[판독 불확실]","[판정 불확실]","［ 판독 불확실 ］")){TranslationClient.Block b=block("ゴ",marker,"effect",false);variants&=DisplayPolicy.uncertain(b);}
        check(variants,"both reported uncertainty markers are hidden even when model flag is missing");
        TranslationClient.Page shown=DisplayPolicy.visible(raw,true);check(shown.blocks.equals(Arrays.asList(dialogue,effect)),"reliable dialogue and effects remain visible while uncertain groups are removed");
        TranslationClient.Page noEffects=DisplayPolicy.visible(raw,false);check(noEffects.blocks.equals(Arrays.asList(dialogue)),"effects toggle removes effects while retaining reliable dialogue");
        check(raw.blocks.size()==4&&DisplayPolicy.visible(raw,true).blocks.size()==2,"display changes preserve cached results for local restoration without retranslation");
        check(shown.report.contains("판독 불확실 숨김: 2개")&&shown.report.contains("[판정 불확실]")&&noEffects.report.contains("효과음 숨김: 1개"),"hidden source, translation and reason stay available in processing details");
        TranslationClient.Page unreadable=new TranslationClient.Page();unreadable.blocks.add(flagged);TranslationClient.Page empty=DisplayPolicy.visible(unreadable,true);
        Bitmap bitmap=Bitmap.createBitmap(1000,500,Bitmap.Config.ARGB_8888);TranslationView view=new TranslationView(c,empty,1000,500);view.layout(0,0,1000,500);view.draw(new Canvas(bitmap));
        check(empty.blocks.isEmpty()&&Color.alpha(bitmap.getPixel(120,80))==0,"uncertain-only result draws neither Korean placeholder nor white text mask");bitmap.recycle();
        Core.prefs(c).edit().remove("show_effects").commit();
    }
    private TranslationClient.Block block(String source,String translation,String kind,boolean uncertain){TranslationClient.Block b=new TranslationClient.Block();b.source=source;b.translation=translation;b.note="";b.kind=kind;b.uncertain=uncertain;b.bounds=new RectF(100,50,220,120);b.lines.add(new RectF(b.bounds));return b;}
    private android.widget.CheckBox findEffects(android.view.View root){if(root instanceof android.widget.CheckBox&&((android.widget.CheckBox)root).getText().toString().equals("효과음 표시"))return (android.widget.CheckBox)root;if(root instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)root;for(int i=0;i<group.getChildCount();i++){android.widget.CheckBox found=findEffects(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private String labels(android.view.View root){StringBuilder text=new StringBuilder();if(root instanceof android.widget.TextView)text.append(((android.widget.TextView)root).getText()).append('\n');if(root instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)root;for(int i=0;i<group.getChildCount();i++)text.append(labels(group.getChildAt(i)));}return text.toString();}
    private byte[] unsigned(byte[] bytes){return bytes[0]==0?Arrays.copyOfRange(bytes,1,bytes.length):bytes;}
    private String jwt(KeyPair pair,JSONObject claims)throws Exception{String data=Auth.b64("{\"alg\":\"RS256\",\"kid\":\"qa\"}".getBytes(StandardCharsets.UTF_8))+"."+Auth.b64(claims.toString().getBytes(StandardCharsets.UTF_8));Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());signature.update(data.getBytes(StandardCharsets.US_ASCII));return data+"."+Auth.b64(signature.sign());}
}
