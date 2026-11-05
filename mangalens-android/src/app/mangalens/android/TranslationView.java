package app.mangalens.android;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.view.View;
import java.util.*;

final class TranslationView extends View {
    final TranslationClient.Page page;
    private final int imageWidth,imageHeight;
    private final Paint mask=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<Placement> placements=new ArrayList<>();
    private static final class Placement {final RectF rect;final StaticLayout text;Placement(RectF r,StaticLayout t){rect=r;text=t;}}
    TranslationView(Context c,TranslationClient.Page page,int width,int height){super(c);this.page=page;imageWidth=width;imageHeight=height;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);layout(c);}
    private void layout(Context c){
        float scale=Core.prefs(c).getInt("font",100)/100f,density=c.getResources().getDisplayMetrics().density;
        mask.setColor(Color.WHITE);text.setColor(Color.rgb(20,25,30));
        for(TranslationClient.Block b:page.blocks){
            RectF cell=new RectF(5,5,imageWidth-5,imageHeight-5);float cx=b.bounds.centerX(),cy=b.bounds.centerY();
            for(TranslationClient.Block other:page.blocks){if(other==b)continue;float dx=other.bounds.centerX()-cx,dy=other.bounds.centerY()-cy;
                if(Math.abs(dx)>=Math.abs(dy)){if(dx>0)cell.right=Math.min(cell.right,(other.bounds.centerX()+cx)/2-3);else if(dx<0)cell.left=Math.max(cell.left,(other.bounds.centerX()+cx)/2+3);}
                else{if(dy>0)cell.bottom=Math.min(cell.bottom,(other.bounds.centerY()+cy)/2-3);else cell.top=Math.max(cell.top,(other.bounds.centerY()+cy)/2+3);}
            }
            if(cell.width()<12||cell.height()<12)cell=new RectF(b.bounds);
            int desired=(int)Math.max(12,Math.min(cell.width()-8,Math.max(b.bounds.width()+12,98*density)));
            float start=18*density*scale,min=Math.min(start,8*density);StaticLayout fitted=null;
            for(float size=start;size>=min;size-=Math.max(.5f,density/2)){
                text.setTextSize(size);fitted=make(b.translation,desired);
                if(fitted.getHeight()+8<=cell.height())break;
            }
            if(fitted==null)continue;
            // If the narrow balloon needs more space, use its available cell before reducing further.
            if(fitted.getHeight()+8>cell.height()&&cell.width()-8>desired){desired=Math.max(12,(int)cell.width()-8);fitted=make(b.translation,desired);}
            float x=Math.max(cell.left+4,Math.min(cx-desired/2f,cell.right-desired-4));
            float y=Math.max(cell.top+4,Math.min(cy-fitted.getHeight()/2f,cell.bottom-fitted.getHeight()-4));
            placements.add(new Placement(new RectF(x-4,y-4,x+desired+4,y+fitted.getHeight()+4),fitted));
        }
    }
    private StaticLayout make(String value,int width){
        String natural=wrap(value.replaceAll("(?<!\n)\n(?!\n)"," "),text,width);
        return StaticLayout.Builder.obtain(natural,0,natural.length(),new TextPaint(text),width).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(1,1.08f).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
    }
    static String wrap(String value,Paint paint,float width){
        StringBuilder result=new StringBuilder();
        for(String paragraph:value.split("\n",-1)){
            if(result.length()>0)result.append('\n');StringBuilder line=new StringBuilder();
            for(String word:paragraph.trim().split("\\s+")){
                if(word.isEmpty())continue;
                if(line.length()>0&&paint.measureText(line+" "+word)<=width){line.append(' ').append(word);continue;}
                if(line.length()>0){result.append(line).append('\n');line.setLength(0);}
                if(paint.measureText(word)<=width){line.append(word);continue;}
                android.icu.text.BreakIterator it=android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.KOREAN);it.setText(word);int begin=it.first(),end;
                while((end=it.next())!=android.icu.text.BreakIterator.DONE){String cluster=word.substring(begin,end);if(line.length()>0&&paint.measureText(line+cluster)>width){result.append(line).append('\n');line.setLength(0);}line.append(cluster);begin=end;}
            }
            result.append(line);
        }
        return result.toString();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);int[] origin=new int[2];getLocationOnScreen(origin);canvas.save();canvas.translate(-origin[0],-origin[1]);
        for(TranslationClient.Block b:page.blocks)for(RectF strip:b.lines){RectF padded=new RectF(strip);padded.inset(-Core.dp(getContext(),3),-Core.dp(getContext(),3));canvas.drawRoundRect(padded,Core.dp(getContext(),6),Core.dp(getContext(),6),mask);}
        for(Placement p:placements){canvas.drawRoundRect(p.rect,Core.dp(getContext(),7),Core.dp(getContext(),7),mask);canvas.save();canvas.translate(p.rect.left+4,p.rect.top+4);p.text.draw(canvas);canvas.restore();}
        canvas.restore();
    }
}
