package app.mangalens.android;

import android.content.*;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class Auth {
    private static final String ISSUER="https://auth.openai.com",RESOURCE="https://api.openai.com/v1";
    static final AtomicBoolean signingIn=new AtomicBoolean();
    static volatile ServerSocket listener;
    private static final AtomicInteger generation=new AtomicInteger();
    private static Context loginContext;
    static String random(int count){byte[] bytes=new byte[count];new SecureRandom().nextBytes(bytes);return b64(bytes);}
    static String b64(byte[] bytes){return Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    static byte[] unb64(String text){return Base64.decode(text,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    static void begin(android.app.Activity activity){
        if(!signingIn.compareAndSet(false,true))return;
        Context app=activity.getApplicationContext();loginContext=app;int operation=generation.incrementAndGet();
        try{app.startForegroundService(new Intent(app,LoginService.class));}
        catch(Exception ex){signingIn.set(false);Core.status(Core.message(ex));return;}
        Core.status("브라우저에서 ChatGPT 로그인과 요금제 사용 허용을 완료해 주세요.");
        Core.IO.execute(()->{
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                listener=server;server.setSoTimeout(1000);
                JSONObject old=Core.account(app);String issued=old.optString("client_id"),state=random(32),nonce=random(32),verifier=random(48);
                String redirect="http://127.0.0.1:"+server.getLocalPort()+"/auth/callback";
                Map<String,String> query=new LinkedHashMap<>();
                query.put("client_id",issued.isEmpty()?"dynamic_agent_client":issued);query.put("ext_agent_host_id",Core.host(app));
                query.put("response_type","code");query.put("redirect_uri",redirect);query.put("scope","openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");query.put("resource",RESOURCE);query.put("state",state);query.put("nonce",nonce);query.put("code_challenge_method","S256");query.put("code_challenge",b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))));
                if(issued.isEmpty())query.put("agent_name_hint","MangaLens");else if(!old.optString("id_token").isEmpty())query.put("id_token_hint",old.getString("id_token"));
                String url=ISSUER+"/api/accounts/authorize?"+Http.form(query);
                Core.UI.post(()->{if(!activity.isFinishing())activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));});
                long end=System.currentTimeMillis()+8*60*1000;Map<String,String> callback=null;
                while(signingIn.get()&&generation.get()==operation&&System.currentTimeMillis()<end){
                    try(Socket socket=server.accept()){
                        socket.setSoTimeout(5000);String first=header(socket.getInputStream()).split("\r\n",2)[0];String[] parts=first.split(" ");
                        if(parts.length!=3||!parts[0].equals("GET")||!parts[1].startsWith("/auth/callback?")){page(socket,404,false);continue;}
                        Map<String,String> received=parseQuery(parts[1].substring(parts[1].indexOf('?')+1));
                        if(!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),received.getOrDefault("state","").getBytes(StandardCharsets.UTF_8))){page(socket,400,false);continue;}
                        if(received.containsKey("error")){page(socket,400,false);throw new IOException("로그인 또는 요금제 사용 허용이 취소됐습니다.");}
                        String client=received.getOrDefault("client_id",issued),code=received.getOrDefault("code","");
                        if(client.isEmpty()||client.equals("dynamic_agent_client")||code.isEmpty()||(!issued.isEmpty()&&!client.equals(issued))){page(socket,400,false);throw new IOException("앱 등록 응답을 확인하지 못했습니다. 다시 로그인해 주세요.");}
                        received.put("client_id",client);page(socket,200,true);callback=received;break;
                    }catch(SocketTimeoutException ignored){}
                }
                if(callback==null)throw new IOException("로그인을 완료하지 못했습니다. 앱으로 돌아와 다시 연결해 주세요.");
                String client=callback.get("client_id");
                Map<String,String> form=new LinkedHashMap<>();form.put("grant_type","authorization_code");form.put("client_id",client);form.put("code",callback.get("code"));form.put("code_verifier",verifier);form.put("redirect_uri",redirect);form.put("resource",RESOURCE);
                JSONObject tokens=Http.json(ISSUER+"/api/accounts/oauth/token",null,form);
                JSONObject identity=verify(tokens.getString("id_token"),client,nonce);
                if(!old.optString("subject").isEmpty()&&!old.getString("subject").equals(identity.getString("sub")))throw new IOException("저장된 연결과 다른 계정입니다. 로그아웃한 뒤 새 계정으로 연결해 주세요.");
                JSONObject account=new JSONObject();account.put("client_id",client);account.put("email",identity.optString("email","ChatGPT 계정"));account.put("subject",identity.getString("sub"));apply(account,tokens);
                synchronized(Auth.class){if(generation.get()!=operation||!signingIn.get())return;Core.saveAccount(app,account);}
                Core.status(enabled(account)?"ChatGPT 요금제 연결 완료. 앱으로 돌아가 모델을 확인해 주세요.":"로그인은 완료했지만 ChatGPT 요금제 사용이 허용되지 않았습니다.");
            }catch(Exception ex){if(generation.get()==operation)Core.status(Core.message(ex));}
            finally{if(generation.get()==operation){listener=null;signingIn.set(false);app.stopService(new Intent(app,LoginService.class));Core.UI.post(()->{if(Core.observer!=null)Core.observer.run();});}}
        });
    }
    static void cancel(){generation.incrementAndGet();signingIn.set(false);try{if(listener!=null)listener.close();}catch(Exception ignored){}listener=null;if(loginContext!=null)loginContext.stopService(new Intent(loginContext,LoginService.class));}
    static boolean enabled(JSONObject a){return !a.optString("access_token").isEmpty()&&Arrays.asList(a.optString("scope").split(" ")).contains("chatgpt.tokens.use.direct");}
    static synchronized String access(Context c)throws Exception{
        JSONObject account=Core.account(c);if(!enabled(account))throw new IOException("먼저 ChatGPT 로그인과 요금제 사용 허용을 완료해 주세요.");
        if(account.optLong("expires_at")>System.currentTimeMillis()+120000)return account.getString("access_token");
        String refresh=account.optString("refresh_token");if(refresh.isEmpty())throw new IOException("ChatGPT 연결이 만료됐습니다. 다시 로그인해 주세요.");
        Map<String,String> form=new LinkedHashMap<>();form.put("grant_type","refresh_token");form.put("client_id",account.getString("client_id"));form.put("refresh_token",refresh);form.put("resource",RESOURCE);
        JSONObject tokens=Http.json(ISSUER+"/api/accounts/oauth/token",null,form);
        if(tokens.has("id_token")){JSONObject identity=verify(tokens.getString("id_token"),account.getString("client_id"),null);if(!identity.getString("sub").equals(account.getString("subject")))throw new IOException("갱신된 계정 정보가 다릅니다. 다시 로그인해 주세요.");}
        apply(account,tokens);Core.saveAccount(c,account);if(!enabled(account))throw new IOException("ChatGPT 요금제 사용 허용이 해제됐습니다.");return account.getString("access_token");
    }
    private static void apply(JSONObject account,JSONObject tokens)throws Exception{
        account.put("access_token",tokens.getString("access_token"));
        for(String key:Arrays.asList("refresh_token","id_token","scope"))if(tokens.has(key))account.put(key,tokens.getString(key));
        account.put("expires_at",System.currentTimeMillis()+Math.min(86400,Math.max(1,tokens.optLong("expires_in",3600)))*1000);
    }
    static JSONArray models(Context c)throws Exception{
        JSONObject data=Http.json("https://api.openai.com/v1/models",access(c),null);JSONArray models=data.getJSONArray("models"),visible=new JSONArray();
        for(int i=0;i<models.length();i++){JSONObject m=models.getJSONObject(i);if(m.optString("visibility").equals("list"))visible.put(new JSONObject().put("slug",m.getString("slug")).put("display_name",m.optString("display_name",m.getString("slug"))));}
        if(visible.length()==0)throw new IOException("이 계정에서 사용 가능한 번역 모델을 받지 못했습니다.");return visible;
    }
    static JSONObject verify(String jwt,String client,String nonce)throws Exception{
        JSONObject metadata=Http.json(ISSUER+"/.well-known/openid-configuration",null,null);String jwks=metadata.getString("jwks_uri");
        if(!metadata.getString("issuer").equals(ISSUER)||!jwks.startsWith(ISSUER+"/"))throw new IOException("계정 확인 서버가 올바르지 않습니다.");
        return verifyWithKeys(jwt,client,nonce,Http.json(jwks,null,null).getJSONArray("keys"),System.currentTimeMillis()/1000);
    }
    static JSONObject verifyWithKeys(String jwt,String client,String nonce,JSONArray keys,long now)throws Exception{
        String[] parts=jwt.split("\\.",-1);if(parts.length!=3)throw new IOException("계정 확인 토큰 형식이 올바르지 않습니다.");
        JSONObject header=new JSONObject(new String(unb64(parts[0]),StandardCharsets.UTF_8)),claims=new JSONObject(new String(unb64(parts[1]),StandardCharsets.UTF_8));
        if(!header.optString("alg").equals("RS256"))throw new IOException("지원하지 않는 계정 서명입니다.");
        PublicKey key=null;
        for(int i=0;i<keys.length();i++){JSONObject item=keys.getJSONObject(i);if(item.optString("kid").equals(header.optString("kid"))&&item.optString("kty").equals("RSA")){key=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1,unb64(item.getString("n"))),new BigInteger(1,unb64(item.getString("e")))));break;}}
        if(key==null)throw new IOException("계정 서명 키를 확인하지 못했습니다.");Signature signature=Signature.getInstance("SHA256withRSA");signature.initVerify(key);signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
        if(!signature.verify(unb64(parts[2])))throw new IOException("계정 서명이 일치하지 않습니다.");
        boolean audience=client.equals(claims.optString("aud"));JSONArray audiences=claims.optJSONArray("aud");if(audiences!=null)for(int i=0;i<audiences.length();i++)audience|=client.equals(audiences.optString(i));
        if(!claims.optString("iss").equals(ISSUER)||!audience||claims.optLong("exp")<now-30||claims.optString("sub").isEmpty()||(nonce!=null&&!nonce.equals(claims.optString("nonce"))))throw new IOException("계정의 발급자·대상·만료·로그인 요청을 확인하지 못했습니다.");
        return claims;
    }
    static Map<String,String> parseQuery(String value)throws Exception{
        Map<String,String> result=new HashMap<>();for(String pair:value.split("&")){String[] p=pair.split("=",2);String name=URLDecoder.decode(p[0],"UTF-8"),text=URLDecoder.decode(p.length>1?p[1]:"","UTF-8");if(result.put(name,text)!=null)throw new IOException("중복된 로그인 응답입니다.");}return result;
    }
    private static String header(InputStream in)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int last=0,n;while(out.size()<16384&&(n=in.read())!=-1){out.write(n);last=(last<<8)|n;if(last==0x0d0a0d0a)break;}return out.toString("US-ASCII");
    }
    private static void page(Socket socket,int code,boolean ok)throws Exception{
        byte[] body=("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>MangaLens</title><h1>MangaLens</h1><p>"+(ok?"연결 요청을 받았습니다. MangaLens 앱으로 돌아가 연결 상태를 확인해 주세요.":"로그인 연결을 확인하지 못했습니다. 앱에서 다시 시도해 주세요.")+"</p><p><a href='mangalens://connected'>MangaLens로 돌아가기</a></p>").getBytes(StandardCharsets.UTF_8);
        OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" "+(code==200?"OK":"Bad Request")+"\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; navigate-to mangalens:\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
}
