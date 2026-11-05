package app.mangalens.android;

import android.content.Context;
import android.graphics.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;

final class TranslationClient {
    volatile HttpURLConnection active;
    volatile boolean cancelled;
    private long deadline;
    private String cacheKey="",context="";
    private Page cache;
    static final class Block {
        String source,translation,note,kind;boolean uncertain;RectF bounds;
        final List<RectF> lines=new ArrayList<>();
    }
    static final class Page {
        final List<Block> blocks=new ArrayList<>();
        String report="",context="",model="",effort="";double seconds;boolean reused;
    }
    void cancel(){cancelled=true;HttpURLConnection c=active;if(c!=null)c.disconnect();}
    void reset(){cacheKey="";cache=null;context="";}
    Page translate(Context app,Bitmap screenshot)throws Exception{
        cancelled=false;long started=System.nanoTime();deadline=System.currentTimeMillis()+180000;
        String model=Core.prefs(app).getString("model","");if(model.isEmpty())throw new IOException("먼저 앱에서 ChatGPT 모델을 불러와 선택해 주세요.");
        String effort=Core.effort(model,Core.prefs(app).getString("effort","auto"));
        ByteArrayOutputStream png=new ByteArrayOutputStream();screenshot.compress(Bitmap.CompressFormat.PNG,100,png);byte[] image=png.toByteArray();
        String glossary=Core.prefs(app).getString("glossary","");String key=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(image),Base64.NO_WRAP)+model+"\n"+effort+"\n"+glossary+"\n"+Core.account(app).optString("subject");
        if(key.equals(cacheKey)&&cache!=null){cache.reused=true;return cache;}
        JSONObject request=request(app,image,screenshot.getWidth(),screenshot.getHeight(),model,effort,glossary,context);
        HttpURLConnection c=Http.connection("https://api.openai.com/v1/responses",180000);active=c;
        try{
            c.setRequestMethod("POST");c.setRequestProperty("Authorization","Bearer "+Auth.access(app));c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","text/event-stream");c.setDoOutput(true);
            byte[] body=request.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(body.length);
            check();try(OutputStream out=c.getOutputStream()){out.write(body);}check();
            int status=c.getResponseCode();if(status>=400)throw new IOException(Http.error(status,Http.read(c.getErrorStream(),2000000)));
            JSONObject response=completed(c.getInputStream());check();
            Page page=parse(response,screenshot.getWidth(),screenshot.getHeight());page.model=model;page.effort=effort.isEmpty()?"모델 기본값":effort;page.seconds=(System.nanoTime()-started)/1e9;
            if(!page.blocks.isEmpty()){cache=page;cacheKey=key;context=page.context.length()>1200?page.context.substring(0,1200):page.context;}
            return page;
        }finally{active=null;c.disconnect();}
    }
    private void check()throws IOException{if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();if(deadline>0&&System.currentTimeMillis()>deadline)throw new java.net.SocketTimeoutException();}
    static JSONObject request(Context app,byte[] image,int width,int height,String model,String effort,String glossary,String context)throws Exception{
        String instructions=Http.read(app.getAssets().open("instructions.txt"),30000);
        JSONObject schema=new JSONObject(Http.read(app.getAssets().open("translation-schema.json"),20000));
        String prompt="Japanese ONLY → Korean. Image: "+width+" × "+height+" pixels.";
        if(!glossary.trim().isEmpty())prompt+="\n이름·번역 참고자료:\n"+glossary;
        if(!context.trim().isEmpty())prompt+="\n이전 페이지 참고:\n"+context;
        JSONArray content=new JSONArray().put(new JSONObject().put("type","input_text").put("text",prompt)).put(new JSONObject().put("type","input_image").put("image_url","data:image/png;base64,"+Base64.encodeToString(image,Base64.NO_WRAP)).put("detail","original"));
        JSONObject result=new JSONObject().put("model",model).put("store",false).put("stream",true).put("instructions",instructions)
            .put("input",new JSONArray().put(new JSONObject().put("role","user").put("content",content)))
            .put("text",new JSONObject().put("format",new JSONObject().put("type","json_schema").put("name","manga_translation").put("strict",true).put("schema",schema)));
        if(!effort.isEmpty())result.put("reasoning",new JSONObject().put("effort",effort));return result;
    }
    JSONObject completed(InputStream stream)throws Exception{
        BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8));String line;StringBuilder event=new StringBuilder(),deltas=new StringBuilder(),refusal=new StringBuilder();
        LinkedHashMap<String,JSONObject> messages=new LinkedHashMap<>();int received=0;boolean refused=false;
        while((line=reader.readLine())!=null){
            check();received+=line.length();if(received>12000000)throw new IOException("번역 응답이 너무 큽니다.");
            if(line.trim().startsWith("{")&&event.length()==0&&received==line.length()){
                StringBuilder buffered=new StringBuilder(line);while((line=reader.readLine())!=null){buffered.append('\n').append(line);if(buffered.length()>5000000)throw new IOException("번역 응답이 너무 큽니다.");}
                JSONObject result=new JSONObject(buffered.toString());if(!result.optString("status").equals("completed"))throw new IOException("완료된 번역 응답을 받지 못했습니다.");return result;
            }
            if(line.startsWith("data:")){if(event.length()>0)event.append('\n');event.append(line.substring(5).trim());}
            if(!line.isEmpty()||event.length()==0)continue;
            String data=event.toString();event.setLength(0);if(data.equals("[DONE]"))continue;JSONObject e=new JSONObject(data);String type=e.optString("type");
            if(type.equals("response.output_item.done")){JSONObject item=e.getJSONObject("item");if(item.optString("type").equals("message"))messages.put(item.optString("id","m"+messages.size()),item);}
            if(type.equals("response.output_text.delta"))deltas.append(e.optString("delta"));
            if(type.equals("response.output_text.done")){deltas.setLength(0);deltas.append(e.optString("text"));}
            if(type.startsWith("response.refusal"))refused=true;
            if(type.equals("response.refusal.delta"))refusal.append(e.optString("delta"));
            if(type.equals("response.refusal.done")){refusal.setLength(0);refusal.append(e.optString("refusal"));}
            if(type.equals("response.failed")||type.equals("error")){JSONObject failure=e.optJSONObject("response");throw new IOException(Http.error(400,(failure==null?e:failure).toString()));}
            if(type.equals("response.incomplete"))throw new IOException("번역 응답이 완성되지 않았습니다. 다시 시도해 주세요.");
            if(type.equals("response.completed")){
                if(refused)throw new IOException("응답 거절: "+(refusal.length()==0?"구체적인 사유가 제공되지 않았습니다.":refusal.substring(0,Math.min(1000,refusal.length()))));
                JSONObject result=e.getJSONObject("response");if(!result.optString("status").equals("completed"))throw new IOException("완료된 번역 응답을 받지 못했습니다.");
                JSONArray output=result.optJSONArray("output");if(output==null)output=new JSONArray();Set<String> ids=new HashSet<>();for(int i=0;i<output.length();i++)ids.add(output.getJSONObject(i).optString("id"));
                for(Map.Entry<String,JSONObject> item:messages.entrySet())if(!ids.contains(item.getKey()))output.put(item.getValue());
                boolean hasText=false;for(int i=0;i<output.length();i++){JSONArray parts=output.getJSONObject(i).optJSONArray("content");if(parts!=null)for(int j=0;j<parts.length();j++){String p=parts.getJSONObject(j).optString("type");hasText|=p.equals("output_text")||p.equals("refusal");}}
                if(!hasText&&deltas.length()>0)output.put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",deltas.toString()))));
                result.put("output",output);return result;
            }
        }
        throw new IOException("완료 전에 연결이 끊겼습니다. 번역 결과를 표시하지 않았습니다.");
    }
    static Page parse(JSONObject response,int width,int height)throws Exception{
        StringBuilder text=new StringBuilder();JSONArray output=response.optJSONArray("output");
        if(output!=null)for(int i=0;i<output.length();i++){JSONArray content=output.getJSONObject(i).optJSONArray("content");if(content==null)continue;for(int j=0;j<content.length();j++){JSONObject p=content.getJSONObject(j);if(p.optString("type").equals("refusal"))throw new IOException("응답 거절: "+p.optString("refusal","구체적인 사유가 제공되지 않았습니다."));if(p.optString("type").equals("output_text"))text.append(p.optString("text"));}}
        if(text.length()==0)throw new IOException("번역문이 없는 응답입니다.");
        JSONObject raw;try{raw=new JSONObject(text.toString());}catch(JSONException ex){throw new IOException("번역 결과 형식을 읽지 못했습니다.");}
        Page page=new Page();page.context=raw.optString("context_notes");JSONArray blocks=raw.optJSONArray("blocks");int received=blocks==null?0:blocks.length(),rejected=0;StringBuilder details=new StringBuilder();
        for(int i=0;i<Math.min(received,160);i++){
            JSONObject b=blocks.getJSONObject(i);Block block=new Block();block.source=b.optString("source");block.translation=b.optString("translation");block.kind=b.optString("kind","dialogue");block.note=b.optString("note");block.uncertain=b.optBoolean("uncertain");
            details.append(i+1).append(". 원문: ").append(block.source).append("\n번역: ").append(block.translation).append('\n');if(!block.note.isEmpty())details.append("설명: ").append(block.note).append('\n');
            boolean japanese=!b.optString("source_language","ja").equals("other")&&(japanese(block.source)||block.uncertain);
            JSONArray strips=b.optJSONArray("source_lines");RectF union=null;
            if(strips!=null)for(int j=0;j<Math.min(80,strips.length());j++){
                JSONObject r=strips.getJSONObject(j);double x=r.optDouble("x"),y=r.optDouble("y"),w=r.optDouble("width"),h=r.optDouble("height");
                if(!Double.isFinite(x+y+w+h)||w<=0||h<=0)continue;
                RectF box=new RectF((float)(x/1000*width),(float)(y/1000*height),(float)((x+w)/1000*width),(float)((y+h)/1000*height));
                if(!box.intersect(0,0,width,height)||box.width()<1||box.height()<1)continue;block.lines.add(box);if(union==null)union=new RectF(box);else union.union(box);
            }
            if(!japanese||block.translation.trim().isEmpty()||union==null){rejected++;details.append("표시 제외: ").append(!japanese?"일본어 외 결과":block.translation.trim().isEmpty()?"번역문 누락":"위치 오류").append('\n');}
            else if(block.translation.length()<=3000){block.bounds=union;page.blocks.add(block);}else{rejected++;details.append("표시 제외: 번역문 길이 초과\n");}
            details.append('\n');
        }
        page.report="일본어 검출: "+raw.optString("japanese_detection","unknown")+" / 모델 보고: "+raw.optInt("detected_japanese_regions")+"개\n받은 영역: "+received+" / 표시: "+page.blocks.size()+" / 제외: "+rejected+"\n"+raw.optString("detection_note")+"\n\n"+details;
        return page;
    }
    private static boolean japanese(String source){for(int i=0;i<source.length();){int c=source.codePointAt(i);if((c>=0x3040&&c<=0x30ff)||(c>=0x31f0&&c<=0x31ff)||(c>=0xff66&&c<=0xff9f)||(c>=0x3400&&c<=0x9fff)||(c>=0x20000&&c<=0x323af))return true;i+=Character.charCount(c);}return false;}
}
