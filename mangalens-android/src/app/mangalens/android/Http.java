package app.mangalens.android;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Http {
    static String form(Map<String,String> values)throws Exception{
        StringBuilder result=new StringBuilder();for(Map.Entry<String,String> e:values.entrySet()){if(result.length()>0)result.append('&');result.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));}return result.toString();
    }
    static HttpURLConnection connection(String url,int readTimeout)throws Exception{
        URL target=new URL(url);if(!target.getProtocol().equals("https"))throw new IOException("HTTPS 연결만 지원합니다.");
        HttpURLConnection c=(HttpURLConnection)target.openConnection();c.setConnectTimeout(20000);c.setReadTimeout(readTimeout);c.setRequestProperty("User-Agent","MangaLens-Android/"+Core.VERSION);c.setUseCaches(false);return c;
    }
    static String read(InputStream in,int limit)throws Exception{
        if(in==null)return "";try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){if(out.size()+n>limit)throw new IOException("응답 크기가 허용 범위를 넘었습니다.");out.write(b,0,n);}return out.toString("UTF-8");}
    }
    static JSONObject json(String url,String bearer,Map<String,String> form)throws Exception{
        HttpURLConnection c=connection(url,35000);
        try{
            if(bearer!=null)c.setRequestProperty("Authorization","Bearer "+bearer);
            if(form!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");byte[] data=form(form).getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
            int status=c.getResponseCode();String text=read(status<400?c.getInputStream():c.getErrorStream(),2000000);
            if(status>=400)throw new IOException(error(status,text));return text.trim().isEmpty()?new JSONObject():new JSONObject(text);
        }finally{c.disconnect();}
    }
    static String error(int status,String response){
        String code="";try{code=new JSONObject(response).getJSONObject("error").optString("code");}catch(Exception ignored){}
        if(code.contains("usage_limit")||code.contains("usage_unavailable"))return "ChatGPT 요금제 사용 한도에 도달했습니다. 잠시 후 다시 시도해 주세요. 유료 API로 전환하지 않습니다.";
        if(code.equals("invalid_grant")||code.contains("refresh_token"))return "ChatGPT 연결이 만료되었거나 해제됐습니다. 다시 로그인해 주세요.";
        if(status==401)return "ChatGPT 연결을 확인해 주세요. 다시 로그인해야 할 수 있습니다.";
        if(status==403)return "이 연결에서 요청한 기능을 사용할 수 없습니다. ChatGPT 요금제 사용 허용을 확인해 주세요.";
        if(status==429)return "사용량 또는 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.";
        return "연결 요청을 처리하지 못했습니다. (HTTP "+status+(code.isEmpty()?"":", "+code)+")";
    }
}
