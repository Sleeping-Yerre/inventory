package app.mangalens.android;

import java.util.regex.Pattern;

final class DisplayPolicy {
    private static final Pattern UNREADABLE=Pattern.compile("[\\[［]\\s*(?:판독|판정)\\s*불확실\\s*[\\]］]");
    static boolean uncertain(TranslationClient.Block block){
        return block.uncertain||marker(block.source)||marker(block.translation)||marker(block.note);
    }
    private static boolean marker(String text){return text!=null&&UNREADABLE.matcher(text).find();}
    static TranslationClient.Page visible(TranslationClient.Page original,boolean effects){
        TranslationClient.Page result=new TranslationClient.Page();
        result.context=original.context;result.model=original.model;result.effort=original.effort;result.seconds=original.seconds;result.reused=original.reused;
        int unreadable=0,hiddenEffects=0,order=0;StringBuilder reasons=new StringBuilder();
        for(TranslationClient.Block block:original.blocks){
            order++;
            if(uncertain(block)){
                unreadable++;reasons.append(order).append(". 화면 표시 제외: 판독 불확실\n원문: ").append(block.source).append("\n번역: ").append(block.translation).append("\n\n");
            }else if(!effects&&"effect".equals(block.kind)){
                hiddenEffects++;reasons.append(order).append(". 화면 표시 제외: 효과음 표시 꺼짐\n원문: ").append(block.source).append("\n번역: ").append(block.translation).append("\n\n");
            }else result.blocks.add(block);
        }
        result.report="화면 표시: "+result.blocks.size()+"개 / 판독 불확실 숨김: "+unreadable+"개 / 효과음 숨김: "+hiddenEffects+"개\n\n"+original.report+
            (reasons.length()>0?"\n화면에서 숨긴 영역\n"+reasons:"");
        return result;
    }
}
