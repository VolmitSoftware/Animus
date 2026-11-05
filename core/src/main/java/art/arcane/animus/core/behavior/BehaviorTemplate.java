package art.arcane.animus.core.behavior;

import art.arcane.animus.core.gui.GuiExpression;
import java.util.*;

/*
 * Text with "{{ expression }}" holes. Each hole's value becomes text as GuiExpression.text writes it; a literal "{{"
 * is written "{{ '{{' }}". A hole ends at the first "}}" outside a quoted string.
 */
public final class BehaviorTemplate {
    private final List<Object> parts;private final String field;
    private BehaviorTemplate(List<Object> parts,String field){this.parts=List.copyOf(parts);this.field=field;}

    public static BehaviorTemplate compile(String source,Set<String> variables,String field){
        Objects.requireNonNull(source);
        if(source.length()>BehaviorExpression.MAX_LENGTH)throw new BehaviorExpression.Invalid(field,"exceeds "+BehaviorExpression.MAX_LENGTH+" characters");
        List<Object> parts=new ArrayList<>();StringBuilder literal=new StringBuilder();
        int index=0;
        while(index<source.length()){
            int open=source.indexOf("{{",index);
            if(open<0){literal.append(source,index,source.length());break;}
            literal.append(source,index,open);
            int cursor=open+2;char quote=0;int close=-1;
            while(cursor<source.length()){
                char glyph=source.charAt(cursor);
                if(quote!=0){if(glyph=='\\')cursor++;else if(glyph==quote)quote=0;}
                else if(glyph=='\''||glyph=='"')quote=glyph;
                else if(glyph=='}'&&cursor+1<source.length()&&source.charAt(cursor+1)=='}'){close=cursor;break;}
                cursor++;
            }
            if(close<0)throw new BehaviorExpression.Invalid(field,"unclosed {{ at position "+open);
            if(!literal.isEmpty()){parts.add(literal.toString());literal.setLength(0);}
            parts.add(BehaviorExpression.compile(source.substring(open+2,close),variables,field));
            index=close+2;
        }
        if(!literal.isEmpty())parts.add(literal.toString());
        return new BehaviorTemplate(parts,field);
    }
    public String field(){return field;}

    public String evaluate(BehaviorExpression.Environment environment){
        StringBuilder out=new StringBuilder();
        for(Object part:parts){
            out.append(part instanceof BehaviorExpression hole?GuiExpression.text(hole.evaluate(environment)):(String)part);
            if(out.length()>BehaviorExpression.MAX_TEXT)throw new BehaviorExpression.Failed("behavior-expression-failed",field,"text exceeds "+BehaviorExpression.MAX_TEXT+" characters");
        }
        return out.toString();
    }
}
